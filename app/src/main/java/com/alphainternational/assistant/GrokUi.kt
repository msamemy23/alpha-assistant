package com.alphainternational.assistant

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.content.consume
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.content.hasMediaType
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.window.Dialog
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

val attentionStates=Words.attention
private val avatarColors=listOf(Color(0xFFB9F3D1),Color(0xFF9EC9FF),Color(0xFFFFD48A),Color(0xFFF2A7E8),Color(0xFFFFAE94),Color(0xFFA6E8DF),Color(0xFFC9B6FF))
fun avatarColor(name:String)=if(name=="Alpha") avatarColors[0] else avatarColors[1+Math.floorMod(name.hashCode(),avatarColors.size-1)]
/** A bot's name in its own colour: the soft face colours are too pale to read as words on the light look, so the light look uses plain ink. */
fun nameColor(name:String)=if(Palette.light) Ink else avatarColor(name)

/**
 * Grok Bot-style identity: a simple shape with expressive eyes. Calm at rest; eyes dart and the body bobs while working.
 * With "Remove animations" on in the phone's accessibility settings it stays perfectly still (no endless blinking).
 */
@Composable fun BotAvatar(name:String,size:Dp,working:Boolean=false,modifier:Modifier=Modifier) {
    val calm=rememberReduceMotion()
    val motion=if(calm) null else rememberInfiniteTransition(label="avatar")
    val blink=if(motion==null) 1f else {
        val value by motion.animateFloat(1f,1f,infiniteRepeatable(keyframes {durationMillis=4600;1f at 0;1f at 4300;0.08f at 4400;1f at 4500}),label="blink");value
    }
    val look=if(motion==null) 0f else {
        val value by motion.animateFloat(-1f,1f,infiniteRepeatable(tween(if(working) 650 else 2800,easing=FastOutSlowInEasing),RepeatMode.Reverse),label="look");value
    }
    val bob=if(motion==null) 0f else {
        val value by motion.animateFloat(0f,1f,infiniteRepeatable(tween(420,easing=FastOutSlowInEasing),RepeatMode.Reverse),label="bob");value
    }
    val body=avatarColor(name)
    Canvas(modifier.size(size)) {
        val w=this.size.width;val h=this.size.height;val lift=if(working) bob*h*0.05f else 0f
        drawRoundRect(body,topLeft=Offset(0f,h*0.05f-lift),size=Size(w,h*0.9f),cornerRadius=CornerRadius(w*0.36f,w*0.36f))
        val eyeW=w*0.18f;val eyeH=(h*0.25f*blink).coerceAtLeast(1.5f);val eyeY=h*0.43f-lift
        for(cx in listOf(w*0.33f,w*0.67f)) {
            drawOval(Color.White,topLeft=Offset(cx-eyeW/2,eyeY-eyeH/2),size=Size(eyeW,eyeH))
            if(blink>0.5f) drawCircle(Color(0xFF101010),radius=eyeW*0.29f,center=Offset(cx+look*eyeW*(if(working)0.2f else 0.1f),eyeY+eyeH*0.1f))
        }
        drawArc(Color(0x99101010),startAngle=25f,sweepAngle=130f,useCenter=false,topLeft=Offset(w*0.4f,h*0.57f-lift),size=Size(w*0.2f,h*0.11f),
            style=Stroke(width=(w*0.04f).coerceAtLeast(1.5f),cap=StrokeCap.Round))
    }
}

// The words of a reply (headline, sections, tables, option rows, links) are set in ChatUi.kt.

/** Where a text's pictures live and what a tap on one does. Set for each message, since every bot keeps its own files. */
class PictureHost(val file:(String)->java.io.File?,val open:(String)->Unit)
val LocalPictures=compositionLocalOf<PictureHost?> {null}

/** A picture a bot put in its answer with ![what it shows](file name), loaded off the main thread. Tap to see it big. */
@Composable fun Photo(alt:String,name:String) {
    val host=LocalPictures.current
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null,name,host) {
        value=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {host?.file?.invoke(name)?.takeIf {it.isFile}?.let {Attachments.upright(it,1000).asImageBitmap()}}.getOrNull()
        }
    }
    val image=bitmap
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(4.dp)) {
        if(image!=null) Image(image,alt.ifBlank {name},contentScale=ContentScale.Fit,
            modifier=Modifier.fillMaxWidth().heightIn(max=340.dp).clip(RoundedCornerShape(14.dp)).background(Color.White).clickable {host?.open?.invoke(name)})
        else Box(Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(14.dp)).background(Raised2))
        if(alt.isNotBlank()) Text(alt,color=Muted,fontSize=12.sp)
    }
}

private fun preview(text:String)=(if(text==SENT_GIF) "sent a GIF" else text).replace(Regex("[*_`#>|]"),"").replace(Regex("\\s+")," ").trim().take(140)
private fun stamp(time:Long):String {
    val moment=Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault())
    return if(moment.toLocalDate()==LocalDate.now()) DateTimeFormatter.ofPattern("h:mm a").format(moment) else DateTimeFormatter.ofPattern("MMM d").format(moment)
}

@Composable private fun RoundButton(icon:ImageVector,label:String,onClick:()->Unit) {
    IconButton(onClick=onClick,modifier=Modifier.size(48.dp).background(FieldBg,CircleShape)) {Icon(icon,label,tint=Ink,modifier=Modifier.size(22.dp))}
}

/**
 * Home: the bots, like a messages inbox: the Team chat first, then each bot as a contact you text, then New bot. Brainstorm (the ChatGPT-like chat for
 * thinking things through) is its own row further down, not on top. Away mode, the Library and the Marketplace sit under it. This is the owner's original
 * idea of Alpha; the "front door" with a big Ask box on top was drift and is gone (2026-10-11).
 */
@Composable fun HomeScreen(c:Controller,open:(String)->Unit,newBot:()->Unit,marketplace:()->Unit,openTeam:()->Unit,settings:()->Unit,library:()->Unit={},away:()->Unit={},
    activity:MainActivity?=null,openActivity:(String)->Unit={},goals:()->Unit={},brainstorm:()->Unit={}) {
    var searching by rememberSaveable {mutableStateOf(false)}
    var query by rememberSaveable {mutableStateOf("")}
    val spaceOf=remember(c.tasks) {c.tasks.associate {it.id to it.workspace}}
    val createdOf=remember(c.tasks) {c.tasks.associate {it.id to it.created}}
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(start=20.dp,end=20.dp,top=14.dp,bottom=36.dp)) {
        item {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("ALPHA ASSISTANT",style=Type.label)
                    Text("Bots",style=Type.display,modifier=Modifier.semantics {heading()})
                }
                RoundButton(Icons.Outlined.Search,"Search bots") {searching=!searching;if(!searching) query=""}
                Spacer(Modifier.width(10.dp))
                RoundButton(Icons.Outlined.Add,"New bot",newBot)
                Spacer(Modifier.width(10.dp))
                RoundButton(Icons.Outlined.Settings,"Settings",settings)
            }
            if(searching) OutlinedTextField(query,{query=it},placeholder={Text("Search bots")},singleLine=true,shape=RoundedCornerShape(24.dp),
                modifier=Modifier.fillMaxWidth().padding(top=14.dp))
            Spacer(Modifier.height(18.dp))
        }
        // Teamwork: the group chat with every bot, listed with the bots like a group text.
        if(query.isBlank() || Words.TEAM_CHAT.contains(query,true)) item(key="teamwork") {
            val working=c.tasks.firstOrNull {it.team && it.showsAsTyping()}
            val last=c.teamMessages.lastOrNull {it.kind!="ask" && it.kind!="step"}
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable(onClick=openTeam).padding(vertical=10.dp),verticalAlignment=Alignment.CenterVertically) {
                Box(Modifier.size(54.dp)) {
                    c.bots.take(3).forEachIndexed {i,bot->
                        BotAvatar(bot.name,30.dp,working?.workspace==bot.name,Modifier.offset(x=(i%2*24).dp,y=(i/2*24+if(i==1) 6 else 0).dp))
                    }
                    if(working!=null) Box(Modifier.size(15.dp).align(Alignment.BottomEnd).background(Purple,CircleShape).border(2.dp,Background,CircleShape))
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(Words.TEAM_CHAT,style=Type.row)
                    Text(when {
                        working!=null->working.workspace+" is typing…"
                        last!=null->(if(last.role=="user") "You: " else last.bot.ifBlank {"Alpha"}+": ")+preview(last.text)
                        else->"Everyone together · "+c.bots.joinToString {it.name}
                    },style=Type.note.copy(color=if(working!=null) Purple else Muted),maxLines=2,overflow=TextOverflow.Ellipsis)
                }
            }
        }
        val shown=c.bots.filter {query.isBlank() || (it.name+" "+it.role).contains(query,true)}
        items(shown,key={it.name}) {bot->
            val working=c.tasks.any {it.workspace==bot.name && it.showsAsTyping()}
            val needs=c.tasks.count {it.workspace==bot.name && it.status in attentionStates}
            val last=c.messages.lastOrNull {!it.team && spaceOf[it.taskId]==bot.name}
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable {open(bot.name)}.padding(vertical=10.dp),verticalAlignment=Alignment.CenterVertically) {
                Box {
                    BotAvatar(bot.name,54.dp,working)
                    if(working) Box(Modifier.size(15.dp).align(Alignment.BottomEnd).background(Purple,CircleShape).border(2.dp,Background,CircleShape))
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Text(bot.name,style=Type.row,maxLines=1)
                        if(bot.name=="Alpha") Text("  ★",style=Type.tiny)
                        Spacer(Modifier.weight(1f))
                        last?.let {m->createdOf[m.taskId]?.let {Text(stamp(it),style=Type.tiny)}}
                    }
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Text(if(working) "typing…" else last?.let {(if(it.role=="user") "You: " else "")+preview(it.text)}?:bot.role,
                            Modifier.weight(1f),style=Type.note.copy(color=if(working) Purple else Muted),maxLines=2,overflow=TextOverflow.Ellipsis)
                        if(needs>0) Text(needs.toString(),style=Type.tiny.copy(color=OnAmber,fontWeight=FontWeight.Bold),
                            modifier=Modifier.padding(start=8.dp).background(Amber,CircleShape).padding(horizontal=7.dp,vertical=2.dp)
                                .semantics {contentDescription=if(needs==1) "1 job waiting for you" else "$needs jobs waiting for you"})
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(top=8.dp).clip(RoundedCornerShape(18.dp)).clickable(onClick=newBot).padding(vertical=10.dp),verticalAlignment=Alignment.CenterVertically) {
                Box(Modifier.size(54.dp).border(1.5.dp,Line,RoundedCornerShape(20.dp)),contentAlignment=Alignment.Center) {Icon(Icons.Outlined.Add,null,tint=Muted)}
                Spacer(Modifier.width(14.dp))
                Column {Text("New bot",style=Type.row);Text("Give a bot one job, like Marketing or Bookkeeping",style=Type.small)}
            }
            HorizontalDivider(color=Line,modifier=Modifier.padding(vertical=18.dp))
            // Brainstorm is its own thing: a chat for thinking, like ChatGPT. It has no bot, no tools and nothing it can send.
            if(query.isBlank() || "brainstorm".contains(query,true)) {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable(onClick=brainstorm).padding(vertical=10.dp),verticalAlignment=Alignment.CenterVertically) {
                    Box(Modifier.size(54.dp).background(AccentDim,RoundedCornerShape(20.dp)),contentAlignment=Alignment.Center) {Icon(Icons.Outlined.Lightbulb,null,tint=Accent)}
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            Text("Brainstorm",style=Type.row,modifier=Modifier.weight(1f))
                            if(c.brainstormBusy) Text("thinking…",style=Type.tiny.copy(color=Purple))
                        }
                        Text(c.brainstormLast().takeIf {it.isNotBlank()}?.let {preview(it)}?:"Talk an idea through, like with ChatGPT",style=Type.note.copy(color=Muted),maxLines=2,overflow=TextOverflow.Ellipsis)
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
            AwayCard(c,away)
            GoalsCard(c,goals)
            HomeRow(Icons.Outlined.PhotoLibrary,"Library","Everything your bots made: websites, documents, pictures, videos",library)
            HomeRow(Icons.Outlined.Storefront,"Marketplace","Connect ChatGPT, Gmail, Calendar and more",marketplace)
            Text("Alpha Assistant "+BuildConfig.VERSION_NAME+" · runs on this phone",style=Type.tiny,modifier=Modifier.padding(top=24.dp))
        }
    }
}

/** A plain row on Home: an icon, a title, one line under it, and an arrow. */
@Composable private fun HomeRow(icon:ImageVector,title:String,note:String,onClick:()->Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable(onClick=onClick).padding(vertical=10.dp),verticalAlignment=Alignment.CenterVertically) {
        Icon(icon,null,tint=Ink);Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {Text(title,style=Type.sub);Text(note,style=Type.small,maxLines=2)}
        Icon(Icons.Outlined.ChevronRight,null,tint=Muted)
    }
}

/** Chat header: name opens the profile; the computer turns purple while the bot works. */
@Composable fun ChatHeader(c:Controller,back:()->Unit,profile:()->Unit,computer:()->Unit,call:()->Unit,go:(String)->Unit,editBot:()->Unit) {
    val active=c.tasks.firstOrNull {it.workspace==c.workspace && it.showsAsTyping()}
    var menu by remember {mutableStateOf(false)}
    Row(Modifier.fillMaxWidth().padding(start=4.dp,end=4.dp,top=6.dp,bottom=6.dp),verticalAlignment=Alignment.CenterVertically) {
        IconButton(onClick=back) {Icon(Icons.AutoMirrored.Outlined.ArrowBack,"Back to bots",tint=Ink)}
        Row(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).clickable(onClick=profile).padding(4.dp),verticalAlignment=Alignment.CenterVertically) {
            BotAvatar(c.workspace,36.dp,active!=null)
            Spacer(Modifier.width(10.dp))
            Column {
                Text(c.workspace,fontWeight=FontWeight.SemiBold,fontSize=17.sp,color=Ink,maxLines=1)
                Text(when {active==null->c.bot().role;c.messages.any {it.taskId==active.id && it.role=="assistant"}->"working on it";else->"typing…"},
                    color=Muted,fontSize=12.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
            }
        }
        IconButton(onClick=call) {Icon(Icons.Outlined.Call,"Call "+c.workspace,tint=Ink)}
        Box {
            IconButton(onClick={menu=true}) {Icon(Icons.Outlined.MoreVert,"More",tint=Muted)}
            DropdownMenu(expanded=menu,onDismissRequest={menu=false}) {
                DropdownMenuItem(text={Text("Profile, memory & skills")},leadingIcon={Icon(Icons.Outlined.AutoAwesome,null)},onClick={menu=false;profile()})
                DropdownMenuItem(text={Text("Routines & activity")},leadingIcon={Icon(Icons.Outlined.Schedule,null)},onClick={menu=false;go("activity")})
                DropdownMenuItem(text={Text("Watch it work · browser")},leadingIcon={Icon(Icons.Outlined.Computer,null)},onClick={menu=false;computer()})
                DropdownMenuItem(text={Text("Teach by showing")},leadingIcon={Icon(Icons.Outlined.School,null)},onClick={menu=false;computer();c.startTeaching()})
                DropdownMenuItem(text={Text("Edit job & rules")},leadingIcon={Icon(Icons.Outlined.Tune,null)},onClick={menu=false;editBot()})
                DropdownMenuItem(text={Text("Marketplace")},leadingIcon={Icon(Icons.Outlined.Storefront,null)},onClick={menu=false;go("marketplace")})
            }
        }
    }
    HorizontalDivider(color=Line)
}

/** Header for secondary pages, keeping the bot visible as context. */
@Composable fun PageBar(c:Controller,title:String,showBot:Boolean,back:()->Unit) {
    Row(Modifier.fillMaxWidth().padding(start=4.dp,end=16.dp,top=6.dp,bottom=6.dp),verticalAlignment=Alignment.CenterVertically) {
        IconButton(onClick=back) {Icon(Icons.AutoMirrored.Outlined.ArrowBack,"Back",tint=Ink)}
        if(showBot) {BotAvatar(c.workspace,28.dp);Spacer(Modifier.width(10.dp))}
        Text(if(showBot) c.workspace+" · "+title else title,fontWeight=FontWeight.SemiBold,fontSize=16.sp,color=Ink)
    }
    HorizontalDivider(color=Line)
}

/**
 * A bot's chat, like a text thread: your texts in blue on the right, the bot's in gray on the left, a typing bubble
 * while it works (tap it to see what it's doing), and simple reply buttons when it needs something from you.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable fun BotChat(activity:MainActivity,c:Controller,openFile:(String)->Unit,call:()->Unit,connect:()->Unit,activityPage:()->Unit,computer:()->Unit={},
    signIn:(String,String)->Unit={_,_->}) {
    // The text box owns the draft. Nothing copies it back in while you type, so the keyboard's words, emoji and GIFs are
    // never overwritten mid-word; the skills menu, dictation and suggestions replace it through draft().
    val field=rememberSaveable(c.workspace,saver=androidx.compose.foundation.text.input.TextFieldState.Saver) {androidx.compose.foundation.text.input.TextFieldState()}
    val input=field.text.toString()
    fun draft(text:String)=field.setTextAndPlaceCursorAtEnd(text)
    var peek by remember {mutableStateOf(false)}
    var reacting by remember {mutableStateOf("")}
    val pictures=remember(c.workspace) {PictureHost({name->c.pictureFile(name,"")},{name->openFile(name)})}
    var showLearned by remember {mutableStateOf("")}
    var attach by remember {mutableStateOf(false)}
    var attached by remember(c.workspace) {mutableStateOf(listOf<String>())}
    val taskOf=remember(c.tasks) {c.tasks.associateBy {it.id}}
    val history=c.messages.filter {!it.team && taskOf[it.taskId]?.workspace==c.workspace && it.role in setOf("user","assistant","update")}
    val active=c.tasks.firstOrNull {it.workspace==c.workspace && it.showsAsTyping()}
    val waiting=c.tasks.any {it.workspace==c.workspace && it.status=="Queued" && it.from==OWNER_QUEUE}
    val list=rememberLazyListState()
    val send={if(c.submit(field.text.toString(),attached)!=null) {draft("");attached=emptyList()}}
    DisposableEffect(c.workspace) {
        activity.onSpeech={draft(it)}
        activity.onAttached={name->attached=(attached+name).distinct().take(8)}
        onDispose {activity.onSpeech=null;activity.onAttached=null}
    }
    // Something shared to Alpha from another app: it waits in the box (and as an attachment) until the owner sends it.
    activity.shared?.let {item->
        LaunchedEffect(item.id) {
            if(item.text.isNotBlank()) draft(item.text)
            if(item.files.isNotEmpty()) attached=(attached+item.files).distinct().take(8)
            activity.consumeShared(item)
        }
    }
    var viewing by remember {mutableStateOf<Source?>(null)}
    val actions=remember(c,activity) {ChatActions(link={activity.openLink(it)},copy={activity.copyText(it)},share={activity.shareText(it)},
        ask={c.submit(it,emptyList())},openSource={viewing=it},evidence={c.evidencePicture(it)})}
    // One row per message; a reply is one block (not a string of bubbles), with its pictures, steps and buttons under it.
    val rows=remember(history,taskOf) {
        history.map {m->Triple(m,if(m.role=="user") m.text else TextThread.links(m.text).first.trim(),0)}
    }
    LaunchedEffect(rows.size,active!=null,c.tasks.firstOrNull {it.workspace==c.workspace}?.status) {
        if(rows.isNotEmpty()) {kotlinx.coroutines.delay(60);list.animateScrollToItem(list.layoutInfo.totalItemsCount.coerceAtLeast(1)-1)}
    }
    // While an answer is being written, follow it down as it grows, but only if the owner is looking at it (scrolling up to read stops the following).
    val growth=c.liveTextOf(active?.id.orEmpty()).length/150
    LaunchedEffect(growth) {
        val info=list.layoutInfo
        if(growth>0 && info.visibleItemsInfo.lastOrNull()?.index==info.totalItemsCount-1) list.scrollBy(2000f)
    }
    CompositionLocalProvider(LocalChat provides actions) {
    Column(Modifier.fillMaxSize().imePadding()) {
        BrowserPanel(activity,c,computer)
        if(history.isEmpty() && active==null) Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal=22.dp,vertical=24.dp),
            horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center) {
            BotAvatar(c.workspace,84.dp)
            Text(c.workspace,fontSize=24.sp,fontWeight=FontWeight.SemiBold,color=Ink,modifier=Modifier.padding(top=16.dp))
            Text(c.bot().role,color=Muted,textAlign=TextAlign.Center,modifier=Modifier.padding(top=4.dp,bottom=22.dp))
            if(!c.aiConnected()) {
                Button(onClick=connect,colors=ButtonDefaults.buttonColors(containerColor=Strong,contentColor=OnStrong)) {Text("Connect your ChatGPT plan")}
                TextButton(onClick={c.openProviderSettings()}) {Text("or use an API key or another AI",color=Muted)}
            }
            else FrontDoor.suggestions(BuildConfig.SHOP_NAME,Profile.generic).take(5).forEach {idea->
                Text(idea,style=Type.chip.copy(fontSize=15.sp),modifier=Modifier.padding(vertical=4.dp).minimumInteractiveComponentSize().clip(RoundedCornerShape(20.dp)).border(1.dp,Outline,RoundedCornerShape(20.dp))
                    .clickable {draft(FrontDoor.fill(idea))}.padding(horizontal=16.dp,vertical=10.dp))
            }
        } else LazyColumn(state=list,modifier=Modifier.weight(1f).fillMaxWidth(),contentPadding=PaddingValues(horizontal=12.dp,vertical=14.dp),verticalArrangement=Arrangement.spacedBy(3.dp,Alignment.Bottom)) {
            itemsIndexed(rows,key={_,row->row.first.id+"-"+row.third}) {i,row->
                val m=row.first;val part=row.second
                val mine=m.role=="user"
                val previous=rows.getOrNull(i-1)
                val time=m.at.takeIf {it>0}?:taskOf[m.taskId]?.created?:0L
                val previousTime=previous?.first?.let {p->p.at.takeIf {it>0}?:taskOf[p.taskId]?.created}
                val sameAsPrevious=previous!=null && (previous.first.role=="user")==mine
                val lastOfJob=m.role=="assistant" && history.lastOrNull {it.taskId==m.taskId && it.role=="assistant"}?.id==m.id
                Column(Modifier.padding(top=if(sameAsPrevious) 2.dp else 14.dp)) {
                    if(TextThread.showStamp(previousTime,time)) TimeLine(time)
                    if(mine) UserMessage(part,m.react) {m.files.forEach {name->AttachmentChip(c,name,big=true) {openFile(name)}}}
                    else AssistantMessage(c.workspace,time,!sameAsPrevious || TextThread.showStamp(previousTime,time),m.copy(text=part),rows.lastOrNull()?.first?.id==m.id && active==null,
                        pictures,{c.react(m.id,it)},showActions=lastOfJob,
                        // What the bot made rides along under the text, expanded or not.
                        files={if(lastOfJob) c.artifacts(m.taskId).filter {it !in Pictures.shown(m.text)}.forEach {name->AttachmentChip(c,name,big=true) {openFile(name)}}}) {
                        // Its notes show when the reply is expanded.
                        if(m.evidence.isBlank() && m.followups.isEmpty()) LinkChips(remember(m.text) {TextThread.links(m.text).second}) {activity.openLink(it)}
                        CollabLine(c.collabsFor(m.taskId))
                        if(m.model.isNotBlank() && c.aiSettings.label) Text(m.model,color=Muted,fontSize=10.sp)
                        // What the bot learned from this conversation, quietly, with a tap to see or forget it.
                        val learned=if(lastOfJob) c.memories.filter {it.task==m.taskId && it.workspace==c.workspace} else emptyList()
                        if(learned.isNotEmpty()) Text("Remembered: "+learned.first().title+(if(learned.size>1) " +"+(learned.size-1) else ""),color=Muted,fontSize=11.sp,
                            maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.clip(RoundedCornerShape(10.dp)).clickable {showLearned=m.taskId}.padding(horizontal=4.dp,vertical=2.dp))
                    }
                }
            }
            if(waiting && active==null) item {Text("Waiting its turn…",color=Muted,fontSize=11.sp,textAlign=TextAlign.End,modifier=Modifier.fillMaxWidth().padding(end=6.dp))}
            if(active!=null) item(key="typing") {
                Column(Modifier.padding(top=14.dp)) {
                    // The answer grows here as it is written. Before there are words: once real work starts (the "on it" text went out, or the bot has taken
                    // steps) the live card shows what it is doing and the pages it has looked at; a quick chat just shows three quiet dots.
                    val posted=history.filter {it.taskId==active.id && it.role=="assistant"}
                    LiveJob(c,active,posted.lastOrNull()?.text,posted.isNotEmpty(),pictures,peek,{peek=!peek},computer)
                }
            }
            // When the bot needs something, its last text says so in plain words; these are the one-tap answers.
            val stuck=c.tasks.firstOrNull {it.workspace==c.workspace && it.routineId.isBlank() && it.from!=OWNER_QUEUE}
                ?.takeIf {active==null && (it.status in setOf("Paused","Needs you","Needs connection","Needs review","Check result") || c.isCutShort(it.id))}
            if(stuck!=null) item(key="replies-"+stuck.id) {
                val site=stuck.waitingConnection.removePrefix("site:").takeIf {stuck.waitingConnection.startsWith("site:")}
                val label=WorkWords.connectionLabel(stuck.waitingConnection)
                Box(Modifier.padding(top=6.dp)) {
                    QuickReplies(when {
                        site!=null->listOf(("Sign in to "+(android.net.Uri.parse(site).host?.removePrefix("www.")?:"the site")) to {signIn(site,stuck.id)},"Never mind" to {c.cancel(stuck.id)})
                        stuck.status=="Needs connection"->listOf(("Connect "+label) to connect,"Done, keep going" to {c.resume(stuck.id)})
                        stuck.status=="Needs review"->listOf("Review" to {c.reopenReview(stuck.id)},"Never mind" to {c.cancel(stuck.id)})
                        stuck.status=="Check result"->listOf("What happened?" to activityPage,"Never mind" to {c.cancel(stuck.id)})
                        stuck.detail.contains("Allow texts")->listOf("Allow texts" to {activity.requestSms {ok->if(ok) c.resume(stuck.id)}},"Never mind" to {c.cancel(stuck.id)})
                        else->listOf("Keep going" to {c.resume(stuck.id)},"Never mind" to {c.cancel(stuck.id)})
                    })
                }
            }
        }
        if(input.startsWith("/")) {
            val query=input.drop(1).trim().lowercase()
            val skills=Skills.all(c.userSkills).filter {query.isBlank() || (it.getString("title")+" "+it.getString("id")).lowercase().contains(query)}.take(6)
            Column(Modifier.fillMaxWidth().padding(horizontal=14.dp,vertical=6.dp).background(SheetBg,RoundedCornerShape(18.dp)).padding(vertical=6.dp)) {
                Text("SKILLS",color=Muted,fontSize=10.sp,letterSpacing=1.2.sp,modifier=Modifier.padding(horizontal=14.dp,vertical=4.dp))
                if(skills.isEmpty()) Text("No skill matches. Ask "+c.workspace+" to save one.",color=Muted,fontSize=13.sp,modifier=Modifier.padding(14.dp))
                skills.forEach {skill->
                    Row(Modifier.fillMaxWidth().clickable {draft("Use the “"+skill.getString("title")+"” skill (id "+skill.getString("id")+"): ")}.padding(horizontal=14.dp,vertical=9.dp),
                        verticalAlignment=Alignment.CenterVertically) {
                        Icon(if(skill.getString("source")=="built-in") Icons.Outlined.AutoAwesome else Icons.Outlined.School,null,tint=Ink,modifier=Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Column {Text(skill.getString("title"),fontSize=14.sp,color=Ink);Text(skill.getString("purpose"),fontSize=11.sp,color=Muted,maxLines=1,overflow=TextOverflow.Ellipsis)}
                    }
                }
            }
        }
        if(attached.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start=14.dp,end=14.dp,top=8.dp),
            horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            attached.forEach {name->
                Box {
                    AttachmentChip(c,name,big=false) {attached=attached-name}
                    Box(Modifier.align(Alignment.TopEnd).size(22.dp).background(Background,CircleShape).clickable {attached=attached-name},
                        contentAlignment=Alignment.Center) {Icon(Icons.Outlined.Close,"Remove $name",tint=Ink,modifier=Modifier.size(14.dp))}
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal=10.dp,vertical=8.dp),verticalAlignment=Alignment.Bottom) {
            Box {
                IconButton(onClick={attach=true},modifier=Modifier.size(44.dp).background(FieldBg,CircleShape)) {Icon(Icons.Outlined.Add,"Add a photo or file",tint=Ink)}
                DropdownMenu(expanded=attach,onDismissRequest={attach=false}) {
                    DropdownMenuItem(text={Text("Take a photo")},leadingIcon={Icon(Icons.Outlined.PhotoCamera,null)},onClick={attach=false;activity.takePhoto()})
                    DropdownMenuItem(text={Text("Add a photo")},leadingIcon={Icon(Icons.Outlined.Image,null)},onClick={attach=false;activity.pickPhoto()})
                    DropdownMenuItem(text={Text("Attach a document or PDF")},leadingIcon={Icon(Icons.Outlined.Description,null)},onClick={attach=false;activity.pickDocument()})
                    DropdownMenuItem(text={Text("Use a skill")},leadingIcon={Icon(Icons.Outlined.AutoAwesome,null)},onClick={attach=false;draft("/")})
                }
            }
            Spacer(Modifier.width(8.dp))
            TextBox(activity,field,"Text "+c.workspace,Modifier.weight(1f)) {send()}
            Spacer(Modifier.width(8.dp))
            if(input.isBlank() && attached.isEmpty()) IconButton(onClick=call,modifier=Modifier.size(44.dp).background(FieldBg,CircleShape)) {Icon(Icons.Outlined.GraphicEq,"Call "+c.workspace,tint=Ink)}
            else FilledIconButton(onClick={send()},modifier=Modifier.size(44.dp),
                colors=IconButtonDefaults.filledIconButtonColors(containerColor=MineBubble,contentColor=Color.White)) {Icon(Icons.Outlined.ArrowUpward,"Send")}
        }
    }
    viewing?.let {SourceViewer(it) {viewing=null}}
    }
    if(showLearned.isNotBlank()) LearnedDialog(c,showLearned) {showLearned=""}
}

/**
 * The box you type in, in a bot's chat and in Teamwork. Emoji, word suggestions and the keyboard's GIF and sticker
 * buttons all work here: a GIF or sticker is attached like a photo, and the bot sees it.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable fun TextBox(activity:MainActivity,field:androidx.compose.foundation.text.input.TextFieldState,placeholder:String,modifier:Modifier,send:()->Unit) {
    val gifs=remember(activity) {androidx.compose.foundation.content.ReceiveContentListener {content->
        if(!content.hasMediaType(androidx.compose.foundation.content.MediaType.Image)) content
        else content.consume {item->item.uri?.let {uri->activity.importImage(uri,content.clipMetadata.clipDescription.getMimeType(0));true}?:false}
    }}
    BasicTextField(state=field,modifier=modifier.contentReceiver(gifs).focusOnAsk(activity),
        textStyle=MaterialTheme.typography.bodyLarge.copy(color=Ink),cursorBrush=androidx.compose.ui.graphics.SolidColor(Ink),
        lineLimits=androidx.compose.foundation.text.input.TextFieldLineLimits.MultiLine(maxHeightInLines=5),
        keyboardOptions=KeyboardOptions(capitalization=androidx.compose.ui.text.input.KeyboardCapitalization.Sentences,imeAction=ImeAction.Send),
        onKeyboardAction={send()},
        decorator={inner->
            Row(Modifier.background(FieldBg,RoundedCornerShape(22.dp)).padding(start=16.dp,end=2.dp),verticalAlignment=Alignment.CenterVertically) {
                Box(Modifier.weight(1f).padding(vertical=12.dp)) {
                    if(field.text.isEmpty()) Text(placeholder,color=Muted,fontSize=15.sp,maxLines=1)
                    inner()
                }
                IconButton(onClick={activity.speak()}) {Icon(Icons.Outlined.Mic,"Talk instead of typing",tint=Muted)}
            }
        })
}

/** A photo shows as a thumbnail; a PDF or other file as a labelled chip. */
@Composable fun AttachmentChip(c:Controller,name:String,big:Boolean,taskId:String="",onClick:()->Unit) {
    val image=Attachments.kind(name)=="image"
    // The file lives in the folder of the bot that made it (Teamwork shows every bot's files).
    val source=remember(name,taskId,c.workspace) {runCatching {if(taskId.isBlank()) c.file(name) else c.fileFor(name,taskId)}.getOrNull()}
    // GIFs and stickers play, like in a text thread; a bot's animated emoji shows big with no frame.
    if(Gifs.plays(name) && source?.isFile==true) {
        if(Gifs.isEmoji(name)) AnimatedGif(source,Modifier.size(if(big) 120.dp else 64.dp).clickable(onClick=onClick),fit=true)
        else AnimatedGif(source,Modifier.size(if(big) 200.dp else 64.dp).clip(RoundedCornerShape(16.dp)).clickable(onClick=onClick))
        return
    }
    // Sounds and videos play right in the chat.
    if(Attachments.kind(name)=="audio" && source?.isFile==true) {AudioChip(source,name);return}
    if(Attachments.kind(name)=="video" && source?.isFile==true) {VideoChip(source,name,onClick);return}
    val thumb=remember(name,source) {
        if(!image || source==null) null else runCatching {Attachments.upright(source,if(big) 900 else 240).asImageBitmap()}.getOrNull()
    }
    // Photos, charts and drawings keep their shape in chat; the small previews in the composer are squares.
    if(thumb!=null && big) Image(thumb,name,contentScale=ContentScale.Fit,
        modifier=Modifier.widthIn(max=270.dp).heightIn(max=320.dp).clip(RoundedCornerShape(16.dp)).background(Color.White).clickable(onClick=onClick))
    else if(thumb!=null) Image(thumb,name,contentScale=ContentScale.Crop,
        modifier=Modifier.size(64.dp).clip(RoundedCornerShape(16.dp)).clickable(onClick=onClick))
    else if(name.endsWith(".html",true) && big) Column(Modifier.width(250.dp).clip(RoundedCornerShape(16.dp)).background(Panel).border(1.dp,Line,RoundedCornerShape(16.dp)).clickable(onClick=onClick)) {
        // The page as it looks on a phone, then what it is.
        ThingPicture(c,(if(taskId.isBlank()) c.workspace else c.botOf(taskId)?:c.workspace),name,Modifier.fillMaxWidth().height(170.dp))
        Row(Modifier.padding(horizontal=12.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically) {
            Icon(Icons.Outlined.Language,null,tint=Accent,modifier=Modifier.size(20.dp));Spacer(Modifier.width(10.dp))
            Column {Text("Open it",style=Type.sub.copy(fontSize=14.sp));Text(name,style=Type.small.copy(fontSize=12.sp),maxLines=1,overflow=TextOverflow.Ellipsis)}
        }
    }
    else if(name.endsWith(".html",true)) Row(Modifier.clip(RoundedCornerShape(16.dp)).background(Raised2).clickable(onClick=onClick).padding(horizontal=14.dp,vertical=12.dp),
        verticalAlignment=Alignment.CenterVertically) {
        Icon(Icons.Outlined.Language,null,tint=Ink,modifier=Modifier.size(22.dp));Spacer(Modifier.width(10.dp))
        Column {Text("Open the website",color=Ink,fontSize=14.sp,fontWeight=FontWeight.SemiBold);Text(name,color=Muted,fontSize=12.sp,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.widthIn(max=200.dp))}
    }
    else Row(Modifier.clip(RoundedCornerShape(14.dp)).background(Raised2).clickable(onClick=onClick).padding(horizontal=12.dp,vertical=10.dp),
        verticalAlignment=Alignment.CenterVertically) {
        Icon(if(name.endsWith(".pdf",true)) Icons.Outlined.PictureAsPdf else if(image) Icons.Outlined.Image else Icons.Outlined.Description,null,tint=Ink,modifier=Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp));Text(name,color=Ink,fontSize=13.sp,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.widthIn(max=200.dp))
    }
}

/** New bot: pick a Grok Bot-style specialist template or describe your own. */
@Composable fun NewBotDialog(c:Controller,dismiss:()->Unit,created:()->Unit) {
    var name by remember {mutableStateOf("")}
    var role by remember {mutableStateOf("")}
    var rules by remember {mutableStateOf("")}
    var error by remember {mutableStateOf("")}
    Dialog(onDismissRequest=dismiss) {
        Surface(shape=RoundedCornerShape(28.dp),color=SheetBg) {
            Column(Modifier.padding(22.dp).verticalScroll(rememberScrollState()).imePadding(),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    BotAvatar(name.ifBlank {"New"},44.dp);Spacer(Modifier.width(12.dp))
                    Column {Text("New bot",style=MaterialTheme.typography.titleLarge,color=Ink);Text("Give it one clear job",color=Muted,fontSize=13.sp)}
                }
                Text("START FROM A TEMPLATE",fontSize=10.sp,letterSpacing=1.1.sp,color=Muted)
                Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    BotTemplates.all.filter {t->c.bots.none {it.name.equals(t.name,true)}}.forEach {template->
                        FilterChip(selected=name==template.name,onClick={name=template.name;role=template.role;rules=template.rules},label={Text(template.name)})
                    }
                }
                OutlinedTextField(name,{name=it},label={Text("Name")},singleLine=true,modifier=Modifier.fillMaxWidth())
                OutlinedTextField(role,{role=it},label={Text("Its job")},minLines=2,modifier=Modifier.fillMaxWidth())
                OutlinedTextField(rules,{rules=it},label={Text("Standing rules (optional)")},minLines=2,modifier=Modifier.fillMaxWidth())
                if(error.isNotBlank()) Text(error,color=MaterialTheme.colorScheme.error,fontSize=12.sp)
                Button(onClick={
                    runCatching {c.createBot(name,role);if(rules.isNotBlank())c.updateBot(c.workspace,role,rules)}
                        .onSuccess {created()}.onFailure {error=it.message.orEmpty()}
                },modifier=Modifier.fillMaxWidth().height(50.dp),shape=RoundedCornerShape(25.dp),
                    colors=ButtonDefaults.buttonColors(containerColor=Strong,contentColor=OnStrong)) {Text("Create bot",fontWeight=FontWeight.SemiBold)}
                TextButton(onClick=dismiss,modifier=Modifier.align(Alignment.CenterHorizontally)) {Text("Cancel",color=Muted)}
            }
        }
    }
}

/** Approval card: what the bot wants to do, in plain words, before anything happens. */
@Composable fun ReviewDialog(c:Controller,review:Approval,later:()->Unit,computer:()->Unit,approve:()->Unit={c.approve()}) {
    val summary=remember(review) {Reviews.summary(review.tool,review.arguments)}
    var details by remember {mutableStateOf(false)}
    val bot=c.tasks.firstOrNull {it.id==review.taskId}?.workspace?:c.workspace
    Dialog(onDismissRequest={}) {
        Surface(shape=RoundedCornerShape(28.dp),color=SheetBg) {
            Column(Modifier.padding(22.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    BotAvatar(bot,42.dp);Spacer(Modifier.width(12.dp))
                    Column {Text(bot+" needs your OK",color=Muted,fontSize=12.sp);Text(summary.title,style=MaterialTheme.typography.titleLarge,color=Ink)}
                }
                summary.rows.forEach {(label,value)->
                    Column {
                        Text(label.uppercase(),fontSize=10.sp,letterSpacing=1.1.sp,color=Muted)
                        SelectionContainer {Text(c.redact(value).take(4000),color=Ink,style=MaterialTheme.typography.bodyMedium)}
                    }
                }
                if(review.preview.isNotBlank()) SelectionContainer {Text(c.redact(review.preview),fontSize=12.sp,color=Muted)}
                if(review.tool=="browser_click") Text("This clicks the selected element on the website. Look at the computer first if you're unsure.",color=Muted,fontSize=12.sp)
                TextButton(onClick={details=!details},contentPadding=PaddingValues(0.dp)) {Text(if(details) "Hide details" else "Show details",color=Muted,fontSize=12.sp)}
                if(details) SelectionContainer {Text(c.redact(runCatching {JSONObject(review.arguments).toString(2)}.getOrDefault(review.arguments)),fontFamily=FontFamily.Monospace,fontSize=11.sp,color=Muted)}
                if(review.tool=="sms_send") Text("It's sent from your phone number and shows up in your Messages app.",color=Muted,fontSize=12.sp)
                Button(onClick=approve,modifier=Modifier.fillMaxWidth().height(50.dp),shape=RoundedCornerShape(25.dp),
                    colors=ButtonDefaults.buttonColors(containerColor=Strong,contentColor=OnStrong)) {Text("Approve",fontWeight=FontWeight.SemiBold)}
                OutlinedButton(onClick={c.skipReview()},modifier=Modifier.fillMaxWidth().height(50.dp),shape=RoundedCornerShape(25.dp)) {Text("Decline",color=Ink)}
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.Center) {
                    TextButton(onClick=later) {Text("Decide later",color=Muted)}
                    if(review.tool.startsWith("browser_")) TextButton(onClick=computer) {Text("Look at the computer",color=Muted)}
                }
            }
        }
    }
}
