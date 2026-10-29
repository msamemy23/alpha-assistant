package com.alphainternational.assistant

import android.view.ViewGroup
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private data class Field(val key:String,val label:String,val value:String="",val multi:Boolean=false,val secret:Boolean=false)

/** Grok Bot-shaped navigation: bot roster → bot chat → profile, routines, computer and marketplace pages. */
@Composable fun AlphaApp(activity:MainActivity,c:Controller) {
    var route by rememberSaveable { mutableStateOf("home") }
    val screen=route.substringAfterLast('/')
    fun go(next:String) { route="$route/$next" }
    fun back() { route=route.substringBeforeLast('/',"home") }
    // Another screen asked for a page to be opened (the "use another AI" button on a bot with no AI yet).
    LaunchedEffect(c.navRequest) {if(c.navRequest.isNotEmpty()) {route="home/"+c.navRequest;c.navRequest=""}}
    var dialog by rememberSaveable { mutableStateOf("") }
    var editMemory by remember { mutableStateOf<Memory?>(null) }
    var editFile by remember { mutableStateOf("") }
    var previewFile by remember { mutableStateOf("") }
    var previewSite by remember { mutableStateOf("") }
    var previewVideo by remember { mutableStateOf("") }
    var vaultOrigin by remember { mutableStateOf("") }
    var signInUrl by rememberSaveable { mutableStateOf("") }
    var signInTask by rememberSaveable { mutableStateOf("") }
    val appScope=rememberCoroutineScope()
    // Signing in happens on the website itself, full screen in Alpha's browser: the owner types their own password (Forgot password works there).
    fun openSignIn(url:String,task:String) {
        val address=runCatching {c.signInAddress(url)}.getOrElse {c.showNotice(plainError(it));return}
        signInUrl=address;signInTask=task;go("signin")
        appScope.launch {runCatching {c.web().open(address)}.onFailure {c.showNotice(plainError(it))}}
    }
    // Sign-ins from the bot builder open the same flows as Marketplace; the owner signs in on the real page.
    val signIn:(String)->Unit={id->when {
        id in BotPlans.google->activity.connectGoogle(BotPlans.google.getValue(id))
        id=="supabase"||id=="vercel"->dialog=id
        id.startsWith("web:")->openSignIn(id.removePrefix("web:"),"")
        else->AppCatalog.find(id)?.let {app->if(app.auth=="oauth") c.connectApp(app) else dialog="app:"+app.id}
    }}
    val snackbar=remember { SnackbarHostState() }
    val openFile:(String)->Unit={name->if(Attachments.kind(name) in setOf("pdf","image"))previewFile=name else if(Attachments.kind(name)=="video")previewVideo=name else if(Attachments.kind(name)=="audio") {} else if(Office.isOffice(name)) activity.openOffice(c.file(name)) else if(name.endsWith(".html",true))previewSite=name else {editFile=name;dialog="file"}}
    androidx.activity.compose.BackHandler(enabled=screen!="home") { back() }
    // (A tap on a notification waits until the chats and requests have been read, so "needs your OK" can be found.)
    LaunchedEffect(activity.navigationRequest,c.hydrated) {
        activity.navigationRequest?.takeIf {c.hydrated}?.let {request->
            if(c.bots.any {it.name==request.workspace})c.switchWorkspace(request.workspace)
            when(request.destination) {
                "call"->dialog="call"
                "tasks"->{dialog="";route="home/chat/activity"}
                "chat"->{dialog="";route="home/chat"}
                // From a Teamwork notification: open the group chat.
                "team"->{dialog="";route="home/team"}
                "away"->{dialog="";route="home/away"}
                "goals"->{dialog="";route="home/goals"}
                "proactive"->{dialog="";route="home/proactive"}
                // Front doors (shortcuts, the Quick Settings tile, the widget): Doors.route says what each one means. Nothing here starts a call or turns Away mode on by itself.
                "ask"->{dialog="";route=Doors.ASK_SCREEN;activity.focusComposer()}
                "talk"->dialog="call"
                "away_toggle"->{dialog="";route="home/away";Reach.awayShortcut(c)}
                "connect"->{dialog="";route="home/marketplace"}
                // From an approval notification: open the bot with its approval card showing.
                "review"->{dialog="";route="home/chat";c.tasks.firstOrNull {it.workspace==c.workspace && it.status=="Needs review"}?.let {c.reopenReview(it.id)}}
            }
            activity.consumeNavigation(request)
        }
    }
    c.version
    DisposableEffect(screen,dialog) {
        activity.secureScreen(screen=="marketplace" || dialog=="vault")
        onDispose { activity.secureScreen(false) }
    }
    LaunchedEffect(c.notice) {
        if(c.notice.isNotBlank()) { snackbar.showSnackbar(c.notice,actionLabel="OK",duration=SnackbarDuration.Long); c.clearNotice() }
    }
    Scaffold(containerColor=Background,snackbarHost={ SnackbarHost(snackbar) }) { padding->
        Box(Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize()) {
            // The phone browser stays mounted (hidden) so bots can browse while you chat.
            if(screen !in setOf("computer","signin") && !(c.browserPanelUp && screen in setOf("chat","team"))) BrowserSurface(activity,false,Modifier.matchParentSize())
            when(screen) {
                "chat"->Column(Modifier.fillMaxSize()) {
                    ChatHeader(c,{back()},{go("profile")},{go("computer")},{dialog="call"},{go(it)},{dialog="bot_edit"})
                    BotChat(activity,c,openFile,{dialog="call"},{go("marketplace")},{go("activity")},{go("computer")}) {url,task->openSignIn(url,task)}
                }
                "activity"->Column(Modifier.fillMaxSize()) {
                    PageBar(c,"Routines & activity",true) {back()}
                    TasksScreen(c,{dialog="routine"},{go("computer")},{c.enableImportantUpdates();activity.enableNotifications()},{go("marketplace")})
                }
                "computer"->Column(Modifier.fillMaxSize()) {
                    PageBar(c,"Computer",true) {back()}
                    WorkspaceScreen(activity,c,{editFile=""; dialog="file"},openFile,{url->openSignIn(url,"")}) {route="home/chat"}
                }
                "away"->Column(Modifier.fillMaxSize()) {
                    PageBar(c,"Away mode",false) {back()}
                    AwayScreen(activity,c)
                }
                "goals"->Column(Modifier.fillMaxSize()) {
                    PageBar(c,"Goals",false) {back()}
                    GoalsScreen(c) {go("proactive")}
                }
                "proactive"->Column(Modifier.fillMaxSize()) {
                    PageBar(c,"Proactive",false) {back()}
                    ProactiveScreen(activity,c)
                }
                "library"->Column(Modifier.fillMaxSize()) {
                    PageBar(c,"Library",false) {back()}
                    // Open a thing from whichever bot made it: switch to that bot first, since a bot's files are its own.
                    LibraryScreen(c,{bot,name->c.switchWorkspace(bot);openFile(name)},activity,{editFile="";dialog="file"})
                }
                "marketplace"->Column(Modifier.fillMaxSize()) {
                    PageBar(c,"Marketplace",false) {back()}
                    ConnectionsScreen(activity,c,{dialog=it})
                }
                "profile"->Column(Modifier.fillMaxSize()) {
                    PageBar(c,"Profile",true) {back()}
                    MemoryScreen(c,{editMemory=null;dialog="memory"},{editMemory=it;dialog="memory"},{dialog="bot_edit"})
                }
                "signin"->SignInScreen(activity,c,signInUrl,{back()}) {c.finishSignIn(signInUrl,signInTask);back()}
                "team"->TeamChat(activity,c,{back()},openFile,{go("marketplace")},{go("activity")},{go("computer")})
                "brainstorm"->BrainstormScreen(activity,c) {back()}
                "settings"->SettingsScreen(activity,c,{back()},{name->c.switchWorkspace(name);dialog="bot_edit"},{go("builder")},{go("marketplace")},
                    {url->openSignIn(url,"")},{go(it)})
                "builder"->BuilderScreen(activity,c,{back()},signIn,{name->c.switchWorkspace(name);route="home/chat"},{dialog="bot_new"},{go("marketplace")})
                "business"->BusinessScreen(c,{back()}) {text->c.askFounder(text);route="home/chat"}
                else-> {
                    // A screen another part of the app registered (Backup, Proactive...) gets the usual page bar; anything unknown is Home.
                    val extra=Screens.get(screen)
                    if(extra!=null) Column(Modifier.fillMaxSize()) {
                        PageBar(c,extra.title,false) {back()}
                        extra.content(activity,c) {back()}
                    } else HomeScreen(c,{name->c.switchWorkspace(name);route="home/chat"},{route="home/builder"},{go("marketplace")},{route="home/team"},{route="home/settings"},{go("library")},{go("away")},
                        activity,{name->c.switchWorkspace(name);route="home/chat/activity"},{go("goals")},{go("brainstorm")},{go("business")},{c.startBusinessChat();route="home/chat"})
                }
            }
            // A moment after start on a phone with a lot of history, while the chats and requests are read in the background.
            if(!c.hydrated) Box(Modifier.matchParentSize().background(Background),contentAlignment=Alignment.Center) {CircularProgressIndicator(color=Ink,strokeWidth=2.dp,modifier=Modifier.size(28.dp))}
        }
    }
    when(dialog) {
        "call"->CallDialog(activity,c,{dialog=""},{dialog="";route="home/marketplace"})
        "memory"-> {
            val memory=editMemory
            FormDialog("Remember something","Saved in "+c.workspace+". You can edit or remove it later.",
                listOf(Field("title","Title",memory?.title.orEmpty()),Field("text","Details",memory?.text.orEmpty(),true),
                    Field("source","Source",memory?.source?:"You")),c,{dialog=""}) { values->
                c.saveMemory(values.getValue("title"),values.getValue("text"),values.getValue("source"),memory?.id?:java.util.UUID.randomUUID().toString(),
                    private=memory?.private==true); dialog=""
            }
        }
        "file"->FormDialog(if(editFile.isBlank()) "New file" else editFile,"Private to your "+c.workspace+" workspace.",
            listOf(Field("name","File name",editFile.ifBlank { "notes.txt" }),Field("text","Contents",if(editFile.isBlank()) "" else c.file(editFile).readText(),true)),
            c,{dialog=""}) { values->c.saveLocalFile(values.getValue("name"),values.getValue("text")); dialog="" }
        "routine"->FormDialog("Create a routine","Runs on this phone at the time you choose (Android may delay it a little). The result appears in Chat with a notification. Tip: you can also just ask in Chat, like “every weekday at 8 AM brief me”.",
            listOf(Field("title","Routine name"),Field("prompt","What should "+c.workspace+" do each time?",multi=true),
                Field("time","Time of day, like 8:00 am (leave blank to repeat by hours)","8:00 am"),
                Field("days","Days: daily, weekdays, weekends or mon,wed,fri","weekdays"),Field("hours","If no time: every how many hours?","24")),
            c,{dialog=""}) { v->
                c.addRoutine(v.getValue("title"),v.getValue("prompt"),v.getValue("hours").trim().toIntOrNull()?:24,v.getValue("time"),v.getValue("days"),report=true)
                activity.enableNotifications(); dialog=""
            }
        "bot_new"->NewBotDialog(c,{dialog=""}) {dialog="";route="home/chat"}
        "bot_edit"-> {
            val bot=c.bot()
            FormDialog("Edit "+bot.name,"Its job and standing rules are used in every request to this bot. You can also say “from now on…” in Chat.",
                listOf(Field("role","Its job",bot.role,true),Field("rules","Standing rules",bot.rules,true)),c,{dialog=""},help={
                    // You choose each bot's brain: its own model and how hard it thinks. Blank follows the main setting (Settings → AI); nothing picks for you.
                    TextButton(onClick={dialog="bot_model"}) {Text("Model: "+(c.modelCatalog.firstOrNull {it.slug==bot.model}?.name?:"main setting"),color=Ink)}
                    TextButton(onClick={dialog="bot_effort"}) {Text("Intelligence: "+(if(bot.effort.isBlank()) "main setting" else ModelPlan.effortLabel(bot.effort)),color=Ink)}
                    if(c.bots.size>1) TextButton(onClick={dialog="bot_delete"}) {
                        Text("Delete this bot",color=MaterialTheme.colorScheme.error)
                    }
                }) { v->c.updateBot(bot.name,v.getValue("role"),v.getValue("rules"));c.showNotice(bot.name+" updated.");dialog="" }
        }
        "bot_model"-> {
            val bot=c.bot()
            Picker("Model for "+bot.name,listOf(Choice("","Main setting","Whatever Settings → AI says"))+
                c.modelCatalog.filter {it.listed}.map {Choice(it.slug,it.name,it.efforts.joinToString {e->ModelPlan.effortLabel(e)})},bot.model,{dialog="bot_edit"}) {
                c.setBotModel(bot.name,it,bot.effort);dialog="bot_edit"
            }
        }
        "bot_effort"-> {
            val bot=c.bot()
            val levels=c.modelCatalog.firstOrNull {it.slug==bot.model}?.efforts.orEmpty().ifEmpty {ModelPlan.efforts}
            Picker("Intelligence for "+bot.name,listOf(Choice("","Main setting","Whatever Settings → AI says"))+
                levels.map {Choice(it,ModelPlan.effortLabel(it),when(it) {"minimal","low"->"Fastest, lightest";"medium"->"Balanced";"high"->"More careful thinking";"xhigh"->"Deepest thinking, slowest";else->""})},bot.effort,{dialog="bot_edit"}) {
                c.setBotModel(bot.name,bot.model,it);dialog="bot_edit"
            }
        }
        "bot_delete"-> {
            val bot=c.bot()
            AlertDialog(onDismissRequest={dialog=""},containerColor=SheetBg,title={Text("Delete "+bot.name+"?")},
                text={Text("This deletes "+bot.name+"'s private chats, tasks, memories, routines and files on this phone. Team chat messages stay. This can't be undone.")},
                confirmButton={TextButton(onClick={dialog="";runCatching {c.removeBot(bot.name);if(screen!="settings") route="home"}.onFailure {c.showNotice(plainError(it))}}) {
                    Text("Delete",color=MaterialTheme.colorScheme.error)}},
                dismissButton={TextButton(onClick={dialog=""}) {Text("Cancel",color=Ink)}})
        }
        "vault"->FormDialog("Save a website login","Saved behind your phone lock. Alpha never receives the password.",
            listOf(Field("origin","Website origin",vaultOrigin.ifBlank {"https://"}),Field("username","Username or email"),Field("password","Password",secret=true)),
            c,{dialog="";vaultOrigin=""}) { v->
                activity.authenticate { c.saveVault(v.getValue("origin"),v.getValue("username"),v.getValue("password")); dialog="";vaultOrigin="" }
            }
        "supabase"->FormDialog("Sign in to Supabase","Your phone's browser must be signed in to the Supabase account that owns your project. If Supabase says you're not a member, tap Check which account, log out, sign in with the right account, then tap Save again. After approving, tap Return to Alpha (you have 10 minutes). Database changes always need your OK.",
            listOf(Field("project","Supabase project ID",c.config("supabase_management").optString("project").ifBlank {Profile.supabaseProject}),
                Field("organization","Organization slug (optional)",c.config("supabase_management").optString("organization_slug").ifBlank {Profile.supabaseOrganization})),c,{dialog=""},help={
                TextButton(onClick={activity.openExternal("https://supabase.com/dashboard/account/me")}){Text("Check which account")}
                TextButton(onClick={dialog="supabase_token"}){Text("Use an access token instead",color=Muted)}
            }) {v->c.connectSupabase(v.getValue("project"),v.getValue("organization"));dialog=""}
        "supabase_token"->FormDialog("Connect Supabase with a token","Connect your selected project's database tools. Alpha asks you to review project writes and verifies sample invoices by reading them back.",
            listOf(Field("project","Supabase project ID",c.config("supabase_management").optString("project").ifBlank {Profile.supabaseProject}),
                Field("token","Project-scoped access token",secret=true)),c,{dialog=""},help={
                TextButton(onClick={activity.openExternal("https://supabase.com/dashboard/account/tokens")}) {Text("Get a Supabase access token")}
                TextButton(onClick={dialog="supabase_data"}) {Text("Use a database user instead",color=Muted)}
            }) {v->
                val count=c.connectSupabaseManagement(v.getValue("project"),v.getValue("token"))
                c.showNotice("Supabase connected. Project table access checked; $count tools available.");dialog=""
            }
        "supabase_data"->FormDialog("Connect a Supabase database user","Use a publishable key and a Supabase Auth user. Allowed tables still follow row-level security.",
            listOf(Field("url","Project URL",c.config("supabase").optString("url")),Field("key","Publishable / anon key",secret=true),
                Field("email","Supabase user email"),Field("password","User password",secret=true),Field("tables","Allowed table names, comma separated",c.config("supabase").optJSONArray("tables")?.strings()?.joinToString().orEmpty())),
            c,{dialog=""}) { v->
                c.connectors.connectSupabase(v.getValue("url"),v.getValue("key"),v.getValue("email"),v.getValue("password"),
                    v.getValue("tables").split(',').map { it.trim() }.filter { it.isNotBlank() }); c.showNotice("Supabase user connected."); dialog=""
            }
        "vercel"->FormDialog("Connect Vercel","1. Tap Create a Vercel token. 2. Name it Alpha, pick your account under Scope, choose an expiration and tap Create. 3. Copy the token and paste it below. Alpha finds your projects by itself. Preview deployments always need your OK.",
            listOf(Field("token","Vercel token",secret=true),Field("team","Team ID (leave blank)",c.config("vercel").optString("team"))),
            c,{dialog=""},help={TextButton(onClick={activity.openExternal("https://vercel.com/account/settings/tokens")}) {Text("Create a Vercel token")}}) { v->
                val response=c.connectors.connectVercel(v.getValue("token").trim(),v.getValue("team").trim(),emptyList())
                c.showNotice("Vercel connected: "+response.optJSONArray("projects")?.objects()?.joinToString {it.optString("name")}.orEmpty()); dialog=""
            }
        "telnyx"->FormDialog("Connect Telnyx","1. Tap Open Telnyx API keys and sign in on that page. 2. Tap Create API Key, copy it, and paste it below. Alpha only looks: who called, who texted, and caller names. It never sends, buys or changes anything with it. The key stays on this phone (Telnyx keys have full power, so never share it).",
            listOf(Field("token","Telnyx API key",secret=true)),c,{dialog=""},help={TextButton(onClick={activity.openExternal("https://portal.telnyx.com/#/app/api-keys")}) {Text("Open Telnyx API keys")}}) { v->
                val info=c.connectors.connectTelnyx(v.getValue("token").trim());c.showNotice("Telnyx connected ("+info+")."); dialog=""
            }
        "mcp"->FormDialog("Add a connector","Connect a trusted Streamable HTTP MCP server. Bearer-token and public servers work here; servers requiring their own OAuth need a separate integration.",
            listOf(Field("url","HTTPS MCP endpoint",c.config("mcp").optString("url")),Field("token","Bearer token (optional)",secret=true)),
            c,{dialog=""}) { v->
                val count=c.connectMcp(v.getValue("url"),v.getValue("token"))
                c.showNotice("Connector verified. $count tools available."); dialog=""
            }
        else->if(dialog.startsWith("app:")) AppCatalog.find(dialog.removePrefix("app:"))?.let {app->
            FormDialog("Connect "+app.name,app.tokenHelp+" It stays encrypted on this phone.",
                listOf(Field("secret",if(app.auth=="url") "Personal server link" else "Token",secret=true)),c,{dialog=""},help={
                    if(app.helpUrl.isNotBlank()) TextButton(onClick={activity.openExternal(app.helpUrl)}) {Text("Open "+app.name)}
                }) {v->c.connectApp(app,v.getValue("secret"));dialog=""}
        }
    }
    if(previewFile.isNotBlank()) PdfPreview(c,previewFile,{activity.share(c.file(previewFile))}){previewFile=""}
    if(previewVideo.isNotBlank()) VideoPreview(c.file(previewVideo),previewVideo,{activity.share(c.file(previewVideo))}) {previewVideo=""}
    if(previewSite.isNotBlank()) SitePreview(c,previewSite,{activity.share(c.file(previewSite))},{editFile=previewSite;dialog="file";previewSite=""}){previewSite=""}
    c.approval?.let { review-> ReviewDialog(c,review,{c.hideReview()},{c.hideReview();route="home/chat/computer"}) {
        // A text needs Android's permission once; asking right when the owner approves keeps it to one tap.
        if(review.tool=="sms_send") activity.requestSms {ok->if(ok) c.approve() else c.showNotice("Alpha can't send texts without permission. To allow it later: phone Settings → Apps → Alpha Assistant → Permissions → SMS.")}
        else c.approve()
    } }
}


@Composable private fun TasksScreen(c:Controller,addRoutine:()->Unit,workspace:()->Unit,enableUpdates:()->Unit,connect:()->Unit) {
    var expanded by remember { mutableStateOf("") }
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        item { ScreenTitle("Routines & activity","Scheduled jobs, approvals and every task this bot has worked on.") }
        item { OutlinedButton(onClick=addRoutine) {Icon(Icons.Outlined.Schedule,null,Modifier.size(18.dp));Spacer(Modifier.width(8.dp));Text("Create a routine")} }
        item { PanelCard {
            Text("Alpha speaks up when it matters",fontWeight=FontWeight.SemiBold)
            Text("Check connected Gmail and Calendar for relevant leads, appointment changes and decisions. Unchanged events stay quiet. Android may delay background checks.",color=Muted,fontSize=13.sp)
            OutlinedButton(onClick={runCatching(enableUpdates).onFailure {c.showNotice(plainError(it))}}) {Text("Enable important updates")}
        } }
        items(c.routines.filter { it.workspace==c.workspace },key={it.id}) { routine->
            PanelCard {
                Text(routine.title,fontWeight=FontWeight.SemiBold)
                Text(Schedule.describe(routine)+if(routine.report) " · posts in Chat" else " · quiet unless needed",color=Mint,fontSize=12.sp)
                Text(routine.prompt,color=Muted,fontSize=13.sp,maxLines=4)
                Row {
                    TextButton(onClick={c.runRoutineNow(routine.id)}) {Text("Run now")}
                    TextButton(onClick={c.deleteRoutine(routine.id)}) {Text("Remove",color=Muted)}
                }
            }
        }
        val tasks=c.tasks.filter { it.workspace==c.workspace }
        if(tasks.isEmpty()) item { EmptyState(Icons.Outlined.Checklist,"Ready when you are","Ask in Chat, or try the local self-check in Marketplace.") }
        items(tasks,key={it.id}) { task->
            PanelCard {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Text(task.title,Modifier.weight(1f),fontWeight=FontWeight.SemiBold,maxLines=2)
                    Status(task.status)
                }
                Text(plainDetail(task.detail),color=Muted,fontSize=13.sp)
                Row(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                    when(task.status) {
                        "Running"->TextButton(onClick={c.stop(task.id)}) {Text("Pause")}
                        "Needs review"->TextButton(onClick={c.reopenReview(task.id)}) {Text("Review")}
                        "Paused","Queued","Partial"->TextButton(onClick={c.resume(task.id)}) {Text("Keep going")}
                        "Needs connection"-> {TextButton(onClick=connect){Text("Sign in")};TextButton(onClick={c.resume(task.id)}){Text("Done, keep going")}}
                        "Needs you"-> { TextButton(onClick=workspace) {Text("Open the browser")}; TextButton(onClick={c.resume(task.id)}) {Text("Keep going")} }
                    }
                    if(task.status !in listOf("Completed","Cancelled")) TextButton(onClick={c.cancel(task.id)}) {Text("Cancel",color=Muted)}
                    TextButton(onClick={expanded=if(expanded==task.id) "" else task.id}) {Text("Details",color=Muted)}
                }
                if(expanded==task.id) {
                    // What it did, in plain words. The technical record is one tap further down, for whoever needs it.
                    var technical by remember(task.id) {mutableStateOf(false)}
                    task.steps.takeLast(12).forEach {step->Text("✓  "+step,fontSize=13.sp,color=Ink)}
                    if(task.steps.isEmpty()) Text("Nothing to show yet.",color=Muted,fontSize=12.sp)
                    Text("Started "+date(task.created),color=Muted,fontSize=11.sp)
                    TextButton(onClick={technical=!technical}) {Text(if(technical) "Hide technical details" else "Technical details",color=Muted,fontSize=12.sp)}
                    if(technical) {
                        if(task.plan!="[]") org.json.JSONArray(task.plan).objects().forEach {check->
                            val status=runCatching {JSONObject(task.completion).getJSONArray("checks").objects().first {it.getString("id")==check.getString("id")}.getString("status")}.getOrDefault("pending")
                            Text(status+" · "+check.getString("description"),fontSize=12.sp,color=Mint)
                        }
                        if(task.trace.isEmpty()) Text("No tools have run.",color=Muted,fontSize=12.sp)
                        task.trace.forEachIndexed { index,step->Text((index+1).toString()+". "+step,fontSize=12.sp,color=Muted) }
                        TaskProtocol.receipts(org.json.JSONArray(task.history)).takeLast(12).forEach {receipt->
                            Text(receipt.getString("tool")+" · "+if(receipt.getBoolean("success"))"result received" else "needs attention",fontSize=12.sp,color=Muted)
                            SelectionContainer {Text(c.redact(receipt.getJSONObject("result").toString(2)).take(1800),fontSize=11.sp,color=Muted)}
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun WorkspaceScreen(activity:MainActivity,c:Controller,newFile:()->Unit,editFile:(String)->Unit,signIn:(String)->Unit,taught:()->Unit) {
    var section by rememberSaveable { mutableIntStateOf(0) }
    var url by rememberSaveable { mutableStateOf(c.browserUrl) }
    val scope=rememberCoroutineScope()
    // The address box follows wherever the bot (or you) went.
    LaunchedEffect(c.browserUrl) {if(c.browserUrl.isNotBlank()) url=c.browserUrl}
    val working=c.tasks.firstOrNull {it.workspace==c.workspace && it.status=="Running"}
    // Watching: while this screen is open, the pages the bot reads show here, and the latest one shows at once. (Without this the browser stayed blank
    // whenever the bot read pages without it, which looked like nothing was happening.)
    DisposableEffect(Unit) {c.watchingComputer=true;onDispose {c.watchingComputer=false}}
    LaunchedEffect(working?.id,c.browserUrl.isBlank()) {if(working!=null && c.browserUrl.isBlank()) c.watch(working.id)}
    fun go() {
        val address=url.trim().let {if(it.contains("://")) it else "https://"+it}
        scope.launch {runCatching {c.web().open(address)}.onFailure {c.showNotice(plainError(it))}}
    }
    Box(Modifier.fillMaxSize()) {
    if(section!=0) BrowserSurface(activity,false,Modifier.matchParentSize())
    Column(Modifier.fillMaxSize()) {
        when(section) {
            0-> {
                Row(Modifier.fillMaxWidth().padding(start=16.dp,end=16.dp,bottom=12.dp),verticalAlignment=Alignment.CenterVertically) {
                    val keyboard=androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
                    OutlinedTextField(value=url,onValueChange={url=it},label={Text("Website")},placeholder={Text("messages.google.com")},singleLine=true,modifier=Modifier.weight(1f),
                        keyboardOptions=KeyboardOptions(imeAction=ImeAction.Go),keyboardActions=KeyboardActions(onGo={keyboard?.hide();go()}))
                    IconButton(onClick={keyboard?.hide();go()}) {Icon(Icons.AutoMirrored.Outlined.ArrowForward,"Open website")}
                }
                if(working!=null) Row(Modifier.fillMaxWidth().padding(horizontal=14.dp,vertical=4.dp).background(PurpleDim,RoundedCornerShape(14.dp)).padding(horizontal=12.dp,vertical=8.dp),
                    verticalAlignment=Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(14.dp),strokeWidth=2.dp,color=Purple);Spacer(Modifier.width(10.dp))
                    Text(c.workspace+" is working here · "+working.detail,Modifier.weight(1f),color=Ink,fontSize=12.sp,maxLines=2)
                    TextButton(onClick={c.stop(working.id)}) {Text("Pause",fontSize=12.sp,color=Ink)}
                }
                if(c.teaching) Row(Modifier.fillMaxWidth().padding(horizontal=14.dp,vertical=4.dp).background(PurpleDim,RoundedCornerShape(14.dp)).padding(horizontal=12.dp,vertical=6.dp),
                    verticalAlignment=Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).background(Danger,CircleShape));Spacer(Modifier.width(10.dp))
                    Text("Teaching "+c.workspace+" · "+c.taughtSteps+" steps",Modifier.weight(1f),color=Ink,fontSize=13.sp)
                    TextButton(onClick={c.finishTeaching(false)}) {Text("Cancel",color=Muted,fontSize=12.sp)}
                    Button(onClick={if(c.finishTeaching(true)) taught()},colors=ButtonDefaults.buttonColors(containerColor=Strong,contentColor=OnStrong)) {Text("Done",fontSize=12.sp)}
                }
                if(c.browserUrl.isNotBlank())Row(Modifier.fillMaxWidth().padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically) {
                    IconButton(onClick={if(!activity.browser.back()) c.showNotice("No earlier page.")}) {Icon(Icons.AutoMirrored.Outlined.ArrowBack,"Back",tint=Ink,modifier=Modifier.size(20.dp))}
                    IconButton(onClick={activity.browser.reload()}) {Icon(Icons.Outlined.Refresh,"Reload",tint=Ink,modifier=Modifier.size(20.dp))}
                    Text(c.browserTitle.ifBlank {c.browserUrl},Modifier.weight(1f),color=Muted,fontSize=11.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                    if(!c.teaching) TextButton(onClick={c.startTeaching()}){Icon(Icons.Outlined.School,null,Modifier.size(14.dp));Spacer(Modifier.width(4.dp));Text("Teach",fontSize=11.sp)}
                    var chrome by remember {mutableStateOf(false)}
                    TextButton(onClick={chrome=true}){Text("Open in Chrome",fontSize=11.sp)}
                    if(chrome) AlertDialog(onDismissRequest={chrome=false},title={Text("Open in Chrome?")},
                        text={Text("You can look at this page in Chrome, but your bots can't see or use Chrome. They only work in this browser, here in Alpha.")},
                        confirmButton={TextButton(onClick={chrome=false;activity.openExternal(c.browserUrl)}) {Text("Open in Chrome",color=Ink)}},
                        dismissButton={TextButton(onClick={chrome=false}) {Text("Stay here",color=Ink)}})
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    BrowserSurface(activity,c.browserUrl.isNotBlank(),Modifier.matchParentSize())
                    if(c.browserUrl.isBlank()) Column(Modifier.fillMaxSize().padding(24.dp),verticalArrangement=Arrangement.Center) {
                        if(working!=null) EmptyState(Icons.Outlined.Public,"Watching "+c.workspace,"The next page it reads shows here. You can take over at any time for sign-in, passkeys or a CAPTCHA.")
                        else EmptyState(Icons.Outlined.Public,"A browser for your bot","Open a website here. You can take over at any time for sign-in, passkeys or a CAPTCHA.")
                        Text("Some websites block embedded browsers. Their direct connector or an external browser may be needed.",color=Muted,fontSize=12.sp,modifier=Modifier.padding(top=16.dp))
                    }
                }
            }
        }
    }
}

}

@Composable internal fun BrowserSurface(activity:MainActivity,visible:Boolean,modifier:Modifier,interactive:Boolean=visible) {
    // A new browser (after Android took the old one's engine away) gets a new surface.
    key(activity.c.browserGeneration) {
        AndroidView(factory={
            val engine=activity.browser
            (engine.view.parent as? ViewGroup)?.removeView(engine.view)
            // MATCH_PARENT, not the default WRAP_CONTENT: a WebView sized to its content gives pages a zero-height viewport,
            // so full-screen web apps (Google Messages, Gmail, WhatsApp) lay out at height 0 and show a blank page.
            engine.view.apply {layoutParams=ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT)}
        },update={view->
            view.importantForAccessibility=if(visible) android.view.View.IMPORTANT_FOR_ACCESSIBILITY_AUTO else android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            view.isFocusable=interactive;view.isFocusableInTouchMode=interactive
            view.setOnTouchListener {_,_-> !interactive}
        },modifier=if(visible) modifier else modifier.alpha(0f).clearAndSetSemantics {})
    }
}

@Composable private fun PdfPreview(c:Controller,name:String,share:()->Unit,close:()->Unit) {
    val bitmap=remember(name,c.workspace) {runCatching {
        if(Attachments.kind(name)=="image") return@runCatching Attachments.upright(c.file(name),1600)
        ParcelFileDescriptor.open(c.file(name),ParcelFileDescriptor.MODE_READ_ONLY).use {descriptor->
            PdfRenderer(descriptor).use {renderer->renderer.openPage(0).use {page->
                Bitmap.createBitmap(page.width*2,page.height*2,Bitmap.Config.ARGB_8888).also {b->
                    b.eraseColor(android.graphics.Color.WHITE);page.render(b,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
            }}
        }
    }.getOrNull()}
    DisposableEffect(bitmap){onDispose {bitmap?.recycle()}}
    Dialog(onDismissRequest=close) {Surface(shape=RoundedCornerShape(18.dp),color=Background) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(name,color=Mint,fontWeight=FontWeight.Bold)
            if(bitmap!=null)Image(bitmap.asImageBitmap(),name,Modifier.fillMaxWidth().weight(1f,false).verticalScroll(rememberScrollState()))
            else Text("This file could not be opened. The original is still in Computer → Files.")
            Row(Modifier.align(Alignment.End)) {
                TextButton(onClick=share){Icon(Icons.Outlined.Share,null,Modifier.size(16.dp));Spacer(Modifier.width(5.dp));Text("Share")}
                TextButton(onClick=close){Text("Close")}
            }
        }
    }}
}

@Composable private fun ConnectionsScreen(activity:MainActivity,c:Controller,configure:(String)->Unit) {
    val scope=rememberCoroutineScope()
    var advanced by rememberSaveable {mutableStateOf(false)}
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        item { ScreenTitle("Marketplace","Apps and accounts your bots can use. Each connects only when you approve it.") }
        item { PanelCard {
            ConnectionHeading("ChatGPT",Icons.Outlined.AutoAwesome,c.config("chatgpt").optString("access_token").isNotBlank())
            Text("Use your existing eligible plan allowance. Your ChatGPT chat history is separate.",color=Muted,fontSize=13.sp)
            val email=c.config("chatgpt").optString("email"); if(email.isNotBlank()) Text(email,color=Mint,fontSize=12.sp)
            if(c.authStatus.isNotBlank()) Text(c.authStatus,color=Muted,fontSize=12.sp)
            if(c.authError.isNotBlank()) SelectionContainer { Text(c.authError,color=MaterialTheme.colorScheme.error,fontSize=12.sp) }
            // A plan limit looks like a broken connection (every bot stops); say what it is and when it clears.
            c.planLimitNote().takeIf {it.isNotBlank()}?.let {Text(it,color=MaterialTheme.colorScheme.error,fontSize=12.sp)}
            Row {
                Button(onClick={c.connectChatGpt()},enabled=!c.authBusy) {Text(if(email.isBlank()) "Continue with ChatGPT" else "Reconnect")}
                if(c.authBusy) TextButton(onClick={c.cancelSignIn()}) {Text("Cancel",color=Muted)}
            }
            TextButton(onClick={activity.openExternal("https://chatgpt.com/settings/usage")}) {Text("ChatGPT usage settings")}
            Text("Plan-sharing limits can apply to Alpha separately. Check usage settings if a task pauses at a limit.",color=Muted,fontSize=11.sp)
            // (Which model to use, and how hard it thinks, is chosen in Settings → Assistant → Advanced: there is one place for it, not two.)
            if(c.modelCatalog.isNotEmpty()) {
                Row { TextButton(onClick={scope.launch {try {c.refreshModels()} catch(e:Exception){c.showNotice(plainError(e))}}}) {Text("Refresh models")};TextButton(onClick={runCatching {c.disconnect("chatgpt")}.onFailure {c.showNotice(plainError(it))}}) {Text("Disconnect",color=Muted)} }
            }
        } }
        item { PanelCard {
            ConnectionHeading("Google",Icons.Outlined.CloudQueue,c.config("google").optBoolean("connected") && !c.config("google").optBoolean("broken"))
            Text("Grant one service at a time. Personal app setup needs your package and signing certificate registered in Google Cloud; see the setup guide.",color=Muted,fontSize=13.sp)
            if(c.googleStatus.isNotBlank()) Text(c.googleStatus,color=Muted,fontSize=12.sp)
            if(c.googleError.isNotBlank()) SelectionContainer {Text(c.googleError,color=MaterialTheme.colorScheme.error,fontSize=12.sp)}
            GoogleAccess.groups.forEach { (name,scopes)->
                val granted=c.config("google").optJSONArray("scopes")?.strings().orEmpty().containsAll(scopes)
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    Text(name,Modifier.weight(1f),fontSize=14.sp)
                    TextButton(onClick={activity.connectGoogle(name)}) {Text(if(granted) "Granted · reconnect" else "Grant access")}
                }
            }
            Text("Drive can access files created by this app. Import existing text documents through the Library. Photos use the phone picker. Business Profile needs separate API approval.",color=Muted,fontSize=12.sp)
            if(c.config("google").optBoolean("connected")) TextButton(onClick={runCatching {c.disconnect("google")}.onFailure {c.showNotice(plainError(it))}}) {Text("Disconnect Google",color=Muted)}
            TextButton(onClick={activity.openExternal("https://console.cloud.google.com/apis/credentials")}) {Text("Open Google Cloud setup")}
        } }
        item {
            Text("APPS",fontSize=11.sp,letterSpacing=1.4.sp,color=Muted,modifier=Modifier.padding(top=8.dp))
            Text("Connect the apps you use. Alpha can look things up right away and asks before changing anything.",color=Muted,fontSize=13.sp)
            if(c.appStatus.isNotBlank()) Text(c.appStatus,color=Ink,fontSize=12.sp,modifier=Modifier.padding(top=6.dp))
        }
        items(AppCatalog.all,key={"app-"+it.id}) {app->
            val connected=c.server(app.id).optBoolean("connected")
            Row(Modifier.fillMaxWidth().background(Panel,RoundedCornerShape(18.dp)).padding(start=14.dp,end=8.dp,top=12.dp,bottom=12.dp),verticalAlignment=Alignment.CenterVertically) {
                Box(Modifier.size(38.dp).background(avatarColor(app.name),RoundedCornerShape(12.dp)),contentAlignment=Alignment.Center) {
                    Text(app.name.take(1),color=Color.Black,fontWeight=FontWeight.Bold,fontSize=17.sp)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Text(app.name,fontWeight=FontWeight.SemiBold,fontSize=15.sp)
                        if(connected) {Spacer(Modifier.width(6.dp));Icon(Icons.Outlined.CheckCircle,"Connected",tint=Mint,modifier=Modifier.size(16.dp))}
                    }
                    Text(app.blurb,color=Muted,fontSize=12.sp,maxLines=2)
                }
                when {
                    c.appBusy==app.id->Row(verticalAlignment=Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp),strokeWidth=2.dp);TextButton(onClick={c.cancelApp()}) {Text("Cancel",color=Muted)}
                    }
                    connected->TextButton(onClick={c.disconnectApp(app.id)}) {Text("Disconnect",color=Muted)}
                    else->OutlinedButton(onClick={if(app.auth=="oauth") c.connectApp(app) else configure("app:"+app.id)},enabled=c.appBusy.isBlank()) {Text("Connect")}
                }
            }
        }
        // Supabase, Vercel, Telnyx and your own servers are for people who set them up: one tap to open the list.
        item {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable {advanced=!advanced}.padding(vertical=12.dp),verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {Text("Advanced connections",fontWeight=FontWeight.SemiBold);Text("Supabase, Vercel, Telnyx and your own servers",color=Muted,fontSize=12.sp)}
                Icon(Icons.Outlined.ExpandMore,if(advanced) "Hide" else "Show",tint=Muted,modifier=Modifier.rotate(if(advanced) 180f else 0f))
            }
        }
        if(advanced) {
        item { ConnectorCard("Supabase","Read business data, inspect tables and create verified sample invoices in your selected project. Project actions require review.",Icons.Outlined.Storage,
            c.config("supabase_management").optBoolean("connected") || c.config("supabase").optString("access_token").isNotBlank(),{configure("supabase")},{c.disconnect("supabase")},c) }
        if(c.supabaseStatus.isNotBlank() || c.supabaseError.isNotBlank())item {PanelCard {
            Text(c.supabaseStatus,color=Mint,fontSize=13.sp)
            if(c.supabaseError.isNotBlank())Text(c.supabaseError,color=MaterialTheme.colorScheme.error,fontSize=12.sp)
            if(c.supabaseBusy)TextButton(onClick={c.cancelSupabaseSignIn()}){Text("Cancel Supabase sign-in")}
            else if(c.config("supabase_management").optString("token").isNotBlank())TextButton(onClick={c.recheckSupabase()}){Text("Check Supabase access")}
        }}
        item { ConnectorCard("Vercel","Inspect projects, deployments and build events. Preview deployments require review.",Icons.Outlined.Code,
            c.config("vercel").optBoolean("connected"),{configure("vercel")},{c.disconnect("vercel")},c) }
        item { ConnectorCard("Telnyx","See who called and texted your Telnyx numbers, and ask who an unknown caller is. Read only: Alpha never sends or buys anything with it.",Icons.Outlined.Call,
            c.config("telnyx").optBoolean("connected"),{configure("telnyx")},{c.disconnect("telnyx")},c) }
        if(c.config("telnyx").optBoolean("connected")) item {PanelCard {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Tell me about new calls",color=Ink,fontWeight=FontWeight.SemiBold)
                    Text("Checks every 15 minutes and sends a notification with a Who is this? button.",color=Muted,fontSize=12.sp)
                }
                Switch(checked=c.config("telnyx").optBoolean("alerts"),onCheckedChange={on->c.telnyxAlerts(on)})
            }
        }}
        item { ConnectorCard("Custom connector","Any other MCP server with a public address or bearer token.",Icons.Outlined.Extension,
            c.server("custom").optBoolean("connected"),{configure("mcp")},{c.disconnectApp("custom")},c) }
        item { PanelCard {
            Text("Phone check",fontWeight=FontWeight.SemiBold)
            Text("Check encrypted storage and private files without AI sign-in.",color=Muted,fontSize=13.sp)
            OutlinedButton(onClick={c.selfCheck()}) {Text("Run local self-check")}
            Text("Alpha Assistant "+BuildConfig.VERSION_NAME+" · Android 11+\nNo bot server or PC is needed while you use the app.",color=Muted,fontSize=11.sp)
        } }
        }
    }
}

@Composable private fun MemoryScreen(c:Controller,add:()->Unit,edit:(Memory)->Unit,editBot:()->Unit) {
    var section by rememberSaveable { mutableIntStateOf(0) }
    // "What I know about you" shows one bot's notes at a time (see MemoryUi.kt); the owner can switch bots and search here.
    var viewBot by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    var editing by remember { mutableStateOf<Memory?>(null) }
    var adding by remember { mutableStateOf(false) }
    val shown=viewBot.takeIf {name->c.bots.any {it.name==name}}?:c.workspace
    if(adding || editing!=null) MemoryEditor(c,shown,editing) {adding=false;editing=null}
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        item { ScreenTitle("What I know about you","What "+c.workspace+" knows, the rules it follows and the skills it can use.")
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                listOf("What I know","Rules","Skills").forEachIndexed {index,label->FilterChip(selected=section==index,onClick={section=index},label={Text(label)})}
            }
        }
        when(section) {
            0->memoryItems(c,shown,query,{viewBot=it;query=""},{query=it}) {m->editing=m;adding=m==null}
            1->item { PanelCard {
                Row(verticalAlignment=Alignment.CenterVertically) {BotAvatar(c.workspace,34.dp);Spacer(Modifier.width(10.dp));Text(c.workspace,fontWeight=FontWeight.SemiBold)}
                Text("Job",color=Muted,fontSize=11.sp);Text(c.bot().role.ifBlank {"Not set"})
                Text("Standing rules",color=Muted,fontSize=11.sp)
                SelectionContainer {Text(c.bot().rules.ifBlank {"None yet. Add rules like “${Profile.ruleExample}” or “Never send anything without asking me.” You can also say “from now on…” in Chat."},
                    color=if(c.bot().rules.isBlank()) Muted else Ink,fontSize=14.sp)}
                OutlinedButton(onClick=editBot) {Text("Edit job and rules")}
            } }
            else-> {
                item { Text("Skills are step-by-step ways of doing a job. Type / in Chat to use one. To add your own, explain a task in Chat and say “save this as a skill”.",color=Muted,fontSize=13.sp) }
                items(Skills.all(c.userSkills),key={it.getString("source")+it.getString("id")}) { skill->PanelCard {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Text(skill.getString("title"),Modifier.weight(1f),fontWeight=FontWeight.SemiBold)
                        Text(if(skill.getString("source")=="built-in") "Built-in" else "Yours",fontSize=10.sp,color=Mint)
                    }
                    Text(skill.getString("purpose"),color=Muted,fontSize=13.sp)
                    c.userSkills.firstOrNull {it.id==skill.getString("id") && skill.getString("source")!="built-in"}?.let {taught->
                        SelectionContainer {Text(taught.instructions,fontSize=12.sp,color=Ink,maxLines=12)}
                        TextButton(onClick={c.deleteSkill(taught.id)}) {Text("Remove skill",color=Muted)}
                    }
                } }
            }
        }
    }
}


@Composable private fun ConnectorCard(title:String,description:String,icon:ImageVector,connected:Boolean,configure:()->Unit,disconnect:()->Unit,c:Controller) {
    PanelCard {
        ConnectionHeading(title,icon,connected);Text(description,color=Muted,fontSize=13.sp)
        Row {OutlinedButton(onClick=configure) {Text(if(connected) "Configure" else "Connect")}
            if(connected) TextButton(onClick={runCatching(disconnect).onFailure {c.showNotice(plainError(it))}}) {Text("Disconnect",color=Muted)}}
    }
}
@Composable private fun ConnectionHeading(title:String,icon:ImageVector,connected:Boolean) {
    Row(verticalAlignment=Alignment.CenterVertically) {Icon(icon,null,tint=Mint,modifier=Modifier.size(22.dp));Spacer(Modifier.width(10.dp));Text(title,Modifier.weight(1f),fontWeight=FontWeight.SemiBold)
        if(connected) Icon(Icons.Outlined.CheckCircle,"Connected",tint=Mint,modifier=Modifier.size(18.dp))}
}
@Composable private fun ScreenTitle(title:String,subtitle:String) {
    Column(Modifier.padding(bottom=10.dp)) {Text(title,style=MaterialTheme.typography.headlineMedium);Text(subtitle,color=Muted,fontSize=14.sp,modifier=Modifier.padding(top=8.dp))}
}
@Composable private fun PanelCard(content:@Composable ColumnScope.()->Unit) {
    Column(Modifier.fillMaxWidth().background(Panel,RoundedCornerShape(20.dp)).padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp),content=content)
}
@Composable private fun EmptyState(icon:ImageVector,title:String,text:String) {
    Column(Modifier.fillMaxWidth().padding(vertical=30.dp),horizontalAlignment=Alignment.CenterHorizontally) {
        Icon(icon,null,tint=Mint,modifier=Modifier.size(36.dp));Text(title,fontWeight=FontWeight.SemiBold,modifier=Modifier.padding(top=14.dp))
        Text(text,color=Muted,fontSize=13.sp,modifier=Modifier.padding(top=8.dp),textAlign=androidx.compose.ui.text.style.TextAlign.Center)
    }
}
@Composable private fun Status(text:String) {
    Text(Words.status(text),fontSize=11.sp,color=if(text=="Completed") Mint else if(text in listOf("Needs review","Needs you","Needs connection","Check result","Partial")) Amber else Muted,
        modifier=Modifier.background(Raised2,RoundedCornerShape(8.dp)).padding(horizontal=8.dp,vertical=5.dp))
}
private fun date(time:Long):String=DateTimeFormatter.ofPattern("MMM d, h:mm a").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(time))

@Composable private fun FormDialog(title:String,description:String,fields:List<Field>,c:Controller,dismiss:()->Unit,help:@Composable ()->Unit={},save:suspend (Map<String,String>)->Unit) {
    var values by remember { mutableStateOf(fields.associate { it.key to it.value }) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val scope=rememberCoroutineScope()
    Dialog(onDismissRequest={if(!busy) dismiss()}) {
        Surface(shape=RoundedCornerShape(24.dp),color=Panel) {
            Column(Modifier.padding(22.dp).verticalScroll(rememberScrollState()).imePadding(),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                Text(title,style=MaterialTheme.typography.titleLarge)
                Text(description,color=Muted,fontSize=13.sp)
                help()
                fields.forEach { field->
                    OutlinedTextField(value=values[field.key].orEmpty(),onValueChange={values=values+(field.key to it)},label={Text(field.label,fontSize=12.sp)},
                        singleLine=!field.multi,minLines=if(field.multi) 3 else 1,maxLines=if(field.multi) 8 else 1,
                        visualTransformation=if(field.secret) PasswordVisualTransformation() else VisualTransformation.None,
                        modifier=Modifier.fillMaxWidth(),enabled=!busy)
                }
                if(error.isNotBlank()) Text(error,color=MaterialTheme.colorScheme.error,fontSize=12.sp)
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End,verticalAlignment=Alignment.CenterVertically) {
                    TextButton(onClick=dismiss,enabled=!busy) {Text("Cancel")}
                    Button(onClick={scope.launch {
                        busy=true; error=""
                        try {save(values)} catch(e:Exception){error=Rules.redact(c.redact(plainError(e)),fields.filter {it.secret}.map {values[it.key].orEmpty()})}
                        finally {busy=false}
                    }},enabled=!busy) {if(busy) CircularProgressIndicator(Modifier.size(18.dp),strokeWidth=2.dp) else Text("Save")}
                }
            }
        }
    }
}

/**
 * Signing in, the normal way: the website's own sign-in page, full screen in Alpha's browser. The owner types their own
 * password (or taps Forgot password, or signs in with Google), then taps Done. The bot never sees what they type.
 */
@Composable fun SignInScreen(activity:MainActivity,c:Controller,url:String,close:()->Unit,done:()->Unit) {
    val host=remember(url) {runCatching {java.net.URI(url).host.removePrefix("www.")}.getOrDefault("the website")}
    LaunchedEffect(Unit) {activity.secureScreen(true)}
    DisposableEffect(Unit) {onDispose {activity.secureScreen(false)}}
    // "Sign in with Google / Microsoft" opens a small window of its own; it shows on top of the page until the site closes it.
    var popup by remember {mutableStateOf<android.webkit.WebView?>(null)}
    DisposableEffect(c.browserGeneration) {
        val engine=activity.browser
        engine.onPopup={popup=it};engine.allowPopups(true)
        onDispose {engine.allowPopups(false);engine.onPopup=null}
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start=4.dp,end=12.dp,top=6.dp,bottom=6.dp),verticalAlignment=Alignment.CenterVertically) {
            IconButton(onClick=close) {Icon(Icons.Outlined.Close,"Cancel",tint=Ink)}
            Column(Modifier.weight(1f)) {
                Text("Sign in to $host",fontWeight=FontWeight.SemiBold,fontSize=17.sp,color=Ink,maxLines=1,overflow=TextOverflow.Ellipsis)
                Text("Only you see what you type here.",color=Muted,fontSize=12.sp)
            }
            IconButton(onClick={if(popup!=null) activity.browser.closePopup() else if(!activity.browser.back()) c.showNotice("This is the first page.")}) {Icon(Icons.AutoMirrored.Outlined.ArrowBack,"Back a page",tint=Ink)}
            Button(onClick=done,colors=ButtonDefaults.buttonColors(containerColor=MineBubble,contentColor=Color.White)) {Text("Done")}
        }
        // A sign-in link the site emailed opens in the phone's own browser, which is a different place. Copy it, paste it here, and it opens in Alpha.
        Text("Got a sign-in link by email? Copy it, then tap here.",color=Muted,fontSize=12.sp,
            modifier=Modifier.fillMaxWidth().clickable {
                val clip=(activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).primaryClip
                val link=clip?.takeIf {it.itemCount>0}?.getItemAt(0)?.coerceToText(activity)?.toString()?.trim().orEmpty()
                val problem=SignInLinks.problem(link,url)
                if(problem==null) activity.browser.view.loadUrl(link) else c.showNotice(problem)
            }.padding(horizontal=16.dp,vertical=6.dp))
        HorizontalDivider(color=Line)
        Box(Modifier.weight(1f).fillMaxWidth().imePadding()) {
            BrowserSurface(activity,true,Modifier.matchParentSize())
            popup?.let {window->
                Column(Modifier.matchParentSize().background(Background)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal=4.dp),verticalAlignment=Alignment.CenterVertically) {
                        IconButton(onClick={activity.browser.closePopup()}) {Icon(Icons.Outlined.Close,"Close this window",tint=Ink)}
                        Text("Sign-in window",color=Ink,fontWeight=FontWeight.SemiBold,fontSize=15.sp)
                    }
                    HorizontalDivider(color=Line)
                    key(window) {
                        AndroidView(factory={(window.parent as? ViewGroup)?.removeView(window);window.apply {layoutParams=ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT)}},
                            modifier=Modifier.fillMaxSize())
                    }
                }
            }
        }
    }
}
