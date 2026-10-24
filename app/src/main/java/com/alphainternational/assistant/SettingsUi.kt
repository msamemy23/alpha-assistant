package com.alphainternational.assistant

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.launch

internal data class Choice(val key:String,val label:String,val note:String="",val section:String="")
private data class Confirm(val title:String,val text:String,val action:String,val run:()->Unit)

/**
 * Settings, in a few plain groups instead of one long list: Assistant (with the model and intelligence tucked under Advanced), What Alpha can do,
 * Logins, Connections, Voice, Notifications, Look, and room for Proactive and Backup once those screens exist. Nothing here changes a stored
 * setting or its default; it only moves where the controls are.
 */
@Composable fun SettingsScreen(activity:MainActivity,c:Controller,back:()->Unit,editBot:(String)->Unit,newBot:()->Unit,marketplace:()->Unit,
    openSignIn:(String)->Unit={},go:(String)->Unit={}) {
    val ai=c.aiSettings
    var page by rememberSaveable {mutableStateOf("")}
    LaunchedEffect(c.settingsPage) {if(c.settingsPage.isNotEmpty()) {page=c.settingsPage;c.settingsPage=""}}
    var picker by remember {mutableStateOf("")}
    var confirm by remember {mutableStateOf<Confirm?>(null)}
    var voiceKey by remember {mutableStateOf(false)}
    val backup=rememberBackupFlow(c)
    val listed=c.modelCatalog.filter {it.listed};val more=c.modelCatalog.filterNot {it.listed}
    fun name(slug:String)=c.modelCatalog.firstOrNull {it.slug==slug}?.name?:slug.ifBlank {"Picked automatically"}
    val (dq,de,dh)=ModelPlan.defaults(c.modelCatalog,c.selectedModel)
    androidx.activity.compose.BackHandler(enabled=page.isNotEmpty()) {page=""}
    val title=when(page) {"assistant"->"Assistant";"powers"->"What Alpha can do";"logins"->"Logins";"voice"->"Voice";"notifications"->"Notifications";"privacy"->"Privacy & data";"provider"->"AI provider";"backup"->"Backup";"about"->"About";else->"Settings"}
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start=4.dp,end=16.dp,top=6.dp,bottom=6.dp),verticalAlignment=Alignment.CenterVertically) {
            IconButton(onClick={if(page.isNotEmpty()) page="" else back()}) {Icon(Icons.AutoMirrored.Outlined.ArrowBack,"Back",tint=Ink)}
            Text(title,fontWeight=FontWeight.SemiBold,fontSize=18.sp,color=Ink)
        }
        HorizontalDivider(color=Line)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            when(page) {
                "assistant"->AssistantPage(c,ai,{picker=it},{confirm=it},editBot,newBot,::name,dq,de,dh,listed.size,more.size)
                "powers"->PowersPage(activity,c)
                "logins"->LoginsPage(c,openSignIn)
                "voice"->VoicePage(c,{picker=it},{voiceKey=true})
                "notifications"->NotificationsPage(activity,c)
                "privacy"->PrivacyPage(c,{picker=it},{confirm=it})
                "backup"->BackupPage(backup)
                "provider"->ProviderPage(c)
                "about"->AboutPage(c)
                else-> {
                    val email=c.config("chatgpt").optString("email")
                    Group {
                        Row2(Icons.Outlined.AccountCircle,"ChatGPT",email.ifBlank {"Not connected"}) {marketplace()}
                        Row2(Icons.Outlined.Psychology,"AI provider",c.provider.summary()) {page="provider"}
                        Row2(Icons.Outlined.DataUsage,"AI use today",c.usageToday()) {}
                        if(!c.provider.custom) Row2(Icons.Outlined.DataUsage,"ChatGPT usage",c.planLimitShort().ifBlank {"Check your plan's limits"}) {activity.openExternal("https://chatgpt.com/settings/usage")}
                        if(email.isNotBlank()) Row2(Icons.AutoMirrored.Outlined.Logout,"Sign out of ChatGPT","Your bots, chats and files stay on this phone") {
                            confirm=Confirm("Sign out of ChatGPT?","Your bots can't think until you sign in again. Chats, memories and files stay on this phone.","Sign out") {
                                runCatching {c.disconnect("chatgpt")}.onFailure {c.showNotice(plainError(it))}
                            }
                        }
                    }
                    Group {
                        Row2(Icons.Outlined.AutoAwesome,"Assistant","Your bots, what they learn, when they ask first") {page="assistant"}
                        Row2(Icons.Outlined.TaskAlt,"What Alpha can do","Texting, Away mode, background work and more") {page="powers"}
                        Row2(Icons.Outlined.Lock,"Logins","Websites you're signed in to") {page="logins"}
                        Row2(Icons.Outlined.Storefront,"Connections","ChatGPT, Google and other apps") {marketplace()}
                        Row2(Icons.Outlined.RecordVoiceOver,"Voice","How Alpha sounds on a call") {page="voice"}
                        Row2(Icons.Outlined.Notifications,"Notifications",if(UiPrefs.quiet) "Quiet: only when it matters" else "Only what matters") {page="notifications"}
                        Row2(Icons.Outlined.Palette,"Look",UiPrefs.lookLabel(UiPrefs.appearance)) {picker="look"}
                    }
                    // Room for the Proactive page: its row appears once that screen exists.
                    if(Screens.has("proactive")) Group {
                        Row2(Icons.Outlined.Visibility,"Proactive","Let Alpha notice things and offer to help") {go("proactive")}
                    }
                    Group {
                        Row2(Icons.Outlined.CloudUpload,"Backup","Keep a safe copy of your bots, memory and skills") {page="backup"}
                        Row2(Icons.Outlined.Shield,"Privacy & data","Clear chats and memories") {page="privacy"}
                        Row2(Icons.Outlined.Info,"About","Alpha Assistant "+BuildConfig.VERSION_NAME) {page="about"}
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    BackupDialogs(backup)
    val modelChoices=listed.map {Choice(it.slug,it.name,it.efforts.joinToString {e->ModelPlan.effortLabel(e)})}+
        more.map {Choice(it.slug,it.name,"May be older or limited","More models")}
    when(picker) {
        "look"->Picker("Look",UiPrefs.looks.map {(key,label)->Choice(key,label,if(key=="system") "Dark or light, the same as your phone" else "")},UiPrefs.appearance,{picker=""}) {UiPrefs.setAppearance(c,it);picker=""}
        "model"->Picker("Model",listOf(Choice(ModelPlan.AUTO,"Auto","Quick, everyday and hard jobs each get the right model"))+modelChoices,ai.model,{picker=""}) {c.updateAi(ai.copy(model=it));picker=""}
        "effort"-> {
            val fixed=c.modelCatalog.firstOrNull {it.slug==ai.model}?.efforts.orEmpty()
            val levels=fixed.ifEmpty {ModelPlan.efforts}
            Picker("Intelligence",listOf(Choice(ModelPlan.AUTO,"Auto","Low for quick, Medium for everyday, High for hard"))+
                levels.map {Choice(it,ModelPlan.effortLabel(it),when(it) {"minimal","low"->"Fastest, lightest";"medium"->"Balanced";"high"->"More careful thinking";"xhigh"->"Deepest thinking, slowest";else->""})},ai.effort,{picker=""}) {
                c.updateAi(ai.copy(effort=it));picker=""
            }
        }
        "quick"->Picker("Quick jobs use",modelChoices,ai.quick.ifBlank {dq},{picker=""}) {c.updateAi(ai.copy(quick=it));picker=""}
        "everyday"->Picker("Everyday jobs use",modelChoices,ai.everyday.ifBlank {de},{picker=""}) {c.updateAi(ai.copy(everyday=it));picker=""}
        "hard"->Picker("Hard jobs use",modelChoices,ai.hard.ifBlank {dh},{picker=""}) {c.updateAi(ai.copy(hard=it));picker=""}
        "voice_provider"->Picker("Voice for calls",CloudVoice.providers.map {(k,v)->Choice(k,v,when(k) {
            "elevenlabs"->"Sounds like a real person: laughs, warmth, excitement. Needs your ElevenLabs key."
            "openai"->"Natural and warm. Needs an OpenAI API key."
            else->"Free and offline."})},c.config("voice_cloud").optString("provider","built_in").ifBlank {"built_in"},{picker=""}) {
            // Each service keeps its own key, so switching back and forth doesn't mean pasting it again.
            val old=c.config("voice_cloud");val next=org.json.JSONObject(old.toString()).put("provider",it)
            if(old.optString("provider")!=it) next.put("key",old.optString("key_$it")).put("voice","")
            c.saveConfig("voice_cloud",next);picker=""
            if(it!="built_in" && next.optString("key").isBlank()) voiceKey=true
        }
        "voice_pick"-> {
            val vc=c.config("voice_cloud")
            val voices=if(vc.optString("provider")=="openai") CloudVoice.openaiVoices else CloudVoice.elevenVoices
            Picker("Which voice",voices.map {(k,v)->Choice(k,v)},vc.optString("voice").ifBlank {voices.keys.first()},{picker=""}) {c.saveConfig("voice_cloud",vc.put("voice",it));picker=""}
        }
        "approvals"->Picker("Bots ask before",listOf(Choice("bots","Only making a new bot",if(Profile.generic) "Fastest: everything else just gets done." else "Everything else just gets done. (Your pick.)"),
            Choice("important","Sending, paying, deleting",if(Profile.generic) "Plus making a new bot. Recommended." else "Plus making a new bot."),Choice("all","Anything that changes something","The most careful setting.")),
            ai.approvals,{picker=""}) {c.updateAi(ai.copy(approvals=it));picker=""}
        "sms_daily"->Picker("Texts per day",SmsCap.choices.map {Choice(it.toString(),"$it a day",if(it==10) "The most careful" else if(it>=500) "Practically no limit" else "")},
            ai.smsDaily.toString(),{picker=""}) {c.updateAi(ai.copy(smsDaily=it.toIntOrNull()?:ai.smsDaily));picker=""}
        "texting"->Picker("Send texts with",listOf(Choice("phone","My phone","Sent straight from your number. Shows in your Messages app."),
            Choice("google_web","Google Messages web","The bot uses messages.google.com in its browser. You sign in and pair it once."),
            Choice("ask","Ask me each time","The bot asks which way before each text.")),ai.texting,{picker=""}) {c.updateAi(ai.copy(texting=it));picker=""}
        "routines"->Picker("Routines use",listOf(Choice("quick","Quick model","Saves your ChatGPT allowance"),Choice("everyday","Everyday model"),
            Choice("hard","Hard model","Best quality"))+modelChoices.map {it.copy(section="Or a specific model")},ai.routines,{picker=""}) {c.updateAi(ai.copy(routines=it));picker=""}
        "lead"->Picker("Who writes the team's answers?",listOf(Choice("","Pick for me","The bot whose job reads like an advisor or chief of staff"))+c.bots.map {Choice(it.name,it.name,it.role.take(60))},
            c.config("team").optString("lead"),{picker=""}) {name->c.setLeadBot(name);picker=""}
        "clear_bot"->Picker("Clear which bot's chat?",c.bots.map {Choice(it.name,it.name)},"",{picker=""}) {name->picker=""
            confirm=Confirm("Clear "+name+"'s chat?","Its private chat history is removed. Files, memories and routines stay.","Clear") {runCatching {c.clearChat(name)}.onFailure {c.showNotice(plainError(it))}}}
        "forget"->Picker("Forget which bot's memories?",c.bots.map {Choice(it.name,it.name,c.memories.count {m->m.workspace==it.name}.toString()+" memories")},"",{picker=""}) {name->picker=""
            confirm=Confirm("Forget "+name+"'s memories?","Everything "+name+" remembers about you is deleted. Chats and files stay.","Forget") {c.forgetMemories(name)}}
    }
    if(voiceKey) {
        // The key is typed (or pasted) by the owner here and stays encrypted on the phone; the screen is hidden from screenshots meanwhile.
        val vc=c.config("voice_cloud");val provider=vc.optString("provider")
        var key by remember {mutableStateOf("")}
        var error by remember {mutableStateOf("")}
        DisposableEffect(Unit) {activity.secureScreen(true);onDispose {activity.secureScreen(false)}}
        AlertDialog(onDismissRequest={voiceKey=false},containerColor=SheetBg,
            title={Text("Paste your "+(if(provider=="openai") "OpenAI" else "ElevenLabs")+" key")},
            text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value=key,onValueChange={key=it;error=""},singleLine=true,label={Text("Key")},
                    visualTransformation=androidx.compose.ui.text.input.PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth())
                if(error.isNotBlank()) Text(error,color=MaterialTheme.colorScheme.error,fontSize=12.sp)
                Text("It stays encrypted on this phone and is only sent to "+(if(provider=="openai") "OpenAI" else "ElevenLabs")+" to make speech.",color=Muted,fontSize=12.sp)
            }},
            confirmButton={TextButton(onClick={
                runCatching {CloudVoice.checkKey(provider,key)}.onSuccess {clean->
                    c.saveConfig("voice_cloud",vc.put("key",clean).put("key_$provider",clean));voiceKey=false;c.voice.preview()
                }.onFailure {error=plainError(it)}
            }) {Text("Save & hear it",color=Ink)}},
            dismissButton={TextButton(onClick={voiceKey=false}) {Text("Cancel",color=Muted)}})
    }
    confirm?.let {ask->
        AlertDialog(onDismissRequest={confirm=null},title={Text(ask.title)},text={Text(ask.text)},containerColor=SheetBg,
            confirmButton={TextButton(onClick={confirm=null;ask.run()}) {Text(ask.action,color=MaterialTheme.colorScheme.error)}},
            dismissButton={TextButton(onClick={confirm=null}) {Text("Cancel",color=Ink)}})
    }
}

// ---------------------------------------------------------------------------------------------------------------------------------------------
// Assistant
// ---------------------------------------------------------------------------------------------------------------------------------------------

@Composable private fun AssistantPage(c:Controller,ai:AiSettings,pick:(String)->Unit,ask:(Confirm)->Unit,editBot:(String)->Unit,newBot:()->Unit,
    name:(String)->String,dq:String,de:String,dh:String,listedCount:Int,moreCount:Int) {
    var advanced by rememberSaveable {mutableStateOf(false)}
    var refreshing by remember {mutableStateOf(false)}
    val scope=rememberCoroutineScope()
    Section("Your bots")
    c.bots.forEach {bot->
        Row(Modifier.fillMaxWidth().background(Panel,RoundedCornerShape(16.dp)).padding(start=12.dp,end=4.dp,top=10.dp,bottom=10.dp),verticalAlignment=Alignment.CenterVertically) {
            BotAvatar(bot.name,36.dp);Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {Text(bot.name,fontWeight=FontWeight.SemiBold,color=Ink);Text(bot.role,color=Muted,fontSize=12.sp,maxLines=1)}
            TextButton(onClick={editBot(bot.name)}) {Text("Edit",color=Ink)}
            TextButton(enabled=c.bots.size>1,onClick={ask(Confirm("Delete "+bot.name+"?",
                "This deletes "+bot.name+"'s private chats, tasks, memories, routines and files on this phone. Team chat messages stay. This can't be undone.","Delete") {
                runCatching {c.removeBot(bot.name)}.onFailure {c.showNotice(plainError(it))}
            })}) {Text("Delete",color=if(c.bots.size>1) MaterialTheme.colorScheme.error else Muted)}
        }
    }
    OutlinedButton(onClick=newBot) {Icon(Icons.Outlined.Add,null);Spacer(Modifier.width(6.dp));Text("New bot",color=Ink)}
    if(c.bots.size>1) Group {
        Row2(Icons.Outlined.Groups,"Writes the team's answers",c.leadBot()+(if(c.config("team").optString("lead").isBlank()) " · picked automatically" else "")) {pick("lead")}
    }
    if(c.customTools.isNotEmpty()) {
        Section("Tools your bots made")
        c.customTools.forEach {tool->
            Row(Modifier.fillMaxWidth().background(Panel,RoundedCornerShape(16.dp)).padding(start=14.dp,end=4.dp,top=10.dp,bottom=10.dp),verticalAlignment=Alignment.CenterVertically) {
                Icon(if(tool.kind=="web") Icons.Outlined.TravelExplore else Icons.Outlined.Calculate,null,tint=Ink,modifier=Modifier.size(20.dp));Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(tool.title,color=Ink,fontSize=15.sp)
                    Text(tool.description+" · used by "+c.bots.filter {tool.id in it.tools}.joinToString {it.name}.ifBlank {"any bot"},color=Muted,fontSize=12.sp,maxLines=2)
                }
                TextButton(onClick={ask(Confirm("Remove "+tool.title+"?","Bots can no longer use this tool. You or a bot can make it again later.","Remove") {c.deleteTool(tool.id)})}) {
                    Text("Remove",color=MaterialTheme.colorScheme.error)
                }
            }
        }
    }
    Section("How they work")
    Group {
        Toggle("Look things up on the web","Live search with sources for current facts, prices and news.",ai.webSearch) {c.updateAi(ai.copy(webSearch=it))}
        Toggle("Learn on its own","Each bot remembers what you like, what you usually do, the people you deal with, and what worked. You can see or erase it in the bot's Profile.",ai.learn) {c.updateAi(ai.copy(learn=it))}
        Row2(Icons.Outlined.VerifiedUser,"Bots ask before",when(ai.approvals) {
            "important"->"Sending, paying, deleting and making bots"
            "all"->"Anything that changes something"
            else->"Only making a new bot"
        }) {pick("approvals")}
        Text("Bots never buy, pay or delete anything you didn't ask for, and they never see your passwords.",color=Muted,fontSize=12.sp,modifier=Modifier.padding(horizontal=14.dp,vertical=4.dp))
    }
    // The model and how hard it thinks: still here, one tap further in.
    Group {
        Row2(Icons.Outlined.Tune,"Advanced","Model and intelligence level") {advanced=!advanced}
        if(advanced) {
            Row2(Icons.Outlined.AutoAwesome,"Model",if(ai.model==ModelPlan.AUTO) "Auto · picks the right model for each job" else name(ai.model)) {pick("model")}
            Row2(Icons.Outlined.Psychology,"Intelligence",if(ai.effort==ModelPlan.AUTO) "Auto · more thinking for harder jobs" else ModelPlan.effortLabel(ai.effort)) {pick("effort")}
            if(ai.model==ModelPlan.AUTO) {
                Row2(Icons.Outlined.Bolt,"Quick jobs use",name(ai.quick.ifBlank {dq})) {pick("quick")}
                Row2(Icons.Outlined.Work,"Everyday jobs use",name(ai.everyday.ifBlank {de})) {pick("everyday")}
                Row2(Icons.Outlined.Insights,"Hard jobs use",name(ai.hard.ifBlank {dh})) {pick("hard")}
                Toggle("Move up when a task gets long","A task still working after several steps switches to the Hard model.",ai.escalate) {c.updateAi(ai.copy(escalate=it))}
            }
            Row2(Icons.Outlined.Schedule,"Routines use",when(ai.routines) {"quick"->"Quick model · saves allowance";"everyday"->"Everyday model";"hard"->"Hard model";else->name(ai.routines)}) {pick("routines")}
            Toggle("Show which model answered",if(dh.isBlank()) "A small label under each answer with the model and intelligence level." else "A small label under each answer, like \""+name(dh)+" · High\".",ai.label) {c.updateAi(ai.copy(label=it))}
            Row2(Icons.Outlined.Sms,"Send texts with",Texting.label(ai.texting)+when(ai.texting) {
                "google_web"->" · pair it once in a bot's Computer → Browser"
                "ask"->" · the bot asks which way"
                else->" · sent straight from your number"
            }) {pick("texting")}
            Row2(Icons.Outlined.Sms,"Texts per day","Bots send at most "+ai.smsDaily+" a day, and no more than "+(if(Profile.generic) SmsCap.PER_PERSON_GENERIC else SmsCap.PER_PERSON_OWNER)+" to one person") {pick("sms_daily")}
            Text((if(c.modelCatalog.isEmpty()) "Connect ChatGPT in Marketplace to load your models." else "$listedCount models on your plan"+if(moreCount==0) "" else " · $moreCount more"),
                color=Muted,fontSize=12.sp,modifier=Modifier.padding(start=14.dp,top=4.dp))
            TextButton(enabled=!refreshing,onClick={scope.launch {refreshing=true;try {c.refreshModels();c.showNotice("Model list updated.")} catch(e:Exception) {c.showNotice(plainError(e))};refreshing=false}}) {
                Text(if(refreshing) "Refreshing…" else "Refresh model list",color=Ink)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------------------------------------
// What Alpha can do
// ---------------------------------------------------------------------------------------------------------------------------------------------

/** How a power is doing: working, switched off by choice, or blocked and needing a fix. */
private enum class PowerState {ON,OFF,FIX}

@Composable private fun PowerRow(state:PowerState,icon:ImageVector,title:String,note:String,onClick:(()->Unit)?) {
    val color=when(state) {PowerState.ON->Good;PowerState.OFF->Muted;PowerState.FIX->Bad}
    val word=when(state) {PowerState.ON->"On";PowerState.OFF->"Off";PowerState.FIX->"Needs a tap"}
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).then(if(onClick!=null) Modifier.clickable(onClick=onClick) else Modifier).padding(horizontal=14.dp,vertical=12.dp),
        verticalAlignment=Alignment.CenterVertically) {
        Icon(icon,null,tint=Ink,modifier=Modifier.size(20.dp));Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {Text(title,color=Ink,fontSize=15.sp);if(note.isNotBlank()) Text(note,color=Muted,fontSize=12.sp,maxLines=3)}
        Spacer(Modifier.width(10.dp))
        Row(verticalAlignment=Alignment.CenterVertically) {
            Box(Modifier.size(9.dp).background(color,CircleShape));Spacer(Modifier.width(6.dp))
            Text(word,style=Type.tiny.copy(color=color,fontWeight=FontWeight.SemiBold))
        }
    }
}

/** One page that answers "what can Alpha do on this phone right now?" with a green or red mark for each power, and a tap to fix the red ones. */
@Composable private fun PowersPage(activity:MainActivity,c:Controller) {
    // Coming back from one of Android's own screens: pick up the new state without a button press.
    var tick by remember {mutableIntStateOf(0)}
    LaunchedEffect(Unit) {while(true) {kotlinx.coroutines.delay(1_500);tick++}}
    tick;c.awayVersion
    val away=c.awaySettings()
    val texting=PhoneSms.allowed(activity)
    val notices=androidx.core.app.NotificationManagerCompat.from(activity).areNotificationsEnabled()
    val hearing=AwayListener.enabled(activity)
    val background=activity.backgroundAllowed()
    val computer=c.computerStatus()
    val computerState=when {computer.startsWith("On.")->PowerState.ON;computer.startsWith("Turned off")->PowerState.OFF;else->PowerState.FIX}
    val captions=c.captionModels.ready
    Text("Green means it works right now. Anything that needs a tap says so, and tapping it opens the fix.",color=Muted,fontSize=13.sp,modifier=Modifier.padding(horizontal=4.dp))
    Group {
        PowerRow(if(texting) PowerState.ON else PowerState.FIX,Icons.Outlined.Sms,"Texting",
            if(texting) "Bots can text people from your number." else "Android hasn't let Alpha send texts yet.",if(texting) null else ({activity.requestSms {}}))
        PowerRow(if(notices) PowerState.ON else PowerState.FIX,Icons.Outlined.Notifications,"Notifications",
            if(notices) "Alpha can tell you when work is done." else "Alpha can't show notifications.",if(notices) null else ({activity.openNotificationSettings()}))
        PowerRow(if(hearing) PowerState.ON else PowerState.FIX,Icons.Outlined.Hearing,"Hearing your texts",
            if(hearing) "Alpha can notice new texts (needed for Away mode)." else "Needed for Away mode. Turn on notification access for Alpha.",
            if(hearing) null else ({runCatching {activity.startActivity(AwayListener.settingsIntent(activity))}.onFailure {c.showNotice("Open Settings, then Notifications, then Device & app notifications, and turn on Alpha.")}}))
        PowerRow(if(away.on) PowerState.ON else PowerState.OFF,Icons.Outlined.NightsStay,"Away mode",
            if(away.on) (if(away.live) "Answering your texts for you." else "Writing replies, nothing sent without you.") else "Off. Turn it on from Home when you're away.",null)
        PowerRow(if(background) PowerState.ON else PowerState.FIX,Icons.Outlined.BatteryChargingFull,"Working in other apps",
            if(background) "Bots keep working while you use other apps." else "Android may slow bots down while you use other apps.",if(background) null else ({activity.allowBackground()}))
        PowerRow(computerState,Icons.Outlined.Terminal,"Command line (Termux)",computer,{c.setComputerAllowed(!c.computerAllowed());c.showNotice(if(c.computerAllowed()) "Bots can use the command line." else "The command line is off.")})
        if(!BuildConfig.FRIENDS) {
            val phoneOn=PhoneControl.enabled();val phonePaused=c.phonePaused()
            PowerRow(if(!phoneOn) PowerState.FIX else if(phonePaused) PowerState.OFF else PowerState.ON,Icons.Outlined.TouchApp,"Using your apps",
                if(!phoneOn) "Off. Tap to open Android's Accessibility settings, then Installed apps, then \"Alpha: use my apps\", and switch it on. If Android says the setting is restricted: Settings, Apps, Alpha, the three dots, Allow restricted settings."
                else if(phonePaused) "Paused. Tap to let bots use your apps again."
                else "Bots can open and use any app on your phone when you ask. They never type passwords or codes, and they ask you before paying or erasing. Tap to pause.",
                {
                    if(!phoneOn) runCatching {activity.startActivity(PhoneControl.settingsIntent())}.onFailure {c.showNotice("Open Settings, then Accessibility, then Installed apps, and turn on Alpha.")}
                    else {c.setPhonePaused(!phonePaused);c.showNotice(if(phonePaused) "Bots can use your apps again." else "Using your apps is paused.")}
                })
        }
        PowerRow(if(captions) PowerState.ON else PowerState.FIX,Icons.Outlined.ClosedCaption,"Video captions",c.captionStatus(),{c.captionTap()})
    }
    Group {
        Row2(Icons.Outlined.StopCircle,"Stop every bot","Stops everything the bots are working on or waiting to start. Finished work and chats stay.") {c.stopEverything()}
        Row2(Icons.Outlined.StopCircle,"Stop all commands","Ends anything the bots are running right now") {c.stopComputer()}
        Text("Each bot works in its own folder and can't see your private data, texts or contacts. Every command is logged on this phone.",
            color=Muted,fontSize=12.sp,modifier=Modifier.padding(horizontal=14.dp,vertical=4.dp))
    }
    if(!BuildConfig.FRIENDS && PhoneControl.enabled()) Group {
        Toggle("Password and code apps","Let bots use password managers and one-time-code apps. Off is safer: what they show would go to the AI.",c.phoneVaultsAllowed()) {c.setPhoneVaultsAllowed(it)}
        var routes by remember {mutableStateOf(c.phoneRouteCount())}
        Row2(Icons.Outlined.TouchApp,"Routes bots remember",
            if(routes==0) "None yet. After a job in one of your apps works, the bot keeps the steps (never what was typed) so the next one is faster."
            else "$routes saved. Tap to forget them all.") {
            if(routes>0) {c.forgetPhoneRoutes();routes=0;c.showNotice("Saved routes forgotten.")}
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------------------------------------
// Logins
// ---------------------------------------------------------------------------------------------------------------------------------------------

/** Websites the bots can use: the ones you are signed in to, any login saved behind the phone lock, and how signing in works. */
@Composable private fun LoginsPage(c:Controller,signIn:(String)->Unit) {
    var site by remember {mutableStateOf("")}
    Text("Websites your bots can use. You sign in on the real website, so Forgot password and Sign in with Google work, and Alpha never sees what you type.",
        color=Muted,fontSize=13.sp,modifier=Modifier.padding(horizontal=4.dp))
    Row(verticalAlignment=Alignment.CenterVertically) {
        OutlinedTextField(value=site,onValueChange={site=it},label={Text("Website, like instagram.com")},singleLine=true,modifier=Modifier.weight(1f),
            keyboardOptions=androidx.compose.foundation.text.KeyboardOptions(imeAction=androidx.compose.ui.text.input.ImeAction.Go),
            keyboardActions=androidx.compose.foundation.text.KeyboardActions(onGo={if(site.isNotBlank()) signIn(site)}))
        Spacer(Modifier.width(8.dp))
        Button(onClick={if(site.isNotBlank()) signIn(site)},enabled=site.isNotBlank()) {Text("Sign in")}
    }
    Section("Signed in")
    if(c.signedIn.isEmpty()) Text("No websites yet. When a bot needs one, it asks you to sign in right there. Or add one above.",color=Muted,fontSize=13.sp,modifier=Modifier.padding(horizontal=4.dp))
    c.signedIn.forEach {entry->
        val address=entry.optString("site")
        Row(Modifier.fillMaxWidth().background(Panel,RoundedCornerShape(16.dp)).padding(start=14.dp,end=4.dp,top=6.dp,bottom=6.dp),verticalAlignment=Alignment.CenterVertically) {
            Icon(Icons.Outlined.Lock,null,tint=Ink,modifier=Modifier.size(18.dp));Spacer(Modifier.width(12.dp))
            Text(address.removePrefix("https://").removePrefix("www."),Modifier.weight(1f),color=Ink,fontWeight=FontWeight.Medium,maxLines=1)
            TextButton(onClick={signIn(address)}) {Text("Open",color=Ink)}
            TextButton(onClick={c.signOutSite(address)}) {Text("Sign out",color=Muted)}
        }
    }
    if(c.vault.isNotEmpty()) {
        Section("Saved on this phone")
        c.vault.forEach {entry->
            Row(Modifier.fillMaxWidth().background(Panel,RoundedCornerShape(16.dp)).padding(start=14.dp,end=4.dp,top=6.dp,bottom=6.dp),verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(entry.optString("origin").removePrefix("https://").removePrefix("www."),color=Ink,fontWeight=FontWeight.Medium,maxLines=1)
                    Text(entry.optString("username"),color=Muted,fontSize=12.sp,maxLines=1)
                }
                TextButton(onClick={c.deleteVault(entry.optString("id"))}) {Text("Remove",color=Muted)}
            }
        }
        Text("Kept behind your phone's lock. Bots never see the password.",color=Muted,fontSize=12.sp,modifier=Modifier.padding(horizontal=4.dp))
    }
    Section("Good to know")
    Group {
        Text("• A site emailed you a sign-in link? Copy it, open that site's sign-in here, and tap “Got a sign-in link by email?”. It opens inside Alpha.\n" +
            "• A code, a CAPTCHA or a passkey? The bot stops and asks you to take over in the browser, then carries on.\n" +
            "• Signing out removes the sign-in from Alpha's browser only. Your account on the website stays.",
            color=Muted,fontSize=13.sp,modifier=Modifier.padding(14.dp))
    }
    if(c.signedIn.isNotEmpty()) TextButton(onClick={c.signOutEverywhere()}) {Text("Sign out of everything",color=Muted)}
}

// ---------------------------------------------------------------------------------------------------------------------------------------------
// Voice, notifications, privacy, about
// ---------------------------------------------------------------------------------------------------------------------------------------------

@Composable private fun VoicePage(c:Controller,pick:(String)->Unit,askKey:()->Unit) {
    val vc=c.config("voice_cloud");val provider=vc.optString("provider","built_in").ifBlank {"built_in"}
    val live=CloudVoice.active(vc)
    Group {
        if(provider=="built_in") {
            // Alpha's own voice. (Voices that need an outside account's key are not offered here; one already set up keeps working below.)
            Row2(Icons.Outlined.RecordVoiceOver,"Voice for calls","Alpha's own voice · free, works without the internet",more=false) {}
            Row2(Icons.Outlined.PlayArrow,"Hear it","Plays a short hello") {c.voice.preview()}
        } else {
            Row2(Icons.Outlined.RecordVoiceOver,"Voice for calls",(CloudVoice.providers[provider]?:"Built-in")+if(live!=null) " · "+CloudVoice.voiceName(vc) else "") {pick("voice_provider")}
            Row2(Icons.Outlined.Key,"Your "+(if(provider=="openai") "OpenAI" else "ElevenLabs")+" key",if(vc.optString("key").isBlank()) "Paste it to turn this voice on" else "Added · tap to replace") {askKey()}
            Row2(Icons.Outlined.GraphicEq,"Which voice",CloudVoice.voiceName(vc)) {pick("voice_pick")}
            if(live!=null) Row2(Icons.Outlined.PlayArrow,"Hear it","Plays a short hello") {c.voice.preview()}
        }
    }
}

@Composable private fun NotificationsPage(activity:MainActivity,c:Controller) {
    Group {
        Toggle("Quiet","Only tell me when you need me (an OK, a sign-in) or when something I asked for is done. No extras.",UiPrefs.quiet) {UiPrefs.setQuiet(c,it)}
        Text("Without Quiet, Alpha still sends at most about ${NoticeBudget.DAILY_CAP} extras a day, like a routine's report. Things that need you always come through.",
            color=Muted,fontSize=12.sp,modifier=Modifier.padding(horizontal=14.dp,vertical=4.dp))
    }
    Group {
        Row2(Icons.Outlined.Notifications,"Notification settings","Sounds, banners and lock-screen alerts") {activity.openNotificationSettings()}
        Row2(Icons.Outlined.BatteryChargingFull,"Keep working in other apps",
            if(activity.backgroundAllowed()) "Allowed: bots keep working, and text you when done, while you use other apps"
            else "Tap to allow, so Android doesn't slow bots down while you use other apps") {activity.allowBackground()}
        Row2(Icons.Outlined.NotificationsActive,"Important updates","Alerts for new leads, close appointments and decisions") {
            runCatching {c.enableImportantUpdates();activity.enableNotifications()}.onFailure {c.showNotice(plainError(it))}
        }
    }
}

@Composable private fun PrivacyPage(c:Controller,pick:(String)->Unit,ask:(Confirm)->Unit) {
    Group {
        Row2(Icons.Outlined.Forum,"Clear a bot's chat","Choose a bot") {pick("clear_bot")}
        Row2(Icons.Outlined.Groups,"Clear the team chat","Removes the group conversation") {
            ask(Confirm("Clear the team chat?","The group conversation is removed. Each bot's private chat stays.","Clear") {runCatching {c.clearChat(Team.ROOM)}.onFailure {c.showNotice(plainError(it))}})
        }
        Row2(Icons.Outlined.Psychology,"Forget a bot's memories","Choose a bot") {pick("forget")}
        Row2(Icons.Outlined.Public,"Clear browser data","Cookies and site sign-ins in Alpha's browser") {
            ask(Confirm("Clear browser data?","You'll need to sign in to websites again in Alpha's browser.","Clear") {c.signOutEverywhere()})
        }
        Row2(Icons.Outlined.DeleteSweep,"Clear all chats","Every bot and the team chat") {
            ask(Confirm("Clear all chats?","Every chat, including the team chat, is removed. Files, memories, skills and routines stay.","Clear all") {runCatching {c.clearChat("all")}.onFailure {c.showNotice(plainError(it))}})
        }
    }
}

@Composable private fun AboutPage(c:Controller) {
    Group {
        Row2(Icons.Outlined.Info,"Alpha Assistant "+BuildConfig.VERSION_NAME,"Runs on this phone · no bot server",more=false) {}
        Row2(Icons.Outlined.VerifiedUser,"Run self-check","Checks encrypted storage and files") {c.selfCheck()}
        Text("Animated emoji: Noto Emoji by Google, CC BY 4.0.",color=Muted,fontSize=11.sp,modifier=Modifier.padding(horizontal=14.dp,vertical=6.dp))
    }
}

/**
 * Which AI the bots think with: your ChatGPT plan (default), an OpenAI API key, or any other service that speaks the same language (OpenRouter, Groq, Gemini,
 * Claude, a model on this phone...). Takes effect on the next message.
 */
@Composable private fun ProviderPage(c:Controller) {
    val current=c.provider
    var kind by remember(current) {mutableStateOf(current.kind)}
    var url by remember(current) {mutableStateOf(current.baseUrl)}
    var key by remember(current) {mutableStateOf(current.apiKey)}
    var model by remember(current) {mutableStateOf(current.model)}
    var show by remember {mutableStateOf(false)}
    var problem by remember {mutableStateOf("")}
    fun mark(k:String)=if(kind==k) "Selected. " else ""
    Group {
        Row2(Icons.Outlined.AutoAwesome,"Your ChatGPT plan",mark(Provider.CHATGPT)+"Nothing to paste. Uses your plan's allowance.",more=false) {kind=Provider.CHATGPT;problem=""}
        Row2(Icons.Outlined.VpnKey,"OpenAI API key",mark(Provider.OPENAI_KEY)+"Pay as you go on your own OpenAI account.",more=false) {kind=Provider.OPENAI_KEY;problem=""}
        Row2(Icons.Outlined.Hub,"Another service",mark(Provider.COMPATIBLE)+"OpenRouter, Groq, Gemini, Claude, or a model on this phone.",more=false) {kind=Provider.COMPATIBLE;problem=""}
    }
    if(kind==Provider.COMPATIBLE) {
        Section("Pick one, or type its address")
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Provider.presets.forEach {p->
                Text(p.name,color=if(url.trim().trimEnd('/')==p.url) OnStrong else Ink,fontSize=13.sp,
                    modifier=Modifier.clip(RoundedCornerShape(999.dp)).background(if(url.trim().trimEnd('/')==p.url) Strong else Panel).clickable {url=p.url;problem=""}.padding(horizontal=14.dp,vertical=8.dp))
            }
        }
        OutlinedTextField(url,{url=it;problem=""},label={Text("Web address of the service")},placeholder={Text("https://openrouter.ai/api/v1")},singleLine=true,modifier=Modifier.fillMaxWidth())
    }
    if(kind!=Provider.CHATGPT) {
        OutlinedTextField(key,{key=it;problem=""},label={Text(if(kind==Provider.COMPATIBLE) "API key (leave empty for a model on this phone)" else "OpenAI API key")},singleLine=true,
            visualTransformation=if(show) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
            trailingIcon={TextButton(onClick={show=!show}) {Text(if(show) "Hide" else "Show",color=Muted,fontSize=12.sp)}},modifier=Modifier.fillMaxWidth())
        OutlinedTextField(model,{model=it;problem=""},label={Text("Model name")},placeholder={Text(if(kind==Provider.OPENAI_KEY) "for example gpt-4.1" else "as the service names it")},
            singleLine=true,modifier=Modifier.fillMaxWidth())
        Text(if(kind==Provider.OPENAI_KEY) "The key is kept encrypted on this phone and is only ever sent to OpenAI. Your messages and files go to OpenAI. Pick a model that can call tools."
            else "The key is kept encrypted on this phone and is only ever sent to the address above. Your messages and files go to that service. "+
            "Other services have no built-in web search: bots read web pages with their own browser instead. Pick a model that can call tools.",color=Muted,fontSize=12.sp)
    }
    if(problem.isNotBlank()) Text(problem,color=MaterialTheme.colorScheme.error,fontSize=13.sp)
    Button(onClick={
        problem=try {c.chooseProvider(if(kind==Provider.CHATGPT) Provider() else Provider(kind,url,key,model));""} catch(e:Exception) {plainError(e)}
    },colors=ButtonDefaults.buttonColors(containerColor=Strong,contentColor=OnStrong),modifier=Modifier.fillMaxWidth()) {
        Text(if(kind==Provider.CHATGPT) "Use my ChatGPT plan" else "Use this AI")
    }
}

@Composable private fun Section(title:String) {Text(title.uppercase(),fontSize=11.sp,letterSpacing=1.4.sp,color=Muted,modifier=Modifier.padding(start=4.dp,top=12.dp))}
@Composable private fun Group(content:@Composable ColumnScope.()->Unit) {
    Column(Modifier.fillMaxWidth().background(Panel,RoundedCornerShape(18.dp)).padding(vertical=4.dp),content=content)
}
@Composable private fun Row2(icon:ImageVector,title:String,value:String,more:Boolean=true,onClick:()->Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick=onClick).padding(horizontal=14.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically) {
        Icon(icon,null,tint=Ink,modifier=Modifier.size(20.dp));Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {Text(title,color=Ink,fontSize=15.sp);if(value.isNotBlank()) Text(value,color=Muted,fontSize=12.sp,maxLines=2)}
        if(more) Icon(Icons.Outlined.ChevronRight,null,tint=Muted,modifier=Modifier.size(18.dp))
    }
}
@Composable private fun Toggle(title:String,note:String,checked:Boolean,change:(Boolean)->Unit) {
    Row(Modifier.fillMaxWidth().clickable {change(!checked)}.padding(horizontal=14.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {Text(title,color=Ink,fontSize=15.sp);Text(note,color=Muted,fontSize=12.sp)}
        Switch(checked=checked,onCheckedChange=change,colors=SwitchDefaults.colors(checkedTrackColor=Strong,checkedThumbColor=OnStrong))
    }
}
@Composable internal fun Picker(title:String,choices:List<Choice>,selected:String,close:()->Unit,pick:(String)->Unit) {
    Dialog(onDismissRequest=close) {
        Surface(shape=RoundedCornerShape(24.dp),color=SheetBg) {
            Column(Modifier.padding(vertical=16.dp)) {
                Text(title,style=MaterialTheme.typography.titleLarge,color=Ink,modifier=Modifier.padding(horizontal=20.dp,vertical=6.dp))
                LazyColumn(Modifier.heightIn(max=520.dp)) {
                    var lastSection=""
                    choices.forEach {choice->
                        if(choice.section.isNotBlank() && choice.section!=lastSection) {
                            lastSection=choice.section
                            item {Text(choice.section.uppercase(),fontSize=10.sp,letterSpacing=1.2.sp,color=Muted,modifier=Modifier.padding(start=20.dp,top=12.dp,bottom=4.dp))}
                        }
                        item {
                            Row(Modifier.fillMaxWidth().clickable {pick(choice.key)}.padding(horizontal=12.dp,vertical=6.dp),verticalAlignment=Alignment.CenterVertically) {
                                RadioButton(selected=choice.key==selected,onClick={pick(choice.key)})
                                Column {Text(choice.label,color=Ink,fontSize=15.sp);if(choice.note.isNotBlank()) Text(choice.note,color=Muted,fontSize=12.sp)}
                            }
                        }
                    }
                }
                TextButton(onClick=close,modifier=Modifier.align(Alignment.End).padding(end=12.dp)) {Text("Cancel",color=Ink)}
            }
        }
    }
}

/** Backup: one passphrase-locked file with the bots, memories, skills and settings. Passwords and sign-ins are never put in it. */
@Composable private fun BackupPage(backup:BackupFlow) {
    Group {
        Row2(Icons.Outlined.Backup,"Save a backup",backup.lastBackupText()) {backup.startSave()}
        Row2(Icons.Outlined.Restore,"Restore from a backup","Pick a backup file and type its passphrase") {backup.startRestore()}
        backup.undoText()?.let {Row2(Icons.Outlined.History,"Undo the last restore",it) {backup.startUndo()}}
        Text("A backup keeps your bots, memories, skills and settings if this phone is lost or reset. Passwords and sign-ins are never put in it.",
            color=Muted,fontSize=12.sp,modifier=Modifier.padding(4.dp))
    }
}
