package com.alphainternational.assistant

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** What the empty Brainstorm screen offers to start with: not about the shop, so they fit anyone. */
object BrainstormStarters {
    val ideas=listOf("Help me think through an idea","Give me ten ideas for…","Be the devil's advocate on my plan","Help me choose between two options")
    /** The start of a sentence the owner finishes, or the whole sentence. */
    fun fill(idea:String)=when(idea) {
        "Give me ten ideas for…"->"Give me ten ideas for "
        "Help me think through an idea"->"I'm thinking about "
        else->idea+": "
    }
}

/**
 * Brainstorm, its own screen: talk an idea through like with ChatGPT. Your texts on the right, its answers set as readable text on the left, streaming as they
 * are written. Nothing here uses the team, the phone's tools or the shop's records, and nothing is sent anywhere.
 */
@Composable fun BrainstormScreen(activity:MainActivity,c:Controller,back:()->Unit) {
    val field=rememberSaveable(saver=androidx.compose.foundation.text.input.TextFieldState.Saver) {androidx.compose.foundation.text.input.TextFieldState()}
    val input=field.text.toString()
    var past by remember {mutableStateOf(false)}
    val chat=c.brainstormChat()
    val messages=chat?.messages.orEmpty()
    val list=rememberLazyListState()
    val send={if(c.brainstormSend(field.text.toString())) field.setTextAndPlaceCursorAtEnd("")}
    DisposableEffect(Unit) {
        activity.onSpeech={field.setTextAndPlaceCursorAtEnd(it)}
        onDispose {activity.onSpeech=null}
    }
    val actions=remember(c,activity) {ChatActions(link={activity.openLink(it)},copy={activity.copyText(it)},share={activity.shareText(it)},ask={c.brainstormSend(it)},openSource={},evidence={null})}
    LaunchedEffect(messages.size,c.brainstormBusy,c.brainstormLive.length/200) {
        if(messages.isNotEmpty() || c.brainstormBusy) list.animateScrollToItem(list.layoutInfo.totalItemsCount.coerceAtLeast(1)-1)
    }
    CompositionLocalProvider(LocalChat provides actions) {
        Column(Modifier.fillMaxSize().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(start=4.dp,end=8.dp,top=6.dp),verticalAlignment=Alignment.CenterVertically) {
                IconButton(onClick=back) {Icon(Icons.AutoMirrored.Outlined.ArrowBack,"Back to bots",tint=Ink)}
                Column(Modifier.weight(1f)) {
                    Text("Brainstorm",fontWeight=FontWeight.SemiBold,fontSize=17.sp,color=Ink)
                    Text(if(c.brainstormBusy) "thinking…" else "Think it through · nothing is sent or changed",color=if(c.brainstormBusy) Purple else Muted,fontSize=12.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                }
                IconButton(onClick={past=true}) {Icon(Icons.Outlined.History,"Past brainstorms",tint=Ink)}
                IconButton(onClick={c.brainstormNew()},enabled=!c.brainstormBusy && chat!=null) {Icon(Icons.Outlined.Add,"New brainstorm",tint=if(!c.brainstormBusy && chat!=null) Ink else Muted)}
            }
            HorizontalDivider(color=Line)
            if(messages.isEmpty() && !c.brainstormBusy) Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal=24.dp,vertical=24.dp),
                horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center) {
                Box(Modifier.size(72.dp).background(AccentDim,RoundedCornerShape(26.dp)),contentAlignment=Alignment.Center) {Icon(Icons.Outlined.Lightbulb,null,tint=Accent,modifier=Modifier.size(34.dp))}
                Text("Brainstorm",fontSize=24.sp,fontWeight=FontWeight.SemiBold,color=Ink,modifier=Modifier.padding(top=16.dp))
                Text("Talk an idea through, like with ChatGPT. This chat is just for thinking: nothing here is sent to anyone or saved to your shop.",color=Muted,textAlign=TextAlign.Center,
                    modifier=Modifier.padding(top=6.dp,bottom=20.dp))
                if(!c.aiConnected()) Text("Connect your ChatGPT plan first (Settings, ChatGPT).",color=Muted,textAlign=TextAlign.Center)
                else BrainstormStarters.ideas.forEach {idea->
                    Text(idea,style=Type.chip.copy(fontSize=15.sp),modifier=Modifier.padding(vertical=4.dp).minimumInteractiveComponentSize().clip(RoundedCornerShape(20.dp)).border(1.dp,Outline,RoundedCornerShape(20.dp))
                        .clickable {field.setTextAndPlaceCursorAtEnd(BrainstormStarters.fill(idea))}.padding(horizontal=16.dp,vertical=10.dp))
                }
            } else LazyColumn(state=list,modifier=Modifier.weight(1f).fillMaxWidth(),contentPadding=PaddingValues(horizontal=12.dp,vertical=14.dp),verticalArrangement=Arrangement.spacedBy(3.dp,Alignment.Bottom)) {
                itemsIndexed(messages,key={i,m->chat?.id+"-"+i}) {i,m->
                    val previous=messages.getOrNull(i-1)
                    val mine=m.role=="user"
                    Column(Modifier.padding(top=if(previous!=null && (previous.role=="user")==mine) 2.dp else 14.dp)) {
                        if(i==0 || TextThread.showStamp(previous?.at,m.at)) TimeLine(m.at)
                        if(mine) UserMessage(m.text,"") {} else BrainstormAnswer(m.text,live=false,stop={})
                    }
                }
                if(c.brainstormBusy) item(key="live") {
                    Column(Modifier.padding(top=14.dp)) {
                        if(c.brainstormLive.isBlank()) ThinkingDots("Brainstorm") {} else BrainstormAnswer(c.brainstormLive,live=true,stop={c.brainstormStop()})
                    }
                }
                if(c.brainstormProblem.isNotBlank() && !c.brainstormBusy) item(key="problem") {
                    Row(Modifier.padding(top=10.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Panel).border(1.dp,Line,RoundedCornerShape(14.dp)).padding(horizontal=14.dp,vertical=8.dp),
                        verticalAlignment=Alignment.CenterVertically) {
                        Text("That didn't go through. "+c.brainstormProblem,style=Type.small.copy(color=Ink),modifier=Modifier.weight(1f))
                        val lastOwner=messages.lastOrNull {it.role=="user"}?.text
                        if(lastOwner!=null && messages.lastOrNull()?.role=="user") TextButton(onClick={c.brainstormSend(lastOwner)}) {Text("Try again")}
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=10.dp),verticalAlignment=Alignment.Bottom) {
                TextBox(activity,field,"Message Brainstorm",Modifier.weight(1f)) {send()}
                Spacer(Modifier.width(8.dp))
                if(c.brainstormBusy) FilledIconButton(onClick={c.brainstormStop()},modifier=Modifier.size(46.dp),
                    colors=IconButtonDefaults.filledIconButtonColors(containerColor=Strong,contentColor=OnStrong)) {Icon(Icons.Outlined.Stop,"Stop")}
                else FilledIconButton(onClick={send()},enabled=input.isNotBlank(),modifier=Modifier.size(46.dp),
                    colors=IconButtonDefaults.filledIconButtonColors(containerColor=Strong,contentColor=OnStrong,disabledContainerColor=Raised2,disabledContentColor=Muted)) {Icon(Icons.Outlined.ArrowUpward,"Send")}
            }
        }
    }
    if(past) PastBrainstorms(c,{past=false})
}

/** An answer in a brainstorm: set as readable text like any reply (a real conversation wants all of it), with copy and share under it once it is finished. */
@Composable private fun BrainstormAnswer(text:String,live:Boolean,stop:()->Unit) {
    val chat=LocalChat.current
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(6.dp)) {
        if(live) Row(verticalAlignment=Alignment.CenterVertically) {
            Box(Modifier.size(24.dp).background(AccentDim,CircleShape),contentAlignment=Alignment.Center) {Icon(Icons.Outlined.Lightbulb,null,tint=Accent,modifier=Modifier.size(14.dp))}
            Text("  Brainstorm  ·  writing…",style=Type.small.copy(fontSize=12.sp))
        }
        SelectionContainer {MarkdownText(text)}
        if(live) TextButton(onClick=stop,contentPadding=PaddingValues(horizontal=8.dp)) {Text("Stop",style=Type.small)}
        else Row {
            IconButton(onClick={chat?.copy?.invoke(text)},modifier=Modifier.size(44.dp)) {Icon(Icons.Outlined.ContentCopy,"Copy",tint=Muted,modifier=Modifier.size(18.dp))}
            IconButton(onClick={chat?.share?.invoke(text)},modifier=Modifier.size(44.dp)) {Icon(Icons.Outlined.IosShare,"Share",tint=Muted,modifier=Modifier.size(18.dp))}
        }
    }
}

private fun when_(time:Long):String {
    val moment=Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault())
    return if(moment.toLocalDate()==LocalDate.now()) DateTimeFormatter.ofPattern("h:mm a").format(moment) else DateTimeFormatter.ofPattern("MMM d").format(moment)
}

/** The list of earlier brainstorms: tap one to carry on, or remove it. */
@Composable private fun PastBrainstorms(c:Controller,close:()->Unit) {
    var removing by remember {mutableStateOf("")}
    Dialog(onDismissRequest=close) {
        Surface(shape=RoundedCornerShape(24.dp),color=SheetBg) {
            Column(Modifier.padding(vertical=16.dp)) {
                Text("Past brainstorms",style=MaterialTheme.typography.titleLarge,color=Ink,modifier=Modifier.padding(horizontal=20.dp,vertical=6.dp))
                if(c.brainstormChats.isEmpty()) Text("None yet. Your brainstorms are kept here, on this phone.",color=Muted,modifier=Modifier.padding(horizontal=20.dp,vertical=12.dp))
                LazyColumn(Modifier.heightIn(max=480.dp)) {
                    itemsIndexed(c.brainstormChats,key={_,chat->chat.id}) {_,chat->
                        Row(Modifier.fillMaxWidth().clickable(enabled=!c.brainstormBusy) {c.brainstormOpenChat(chat.id);close()}.padding(horizontal=20.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(chat.title,color=Ink,fontSize=15.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                                Text(when_(chat.updated)+" · "+chat.messages.size+" texts",color=Muted,fontSize=12.sp)
                            }
                            if(removing==chat.id) TextButton(onClick={c.brainstormDelete(chat.id);removing=""}) {Text("Remove",color=MaterialTheme.colorScheme.error)}
                            else IconButton(onClick={removing=chat.id}) {Icon(Icons.Outlined.DeleteOutline,"Remove "+chat.title,tint=Muted)}
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(end=12.dp),horizontalArrangement=Arrangement.End) {TextButton(onClick=close) {Text("Close")}}
            }
        }
    }
}
