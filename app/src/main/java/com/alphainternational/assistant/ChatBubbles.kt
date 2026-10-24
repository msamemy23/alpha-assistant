package com.alphainternational.assistant

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Text-message look: your texts are blue on the right, the bot's are gray on the left, like the Messages app. */
val MineBubble=Color(0xFF0A84FF)

object TextThread {
    /** A bot's reply as the separate texts a person would send: one per paragraph, unless it has lists, tables or code. */
    fun texts(reply:String):List<String> {
        val body=links(reply).first.trim()
        if(body.isBlank()) return emptyList()
        val parts=body.split(Regex("\\n\\s*\\n")).map {it.trim()}.filter {it.isNotBlank()}
        val structured=parts.any {p->p.lines().any {l->l.trimStart().let {it.startsWith("|") || it.startsWith("```") || it.startsWith("#")}}}
        return if(structured || parts.size>5) listOf(body) else parts
    }
    /** Splits off a "Sources" list (shown as small link chips instead) and collects the links in the reply. */
    fun links(reply:String):Pair<String,List<String>> {
        val cut=Regex("\\n\\s*\\**Sources\\**\\s*\\n").find(reply)
        val body=if(cut==null) reply else reply.substring(0,cut.range.first)
        val urls=Regex("""https://[^\s)<>\]]+""").findAll(reply).map {it.value.trimEnd('.',',',';',']','`',')')}
            .distinct().filter {runCatching {Rules.httpsUrl(it)}.isSuccess}.take(6).toList()
        return body to urls
    }
    /** "Today 1:35 PM", "Yesterday 9:02 AM", "Mon, Oct 2 4:10 PM" — shown when a conversation picks up after a break. */
    fun stamp(time:Long,today:LocalDate=LocalDate.now()):String {
        val moment=Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault())
        val clock=DateTimeFormatter.ofPattern("h:mm a").format(moment)
        return when(moment.toLocalDate()) {
            today->"Today $clock"
            today.minusDays(1)->"Yesterday $clock"
            else->DateTimeFormatter.ofPattern("EEE, MMM d").format(moment)+" "+clock
        }
    }
    /** A time line goes above a text when it's the first one, or 30 minutes passed since the one before. */
    fun showStamp(previous:Long?,current:Long)=current>0 && (previous==null || previous<=0 || current-previous>30*60_000)
}

@Composable fun TimeLine(time:Long) {
    Text(TextThread.stamp(time),color=Muted,fontSize=11.sp,textAlign=TextAlign.Center,modifier=Modifier.fillMaxWidth().padding(top=6.dp,bottom=2.dp))
}

/** The row of emoji that pops up over a text you tap. Tap one to react; tap the same again to take it back. */
@Composable fun ReactionBar(current:String,mine:Boolean,pick:(String)->Unit,dismiss:()->Unit) {
    androidx.compose.ui.window.Popup(alignment=if(mine) Alignment.TopEnd else Alignment.TopStart,offset=androidx.compose.ui.unit.IntOffset(0,-150),
        onDismissRequest=dismiss,properties=androidx.compose.ui.window.PopupProperties(focusable=true)) {
        Row(Modifier.widthIn(max=320.dp).clip(RoundedCornerShape(26.dp)).background(Raised3).border(1.dp,Outline,RoundedCornerShape(26.dp))
            .horizontalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal=6.dp,vertical=6.dp),horizontalArrangement=Arrangement.spacedBy(2.dp)) {
            Reactions.quick.forEach {emoji->
                Text(emoji,fontSize=26.sp,modifier=Modifier.clip(CircleShape).background(if(emoji==current) Outline else Color.Transparent)
                    .clickable {pick(emoji)}.padding(horizontal=8.dp,vertical=6.dp))
            }
        }
    }
}

/** Quick replies under the last text, like suggested replies in Messages. */
@Composable fun QuickReplies(replies:List<Pair<String,()->Unit>>) {
    Row(Modifier.fillMaxWidth().padding(start=36.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        replies.forEach {(label,action)->
            Text(label,color=Ink,fontSize=14.sp,fontWeight=FontWeight.Medium,modifier=Modifier.minimumInteractiveComponentSize().clip(RoundedCornerShape(18.dp))
                .border(1.dp,Outline,RoundedCornerShape(18.dp)).clickable(onClick=action).padding(horizontal=14.dp,vertical=8.dp))
        }
    }
}

/** Links in a text become small chips under it (site name only), like link previews. */
@Composable fun LinkChips(urls:List<String>,open:(String)->Unit) {
    if(urls.isEmpty()) return
    Row(Modifier.padding(top=2.dp),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
        urls.take(3).forEach {url->
            Text(android.net.Uri.parse(url).host.orEmpty().removePrefix("www."),color=Ink,fontSize=12.sp,maxLines=1,
                modifier=Modifier.clip(RoundedCornerShape(12.dp)).background(Raised2).clickable {open(url)}.padding(horizontal=10.dp,vertical=6.dp))
        }
    }
}

/** What a bot is doing, in plain words for the peek under the typing dots. */
object WorkWords {
    fun plain(detail:String):String {
        val d=detail.lowercase()
        return when {
            d.startsWith("browser")->"Using the web browser"
            d.startsWith("web fetch") || d.startsWith("web search")->"Reading the web"
            d.startsWith("image")->"Finding a photo"
            d.startsWith("picture draw") || d.startsWith("chart make")->"Drawing the picture"
            d.startsWith("site build")->"Building the website"
            d.startsWith("site publish")->"Putting the website online"
            d.startsWith("sms")->"Sending a text"
            d.startsWith("gmail")->"Working in your email"
            d.startsWith("calendar")->"Checking your calendar"
            d.startsWith("drive") || d.startsWith("docs") || d.startsWith("sheets")->"Working in Google Drive"
            d.contains("pdf")->"Making the document"
            d.startsWith("run code") || d.startsWith("tool run")->"Doing the math"
            d.startsWith("mcp")->"Working in your apps"
            d.startsWith("memory")->"Remembering that"
            d.startsWith("routine")->"Setting up the routine"
            d.startsWith("bot")->"Talking with your other bots"
            d.startsWith("task finish")->"Wrapping up"
            d.startsWith("task plan") || d.startsWith("thinking")->"Thinking"
            else->"Working on it"
        }
    }
    fun connectionLabel(name:String)=when(name) {
        "gmail"->"Gmail";"calendar"->"Google Calendar";"drive"->"Google Drive";"tasks"->"Google Tasks";"contacts"->"Google Contacts";"google"->"Google"
        "supabase"->"Supabase";"vercel"->"Vercel";"telnyx"->"Telnyx";"mcp"->"the app";else->"the account"
    }
}

/** Kinds of things a bot learns, in plain words and in the order they matter most. */
fun WorkWords.kindLabel(kind:String)=when(kind) {
    "summary"->"Where things stand";"preference"->"How you like it";"habit"->"Your habit";"person"->"Person";"lesson"->"Lesson";else->"Fact"
}
fun WorkWords.kindOrder(kind:String)=listOf("summary","preference","habit","person","fact","lesson").indexOf(kind).let {if(it<0) 4 else it}

/** What a bot learned from one conversation, with Forget for anything that's wrong. */
@Composable fun LearnedDialog(c:Controller,taskId:String,close:()->Unit) {
    val learned=c.memories.filter {it.task==taskId}
    if(learned.isEmpty()) {close();return}
    androidx.compose.material3.AlertDialog(onDismissRequest=close,containerColor=SheetBg,
        title={Text("What I remembered")},
        text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
            learned.forEach {m->
                Row(verticalAlignment=Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Text(WorkWords.kindLabel(m.kind),color=Muted,fontSize=11.sp)
                        Text(m.title,color=Ink,fontWeight=FontWeight.SemiBold,fontSize=14.sp)
                        Text(m.text,color=Ink,fontSize=13.sp)
                    }
                    androidx.compose.material3.TextButton(onClick={c.deleteMemory(m.id)}) {Text("Forget",color=Muted)}
                }
            }
        }},
        confirmButton={androidx.compose.material3.TextButton(onClick=close) {Text("Done",color=Ink)}})
}

/** The quick "on it" text a person sends before going off to do the work. */
object Acknowledge {
    private val lines=listOf("On it 👍 I'll text you when it's done.","Got it! Working on it now, I'll text you when it's ready.",
        "On it. I'll let you know when it's done 👍","Sure thing, I'll text you back as soon as it's ready.")
    fun line(request:String)=lines[Math.floorMod(request.hashCode(),lines.size)]
}

/** An animated GIF or WebP from the bot's files, playing on a loop (Android's own decoder; no extra libraries). [fit] shows it whole. */
@Composable fun AnimatedGif(file:java.io.File,modifier:Modifier,fit:Boolean=false) {
    androidx.compose.ui.viewinterop.AndroidView(factory={context->
        android.widget.ImageView(context).apply {scaleType=if(fit) android.widget.ImageView.ScaleType.FIT_CENTER else android.widget.ImageView.ScaleType.CENTER_CROP}
    },update={view->
        if(view.tag!=file.path) {
            view.tag=file.path
            runCatching {android.graphics.ImageDecoder.decodeDrawable(android.graphics.ImageDecoder.createSource(file))}.getOrNull()?.let {drawable->
                view.setImageDrawable(drawable)
                (drawable as? android.graphics.drawable.AnimatedImageDrawable)?.let {it.repeatCount=android.graphics.drawable.AnimatedImageDrawable.REPEAT_INFINITE;it.start()}
            }
        }
    },modifier=modifier)
}

/** "Asked Customer Desk (done) · Estimate Invoice Pro (waiting)": which colleagues a bot asked for this reply, in one quiet line. */
@Composable fun CollabLine(items:List<Controller.Collab>) {
    if(items.isEmpty()) return
    val text=items.joinToString(" · ") {
        "Asked "+it.colleague+" ("+when(it.status) {"asked","waiting"->"waiting";"answer"->"done";"need-input"->"needs info";"cannot","no answer"->"couldn't answer";"not-mine"->"not theirs";else->it.status}+")"
    }
    Text(text,color=Muted,fontSize=11.sp,modifier=Modifier.padding(start=4.dp,top=2.dp))
}
