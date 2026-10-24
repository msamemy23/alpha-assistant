package com.alphainternational.assistant

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** "New bot": the AI bot builder chat. The owner says what they need; the builder proposes a ready bot they create with one tap. */
@OptIn(ExperimentalLayoutApi::class)
@Composable fun BuilderScreen(activity:MainActivity,c:Controller,back:()->Unit,signIn:(String)->Unit,openBot:(String)->Unit,manual:()->Unit,marketplace:()->Unit) {
    val b=c.builder
    var input by rememberSaveable {mutableStateOf("")}
    val list=rememberLazyListState()
    val ready=c.aiConnected()
    LaunchedEffect(Unit) {b.start()}
    DisposableEffect(Unit) {
        activity.onSpeech={input=it}
        onDispose {activity.onSpeech=null}
    }
    val turns=b.turns
    LaunchedEffect(turns.size,b.busy) {if(turns.isNotEmpty()) list.animateScrollToItem(turns.size-1+if(b.busy) 1 else 0)}
    fun send(text:String=input) {if(text.isBlank() || b.busy) return;b.send(text);if(text==input) input=""}
    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(start=4.dp,end=8.dp,top=6.dp,bottom=6.dp),verticalAlignment=Alignment.CenterVertically) {
            IconButton(onClick=back) {Icon(Icons.AutoMirrored.Outlined.ArrowBack,"Back",tint=Ink)}
            Column(Modifier.weight(1f)) {
                Text("New bot",fontWeight=FontWeight.SemiBold,fontSize=18.sp,color=Ink)
                Text("AI sets it up · you approve",color=Muted,fontSize=12.sp)
            }
            var menu by remember {mutableStateOf(false)}
            Box {
                IconButton(onClick={menu=true}) {Icon(Icons.Outlined.MoreVert,"More",tint=Ink)}
                DropdownMenu(expanded=menu,onDismissRequest={menu=false}) {
                    DropdownMenuItem(text={Text("Start over")},leadingIcon={Icon(Icons.Outlined.Refresh,null)},onClick={menu=false;b.start(again=true)})
                    DropdownMenuItem(text={Text("Make it myself")},leadingIcon={Icon(Icons.Outlined.Edit,null)},onClick={menu=false;manual()})
                }
            }
        }
        HorizontalDivider(color=Line)
        if(!ready) Column(Modifier.fillMaxWidth().padding(16.dp).background(Panel,RoundedCornerShape(18.dp)).padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("Connect ChatGPT first",fontWeight=FontWeight.SemiBold,color=Ink)
            Text("The bot builder thinks with your ChatGPT plan. You can also make a bot yourself.",color=Muted,fontSize=13.sp)
            Row {Button(onClick=marketplace) {Text("Connect ChatGPT")};Spacer(Modifier.width(8.dp));TextButton(onClick=manual) {Text("Make it myself",color=Ink)}}
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(),state=list,contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            itemsIndexed(turns) {i,turn->
                val last=i==turns.lastIndex
                if(turn.role=="user") Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
                    Text(turn.text,color=Ink,style=MaterialTheme.typography.bodyLarge,modifier=Modifier.widthIn(max=300.dp)
                        .background(Raised2,RoundedCornerShape(20.dp,20.dp,6.dp,20.dp)).padding(horizontal=15.dp,vertical=10.dp))
                } else Row(Modifier.fillMaxWidth()) {
                    Box(Modifier.size(28.dp).background(Strong,CircleShape),contentAlignment=Alignment.Center) {
                        Icon(Icons.Outlined.AutoAwesome,null,tint=OnStrong,modifier=Modifier.size(16.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                        MarkdownText(turn.text)
                        turn.plan?.let {PlanCard(c,it,last && turn.created.isBlank(),signIn) {skip->b.create(it,skip)}}
                        if(turn.created.isNotBlank()) Created(c,turn.created,signIn,{openBot(turn.created)}) {b.start(again=true)}
                        if(last && !b.busy && turn.choices.isNotEmpty()) FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            turn.choices.forEach {choice->
                                Text(choice,color=Ink,fontSize=14.sp,modifier=Modifier.clip(RoundedCornerShape(20.dp)).border(1.dp,Outline,RoundedCornerShape(20.dp))
                                    .clickable(enabled=ready) {send(choice)}.padding(horizontal=14.dp,vertical=8.dp))
                            }
                        }
                    }
                }
            }
            if(b.busy) item {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp),strokeWidth=2.dp,color=Purple)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(b.status.ifBlank {"Thinking…"},color=Purple,fontSize=13.sp)
                        if(b.streaming.isNotBlank()) Text(b.streaming.takeLast(400),color=Muted,fontSize=13.sp)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=10.dp),verticalAlignment=Alignment.Bottom) {
            TextField(value=input,onValueChange={input=it},enabled=ready,placeholder={Text("Describe your bot, or ask for changes",color=Muted,fontSize=15.sp,maxLines=1)},
                modifier=Modifier.weight(1f),shape=RoundedCornerShape(24.dp),maxLines=5,
                colors=TextFieldDefaults.colors(focusedContainerColor=FieldBg,unfocusedContainerColor=FieldBg,disabledContainerColor=FieldBg,
                    focusedIndicatorColor=Color.Transparent,unfocusedIndicatorColor=Color.Transparent,disabledIndicatorColor=Color.Transparent,cursorColor=Ink,
                    focusedTextColor=Ink,unfocusedTextColor=Ink),
                trailingIcon={IconButton(onClick={activity.speak()},enabled=ready) {Icon(Icons.Outlined.Mic,"Dictate",tint=Muted)}},
                keyboardOptions=KeyboardOptions(imeAction=ImeAction.Send),keyboardActions=KeyboardActions(onSend={send()}))
            Spacer(Modifier.width(8.dp))
            if(b.busy) FilledIconButton(onClick={b.stop()},modifier=Modifier.size(46.dp),
                colors=IconButtonDefaults.filledIconButtonColors(containerColor=Raised2,contentColor=Ink)) {Icon(Icons.Outlined.Stop,"Stop")}
            else FilledIconButton(onClick={send()},enabled=ready && input.isNotBlank(),modifier=Modifier.size(46.dp),
                colors=IconButtonDefaults.filledIconButtonColors(containerColor=Strong,contentColor=OnStrong)) {Icon(Icons.Outlined.ArrowUpward,"Send")}
        }
    }
}


@Composable private fun Heading(text:String) {Text(text.uppercase(),fontSize=10.sp,letterSpacing=1.2.sp,color=Muted,modifier=Modifier.padding(top=6.dp))}

/** Sign-in rows: done, or a button that opens the real sign-in (Google, the app's own page, or the phone-locked website vault). */
@Composable private fun SignIns(c:Controller,logins:List<String>,signIn:(String)->Unit) {
    c.version;c.vault
    logins.forEach {id->
        val done=c.loginConnected(id)
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Icon(if(id.startsWith("web:")) Icons.Outlined.Public else Icons.Outlined.Key,null,tint=Muted,modifier=Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(BotPlans.label(id),color=Ink,fontSize=14.sp,modifier=Modifier.weight(1f))
            if(done) Row(verticalAlignment=Alignment.CenterVertically) {
                Icon(Icons.Outlined.CheckCircle,null,tint=Mint,modifier=Modifier.size(16.dp));Spacer(Modifier.width(4.dp));Text("Signed in",color=Mint,fontSize=13.sp)
            } else OutlinedButton(onClick={signIn(id)},enabled=c.appBusy.isBlank(),contentPadding=PaddingValues(horizontal=14.dp,vertical=4.dp)) {
                Text("Sign in",fontSize=13.sp,color=Ink)
            }
        }
    }
    if(c.appBusy.isNotBlank() || c.appStatus.isNotBlank()) Text(c.appStatus,color=Muted,fontSize=12.sp)
    if(c.googleStatus.isNotBlank()) Text(c.googleStatus,color=Muted,fontSize=12.sp)
    if(logins.any {!c.loginConnected(it)}) Text("You sign in on the real page. Alpha never sees your passwords. You can also sign in later.",color=Muted,fontSize=12.sp)
}

@Composable private fun PlanCard(c:Controller,plan:BotPlan,current:Boolean,signIn:(String)->Unit,create:(Set<Int>)->Unit) {
    var skip by remember(plan) {mutableStateOf(setOf<Int>())}
    var showRules by remember(plan) {mutableStateOf(false)}
    var open by remember(plan) {mutableStateOf("")}
    Column(Modifier.fillMaxWidth().background(Panel,RoundedCornerShape(20.dp)).border(1.dp,if(current) Outline else Line,RoundedCornerShape(20.dp)).padding(16.dp),
        verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            BotAvatar(plan.name,44.dp);Spacer(Modifier.width(12.dp))
            Column {Text(plan.name,fontWeight=FontWeight.SemiBold,fontSize=18.sp,color=Ink);Text(plan.role,color=Muted,fontSize=13.sp)}
        }
        if(plan.logins.isNotEmpty()) {Heading("Sign-ins");SignIns(c,plan.logins,signIn)}
        if(plan.skills.isNotEmpty()) {
            Heading("Skills")
            plan.skills.forEach {id->
                val made=plan.newSkills.firstOrNull {it.id==id}
                val title=made?.title?:Skills.builtIn.firstOrNull {it.id==id}?.title?:c.userSkills.firstOrNull {it.id==id}?.title?:id
                Row(Modifier.fillMaxWidth().clickable(enabled=made!=null) {open=if(open==id) "" else id},verticalAlignment=Alignment.CenterVertically) {
                    Icon(Icons.Outlined.AutoAwesome,null,tint=Muted,modifier=Modifier.size(16.dp));Spacer(Modifier.width(10.dp))
                    Text(title,color=Ink,fontSize=14.sp,modifier=Modifier.weight(1f))
                    if(made!=null) Badge("New")
                }
                if(made!=null && open==id) Text(made.instructions,color=Muted,fontSize=12.sp,modifier=Modifier.padding(start=26.dp))
            }
        }
        Heading("Tools")
        plan.newTools.forEach {tool->
            Row(Modifier.fillMaxWidth().clickable {open=if(open==tool.id) "" else tool.id},verticalAlignment=Alignment.CenterVertically) {
                Icon(if(tool.kind=="web") Icons.Outlined.TravelExplore else Icons.Outlined.Calculate,null,tint=Muted,modifier=Modifier.size(16.dp));Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {Text(tool.title,color=Ink,fontSize=14.sp);Text(tool.description,color=Muted,fontSize=12.sp)}
                Badge("New")
            }
            if(open==tool.id) Text(tool.code,color=Muted,fontSize=11.sp,modifier=Modifier.padding(start=26.dp))
        }
        Text("Plus web search, documents and PDFs, calculator, browser, phone hand-offs, memory and routines.",color=Muted,fontSize=12.sp)
        if(plan.routines.isNotEmpty()) {
            Heading("Routines")
            plan.routines.forEachIndexed {i,r->
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(r.title,color=Ink,fontSize=14.sp)
                        Text(Schedule.describe(Routine("",r.title,r.prompt,r.hours,plan.name,r.time,r.days,true)),color=Muted,fontSize=12.sp)
                    }
                    Switch(checked=i !in skip,enabled=current,onCheckedChange={on->skip=if(on) skip-i else skip+i},
                        colors=SwitchDefaults.colors(checkedTrackColor=Strong,checkedThumbColor=OnStrong))
                }
            }
        }
        if(plan.rules.isNotBlank()) {
            Text(if(showRules) "Hide standing rules" else "Show standing rules",color=Ink,fontSize=13.sp,fontWeight=FontWeight.Medium,
                modifier=Modifier.clickable {showRules=!showRules}.padding(vertical=4.dp))
            if(showRules) Text(plan.rules,color=Muted,fontSize=12.sp)
        }
        if(current) {
            Button(onClick={create(skip)},enabled=!c.builder.busy,modifier=Modifier.fillMaxWidth()) {Text("Create "+plan.name)}
            Text("Want something different? Type it below.",color=Muted,fontSize=12.sp,modifier=Modifier.align(Alignment.CenterHorizontally))
        }
    }
}

@Composable private fun Badge(text:String) {
    Text(text,color=OnStrong,fontSize=10.sp,fontWeight=FontWeight.SemiBold,modifier=Modifier.background(Strong,RoundedCornerShape(8.dp)).padding(horizontal=6.dp,vertical=1.dp))
}

@Composable private fun Created(c:Controller,name:String,signIn:(String)->Unit,open:()->Unit,again:()->Unit) {
    val bot=c.bots.firstOrNull {it.name==name}
    Column(Modifier.fillMaxWidth().background(Panel,RoundedCornerShape(20.dp)).padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            BotAvatar(name,40.dp);Spacer(Modifier.width(12.dp))
            Column {Text(name,fontWeight=FontWeight.SemiBold,color=Ink);Text("Ready · in the team chat",color=Muted,fontSize=12.sp)}
        }
        bot?.apps?.filter {!c.loginConnected(it)}?.takeIf {it.isNotEmpty()}?.let {left->Heading("Still to sign in");SignIns(c,left,signIn)}
        Button(onClick=open,modifier=Modifier.fillMaxWidth(),enabled=bot!=null) {Text("Open "+name)}
        TextButton(onClick=again,modifier=Modifier.align(Alignment.CenterHorizontally)) {Text("Make another bot",color=Ink)}
    }
}
