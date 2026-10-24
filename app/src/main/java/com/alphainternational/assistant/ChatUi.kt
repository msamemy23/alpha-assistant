package com.alphainternational.assistant

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** What the buttons and links inside a reply can do. Set by the chat that shows the reply, so the reply itself needs no knowledge of the app. */
class ChatActions(val link:(String)->Unit,val copy:(String)->Unit,val share:(String)->Unit,val ask:(String)->Unit,
    val openSource:(Source)->Unit,val evidence:(String)->ImageBitmap?)
val LocalChat=compositionLocalOf<ChatActions?> {null}

private val Soft=RoundedCornerShape(14.dp)

// ---------------------------------------------------------------------------------------------------------------------------------------------
// The words of a reply
// ---------------------------------------------------------------------------------------------------------------------------------------------

/** One line of a reply with its bold, italic, code and links; links are tappable and open through the chat. */
@Composable fun richText(text:String):AnnotatedString {
    val chat=LocalChat.current
    return remember(text,chat,Palette.light) {
        buildAnnotatedString {
            for(span in ChatMarkdown.spans(text)) {
                if(span.url.isNotBlank()) withLink(LinkAnnotation.Clickable("link",TextLinkStyles(SpanStyle(color=Accent,textDecoration=TextDecoration.Underline))) {chat?.link?.invoke(span.url)}) {append(span.text)}
                else {
                    pushStyle(SpanStyle(fontWeight=if(span.bold) FontWeight.SemiBold else null,fontStyle=if(span.italic) FontStyle.Italic else null,
                        fontFamily=if(span.code) CodeFace else null,background=if(span.code) Raised2 else Color.Unspecified))
                    append(span.text);pop()
                }
            }
        }
    }
}

/** A reply as a well-set article: a serif headline, clear sections, tidy lists, quotes, tables that scroll, option rows with buttons, pictures. */
@Composable fun MarkdownText(text:String) {
    val blocks=remember(text) {ChatMarkdown.blocks(text)}
    Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
        for((index,block) in blocks.withIndex()) when(block) {
            is ChatMarkdown.Block.Heading->Text(richText(block.text),style=when(block.level) {1->Type.answer;2->Type.section;else->Type.sub},
                modifier=Modifier.padding(top=if(index==0) 0.dp else if(block.level<=2) 8.dp else 2.dp))
            is ChatMarkdown.Block.Para->Text(richText(block.text),style=Type.body)
            is ChatMarkdown.Block.Bullet->Row(Modifier.padding(start=(block.indent*14).dp),verticalAlignment=Alignment.Top) {
                if(block.marker=="•") Box(Modifier.padding(top=9.dp,end=12.dp,start=2.dp).size(6.dp).background(Accent,CircleShape))
                else Text(block.marker,style=Type.body.copy(color=Accent,fontWeight=FontWeight.SemiBold),modifier=Modifier.width(30.dp))
                Text(richText(block.text),style=Type.body,modifier=Modifier.weight(1f))
            }
            is ChatMarkdown.Block.Quote->Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(AccentDim.copy(alpha=0.55f)).padding(12.dp)) {
                Icon(Icons.Outlined.Info,null,tint=Accent,modifier=Modifier.size(18.dp).padding(top=2.dp));Spacer(Modifier.width(10.dp))
                Text(richText(block.text),style=Type.body.copy(fontSize=15.sp,lineHeight=22.sp),modifier=Modifier.weight(1f))
            }
            is ChatMarkdown.Block.Code->CodeBlock(block.text)
            is ChatMarkdown.Block.Table->TableBlock(block.rows)
            is ChatMarkdown.Block.Rows->OptionRows(block.rows)
            is ChatMarkdown.Block.Image->Photo(block.alt,block.name)
            ChatMarkdown.Block.Rule->HorizontalDivider(color=Line,modifier=Modifier.padding(vertical=4.dp))
        }
    }
}

@Composable private fun CodeBlock(code:String) {
    val chat=LocalChat.current
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Panel).border(1.dp,Line,RoundedCornerShape(12.dp))) {
        Row(Modifier.fillMaxWidth().background(Raised).padding(horizontal=12.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
            Text("CODE",style=Type.label,modifier=Modifier.weight(1f))
            Text("Copy",style=Type.small.copy(color=Accent,fontWeight=FontWeight.Medium),modifier=Modifier.clip(RoundedCornerShape(8.dp)).clickable {chat?.copy?.invoke(code)}.padding(horizontal=8.dp,vertical=2.dp))
        }
        SelectionContainer {Text(code,style=Type.mono,modifier=Modifier.horizontalScroll(rememberScrollState()).padding(12.dp))}
    }
}

/** A table as a card: the header in bold, rows separated by hairlines, wide tables scroll sideways instead of squeezing. */
@Composable private fun TableBlock(rows:List<List<String>>) {
    val columns=rows.maxOfOrNull {it.size}?:0
    val wide=columns>3 || rows.any {r->r.any {it.length>28}}
    val scroll=rememberScrollState()
    Column(Modifier.fillMaxWidth().clip(Soft).background(Panel).border(1.dp,Line,Soft).then(if(wide) Modifier.horizontalScroll(scroll) else Modifier)) {
        rows.forEachIndexed {index,row->
            Row(Modifier.then(if(wide) Modifier.width((columns*140).dp) else Modifier.fillMaxWidth()).background(if(index==0) Raised else Color.Transparent).padding(horizontal=12.dp,vertical=9.dp),
                horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                for(i in 0 until columns) Text(richText(row.getOrElse(i) {""}),Modifier.weight(1f),
                    style=(if(index==0) Type.label.copy(color=Muted) else Type.small.copy(color=Ink,fontSize=14.sp,fontWeight=if(i==0) FontWeight.Medium else FontWeight.Normal)))
            }
            if(index<rows.lastIndex) HorizontalDivider(color=Line)
        }
    }
}

/** Options with a price and a button: each row opens, calls or texts. */
@Composable private fun OptionRows(rows:List<ChatMarkdown.Row>) {
    val chat=LocalChat.current
    Column(Modifier.fillMaxWidth().clip(Soft).background(Panel).border(1.dp,Line,Soft)) {
        rows.forEachIndexed {index,row->
            Row(Modifier.fillMaxWidth().then(if(row.url.isNotBlank()) Modifier.clickable {chat?.link?.invoke(row.url)} else Modifier).padding(horizontal=14.dp,vertical=12.dp),
                verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(row.title,style=Type.sub,maxLines=2,overflow=TextOverflow.Ellipsis)
                    if(row.detail.isNotBlank()) Text(row.detail,style=Type.small,maxLines=2,overflow=TextOverflow.Ellipsis)
                }
                if(row.value.isNotBlank()) Text(row.value,style=Type.sub.copy(fontWeight=FontWeight.Bold),modifier=Modifier.padding(start=10.dp))
                if(row.action.isNotBlank()) Text(row.action,style=Type.sub.copy(color=Accent,fontSize=13.sp),
                    modifier=Modifier.padding(start=10.dp).clip(RoundedCornerShape(999.dp)).background(AccentDim).padding(horizontal=12.dp,vertical=6.dp))
            }
            if(index<rows.lastIndex) HorizontalDivider(color=Line)
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------------------------------------
// What the bot looked at
// ---------------------------------------------------------------------------------------------------------------------------------------------

/** A page the bot looked at: a screenshot of it as the bot saw it, the site and the title. Tap to see it big. */
@Composable fun SourceCard(source:Source,width:Dp=172.dp,height:Dp=108.dp,open:()->Unit) {
    val chat=LocalChat.current
    val picture by produceState<ImageBitmap?>(null,source.shot,source.image) {
        value=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {(source.shot.ifBlank {source.image}).takeIf {it.isNotBlank()}?.let {chat?.evidence?.invoke(it)}}
    }
    val calm=rememberReduceMotion()
    val shimmer=if(calm) 0.6f else {
        val pulse=rememberInfiniteTransition(label="shot")
        val value by pulse.animateFloat(0.35f,0.8f,infiniteRepeatable(tween(900,easing=FastOutSlowInEasing),RepeatMode.Reverse),label="shimmer")
        value
    }
    Column(Modifier.width(width).clip(Soft).background(Panel).border(1.dp,Line,Soft).clickable(onClick=open)) {
        Box(Modifier.fillMaxWidth().height(height).background(Raised2)) {
            val image=picture
            if(image!=null) Image(image,source.title,contentScale=ContentScale.Crop,alignment=Alignment.TopCenter,modifier=Modifier.fillMaxSize())
            else Box(Modifier.fillMaxSize().alpha(if(source.shot.isBlank() && source.image.isBlank()) shimmer else 1f),contentAlignment=Alignment.Center) {
                Text(source.domain.firstOrNull()?.uppercase()?:"•",style=Type.display.copy(fontSize=34.sp,color=Muted))
            }
        }
        Column(Modifier.padding(horizontal=10.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Box(Modifier.size(14.dp).background(AccentDim,CircleShape),contentAlignment=Alignment.Center) {Text(source.domain.firstOrNull()?.uppercase()?:"",style=Type.label.copy(fontSize=8.sp,color=Accent,letterSpacing=0.sp))}
                Spacer(Modifier.width(6.dp))
                Text(source.domain,style=Type.small.copy(fontSize=12.sp),maxLines=1,overflow=TextOverflow.Ellipsis)
            }
            val small=width<150.dp
            Text(source.title.ifBlank {source.domain},style=Type.sub.copy(fontSize=if(small) 12.sp else 13.sp,lineHeight=if(small) 15.sp else 17.sp),maxLines=2,overflow=TextOverflow.Ellipsis)
        }
    }
}

/** "What I looked at": the pages behind an answer, as cards with screenshots you can swipe through. */
@Composable fun SourceStrip(sources:List<Source>) {
    if(sources.isEmpty()) return
    val chat=LocalChat.current
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("WHAT I LOOKED AT",style=Type.label)
        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            sources.forEach {s->SourceCard(s) {chat?.openSource?.invoke(s)}}
        }
    }
}

/** The page as the bot saw it, full size, with a button to open the live page. */
@Composable fun SourceViewer(source:Source,close:()->Unit) {
    val chat=LocalChat.current
    val picture by produceState<ImageBitmap?>(null,source.shot) {value=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {source.shot.takeIf {it.isNotBlank()}?.let {chat?.evidence?.invoke(it)}}}
    Dialog(onDismissRequest=close,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxSize(),color=Background) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(start=16.dp,end=4.dp,top=6.dp),verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(source.title.ifBlank {source.domain},style=Type.sub,maxLines=2,overflow=TextOverflow.Ellipsis)
                        Text(source.domain,style=Type.small)
                    }
                    IconButton(onClick=close) {Icon(Icons.Outlined.Close,"Close",tint=Ink)}
                }
                Box(Modifier.weight(1f).fillMaxWidth().padding(12.dp).clip(RoundedCornerShape(16.dp)).background(Panel),contentAlignment=Alignment.TopCenter) {
                    val image=picture
                    if(image!=null) Image(image,source.title,contentScale=ContentScale.FillWidth,alignment=Alignment.TopCenter,modifier=Modifier.fillMaxWidth().verticalScroll(rememberScrollState()))
                    else Text("The picture of this page isn't ready, or the page wouldn't draw.",style=Type.small,modifier=Modifier.padding(32.dp))
                }
                Button(onClick={chat?.link?.invoke(source.url)},modifier=Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=10.dp).height(50.dp),shape=RoundedCornerShape(25.dp),
                    colors=ButtonDefaults.buttonColors(containerColor=Accent,contentColor=OnAccent)) {Text("Open the page",fontWeight=FontWeight.SemiBold)}
            }
        }
    }
}

/** "Searched 2 times · looked at 3 pages ›": tap for the list of steps, like the work log in ChatGPT and Perplexity. */
@Composable fun StepsLine(steps:List<String>) {
    // "1 step" says nothing (it was the bot looking in its own files): a line is only worth showing when it can say what was done.
    if(steps.isEmpty() || StepWords.summary(steps)=="1 step") return
    var open by remember {mutableStateOf(false)}
    Column {
        Row(Modifier.clip(RoundedCornerShape(10.dp)).clickable {open=!open}.padding(vertical=4.dp),verticalAlignment=Alignment.CenterVertically) {
            Icon(Icons.Outlined.TaskAlt,null,tint=Accent,modifier=Modifier.size(15.dp));Spacer(Modifier.width(7.dp))
            Text(StepWords.summary(steps),style=Type.small.copy(fontSize=13.sp))
            Icon(Icons.Outlined.ExpandMore,null,tint=Muted,modifier=Modifier.size(18.dp).rotate(if(open) 180f else 0f))
        }
        AnimatedVisibility(open) {
            Column(Modifier.padding(start=7.dp,top=2.dp,bottom=4.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                steps.forEach {s->Row(verticalAlignment=Alignment.Top) {Box(Modifier.padding(top=7.dp,end=12.dp).size(4.dp).background(Muted,CircleShape));Text(s,style=Type.small.copy(color=Ink,fontSize=13.sp))}}
            }
        }
    }
}

/** Suggested next questions, as buttons under the answer. */
@OptIn(ExperimentalLayoutApi::class)
@Composable fun FollowupChips(items:List<String>) {
    val chat=LocalChat.current
    if(items.isEmpty()) return
    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        items.forEach {text->
            Row(Modifier.minimumInteractiveComponentSize().clip(RoundedCornerShape(999.dp)).border(1.dp,Outline,RoundedCornerShape(999.dp)).clickable {chat?.ask?.invoke(text)}.padding(horizontal=14.dp,vertical=8.dp),
                verticalAlignment=Alignment.CenterVertically) {
                Icon(Icons.Outlined.SubdirectoryArrowRight,null,tint=Accent,modifier=Modifier.size(15.dp));Spacer(Modifier.width(6.dp))
                Text(text,style=Type.sub.copy(fontSize=14.sp,fontWeight=FontWeight.Medium))
            }
        }
    }
}

/** Copy, share and react, quietly under the answer. The reaction picker pops up over the react button. Each button is a full 48 dp touch target. */
@Composable fun ReplyActions(text:String,react:String,pick:(String)->Unit) {
    val chat=LocalChat.current
    var picking by remember {mutableStateOf(false)}
    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(0.dp)) {
        if(text.isNotBlank()) listOf(Triple(Icons.Outlined.ContentCopy,"Copy",{chat?.copy?.invoke(text)}),Triple(Icons.Outlined.IosShare,"Share",{chat?.share?.invoke(text)})).forEach {(icon,label,action)->
            IconButton(onClick={action()},modifier=Modifier.size(48.dp)) {Icon(icon,label,tint=Muted,modifier=Modifier.size(18.dp))}
        }
        Box {
            if(react.isBlank()) IconButton(onClick={picking=true},modifier=Modifier.size(48.dp)) {Icon(Icons.Outlined.AddReaction,"React",tint=Muted,modifier=Modifier.size(18.dp))}
            else Text(react,fontSize=15.sp,modifier=Modifier.padding(start=4.dp).minimumInteractiveComponentSize().clip(CircleShape).background(Raised2).clickable {picking=true}.padding(horizontal=8.dp,vertical=3.dp))
            if(picking) ReactionBar(react,false,{pick(it);picking=false},{picking=false})
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------------------------------------
// Messages
// ---------------------------------------------------------------------------------------------------------------------------------------------

/** The owner's message: a rounded block on the right. */
@Composable fun UserMessage(text:String,react:String,files:@Composable ColumnScope.()->Unit) {
    Column(Modifier.fillMaxWidth().padding(start=48.dp),horizontalAlignment=Alignment.End,verticalArrangement=Arrangement.spacedBy(6.dp)) {
        files()
        if(text.isNotBlank() && text!=SENT_GIF) Box(Modifier.padding(bottom=if(react.isNotBlank()) 10.dp else 0.dp)) {
            SelectionContainer {
                Text(richText(text),style=Type.body,modifier=Modifier.clip(RoundedCornerShape(20.dp,20.dp,6.dp,20.dp)).background(Raised2).padding(horizontal=16.dp,vertical=11.dp))
            }
            if(react.isNotBlank()) Text(react,fontSize=14.sp,modifier=Modifier.align(Alignment.BottomStart).offset(x=(-6).dp,y=10.dp).background(Panel,CircleShape).border(1.5.dp,Background,CircleShape).padding(horizontal=6.dp,vertical=2.dp))
        }
    }
}

/**
 * A bot's reply: its face and name, the whole answer set as it is written (the owner wants to read the answer, never to open it), the pictures and files it
 * made, the pages it looked at, how it got there, copy / share / react, and (on the newest reply only) suggested next questions as buttons. Press and hold on
 * the words to select them. Only the bot's WORK is expandable (see [LiveWork] while it works, and the steps line here): the answer is not.
 */
@Composable fun AssistantMessage(who:String,time:Long,showHeader:Boolean,m:Message,latest:Boolean,pictures:PictureHost?,react:(String)->Unit,
    showActions:Boolean=true,face:()->Unit={},files:@Composable ColumnScope.()->Unit={},extras:@Composable ColumnScope.()->Unit={}) {
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        if(showHeader) Row(verticalAlignment=Alignment.CenterVertically,modifier=Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick=face)) {
            BotAvatar(who,24.dp);Spacer(Modifier.width(8.dp))
            Text(who,style=Type.sub.copy(fontSize=14.sp,color=nameColor(who)))
            if(time>0) Text("  ·  "+TextThread.stamp(time).substringAfter("Today ").substringAfter("Yesterday "),style=Type.small.copy(fontSize=12.sp))
        }
        val sources=remember(m.evidence) {Evidence.parse(m.evidence)}
        val steps=remember(m.steps) {runCatching {org.json.JSONArray(m.steps).let {a->(0 until a.length()).map {a.optString(it)}}}.getOrDefault(emptyList())}
        CompositionLocalProvider(LocalPictures provides pictures) {SelectionContainer {MarkdownText(m.text)}}
        ResultStrip(remember(m.receipt) {Receipts.parse(m.receipt)})
        SourceStrip(sources)
        StepsLine(steps)
        files()
        if(latest) FollowupChips(m.followups)
        if(showActions || m.react.isNotBlank()) ReplyActions(m.text,m.react,react)
        extras()
    }
}

/**
 * What a bot is doing right now, in plain steps, with thumbnails of the pages it has looked at so far (like the live work view in Perplexity).
 * A quick chat with no steps shows just the typing dots.
 */
@Composable fun LiveWork(who:String,line:String,steps:List<String>,sources:List<Source>,watch:()->Unit,stop:()->Unit) {
    val chat=LocalChat.current
    var open by remember {mutableStateOf(false)}
    val calm=rememberReduceMotion()
    val glow=if(calm) 1f else {
        val pulse=rememberInfiniteTransition(label="live")
        val value by pulse.animateFloat(0.45f,1f,infiniteRepeatable(tween(800,easing=FastOutSlowInEasing),RepeatMode.Reverse),label="glow")
        value
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Panel).border(1.dp,Line,RoundedCornerShape(18.dp)).padding(14.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically,modifier=Modifier.clickable {open=!open}) {
            BotAvatar(who,24.dp,true);Spacer(Modifier.width(10.dp))
            Text(line,style=Type.sub.copy(fontSize=15.sp),modifier=Modifier.weight(1f).alpha(glow),maxLines=1,overflow=TextOverflow.Ellipsis)
            Icon(Icons.Outlined.ExpandMore,null,tint=Muted,modifier=Modifier.size(20.dp).rotate(if(open) 180f else 0f))
            // TalkBack hears what the bot is doing now and then (at most every few seconds), not every change.
            Announce(who+": "+line)
        }
        AnimatedVisibility(open) {
            Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
                if(steps.isNotEmpty()) Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    steps.takeLast(5).forEachIndexed {i,s->
                        val last=i==steps.takeLast(5).lastIndex
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            Icon(if(last) Icons.Outlined.Autorenew else Icons.Outlined.CheckCircle,null,tint=if(last) Purple else Accent,modifier=Modifier.size(15.dp));Spacer(Modifier.width(9.dp))
                            Text(s,style=Type.small.copy(color=if(last) Ink else Muted,fontSize=13.sp),maxLines=1,overflow=TextOverflow.Ellipsis)
                        }
                    }
                }
                if(sources.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    sources.takeLast(4).forEach {s->SourceCard(s,width=132.dp,height=82.dp) {chat?.openSource?.invoke(s)}}
                }
                Row {
                    TextButton(onClick=watch,contentPadding=PaddingValues(horizontal=8.dp)) {Text("Watch",style=Type.small.copy(color=Ink))}
                    TextButton(onClick=stop,contentPadding=PaddingValues(horizontal=8.dp)) {Text("Stop",style=Type.small)}
                }
            }
        }
    }
}

/** Three quiet dots for a quick chat. */
@Composable fun ThinkingDots(who:String,onClick:()->Unit) {
    val calm=rememberReduceMotion()
    val pulse=if(calm) null else rememberInfiniteTransition(label="dots")
    Row(Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick=onClick).padding(vertical=6.dp).semantics {contentDescription=who+" is thinking"},verticalAlignment=Alignment.CenterVertically) {
        BotAvatar(who,24.dp,true);Spacer(Modifier.width(12.dp))
        repeat(3) {i->
            val level=if(pulse==null) 0.7f else {
                val value by pulse.animateFloat(0.25f,1f,infiniteRepeatable(tween(520,delayMillis=i*160,easing=FastOutSlowInEasing),RepeatMode.Reverse),label="d$i")
                value
            }
            Box(Modifier.padding(end=5.dp).size(8.dp).alpha(level).background(Accent,CircleShape))
        }
    }
}

/**
 * An answer while it is being written: the same set-up article as a finished reply (headline, lists, tables), growing as the words arrive. It replaces
 * the typing dots as soon as there is text, and the finished reply takes its place without a jump (same face, name and spacing). No buttons to copy
 * or react until it is done. TalkBack is told once that the answer is being written, not for every word.
 */
@Composable fun LiveAnswer(who:String,text:String,pictures:PictureHost?,stop:()->Unit) {
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            BotAvatar(who,24.dp,true);Spacer(Modifier.width(8.dp))
            Text(who,style=Type.sub.copy(fontSize=14.sp,color=nameColor(who)))
            Text("  ·  writing…",style=Type.small.copy(fontSize=12.sp))
        }
        // The words are not announced one by one; the line above is what TalkBack reads.
        Column(Modifier.clearAndSetSemantics {}) {CompositionLocalProvider(LocalPictures provides pictures) {MarkdownText(text)}}
        TextButton(onClick=stop,contentPadding=PaddingValues(horizontal=8.dp)) {Text("Stop",style=Type.small)}
        Announce(who+" is writing an answer")
    }
}

/** What the job really did, in a few plain lines under its answer ("3 drafts saved", "0 sent"). Draws nothing when there are none. */
@Composable fun ResultStrip(lines:List<String>) {
    if(lines.isEmpty()) return
    Column(Modifier.fillMaxWidth().clip(Soft).background(Panel).border(1.dp,Line,Soft).padding(horizontal=12.dp,vertical=9.dp).semantics(mergeDescendants=true) {},
        verticalArrangement=Arrangement.spacedBy(5.dp)) {
        lines.forEach {line->
            val info=Receipts.isInfo(line)
            Row(verticalAlignment=Alignment.CenterVertically) {
                Icon(if(info) Icons.Outlined.Info else Icons.Outlined.CheckCircle,null,tint=if(info) Muted else Accent,modifier=Modifier.size(16.dp));Spacer(Modifier.width(8.dp))
                Text(line,style=Type.small.copy(color=Ink,fontSize=13.sp))
            }
        }
    }
}

/** True when the phone is set to remove animations (Settings → Accessibility → Remove animations). Endless motion (blinking, pulsing) is switched off then. */
@Composable fun rememberReduceMotion():Boolean {
    val context=androidx.compose.ui.platform.LocalContext.current
    return remember {runCatching {android.provider.Settings.Global.getFloat(context.contentResolver,android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,1f)==0f}.getOrDefault(false)}
}

/** [value] but changing at most once every [ms]: the newest value always arrives, the ones in between are skipped. */
@Composable fun rememberThrottled(value:String,ms:Long=4000):String {
    var shown by remember {mutableStateOf(value)}
    var at by remember {mutableLongStateOf(0L)}
    LaunchedEffect(value) {
        val wait=at+ms-System.currentTimeMillis()
        if(wait>0) kotlinx.coroutines.delay(wait)
        shown=value;at=System.currentTimeMillis()
    }
    return shown
}

/** A line TalkBack reads out when it changes (at most every few seconds), with nothing to see on screen. */
@Composable fun Announce(text:String) {
    val spoken=rememberThrottled(text)
    Box(Modifier.size(1.dp).semantics {liveRegion=LiveRegionMode.Polite;contentDescription=spoken})
}
