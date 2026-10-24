package com.alphainternational.assistant

import org.json.JSONArray
import org.json.JSONObject

/**
 * Which tools a job is given. Sending all of the app's ~80 tool descriptions on every round is slow and costly and gives the model too much to
 * choose from, so a job starts with the everyday tools ("core") and gets a group when the request is about it (matched here from the words of the
 * request and the bot's own set-up), or when the model asks with tools_enable. A group, once on, stays on for the whole job: the list only grows,
 * which also keeps the start of every request identical from round to round so the AI service can reuse it.
 * Nothing becomes unreachable: a tool that no group lists is an everyday tool, and any group can be switched on by the model at any time.
 * To move a tool out of the everyday set, list it in a group below (and nowhere else).
 */
object ToolGroups {
    /** One group: [tools] belong to it and to no other group; [words] switch it on from the request; [alsoOn] are groups it needs to be useful. */
    data class Group(val id:String,val summary:String,val tools:List<String>,val words:Regex?,val alsoOn:List<String> = emptyList())

    private fun w(pattern:String)=Regex(pattern,RegexOption.IGNORE_CASE)

    /** The groups, in the fixed order their tools are appended to a job's list. */
    val groups:List<Group> = listOf(
        Group("studio","making websites, web apps and tools (design, photos, icons, checking the page)",
            listOf("studio_start","studio_photos","photo_prepare","studio_icons","page_screenshot","section_replace","site_build_shop"),null,listOf("vercel")),
        Group("games","making games and playful interactive pages",listOf("game_template","game_library"),
            w("\\bgames?\\b|arcade|puzzle|trivia|\\bsnake\\b|tetris|\\bpong\\b|flappy|wordle|crossword|minigame|playable|\\bquiz game\\b"),listOf("studio")),
        Group("media","videos, music, sounds and video captions",
            listOf("media_probe","media_job","video_captions","video_contact_sheet","media_save","audio_guide","audio_job","audio_code"),
            w("\\bvideos?\\b|\\bmp4\\b|\\bmov\\b|\\breels?\\b|\\bclips?\\b|tik ?tok|youtube|\\bshorts\\b|slideshow|footage|captions?|subtitles?|transcri(?:be|pt|ption)|\\bmusic\\b|\\bsongs?\\b|jingle|ringtone|" +
                "sounds? (?:effects?|files?|clips?|tracks?)|\\baudio\\b|drum beat|\\bbeat (?:for|loop)|melody|soundtrack|\\bwav\\b|\\bmp3\\b|\\bm4a\\b|voice ?over|podcast|hold music|\\bedit (?:the|my|this) (?:video|clip|reel)"),listOf("shell")),
        Group("shell","the phone's command line (ffmpeg, python, converting and batch-processing files)",listOf("computer_status","run_shell","install_package"),
            w("\\bffmpeg\\b|command line|terminal|\\bshell\\b|termux|\\bpython\\b|\\bscripts?\\b|imagemagick|\\bsqlite\\b|\\bgit\\b|\\bunzip\\b|\\bpip\\b|\\bnpm\\b|convert (?:the|a|my|all|these|this) |\\bcompress\\b|resize (?:all |the )?(?:images?|photos?|pictures?)|batch process")),
        Group("vercel","putting a finished website online, and Vercel projects and deployments",
            listOf("site_publish","vercel_projects","vercel_deployments","vercel_deployment","vercel_logs","vercel_preview"),
            w("\\bvercel\\b|\\bdeploy|put (?:it|the site|my site|the website|this|that) (?:online|live)|go live|make (?:it|the site|the website) live|host (?:the|my|this) (?:site|website|page|app)|launch (?:the |my )?(?:site|website)|\\bdomain\\b|publish (?:the|my|this|that|it)\\b")),
        Group("telnyx","the Telnyx phone numbers: who called, texts and caller names (read only)",listOf("telnyx_calls","telnyx_messages","telnyx_lookup","telnyx_numbers","telnyx_ai_calls"),
            w("telnyx|who (?:called|is calling|rang)|missed calls?|call (?:log|history|records?)|(?:incoming|inbound|outbound) calls?|caller|calls? (?:from|to) (?:my|the) (?:shop|business|number)|who is (?:this|that) number|phone calls?")),
        Group("google_write","saving email drafts, sending email, adding calendar events, making Google Docs, Sheets, Drive files and tasks",
            listOf("gmail_draft","gmail_send","calendar_create","drive_create_text","docs_create","sheets_create","sheets_append","tasks_create"),
            w("e-?mail|gmail|inbox|\\bdrafts?\\b|\\breply\\b|forward|calendar|\\bevents?\\b|appointments?|\\bmeetings?\\b|\\bschedule|\\bbook (?:a|an|the|me)\\b|\\binvite|\\bdrive\\b|google docs?|\\bdocs?\\b|\\bsheets?\\b|spreadsheet|\\btasks?\\b|to-?do|reminder|follow[- ]?up|\\bmail\\b")),
        Group("apps","connected apps from Marketplace (Zapier, Notion, Stripe, PayPal, Trello and others)",listOf("mcp_list","mcp_call"),
            w("zapier|notion|stripe|paypal|trello|airtable|jira|todoist|slack|asana|hubspot|quickbooks|xero|shopify|mailchimp|zoho|\\bmake\\.com|integrations?|connected apps?|\\boutlook\\b|\\bteams\\b|connector")),
        Group("documents","PDF documents, invoices and estimates, charts and drawings",listOf("document_pdf","invoice_pdf","supabase_sample_invoice","chart_make","picture_draw","docx_create","xlsx_create","pptx_create","deep_research","research_check"),
            w("\\bpdf\\b|\\bword (?:doc|file|document)|\\bexcel\\b|powerpoint|\\.docx|\\.xlsx|\\.pptx|slide ?deck|\\bslides?\\b|deep research|research (?:report|paper)|thorough(?:ly)? research|documents?|invoices?|estimates?|\\bquotes?\\b|\\bletter\\b|flyers?|brochure|price ?list|checklist|handout|certificate|receipt|proposal|contract|r[eé]sum[eé]|\\bcv\\b|\\bprint|\\bcharts?\\b|\\bgraphs?\\b|\\bpie\\b|\\blogo\\b|\\bdraw|sketch|diagram|floor ?plan|illustration|poster|\\blabel\\b|infographic")),
        Group("browser","the on-screen web browser: signing in, forms, clicking, and anything web_fetch can't do",
            listOf("browser_open","browser_read","browser_search","browser_scroll","browser_click","browser_fill","browser_press_enter","browser_back","browser_signin","social_prepare","browser_look"),
            w("log ?in|sign ?in|\\blogin\\b|password|captcha|2fa|passkey|browse|browser|\\bclick\\b|fill (?:in|out)|\\bforms?\\b|checkout|order (?:it|them|this|that|online)|add to cart|\\breserve|book (?:a|an|the) (?:table|flight|room|ticket|hotel)|\\bpost (?:on|to|it)\\b|facebook|instagram|linkedin|twitter|\\bx\\.com|craigslist|signed in")),
        Group("phone","using the owner's other apps on this phone (open any app, read the screen, tap, type, scroll)",
            listOf("apps_list","screen_read","screen_do","screen_look","screen_confirm"),
            // (Every \\b below is a regex word edge. A single \b in a Kotlin string is a backspace character, which made most of these never match.)
            w("\\b(?:on|in|from|using|through|via|into) (?:my|the|this) (?:phone|iphone|android|screen)\\b|\\bmy (?:phone|screen)\\b|\\b(?:open|launch|start|use|check|go into|go to|in) (?:the |my )?[a-z0-9 ]{0,24}\\bapp\\b|\\bscreen ?(?:shot|read)|\\btap (?:on|the)\\b|\\bswipe\\b|" +
                "\\b(?:calculator|camera|clock|alarm app|settings app|notes app|samsung notes|contacts app|phone app|files app|my files|messages app|chrome app|google chrome|play store)\\b.{0,40}\\b(?:open|tap|press|use|turn|set|check|find|write|type|read)\\b|\\b(?:open|launch|start|use|tap|press) (?:the |my )?(?:calculator|camera|clock|settings|notes|samsung notes|contacts|chrome|google chrome|gallery|photos|files|my files|messages|maps|calendar)\\b|" +
                "whats ?app|instagram|facebook|messenger|tiktok|snapchat|telegram|twitter|\\buber\\b|\\blyft\\b|doordash|grubhub|instacart|venmo|cash ?app|zelle|spotify|netflix|google (?:maps|photos|keep|wallet|pay)|play store|gallery|\\bdialer\\b|reddit|linkedin|pinterest|discord|offerup|marketplace")),
        Group("outreach","recording and measuring outreach campaigns: every message sent, replies, which version works (campaign_sent, campaign_outcome, campaign_report)",
            listOf("campaign_sent","campaign_outcome","campaign_report"),
            w("outreach|cold (?:e-?mail|call|message)|campaigns?|prospects?|\\bleads?\\b|reach out|follow[- ]?ups?|(?:send|contact|email|message|find)\\s+(?:another |a |the |\\d+ )?(?:more )?\\d*\\s*(?:e-?mails?|messages?|compan(?:y|ies)|businesses|customers)\\b|\\bfleets?\\b"),listOf("phone")),
        Group("automation","routines and schedules, saved skills, your own tools, and making or changing bots",
            listOf("routine_create","routine_list","routine_delete","skill_save","skill_delete","tool_create","tool_list","tool_run","bot_create","bot_update","trigger_create","trigger_list","trigger_delete"),
            w("routines?|\\bwhen\\b.{0,50}\\b(?:emails?|texts?|calls?|messages?|arrives?|changes?|updates?)\\b|\\btriggers?\\b|tell me if|let me know (?:if|when)|every (?:day|morning|night|evening|week|month|hour|monday|tuesday|wednesday|thursday|friday|saturday|sunday|\\d+)|\\bdaily\\b|\\bweekly\\b|\\bmonthly\\b|each (?:day|morning|week|night)|recurring|automat|" +
                "schedule (?:a|this|it|the)|remind me|from now on|always (?:sign|use|text|send|reply|write|say|call|cc|include)|never (?:send|text|email|reply|contact|call|use|answer)|standing rules?|new bot|" +
                "(?:create|make|build|add) (?:me )?(?:a |another )?bot|another bot|\\bskills?\\b|teach (?:you|alpha|the bot|this bot)|remember how|custom tool|make (?:a|me a) tool|keep an eye|watch for|monitor|notify me|check back"))
    )
    val ids:List<String> get()=groups.map {it.id}
    private val byTool:Map<String,String> by lazy {groups.flatMap {g->g.tools.map {it to g.id}}.toMap()}
    fun find(id:String):Group?=groups.firstOrNull {it.id==id}

    /** The group a tool is in, or "core" (the everyday tools). */
    fun groupOf(tool:String):String=byTool[tool]?:"core"

    /** What a job knows about itself and its bot that decides the groups (all optional, all plain data so the rules are testable). */
    data class Kit(
        /** The bot already has a website in its files. */
        val hasSite:Boolean=false,
        /** Names and ids of the connected Marketplace apps. */
        val appNames:List<String> = emptyList(),
        /** Sites the owner signed in to in Alpha's browser (a request that names one needs the browser). */
        val signedIn:List<String> = emptyList(),
        /** The bot's own sign-ins ("web:https://…" or a service id), skills and tools. */
        val botApps:List<String> = emptyList(),val botSkills:List<String> = emptyList(),val botTools:List<String> = emptyList(),
        /** Text of the saved recipe chosen for this request (the tool names in it switch their groups on). */
        val recipe:String="",
        /** Tools already used earlier in this job (after a restart the groups are rebuilt from the saved history). */
        val used:Set<String> = emptySet(),
        /** The bot's last text, for a short reply like "yes do it" (the groups its question was about). */
        val before:String="")

    /** The groups for a request, in the fixed order. [Kit.before] counts only for a short reply ("yes, do it") to what the bot just said. */
    fun select(prompt:String,kit:Kit=Kit()):List<String> {
        val on=LinkedHashSet<String>()
        val text=prompt.take(4000)
        val lookAt=if(text.length<=80 && kit.before.isNotBlank()) text+"\n"+kit.before.take(600) else text
        groups.forEach {g->if(g.words!=null && g.words.containsMatchIn(lookAt)) on+=g.id}
        if(Studio.wants(lookAt,kit.hasSite)) on+="studio"
        if(kit.appNames.any {n->n.length>=3 && lookAt.contains(n,true)}) on+="apps"
        if(kit.signedIn.any {s->val host=s.removePrefix("https://").removePrefix("http://").removePrefix("www.").substringBefore('/').substringBeforeLast('.');host.length>=4 && lookAt.contains(host,true)}) on+="browser"
        if(kit.botApps.any {it.startsWith("web:")}) on+="browser"
        if(kit.botTools.isNotEmpty()) on+="automation"
        // A saved recipe (and the tools already used in this job) bring the groups of every tool they name.
        if(kit.recipe.isNotBlank()) toolNames(kit.recipe).forEach {n->groupOf(n).takeIf {it!="core"}?.let {on+=it}}
        kit.used.forEach {n->groupOf(n).takeIf {it!="core"}?.let {on+=it}}
        return close(on)
    }

    /** Adds the groups a group needs (games need the page checks of studio, video work often wants the command line), in the fixed order. */
    fun close(on:Collection<String>):List<String> {
        val all=LinkedHashSet(on)
        var changed=true
        while(changed) {changed=false;all.toList().forEach {id->find(id)?.alsoOn?.forEach {if(all.add(it)) changed=true}}}
        return groups.map {it.id}.filter {it in all}
    }

    /** Tool names mentioned in some text, such as the steps of a saved recipe. */
    fun toolNames(text:String):Set<String> {
        val known=byTool.keys
        return Regex("[a-z][a-z0-9]*(?:_[a-z0-9]+)+").findAll(text).map {it.value}.filter {it in known}.toSet()
    }

    /** The tool names a job has used so far, read back from its saved history (so a resumed job gets the groups it had). */
    fun usedIn(history:JSONArray):Set<String> = (0 until history.length()).mapNotNull {history.optJSONObject(it)}
        .filter {it.optString("type")=="function_call"}.map {it.optString("name").substringAfterLast('.')}.toSet()

    /** [all] ordered the way a job sends it: everyday tools first (in their usual order), then each switched-on group in the fixed order. */
    fun visible(all:List<Tool>,on:Collection<String>):List<Tool> {
        val order=groups.map {it.id}
        return all.withIndex().filter {entry->val g=groupOf(entry.value.name);g=="core" || g in on}
            .sortedWith(compareBy({entry->val g=groupOf(entry.value.name);if(g=="core") -1 else order.indexOf(g)},{entry->entry.index})).map {it.value}
    }

    /** The current list plus the tools of newly switched-on groups at the end. The start of the list never changes, so the request's start stays the same. */
    fun append(current:List<Tool>,all:List<Tool>,newGroups:Collection<String>):List<Tool> {
        val have=current.map {it.name}.toSet()
        return current+visible(all,newGroups).filter {groupOf(it.name) in newGroups && it.name !in have}
    }

    /** The answer to tools_enable: what was switched on, in plain words for the model. */
    fun enabledResult(group:Group,names:List<String>):JSONObject = obj("result" to "Switched on ${group.id}: "+group.summary+". You can use these from your next step: "+names.joinToString()+".",
        "group" to group.id,"tools" to arr(names))

    /** The tools_enable tool itself: always available, and its description is the model's map of what can be switched on. */
    fun enableTool():Tool = Tool("tools_enable","Switch on more tools for this job. You start with the everyday tools; the others come on by themselves when the request is about them, " +
        "and if you need one that isn't there, switch its group on with this and carry on. They stay on for the rest of the job. Groups: "+
        groups.joinToString("; ") {it.id+" = "+it.summary+" ("+it.tools.joinToString()+")"}+".",
        obj("type" to "object","properties" to obj("group" to obj("type" to "string","enum" to arr(groups.map {it.id}))),"required" to arr(listOf("group")),"additionalProperties" to false))
}
