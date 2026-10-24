package com.alphainternational.assistant

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.NightsStay
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private fun clock(time:Long):String {
    val moment=Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault())
    return if(moment.toLocalDate()==LocalDate.now()) DateTimeFormatter.ofPattern("h:mm a").format(moment) else DateTimeFormatter.ofPattern("MMM d, h:mm a").format(moment)
}

/** Away mode: the switch, how it behaves, the replies waiting for the owner, and what it did. */
@Composable fun AwayScreen(activity:MainActivity,c:Controller) {
    c.awayVersion
    val s=c.awaySettings()
    val pending=c.awayPending()
    val entries=c.awayEntries().reversed().take(40)
    var access by remember {mutableStateOf(AwayListener.enabled(activity))}
    var editing by remember {mutableStateOf("")}
    var draft by remember {mutableStateOf("")}
    // Coming back from Android's Notification access screen: pick up the new state without a button press.
    LaunchedEffect(Unit) {while(true) {access=AwayListener.enabled(activity);kotlinx.coroutines.delay(1_500)}}
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(start=16.dp,end=16.dp,top=8.dp,bottom=40.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        item {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(if(s.on) AccentDim else Panel).border(1.dp,if(s.on) Accent else Line,RoundedCornerShape(18.dp)).padding(16.dp),
                verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if(s.on) "Away mode is ON" else "Away mode is off",style=Type.section)
                    Text(if(s.on) (if(s.live) "Answering your texts for you." else "Draft only: nothing is sent until you tap Send.")
                        else "Alpha answers the texts you can leave to it (friends, estimates, \"is my car ready\") and asks you about the rest.",style=Type.small)
                }
                Switch(checked=s.on,onCheckedChange={want->val problem=c.awaySetOn(want);if(problem.isNotBlank()) c.showNotice(problem)})
            }
        }
        if(!access) item {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Panel).border(1.dp,Amber,RoundedCornerShape(16.dp)).padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text("One switch for Android",style=Type.sub)
                Text("Alpha hears new texts through the Messages app's notification. Android wants you to allow that once: tap below, then turn on \"Alpha Assistant\". " +
                    "If Android says the setting is restricted, open Alpha's App info, tap the three dots at the top right and choose Allow restricted settings.",style=Type.small)
                Button(onClick={runCatching {activity.startActivity(AwayListener.settingsIntent(activity))}.onFailure {c.showNotice("Open Settings → Notifications → Device & app notifications and turn on Alpha.")}},
                    colors=ButtonDefaults.buttonColors(containerColor=Accent,contentColor=OnAccent)) {Text("Turn on notification access")}
            }
        }
        val hidden=c.awayEntries().count {it.note==AwayRules.HIDDEN_BY_ANDROID && System.currentTimeMillis()-it.at<24*3_600_000L}
        if(hidden>0) item {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Panel).border(1.dp,Amber,RoundedCornerShape(16.dp)).padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text("Android hid $hidden "+(if(hidden==1) "text" else "texts")+" from Alpha",style=Type.sub)
                Text("Android 15 and newer hides the words of texts from numbers that aren't in your contacts. Alpha needs one more permission, which only a computer can give it. It is a one-time step done from a computer (developer settings); ask whoever set Alpha up for you.",style=Type.small)
            }
        }
        item {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Panel).border(1.dp,Line,RoundedCornerShape(16.dp)).padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Text("How it answers",style=Type.sub)
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    listOf(false to "Draft only",true to "Live").forEach {(live,label)->
                        val on=s.live==live
                        Text(label,style=Type.sub.copy(fontSize=14.sp,color=if(on) OnAccent else Ink),
                            modifier=Modifier.clip(RoundedCornerShape(999.dp)).background(if(on) Accent else Raised2).clickable {c.awayUpdate {it.copy(live=live)}}.padding(horizontal=16.dp,vertical=8.dp))
                    }
                }
                Text(if(s.live) "Live: replies to friends, estimates and \"is it ready\" go out by themselves. Everything else waits for you."
                    else "Draft only: nothing is sent. You get a notification with the reply it would send, and tap Send. A good way to start.",style=Type.small)
                Text("Turn off by itself after",style=Type.sub.copy(fontSize=14.sp))
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    listOf(4,8,12,24).forEach {h->
                        val on=s.offAfterHours==h
                        Text("$h h",style=Type.sub.copy(fontSize=13.sp,color=if(on) OnAccent else Ink),
                            modifier=Modifier.clip(RoundedCornerShape(999.dp)).background(if(on) Accent else Raised2).clickable {c.awayUpdate {it.copy(offAfterHours=h)}}.padding(horizontal=14.dp,vertical=7.dp))
                    }
                }
                OutlinedTextField(s.friendTag,{v->c.awayUpdate {it.copy(friendTag=v.take(60))}},label={Text("Added to the first reply to each friend")},singleLine=true,modifier=Modifier.fillMaxWidth())
                OutlinedTextField(s.signOff,{v->c.awayUpdate {it.copy(signOff=v.take(60))}},label={Text("Signed on customer replies")},placeholder={Text(Profile.shopName.ifBlank {"your business name"})},
                    singleLine=true,modifier=Modifier.fillMaxWidth())
            }
        }
        if(pending.isNotEmpty()) {
            item {Text("WAITING FOR YOU",style=Type.label)}
            items(pending.reversed(),key={it.id}) {p->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Panel).border(1.dp,Line,RoundedCornerShape(16.dp)).padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Row {Text(p.who,style=Type.sub,modifier=Modifier.weight(1f));Text(clock(p.at),style=Type.small)}
                    Text("“"+p.incoming+"”",style=Type.body.copy(fontSize=15.sp))
                    if(p.why.isNotBlank()) Text(p.why,style=Type.small.copy(color=Amber))
                    if(editing==p.id) OutlinedTextField(draft,{draft=it},label={Text("Your reply")},modifier=Modifier.fillMaxWidth(),minLines=2)
                    else if(p.draft.isNotBlank()) Text("Draft: "+p.draft,style=Type.small.copy(color=Ink))
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        if(p.draft.isNotBlank() || editing==p.id) Button(onClick={if(c.awaySend(p.id,if(editing==p.id) draft else null)) editing=""},
                            colors=ButtonDefaults.buttonColors(containerColor=Accent,contentColor=OnAccent)) {Text("Send")}
                        OutlinedButton(onClick={if(editing==p.id) editing="" else {editing=p.id;draft=p.draft}}) {Text(if(editing==p.id) "Cancel" else if(p.draft.isBlank()) "Write a reply" else "Edit")}
                        TextButton(onClick={c.awaySkip(p.id)}) {Text("Skip",color=Muted)}
                    }
                }
            }
        }
        item {Text("WHAT IT DID",style=Type.label)}
        if(entries.isEmpty()) item {Text("Nothing yet.",style=Type.small)}
        items(entries,key={it.at.toString()+it.who+it.kind}) {e->
            Column(Modifier.fillMaxWidth().padding(vertical=2.dp)) {
                Row {
                    Text(e.who+" · "+when(e.kind) {"SEND"->"answered";"DRAFT"->"drafted";"ASK"->"needed you";"SKIP"->"skipped";else->"left alone"},style=Type.sub.copy(fontSize=14.sp),modifier=Modifier.weight(1f))
                    Text(clock(e.at),style=Type.small)
                }
                Text("“"+e.incoming.take(120)+"”",style=Type.small)
                if(e.reply.isNotBlank()) Text("→ "+e.reply.lineSequence().first().take(140),style=Type.small.copy(color=Accent))
                else if(e.note.isNotBlank()) Text(e.note,style=Type.small.copy(fontWeight=FontWeight.Normal))
            }
            HorizontalDivider(color=Line)
        }
    }
}

/** The Home card: Away mode at a glance, with its own switch. */
@Composable fun AwayCard(c:Controller,open:()->Unit) {
    c.awayVersion
    val s=c.awaySettings()
    val waiting=c.awayPending().size
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable(onClick=open).padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
        Icon(Icons.Outlined.NightsStay,null,tint=if(s.on) Accent else Ink)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("Away mode",fontWeight=FontWeight.SemiBold,color=Ink)
            Text(when {s.on && waiting>0->"On · $waiting waiting for you";s.on->if(s.live) "On · answering your texts" else "On · drafts only";else->"Alpha answers your texts while you're away"},color=if(s.on) Accent else Muted,fontSize=13.sp)
        }
        Icon(Icons.Outlined.ChevronRight,null,tint=Muted)
    }
}
