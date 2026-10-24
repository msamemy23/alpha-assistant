package com.alphainternational.assistant

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private fun statusWords(status:String)=when(status) {"active"->"Working on it";"waiting"->"Waiting on someone";"paused"->"Paused";"done"->"Done";else->status}

@Composable private fun PanelCard(content:@Composable ColumnScope.()->Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Panel).border(1.dp,Line,RoundedCornerShape(16.dp)).padding(14.dp),verticalArrangement=Arrangement.spacedBy(6.dp),content=content)
}
@Composable private fun Pill(label:String,on:Boolean,click:()->Unit) {
    Text(label,style=Type.sub.copy(fontSize=13.sp,color=if(on) Color.Black else Ink),
        modifier=Modifier.clip(RoundedCornerShape(999.dp)).background(if(on) Accent else Raised2).clickable(onClick=click).padding(horizontal=14.dp,vertical=7.dp))
}
@Composable private fun SwitchRow(title:String,note:String,checked:Boolean,change:(Boolean)->Unit) {
    Row(Modifier.fillMaxWidth().clickable {change(!checked)}.padding(vertical=4.dp),verticalAlignment=Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {Text(title,style=Type.sub);if(note.isNotBlank()) Text(note,style=Type.small)}
        Switch(checked=checked,onCheckedChange=change,colors=SwitchDefaults.colors(checkedTrackColor=Color.White,checkedThumbColor=Color.Black))
    }
}

/**
 * Goals: what Alpha is staying on top of, the check-backs it promised, what it noticed, the "when this happens" rules, and a short list of what automatic
 * work did lately. Each goal shows its status, the last thing done and when it will look again; the owner can mark it done, pause it or delete it.
 */
@Composable fun GoalsScreen(c:Controller,openSettings:()->Unit) {
    c.version
    val p=c.proactive
    val s=p.settings();val noticed=p.openNotices();val goals=p.openGoals();val ups=p.pendingFollowups();val triggers=p.triggers().triggers
    val done=p.doneGoals();val recent=p.log().reversed().take(12)
    var showDone by remember {mutableStateOf(false)}
    var deleteGoal by remember {mutableStateOf<Goal?>(null)}
    val now=System.currentTimeMillis()
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(start=16.dp,end=16.dp,top=8.dp,bottom=40.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        if(s.paused) item {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Panel).border(1.dp,Amber,RoundedCornerShape(16.dp)).padding(14.dp),verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {Text("Everything automatic is paused",style=Type.sub);Text("No checks, follow-ups, triggers or briefs run until you resume.",style=Type.small)}
                TextButton(onClick={p.updateSettings {it.copy(paused=false)}}) {Text("Resume",color=Accent)}
            }
        }
        if(noticed.isNotEmpty()) {
            item {Text("NOTICED",style=Type.label)}
            items(noticed,key={"n-"+it.id}) {n->
                PanelCard {
                    Text(n.title,style=Type.sub)
                    Text(n.saw,style=Type.small.copy(color=Ink))
                    Text(n.source+(if(n.why.isNotBlank()) " · "+n.why else ""),style=Type.small)
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
                        if(n.action.isNotBlank()) Button(onClick={p.runNotice(n.id);c.showNotice("On it. The result will be in the chat.")},colors=ButtonDefaults.buttonColors(containerColor=Accent,contentColor=Color.Black)) {Text(n.action)}
                        TextButton(onClick={p.dismissNotice(n.id)}) {Text("Not now",color=Muted)}
                    }
                }
            }
        }
        item {Text("GOALS",style=Type.label)}
        if(goals.isEmpty()) item {Text("No goals yet. Tell any bot what to stay on top of, like \"Keep after Mike until he pays the invoice.\" It will keep track here and tell you when something happens.",style=Type.small)}
        items(goals,key={"g-"+it.id}) {g->
            PanelCard {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Text(g.title,style=Type.sub,modifier=Modifier.weight(1f))
                    Text(statusWords(g.status),style=Type.small.copy(color=if(g.status=="paused") Amber else Accent))
                }
                Text(g.bot+(if(g.lastAction.isNotBlank()) " · Last: "+g.lastAction+" ("+When.ago(g.lastActionAt,now)+")" else " · Nothing done yet"),style=Type.small.copy(color=Ink))
                if(g.nextCheck>0) Text("Next check: "+When.describe(g.nextCheck,now),style=Type.small.copy(color=if(g.status!="paused" && g.nextCheck<now) Amber else Muted))
                if(g.notes.isNotBlank()) Text(g.notes,style=Type.small)
                Row(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick={p.markDone(g.id)}) {Text("Done",color=Accent)}
                    TextButton(onClick={p.setPaused(g.id,g.status!="paused")}) {Text(if(g.status=="paused") "Resume" else "Pause",color=Ink)}
                    TextButton(onClick={deleteGoal=g}) {Text("Delete",color=Muted)}
                }
            }
        }
        if(ups.isNotEmpty()) {
            item {Text("CHECK-BACKS I PROMISED",style=Type.label)}
            items(ups,key={"f-"+it.id}) {f->
                PanelCard {
                    Text(f.what,style=Type.sub.copy(fontSize=14.sp))
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Text(f.bot+" · "+When.describe(f.due,now),style=Type.small,modifier=Modifier.weight(1f))
                        TextButton(onClick={p.cancelFollowupUi(f.id)}) {Text("Cancel",color=Muted)}
                    }
                }
            }
        }
        item {Text("WHEN THIS HAPPENS, DO THAT",style=Type.label)}
        if(triggers.isEmpty()) item {Text("None yet. Tell a bot, for example: \"When Mike texts me, draft a reply\", or \"Tell me if that part comes back in stock.\"",style=Type.small)}
        items(triggers,key={"t-"+it.id}) {t->
            PanelCard {
                Text(t.sentence(),style=Type.sub.copy(fontSize=14.sp))
                Text("Then "+t.bot+": "+t.instruction,style=Type.small.copy(color=Ink))
                Text((if(t.lastCheck>0) "Checked "+When.ago(t.lastCheck,now) else "Not checked yet")+(if(t.hits>0) " · fired "+t.hits+(if(t.hits==1) " time" else " times") else "")+
                    (if(t.allowSend) " · may send replies" else " · drafts only"),style=Type.small)
                if(t.error.isNotBlank()) Text(t.error,style=Type.small.copy(color=Amber))
                if(t.kind=="text" && !AwayListener.enabled(LocalContext.current)) Text("Alpha can't see your texts yet. Turn on notification access on the Away mode screen.",style=Type.small.copy(color=Amber))
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Switch(checked=t.enabled,onCheckedChange={p.setTriggerEnabled(t.id,it)},colors=SwitchDefaults.colors(checkedTrackColor=Color.White,checkedThumbColor=Color.Black))
                    Text(if(t.enabled) "  On" else "  Paused",style=Type.small,modifier=Modifier.weight(1f))
                    TextButton(onClick={p.deleteTrigger(t.id)}) {Text("Delete",color=Muted)}
                }
            }
        }
        if(recent.isNotEmpty()) {
            item {Text("RECENTLY",style=Type.label)}
            itemsIndexed(recent,key={i,_->"l-"+i}) {_,e->
                Column(Modifier.fillMaxWidth()) {
                    Text(e.text,style=Type.small.copy(color=Ink))
                    Text(When.ago(e.at,now),style=Type.small)
                }
                HorizontalDivider(color=Line)
            }
        }
        if(done.isNotEmpty()) {
            item {TextButton(onClick={showDone=!showDone}) {Text(if(showDone) "Hide finished goals" else "Show finished goals ("+done.size+")",color=Muted)}}
            if(showDone) items(done,key={"d-"+it.id}) {g->
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    Text(g.title,style=Type.small.copy(color=Muted),modifier=Modifier.weight(1f))
                    TextButton(onClick={p.reopen(g.id)}) {Text("Bring back",color=Muted)}
                }
            }
        }
        item {OutlinedButton(onClick=openSettings) {Text("Proactive settings",color=Ink)}}
    }
    deleteGoal?.let {g->
        AlertDialog(onDismissRequest={deleteGoal=null},title={Text("Delete this goal?")},text={Text("“"+g.title+"” and its check-backs will be removed.")},
            confirmButton={TextButton(onClick={p.deleteGoal(g.id);deleteGoal=null}) {Text("Delete")}},dismissButton={TextButton(onClick={deleteGoal=null}) {Text("Keep it")}})
    }
}

/** The Home card for goals: a one-line summary that opens the Goals screen. */
@Composable fun GoalsCard(c:Controller,open:()->Unit) {
    c.version
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable(onClick=open).padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
        Icon(Icons.Outlined.Flag,null,tint=Ink)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("Goals and follow-ups",fontWeight=FontWeight.SemiBold,color=Ink)
            Text(c.proactive.summary(),color=Muted,fontSize=13.sp)
        }
        Icon(Icons.Outlined.ChevronRight,null,tint=Muted)
    }
}

/** Settings for everything Alpha does on its own: the morning brief, how many alerts a day, quiet hours, and the pause switch. */
@Composable fun ProactiveScreen(activity:MainActivity,c:Controller) {
    c.version
    val p=c.proactive
    val s=p.settings();val triggers=p.triggers().triggers
    val context=LocalContext.current
    fun pickTime(minute:Int,done:(Int)->Unit) {
        android.app.TimePickerDialog(context,{_,h,m->done(h*60+m)},minute/60,minute%60,false).show()
    }
    val briefMinutes=runCatching {java.time.LocalTime.parse(s.briefTime).let {it.hour*60+it.minute}}.getOrDefault(450)
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(start=16.dp,end=16.dp,top=8.dp,bottom=40.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        item {
            PanelCard {
                SwitchRow("Pause everything automatic","Stops the checks, follow-ups, triggers and the morning brief until you turn it back on.",s.paused) {on->p.updateSettings {it.copy(paused=on)}}
            }
        }
        item {
            PanelCard {
                SwitchRow("Morning brief","One short message each morning with your day: calendar, important mail, what is due. If there is nothing to say, you get nothing.",s.briefOn) {on->p.updateSettings {it.copy(briefOn=on)}}
                if(s.briefOn) {
                    Text("Time",style=Type.small)
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        listOf(6*60+30,7*60,7*60+30,8*60).forEach {m->Pill(Quiet.label(m),briefMinutes==m) {p.updateSettings {it.copy(briefTime="%02d:%02d".format(m/60,m%60))}}}
                    }
                    TextButton(onClick={pickTime(briefMinutes) {m->p.updateSettings {it.copy(briefTime="%02d:%02d".format(m/60,m%60))}}}) {Text("Another time ("+Quiet.label(briefMinutes)+")",color=Ink)}
                }
                if(s.briefOn && !p.googleConnected()) Text("Connect Google in Marketplace to include your calendar and mail. Until then the brief only covers your goals and what is waiting for you.",style=Type.small.copy(color=Amber))
                TextButton(onClick={p.briefNow()}) {Text("Brief me now",color=Accent)}
            }
        }
        item {
            PanelCard {
                SwitchRow("Tell me what it notices","Two things on your calendar at once, an email with a deadline in the next two days, a goal that went quiet. Never anything twice.",s.noticing) {on->p.updateSettings {it.copy(noticing=on)}}
                if(s.noticing) {
                    Text("Most alerts in one day",style=Type.small)
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {listOf(1,3,5).forEach {n->Pill(n.toString(),s.budget==n) {p.updateSettings {it.copy(budget=n)}}}}
                    Text("The rest wait in the Goals screen under Noticed.",style=Type.small)
                    if(!p.googleConnected()) Text("Connect Google in Marketplace so it can look at your calendar and mail.",style=Type.small.copy(color=Amber))
                    SwitchRow("Check mail more carefully","One quick extra look at the few emails that mention a deadline, to cut false alarms. Uses a little of your plan.",s.quickPass) {on->p.updateSettings {it.copy(quickPass=on)}}
                }
            }
        }
        item {
            PanelCard {
                Text("Quiet hours",style=Type.sub)
                Text("No alerts, check-backs or triggers wake you between these times (a trigger can be set to run at night).",style=Type.small)
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
                    OutlinedButton(onClick={pickTime(s.quietFrom) {m->p.updateSettings {it.copy(quietFrom=m)}}}) {Text("From "+Quiet.label(s.quietFrom),color=Ink)}
                    OutlinedButton(onClick={pickTime(s.quietTo) {m->p.updateSettings {it.copy(quietTo=m)}}}) {Text("Until "+Quiet.label(s.quietTo),color=Ink)}
                }
            }
        }
        item {Text("TRIGGERS AND WATCHES",style=Type.label)}
        if(triggers.isEmpty()) item {Text("None. Tell a bot what to watch for: \"When the supplier emails, draft a reply\", \"Tell me if this page changes.\"",style=Type.small)}
        items(triggers,key={"t-"+it.id}) {t->
            PanelCard {
                Text(t.sentence(),style=Type.sub.copy(fontSize=14.sp))
                Text("Then "+t.bot+": "+t.instruction,style=Type.small.copy(color=Ink))
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Switch(checked=t.enabled,onCheckedChange={p.setTriggerEnabled(t.id,it)},colors=SwitchDefaults.colors(checkedTrackColor=Color.White,checkedThumbColor=Color.Black))
                    Text(if(t.enabled) "  On" else "  Paused",style=Type.small,modifier=Modifier.weight(1f))
                    TextButton(onClick={p.deleteTrigger(t.id)}) {Text("Delete",color=Muted)}
                }
            }
        }
    }
}
