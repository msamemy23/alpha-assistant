package com.alphainternational.assistant

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Field:Color get()=FieldBg

/**
 * Teamwork: one group chat with every bot. The right bot answers by its job (or by name); tap a face to talk to that
 * bot privately in a sheet over the group, then close it to come straight back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun TeamChat(activity:MainActivity,c:Controller,back:()->Unit,openFile:(String)->Unit,connect:()->Unit,activityPage:()->Unit,computer:()->Unit={}) {
    val field=rememberSaveable(saver=androidx.compose.foundation.text.input.TextFieldState.Saver) {androidx.compose.foundation.text.input.TextFieldState()}
    val input=field.text.toString()
    var attach by remember {mutableStateOf(false)}
    var attached by remember {mutableStateOf(listOf<String>())}
    var privateBot by rememberSaveable {mutableStateOf("")}
    var steps by remember {mutableStateOf(false)}
    var reacting by remember {mutableStateOf("")}
    // A colleague's reply to a bot never shows in the group; a bot's piece of a case shows as one small line.
    val history=c.teamMessages.filter {it.kind!="ask"}
    // Every bot called in the group works at the same time, so the room shows all of them typing.
    val working=c.tasks.filter {it.team && it.showsAsTyping()}
    // An open group message keeps every bot quiet until it has something to say; a bot that starts writing a real answer shows it growing at once.
    val speaking=c.tasks.filter {it.team && it.status=="Running" && !it.showsAsTyping() && LiveText.show(c.liveTextOf(it.id),null)}
    val active=working.firstOrNull()
    val list=rememberLazyListState()
    val send={if(c.submitTeam(field.text.toString(),attached)) {field.setTextAndPlaceCursorAtEnd("");attached=emptyList()}}
    // Re-register when the private sheet closes, because the private chat takes over these hooks while it is open.
    DisposableEffect(privateBot) {
        if(privateBot.isBlank()) {
            activity.onSpeech={field.setTextAndPlaceCursorAtEnd(it)}
            activity.onAttached={name->attached=(attached+name).distinct().take(8)}
        }
        onDispose {if(privateBot.isBlank()) {activity.onSpeech=null;activity.onAttached=null}}
    }
    LaunchedEffect(history.size,c.liveText.values.sumOf {it.length}/200,c.teamRouting) {if(history.isNotEmpty()) list.animateScrollToItem(list.layoutInfo.totalItemsCount.coerceAtLeast(1)-1)}
    var viewing by remember {mutableStateOf<Source?>(null)}
    val actions=remember(c,activity) {ChatActions(link={activity.openLink(it)},copy={activity.copyText(it)},share={activity.shareText(it)},
        ask={c.submitTeam(it,emptyList())},openSource={viewing=it},evidence={c.evidencePicture(it)})}
    CompositionLocalProvider(LocalChat provides actions) {
    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(start=4.dp,end=12.dp,top=6.dp),verticalAlignment=Alignment.CenterVertically) {
            IconButton(onClick=back) {Icon(Icons.AutoMirrored.Outlined.ArrowBack,"Back to bots",tint=Ink)}
            Column(Modifier.weight(1f)) {
                Text(Words.TEAM_CHAT,fontWeight=FontWeight.SemiBold,fontSize=17.sp,color=Ink)
                Text(when {
                    c.teamRouting.isNotBlank()->c.teamRouting
                    active!=null->working.map {it.workspace}.distinct().let {n->if(n.size==1) n[0]+" is typing…" else n.take(3).joinToString(", ")+(if(n.size>3) " +"+(n.size-3) else "")+" are typing…"}
                    else->"Everyone's here · tap a face to talk privately"
                },color=if(active!=null || c.teamRouting.isNotBlank()) Purple else Muted,fontSize=12.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
            }
        }
        // Every bot is in the room. Tap a face for a private chat the others can't see.
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=14.dp,vertical=8.dp),horizontalArrangement=Arrangement.spacedBy(14.dp)) {
            c.bots.forEach {bot->
                Column(Modifier.clip(RoundedCornerShape(12.dp)).clickable {c.switchWorkspace(bot.name);privateBot=bot.name}.padding(2.dp),
                    horizontalAlignment=Alignment.CenterHorizontally) {
                    Box {
                        BotAvatar(bot.name,44.dp,working.any {it.workspace==bot.name})
                        if(working.any {it.workspace==bot.name}) Box(Modifier.size(12.dp).align(Alignment.BottomEnd).background(Purple,CircleShape).border(2.dp,Background,CircleShape))
                    }
                    Text(bot.name,color=Muted,fontSize=11.sp,maxLines=1,modifier=Modifier.widthIn(max=64.dp))
                }
            }
        }
        HorizontalDivider(color=Line)
        if(privateBot.isBlank()) BrowserPanel(activity,c,computer)
        if(history.isEmpty()) Column(Modifier.weight(1f).fillMaxWidth().padding(28.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center) {
            Text("Your whole team, together",fontSize=22.sp,fontWeight=FontWeight.SemiBold,color=Ink,textAlign=TextAlign.Center)
            Text("Ask anything. The bot whose job fits answers, or say a name to call one. Bots only speak up when they have work to share. Tap a face above to talk to one privately.",
                color=Muted,textAlign=TextAlign.Center,modifier=Modifier.padding(top=10.dp))
        } else LazyColumn(state=list,modifier=Modifier.weight(1f).fillMaxWidth(),contentPadding=PaddingValues(horizontal=12.dp,vertical=14.dp),verticalArrangement=Arrangement.spacedBy(3.dp,Alignment.Bottom)) {
            // The group chat reads like the single chats: your messages as blocks on the right, each bot's answer set as an article under its name.
            val rows=history.filter {m->m.kind!="step" || history.lastOrNull {it.taskId==m.taskId && it.kind=="step"}?.id==m.id}
                .map {m->Triple(m,if(m.role=="user" || m.kind=="step") m.text else TextThread.links(m.text).first.trim(),0)}
            itemsIndexed(rows,key={_,row->row.first.id+"-"+row.third}) {i,row->
                val m=row.first;val part=row.second
                val mine=m.role=="user";val who=m.bot.ifBlank {"Alpha"}
                val previous=rows.getOrNull(i-1)?.first
                fun sameSpeaker(o:Message?)=o!=null && o.kind!="step" && m.kind!="step" && (o.role=="user")==mine && (mine || o.bot.ifBlank {"Alpha"}==who)
                Column(Modifier.padding(top=if(m.kind=="step") 4.dp else if(sameSpeaker(previous)) 2.dp else 14.dp)) {
                    if(TextThread.showStamp(previous?.at,m.at) && m.kind!="step") TimeLine(m.at)
                    if(m.kind=="step") StepLine(CaseRules.stepLine(who,part),part)
                    else if(mine) UserMessage(part,m.react) {m.files.forEach {name->AttachmentChip(c,name,big=true) {openFile(name)}}}
                    else {
                        val pictures=remember(m.taskId) {PictureHost({name->c.pictureFile(name,m.taskId)},{name->c.botOf(m.taskId)?.let {c.switchWorkspace(it)};openFile(name)})}
                        AssistantMessage(who,m.at,!sameSpeaker(previous) || TextThread.showStamp(previous?.at,m.at),m.copy(text=part),rows.lastOrNull()?.first?.id==m.id && working.isEmpty(),
                            pictures,{c.react(m.id,it)},showActions=(m.taskId.isBlank() || history.lastOrNull {it.taskId==m.taskId && it.role!="user"}?.id==m.id),face={if(c.bots.any {it.name==who}) {c.switchWorkspace(who);privateBot=who}},
                            // Files live in the folder of the bot that made them; open them from there. They show under the text, expanded or not.
                            files={
                                if(m.taskId.isNotBlank() && history.lastOrNull {it.taskId==m.taskId && it.role!="user"}?.id==m.id) (c.artifactsFor(m.taskId)+m.files).distinct().filter {it !in Pictures.shown(m.text)}.forEach {name->
                                    AttachmentChip(c,name,big=true,taskId=m.taskId) {c.botOf(m.taskId)?.let {c.switchWorkspace(it)};openFile(name)}
                                }
                            }) {
                            CollabLine(c.collabsFor(m.taskId))
                            if(m.model.isNotBlank() && c.aiSettings.label) Text(m.model,color=Muted,fontSize=10.sp)
                        }
                    }
                }
            }
            // A request with two or more steps shows where it stands until the lead writes the one answer.
            c.cases.filter {(it.state=="working" || it.state=="merging") && it.steps.size>1 && it.mode!="direct"}.forEach {case->item(key="case-"+case.id) {Box(Modifier.padding(top=14.dp)) {CaseCard(case)}}}
            if(working.isNotEmpty() || speaking.isNotEmpty()) item(key="team-typing") {
                Column(Modifier.padding(top=14.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    (working+speaking).forEach {job->
                        // Each bot's answer grows here as it is written; before that its live card, or three quiet dots.
                        val posted=history.filter {it.taskId==job.id && it.role!="user"}
                        val pictures=remember(job.id) {PictureHost({name->c.pictureFile(name,job.id)},{name->c.botOf(job.id)?.let {c.switchWorkspace(it)};openFile(name)})}
                        LiveJob(c,job,posted.lastOrNull()?.text,posted.isNotEmpty() || job.steps.isNotEmpty(),pictures,steps,{steps=!steps},computer)
                    }
                }
            }
            val stuck=c.tasks.firstOrNull {it.team && it.routineId.isBlank() && it.from!=OWNER_QUEUE}
                ?.takeIf {working.isEmpty() && c.teamRouting.isBlank() && (it.status in setOf("Paused","Needs you","Needs connection","Needs review","Check result") || c.isCutShort(it.id))}
            if(stuck!=null) item(key="team-replies-"+stuck.id) {
                Box(Modifier.padding(top=6.dp)) {
                    QuickReplies(when(stuck.status) {
                        "Needs review"->listOf("Review" to {c.reopenReview(stuck.id)},"Never mind" to {c.cancel(stuck.id)})
                        "Needs connection"->listOf(("Connect "+WorkWords.connectionLabel(stuck.waitingConnection)) to connect,"Done, keep going" to {c.resume(stuck.id,switch=false)})
                        "Check result"->listOf("What happened?" to activityPage,"Never mind" to {c.cancel(stuck.id)})
                        else->listOf("Keep going" to {c.resume(stuck.id,switch=false)},"Never mind" to {c.cancel(stuck.id)})
                    })
                }
            }
        }
        if(attached.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start=14.dp,end=14.dp,top=8.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            attached.forEach {name->AttachmentChip(c,name,big=false) {attached=attached-name}}
        }
        Row(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=10.dp),verticalAlignment=Alignment.Bottom) {
            Box {
                IconButton(onClick={attach=true},modifier=Modifier.size(46.dp).background(Field,CircleShape)) {Icon(Icons.Outlined.Add,"Add to message",tint=Ink)}
                DropdownMenu(expanded=attach,onDismissRequest={attach=false}) {
                    DropdownMenuItem(text={Text("Take a photo")},leadingIcon={Icon(Icons.Outlined.PhotoCamera,null)},onClick={attach=false;activity.takePhoto()})
                    DropdownMenuItem(text={Text("Add a photo")},leadingIcon={Icon(Icons.Outlined.Image,null)},onClick={attach=false;activity.pickPhoto()})
                    DropdownMenuItem(text={Text("Attach a document or PDF")},leadingIcon={Icon(Icons.Outlined.Description,null)},onClick={attach=false;activity.pickDocument()})
                }
            }
            Spacer(Modifier.width(8.dp))
            TextBox(activity,field,"Message the team",Modifier.weight(1f)) {send()}
            Spacer(Modifier.width(8.dp))
            FilledIconButton(onClick={send()},enabled=(input.isNotBlank() || attached.isNotEmpty()),modifier=Modifier.size(46.dp),
                colors=IconButtonDefaults.filledIconButtonColors(containerColor=Strong,contentColor=OnStrong)) {Icon(Icons.Outlined.ArrowUpward,"Send to the team")}
        }
    }
    viewing?.let {SourceViewer(it) {viewing=null}}
    }
    if(privateBot.isNotBlank()) ModalBottomSheet(onDismissRequest={privateBot=""},sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),
        containerColor=Background,dragHandle={BottomSheetDefaults.DragHandle(color=Line)}) {
        Column(Modifier.fillMaxHeight(0.94f)) {
            Row(Modifier.fillMaxWidth().padding(start=16.dp,end=8.dp,bottom=6.dp),verticalAlignment=Alignment.CenterVertically) {
                BotAvatar(privateBot,34.dp,c.tasks.any {it.workspace==privateBot && it.showsAsTyping()});Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(privateBot,fontWeight=FontWeight.SemiBold,fontSize=16.sp,color=Ink)
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Lock,null,tint=Muted,modifier=Modifier.size(12.dp));Spacer(Modifier.width(4.dp))
                        Text("Private · the team can't see this",color=Muted,fontSize=12.sp)
                    }
                }
                IconButton(onClick={privateBot=""}) {Icon(Icons.Outlined.Close,"Back to the team chat",tint=Ink)}
            }
            HorizontalDivider(color=Line)
            BotChat(activity,c,openFile,{},connect,activityPage,computer)
        }
    }
}

/** One quiet line for a bot's piece of a case ("Customer Desk found the car · 2018 Honda Accord"). Tap to read what it wrote. */
@Composable fun StepLine(line:String,full:String) {
    var open by remember {mutableStateOf(false)}
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable {open=!open}.padding(horizontal=4.dp,vertical=3.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            Icon(Icons.Outlined.SubdirectoryArrowRight,null,tint=Muted,modifier=Modifier.size(14.dp));Spacer(Modifier.width(6.dp))
            Text(line,color=Muted,fontSize=12.sp,maxLines=if(open) 4 else 1,overflow=TextOverflow.Ellipsis,modifier=Modifier.weight(1f))
        }
        if(open) Text(TeamRules.parseAnswer(full).text,color=Muted,fontSize=12.sp,modifier=Modifier.padding(start=20.dp,top=4.dp))
    }
}

/**
 * Where a request with several steps stands: "Working on it · Customer Desk ✓ · Parts ⏳". Tap it to see what each bot is doing and what it found so far.
 */
@Composable fun CaseCard(case:Case) {
    var open by remember(case.id) {mutableStateOf(false)}
    val head=if(case.state=="merging") "Putting it together" else "Working on it"
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Panel).border(1.dp,Line,RoundedCornerShape(18.dp)).clickable {open=!open}.padding(14.dp),
        verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            Icon(Icons.Outlined.Autorenew,null,tint=Purple,modifier=Modifier.size(16.dp));Spacer(Modifier.width(8.dp))
            Text(head+" · "+case.steps.joinToString(" · ") {it.bot+" "+CaseRules.mark(it.status)},style=Type.sub.copy(fontSize=14.sp),maxLines=if(open) 3 else 1,overflow=TextOverflow.Ellipsis,modifier=Modifier.weight(1f))
            Icon(Icons.Outlined.ExpandMore,null,tint=Muted,modifier=Modifier.size(18.dp))
        }
        if(open) Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            case.steps.forEach {s->
                Column {
                    Text(s.bot+" "+CaseRules.mark(s.status),style=Type.small.copy(color=Ink,fontSize=13.sp))
                    Text(s.goal,style=Type.small.copy(fontSize=12.sp),maxLines=2,overflow=TextOverflow.Ellipsis)
                    val found=if(s.status==CaseRules.DONE) CaseRules.clean(s.result) else s.note
                    if(found.isNotBlank()) Text(found.replace(Regex("\\s+")," ").take(220),style=Type.small.copy(fontSize=12.sp,color=Muted),maxLines=3,overflow=TextOverflow.Ellipsis)
                }
            }
        }
    }
}
