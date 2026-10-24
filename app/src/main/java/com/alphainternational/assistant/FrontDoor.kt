package com.alphainternational.assistant

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The rules for the first screen. Pure, so they are tested on a computer. */
object FrontDoor {
    /** Things anyone might ask, whatever they do for a living. */
    val general=listOf("Summarize my day","Find something online","Draft a message","Make a document")
    private val shop=listOf("Make me an estimate","Text a customer their car is ready")

    /** General ideas first. The owner's shop ideas come after them, and only in the owner's own copy (never in the friends copy). */
    fun suggestions(shopName:String,friends:Boolean):List<String> = general+(if(shopName.isNotBlank() && !friends) shop else emptyList())

    /** What goes into the box when a suggestion is tapped: the start of a sentence for the ones that need a few more words. */
    fun fill(chip:String)=when(chip) {
        "Find something online"->"Find something online: "
        "Draft a message"->"Draft a message to "
        "Make a document"->"Make a document about "
        else->chip
    }

    /** The group chat gets its own row only once the team is big enough to need it: the box at the top already reaches everyone. */
    fun showTeamRow(botCount:Int)=botCount>=3
}

/** What the thin status line on Home says. Nothing at all when nothing is going on: silence is right. */
object HomeStatus {
    enum class Kind {RUNNING,NEEDS,AWAY}
    /** [bot] is the bot to open when the line is tapped (blank for Away mode). */
    data class Segment(val kind:Kind,val text:String,val bot:String="")

    /** [running] is each working job as (bot, what it is doing); [needs] the bot of each job waiting for the owner. */
    fun segments(running:List<Pair<String,String>>,needs:List<String>,awayOn:Boolean,awayWaiting:Int):List<Segment> {
        val out=mutableListOf<Segment>()
        if(running.size==1) out+=Segment(Kind.RUNNING,running[0].first+" is working"+(if(running[0].second.isBlank()) "" else " · "+running[0].second),running[0].first)
        else if(running.size>1) out+=Segment(Kind.RUNNING,"${running.size} jobs running",running[0].first)
        if(needs.isNotEmpty()) out+=Segment(Kind.NEEDS,"${needs.size} waiting for you",needs[0])
        if(awayOn) out+=Segment(Kind.AWAY,"Away mode on"+(if(awayWaiting>0) " · $awayWaiting waiting" else ""))
        return out
    }
}

/**
 * The one front door: a big box to ask anything (type, talk or attach), suggestions under it, and a nudge to connect ChatGPT when that is missing.
 * What is typed goes to the whole team; [sent] opens the team chat.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable fun AskAnything(activity:MainActivity?,c:Controller,connect:()->Unit,sent:()->Unit) {
    val field=rememberSaveable(saver=TextFieldState.Saver) {TextFieldState()}
    var attached by remember {mutableStateOf(listOf<String>())}
    var attach by remember {mutableStateOf(false)}
    val input=field.text.toString()
    val connected=c.aiConnected()
    // Dictation and picked photos land in this box while Home is showing.
    DisposableEffect(activity) {
        activity?.onSpeech={field.setTextAndPlaceCursorAtEnd(it)}
        activity?.onAttached={name->attached=(attached+name).distinct().take(8)}
        onDispose {activity?.onSpeech=null;activity?.onAttached=null}
    }
    val send={if(c.submitTeam(field.text.toString(),attached)) {field.setTextAndPlaceCursorAtEnd("");attached=emptyList();sent()}}
    Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
        if(!connected) Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Panel).border(1.dp,Line,RoundedCornerShape(18.dp)).padding(start=16.dp,end=8.dp,top=6.dp,bottom=6.dp),
            verticalAlignment=Alignment.CenterVertically) {
            Text("Connect your ChatGPT plan so Alpha can think.",style=Type.note.copy(color=Ink),modifier=Modifier.weight(1f))
            Button(onClick=connect,colors=ButtonDefaults.buttonColors(containerColor=Strong,contentColor=OnStrong)) {Text("Connect")}
        }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(FieldBg).border(1.dp,Line,RoundedCornerShape(26.dp)).padding(start=18.dp,end=8.dp,top=14.dp,bottom=6.dp)) {
            BasicTextField(state=field,modifier=Modifier.fillMaxWidth(),textStyle=Type.body,cursorBrush=SolidColor(Ink),
                lineLimits=TextFieldLineLimits.MultiLine(minHeightInLines=2,maxHeightInLines=6),
                keyboardOptions=KeyboardOptions(capitalization=KeyboardCapitalization.Sentences,imeAction=ImeAction.Send),
                onKeyboardAction={send()},
                decorator={inner->
                    Box {
                        if(field.text.isEmpty()) Text("Ask anything…",style=Type.body.copy(color=Muted))
                        inner()
                    }
                })
            if(attached.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top=8.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                attached.forEach {name->
                    Box {
                        AttachmentChip(c,name,big=false) {attached=attached-name}
                        Box(Modifier.align(Alignment.TopEnd).size(22.dp).background(Background,CircleShape).clickable {attached=attached-name},contentAlignment=Alignment.Center) {
                            Icon(Icons.Outlined.Close,"Remove $name",tint=Ink,modifier=Modifier.size(14.dp))
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                if(activity!=null) Box {
                    IconButton(onClick={attach=true},modifier=Modifier.size(48.dp)) {Icon(Icons.Outlined.Add,"Add a photo or file",tint=Muted)}
                    DropdownMenu(expanded=attach,onDismissRequest={attach=false}) {
                        DropdownMenuItem(text={Text("Take a photo")},leadingIcon={Icon(Icons.Outlined.PhotoCamera,null)},onClick={attach=false;activity.takePhoto()})
                        DropdownMenuItem(text={Text("Add a photo")},leadingIcon={Icon(Icons.Outlined.Image,null)},onClick={attach=false;activity.pickPhoto()})
                        DropdownMenuItem(text={Text("Attach a document or PDF")},leadingIcon={Icon(Icons.Outlined.Description,null)},onClick={attach=false;activity.pickDocument()})
                    }
                }
                Spacer(Modifier.weight(1f))
                if(activity!=null) IconButton(onClick={activity.speak()},modifier=Modifier.size(48.dp)) {Icon(Icons.Outlined.Mic,"Talk instead of typing",tint=Muted)}
                FilledIconButton(onClick={send()},enabled=input.isNotBlank() || attached.isNotEmpty(),modifier=Modifier.size(48.dp),
                    colors=IconButtonDefaults.filledIconButtonColors(containerColor=Strong,contentColor=OnStrong,disabledContainerColor=Raised2,disabledContentColor=Muted)) {
                    Icon(Icons.Outlined.ArrowUpward,"Send to your team")
                }
            }
        }
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(0.dp)) {
            FrontDoor.suggestions(BuildConfig.SHOP_NAME,Profile.generic).forEach {idea->
                Text(idea,style=Type.chip,maxLines=1,overflow=TextOverflow.Ellipsis,
                    modifier=Modifier.minimumInteractiveComponentSize().clip(RoundedCornerShape(999.dp)).border(1.dp,Outline,RoundedCornerShape(999.dp))
                        .clickable {field.setTextAndPlaceCursorAtEnd(FrontDoor.fill(idea))}.padding(horizontal=14.dp,vertical=9.dp))
            }
        }
    }
}

/** One thin line under the box: what is running, what waits for the owner, and whether Away mode is on. Nothing when all is quiet. */
@OptIn(ExperimentalLayoutApi::class)
@Composable fun StatusStrip(c:Controller,openBot:(String)->Unit,openActivity:(String)->Unit,away:()->Unit) {
    c.awayVersion
    val running=c.tasks.filter {it.status=="Running" && !it.from.startsWith("away") && it.showsAsTyping()}
    // Only what is recent counts: a request left waiting last week is still in the lists, but it is not "waiting for you" on the front page.
    val needs=c.tasks.filter {it.status in attentionStates && !it.from.startsWith("away") && System.currentTimeMillis()-it.created<2*24*3_600_000L}
    val awayState=remember(c.awayVersion) {c.awaySettings()}
    val segments=HomeStatus.segments(running.map {it.workspace to ProgressNote.line(c.jobProgress(it.id),it.steps.lastOrNull().orEmpty(),it.detail)},
        needs.map {it.workspace},awayState.on,if(awayState.on) c.awayPending().size else 0)
    if(segments.isEmpty()) return
    FlowRow(Modifier.fillMaxWidth().padding(top=12.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        segments.forEach {segment->
            val tint=when(segment.kind) {HomeStatus.Kind.RUNNING->Purple;HomeStatus.Kind.NEEDS->Amber;HomeStatus.Kind.AWAY->Accent}
            Row(Modifier.minimumInteractiveComponentSize().clip(RoundedCornerShape(999.dp)).background(Panel).border(1.dp,Line,RoundedCornerShape(999.dp))
                .clickable {
                    when(segment.kind) {HomeStatus.Kind.RUNNING->openActivity(segment.bot);HomeStatus.Kind.NEEDS->openBot(segment.bot);HomeStatus.Kind.AWAY->away()}
                }.padding(horizontal=14.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(tint,CircleShape));Spacer(Modifier.width(8.dp))
                Text(segment.text,style=Type.small.copy(color=Ink,fontSize=13.sp),maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.widthIn(max=280.dp))
            }
        }
    }
}

/**
 * What a working bot shows in a chat. Once it has words, its answer grows as it is written, set like a finished one; before that the live card of
 * what it is doing (steps and the pages it looked at); and for a quick chat three quiet dots (tap them to peek at what it is doing).
 * [lastPosted] is the newest text this job already posted, so the growing bubble steps aside the moment the finished one lands.
 */
@Composable fun LiveJob(c:Controller,job:TaskRecord,lastPosted:String?,acked:Boolean,pictures:PictureHost?,peek:Boolean,togglePeek:()->Unit,computer:()->Unit) {
    val live=c.liveTextOf(job.id)
    val words=remember(live) {LiveText.clean(live)}
    if(LiveText.show(live,lastPosted)) {
        LiveAnswer(job.workspace,words,pictures) {c.stop(job.id)}
        return
    }
    val progress=c.jobProgress(job.id)
    if(acked || job.steps.isNotEmpty()) {
        val line=if(progress!=null) ProgressNote.line(progress,job.steps.lastOrNull().orEmpty(),job.detail) else WorkWords.plain(job.detail)
        LiveWork(job.workspace,line,job.steps,Evidence.parse(job.evidence),computer) {c.stop(job.id)}
        return
    }
    Column {
        ThinkingDots(job.workspace,togglePeek)
        if(peek) Row(Modifier.padding(start=36.dp),verticalAlignment=Alignment.CenterVertically) {
            Text(ProgressNote.line(progress,job.steps.lastOrNull().orEmpty(),job.detail),style=Type.small,maxLines=2,modifier=Modifier.weight(1f,fill=false))
            TextButton(onClick=computer) {Text("Watch",style=Type.small.copy(color=Ink))}
            TextButton(onClick={c.stop(job.id)}) {Text("Stop",style=Type.small)}
        }
    }
}
