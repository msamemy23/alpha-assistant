package com.alphainternational.assistant

import org.json.JSONArray
import org.json.JSONObject

/**
 * The finish gate: what the phone checks before a job is allowed to say it is done.
 *  1. Receipts: what really happened, in plain words, built from the tool results (not from what the model says). Shown under the answer.
 *  2. Claim audit: if the answer says something was sent, saved, booked or deleted that no tool result shows, the model gets one chance to fix it.
 *  3. Read-back of a saved email draft: the phone reads it back from Gmail and compares it, instead of trusting the save.
 *  4. A cheap second opinion (the critic) after real work: does the answer do what was asked and match the evidence?
 * Everything here is pure (text and JSON in, text out) so it is tested without a phone.
 */
object Verify {
    /** How many extra rounds the gate may add to one job (a claim fix, an open-step nudge, a critic fix), in total. */
    const val MAX_REPAIRS=2

    /** Added to the answer of a job that ran out of time. */
    const val CUT_SHORT_LINE="I ran out of time before I could finish, so this is what I have so far. Tap Keep going and I'll pick up where I left off."
    /** Added to an answer that still claims something no result shows, after the model was already asked once. */
    const val AUTO_CONTINUE_LINE="I ran out of time for this round, so this is where things stand. I'm carrying on by myself; you don't need to tap anything."
    const val UNCONFIRMED_LINE="I can't confirm everything above was actually done. Please check before you rely on it."

    // ------------------------------------------------------------------------------------------------------------------------------
    // The job's calls and results
    // ------------------------------------------------------------------------------------------------------------------------------
    data class Call(val name:String,val args:JSONObject,val result:JSONObject?,val ok:Boolean)

    /**
     * The tools whose failure still stands at the end: a call that failed and was not followed by a successful call of the same tool. (Live phone, 2026-10-12: a code run that failed on a
     * typo and then ran fine was reported to the second opinion as "running the code failed"; it failed a correct answer and the repair made the bot narrate its own mistake.)
     */
    fun lastingFailures(cs:List<Call>):List<String> =
        cs.withIndex().filter {(i,c)->!c.ok && cs.drop(i+1).none {later->later.name==c.name && later.ok}}.map {it.value.name.replace('_',' ')}.distinct()

    /** What the phone screen showed at the end of the last phone-control calls. The critic is told what the bot could see, or it fails right answers about a screen ("the record does not show the weather"). */
    fun phoneEvidence(cs:List<Call>):List<String> {
        val shown=cs.filter {it.ok && it.name in setOf("screen_do","screen_confirm","screen_read")}.takeLast(3)
        // The newest screen is the one an answer is usually about, so it is shown nearly whole (a camera's mode buttons sit far down its list; a short cut once made the review
        // fail a right answer and the bot "fixed" it into a wrong one). Earlier screens only need their top.
        return shown.mapIndexed {index,c->
            val newest=index==shown.lastIndex
            val reads=c.result?.optJSONArray("reads")?.let {a->(0 until a.length()).mapNotNull {a.optJSONObject(it)}.joinToString(" | ") {it.optString("app")+": "+it.optString("screen").take(if(newest) 2600 else 900)}}.orEmpty()
            val done=c.result?.optJSONArray("steps")?.let {a->(0 until a.length()).mapNotNull {a.optJSONObject(it)}.filter {it.optBoolean("ok")}.joinToString("; ") {it.optString("note").take(70)}}.orEmpty()
            (if(done.isNotBlank()) "Phone steps done: "+done+" | " else "")+(if(reads.isNotBlank()) "Screens read: "+reads+" | " else "")+"The phone screen at the end ("+c.result?.optString("app").orEmpty()+"): "+c.result?.optString("screen").orEmpty().take(if(newest) 3600 else 900)
        }
    }

    /** Every finished tool call of a job (the bookkeeping tools left out), with its details and result. */
    fun calls(history:JSONArray):List<Call> {
        val items=history.objects()
        val byId=items.filter {it.optString("type")=="function_call"}.associateBy {it.optString("call_id")}
        return items.filter {it.optString("type")=="function_call_output"}.mapNotNull {out->
            val call=byId[out.optString("call_id")]?:return@mapNotNull null
            val name=call.optString("name").substringAfterLast('.')
            if(name in TaskProtocol.controlTools) return@mapNotNull null
            val args=runCatching {JSONObject(call.optString("arguments"))}.getOrDefault(JSONObject())
            val result=runCatching {JSONObject(out.optString("output"))}.getOrNull()
            if(result?.optBoolean("slip")==true) return@mapNotNull null
            Call(name,args,result,TaskProtocol.successful(result))
        }
    }

    /** Tools that put something somewhere outside the chat or on the phone (the ones a receipt or a claim can be about). */
    private val writeTools=setOf("gmail_draft","gmail_send","sms_send","calendar_create","drive_create_text","docs_create","sheets_create","sheets_append","tasks_create",
        "invoice_pdf","document_pdf","docx_create","xlsx_create","pptx_create","chart_make","picture_draw","media_job","audio_job","audio_code","video_captions","media_save","studio_start","game_template","site_build_shop","site_publish","vercel_preview",
        "file_write","file_append","file_edit","section_replace","routine_create","routine_delete","skill_save","skill_delete","bot_update","bot_create","tool_create","memory_save",
        "supabase_update","supabase_sample_invoice","notify_important","photo_prepare")
    private val readWords=Regex("^(list|get|search|read|find|fetch|query|describe|show|view|lookup|retrieve|check|count|browse)")

    /** A call to a connected app or the database that changes something (not a look). */
    fun changesSomething(c:Call):Boolean = when(c.name) {
        "mcp_call"->!readWords.containsMatchIn(c.args.optString("tool").lowercase())
        "supabase_management_call"->!(runCatching {SupabaseRules.readOnly(c.args.optString("tool"),JSONObject(c.args.optString("arguments_json","{}").ifBlank {"{}"}))}.getOrDefault(false))
        else->false
    }
    private fun actsOnWebsite(c:Call)=c.name in setOf("browser_click","browser_press_enter","browser_fill") || (c.name in setOf("screen_do","screen_confirm") && c.result?.optBoolean("acted")==true)

    /** The calls that changed something and worked: what a claim of "done" can rest on. (Code that only worked out numbers is not a change, unless it saved a file.) */
    fun effects(calls:List<Call>):List<Call> = calls.filter {it.ok && (it.name in writeTools || changesSomething(it) || actsOnWebsite(it) ||
        (it.name=="run_code" && (it.result?.optJSONArray("files")?.length()?:0)>0))}

    /** Small local writes that don't call for a second opinion by themselves (a job with only these needs 4 or more tool calls to be reviewed). */
    private val light=setOf("file_write","file_append","file_edit","section_replace","memory_save","notify_important","photo_prepare","chart_make","picture_draw","media_save","video_captions","studio_start","game_template","site_build_shop","run_code")

    /** How many things this job really changed that are worth a second look: sends, saves to accounts, documents, videos, routines, the database, websites. */
    fun changes(history:JSONArray):Int = changes(calls(history))
    fun changes(cs:List<Call>):Int = effects(cs).count {it.name !in light}

    /** How many real tool calls a job made (the bookkeeping and chat tools not counted). */
    fun workCalls(history:JSONArray):Int = (0 until history.length()).count {i->
        val item=history.optJSONObject(i)
        item!=null && item.optString("type")=="function_call" && item.optString("name").substringAfterLast('.').let {it !in TaskProtocol.controlTools && it !in chatTools}
    }

    // ------------------------------------------------------------------------------------------------------------------------------
    // 1. Receipts
    // ------------------------------------------------------------------------------------------------------------------------------
    private fun plural(n:Int,one:String,many:String)=if(n==1) "1 $one" else "$n $many"
    private fun names(list:List<String>):String {
        val clean=list.map {it.trim()}.filter {it.isNotBlank()}.distinct()
        return when {clean.isEmpty()->"";clean.size<=2->clean.joinToString(" and ");else->clean.take(2).joinToString(", ")+" and "+(clean.size-2)+" more"}
    }
    private fun first(c:Call,vararg keys:String)=keys.firstNotNullOfOrNull {k->(c.result?.optString(k)?:"").takeIf {it.isNotBlank()}}.orEmpty()
    private fun madeFile(c:Call)=c.result?.optJSONArray("files")?.strings()?.firstOrNull().orEmpty().ifBlank {c.result?.optString("name").orEmpty()}

    /** The failed jobs worth a line: a tool of this kind failed and never worked afterwards. */
    private fun neverWorked(calls:List<Call>,vararg tools:String):Int = calls.filter {it.name in tools && !it.ok}.count {f->calls.none {it.ok && it.name==f.name}}

    /** What the job really did, as short plain lines ("3 email drafts saved · 0 sent"). Only things that changed or tried to change something. */
    fun receiptLines(history:JSONArray):List<String> = receiptLines(calls(history))
    /** The same, from calls already read ([calls] parses every result, so a caller that needs several answers reads them once). */
    fun receiptLines(cs:List<Call>):List<String> {
        val out=ArrayList<String>()
        fun ok(vararg tools:String)=cs.filter {it.ok && it.name in tools}

        val drafts=ok("gmail_draft");val sent=ok("gmail_send");val texts=ok("sms_send")
        if(drafts.isNotEmpty()) {
            val checked=drafts.count {it.result?.optJSONObject("read_back")?.optBoolean("matches")==true}
            out+=plural(drafts.size,"email draft","email drafts")+" saved"+(if(checked==drafts.size) " and checked" else "")+(if(sent.isEmpty()) " · 0 sent" else "")
        }
        if(sent.isNotEmpty()) out+=plural(sent.size,"email","emails")+" sent"+names(sent.map {it.args.optString("to")}).let {if(it.isBlank()) "" else " to $it"}
        if(texts.isNotEmpty()) out+=plural(texts.size,"text","texts")+" sent"+names(texts.map {it.args.optString("to")}).let {if(it.isBlank()) "" else " to $it"}
        ok("phone_action").map {it.args.optString("action")}.distinct().forEach {action->
            when(action) {
                "text"->{out+="Opened Messages with the text ready · not sent"}
                "email"->{out+="Opened your email app with the message ready · not sent"}
                "call"->{out+="Opened the dialer · no call made"}
            }
        }
        val events=ok("calendar_create")
        if(events.size==1) out+="Added “"+events[0].args.optString("title").take(50)+"” to the calendar" else if(events.size>1) out+=plural(events.size,"event","events")+" added to the calendar"
        ok("docs_create").forEach {out+="Google Doc made: "+it.args.optString("title").take(50)}
        ok("sheets_create").forEach {out+="Google Sheet made: "+it.args.optString("title").take(50)}
        ok("sheets_append").takeIf {it.isNotEmpty()}?.let {out+="Rows added to a Google Sheet"}
        ok("drive_create_text").forEach {out+="File uploaded to Google Drive: "+it.args.optString("name").take(50)}
        ok("tasks_create").takeIf {it.isNotEmpty()}?.let {out+=plural(it.size,"Google task","Google tasks")+" added"}

        ok("invoice_pdf").forEach {c->
            val kind=if(c.args.optString("document_type").equals("estimate",true)) "estimate" else "invoice"
            val label=if(c.args.optBoolean("sample")) "Sample $kind" else kind.replaceFirstChar {it.uppercase()}
            out+=(label+" "+first(c,"number")).trim()+" made (PDF) · not sent"
        }
        ok("document_pdf").forEach {out+="PDF made: "+madeFile(it).ifBlank {it.args.optString("file_name")}+" · not sent"}
        ok("docx_create").forEach {out+="Word file made: "+madeFile(it).ifBlank {it.args.optString("file_name")}+" · not sent"}
        ok("xlsx_create").forEach {out+="Excel file made: "+madeFile(it).ifBlank {it.args.optString("file_name")}+" · not sent"}
        ok("pptx_create").forEach {out+="PowerPoint file made: "+madeFile(it).ifBlank {it.args.optString("file_name")}+" · not sent"}
        ok("media_job").forEach {out+="Video made: "+madeFile(it)}
        ok("audio_job","audio_code").forEach {out+="Sound made: "+madeFile(it)}
        ok("video_captions").forEach {out+="Captions written"+(it.result?.optInt("words",0)?.takeIf {w->w>0}?.let {w->" ($w words)"}.orEmpty())}
        ok("media_save").takeIf {it.isNotEmpty()}?.let {out+="Saved to the phone's Gallery"}
        ok("chart_make","picture_draw").forEach {out+="Picture made: "+madeFile(it)}
        ok("game_template").forEach {out+="Game made: "+first(it,"page")}
        ok("studio_start").takeIf {it.isNotEmpty()}?.let {out+="Website or app started"}
        ok("site_build_shop").takeIf {it.isNotEmpty()}?.let {out+="Shop website built · not online yet"}
        ok("site_publish").forEach {out+="Website published"+first(it,"url","address","live_url").let {u->if(u.isBlank()) "" else ": $u"}}
        ok("vercel_preview").takeIf {it.isNotEmpty()}?.let {out+="Preview deployment started"}

        val files=ok("file_write","file_append","file_edit","section_replace").map {it.args.optString("name")}.filter {it.isNotBlank()}.distinct()
        if(files.size==1) out+="File saved: "+files[0] else if(files.size>1) out+=plural(files.size,"file","files")+" saved or changed: "+files.take(3).joinToString()+(if(files.size>3) "…" else "")
        ok("routine_create").forEach {out+="Routine saved"+first(it,"schedule").let {s->if(s.isBlank()) "" else ": $s"}}
        ok("routine_delete").takeIf {it.isNotEmpty()}?.let {out+="Routine removed"}
        ok("skill_save").forEach {out+="Skill saved: "+first(it,"title")}
        ok("bot_update").takeIf {it.isNotEmpty()}?.let {out+="Standing rules updated"}
        ok("bot_create").forEach {out+="Bot created: "+first(it,"bot")}
        ok("tool_create").forEach {out+="Tool saved: "+first(it,"id")}
        ok("supabase_update").takeIf {it.isNotEmpty()}?.let {out+=plural(it.size,"record","records")+" updated"}
        ok("supabase_sample_invoice").takeIf {it.isNotEmpty()}?.let {out+="Sample invoice saved in the database"}
        cs.filter {it.ok && it.name=="supabase_management_call" && changesSomething(it)}.takeIf {it.isNotEmpty()}?.let {out+="Changed data in the database"}
        cs.filter {it.ok && it.name=="mcp_call" && changesSomething(it)}.forEach {out+="Made a change in "+it.args.optString("server").ifBlank {"a connected app"}}
        ok("browser_click","browser_press_enter","browser_fill").size.takeIf {it>0}?.let {out+=plural(it,"action","actions")+" taken on websites"}
        ok("notify_important").filter {it.result?.optBoolean("notified")==true}.takeIf {it.isNotEmpty()}?.let {out+="Important update saved in Chat"}

        // What was tried and did not work (and never worked later).
        neverWorked(cs,"sms_send").takeIf {it>0}?.let {out+=plural(it,"text","texts")+" did not go through"}
        neverWorked(cs,"gmail_send").takeIf {it>0}?.let {out+=plural(it,"email","emails")+" did not go through"}
        neverWorked(cs,"gmail_draft").takeIf {it>0}?.let {out+="An email draft could not be saved"}
        neverWorked(cs,"calendar_create").takeIf {it>0}?.let {out+="A calendar event could not be added"}
        neverWorked(cs,"invoice_pdf","document_pdf").takeIf {it>0}?.let {out+="A document could not be made"}
        neverWorked(cs,"media_job","audio_job","audio_code").takeIf {it>0}?.let {out+="A video or sound could not be made"}
        neverWorked(cs,"site_publish").takeIf {it>0}?.let {out+="The website could not be put online"}
        neverWorked(cs,"docs_create","sheets_create","drive_create_text","tasks_create","sheets_append").takeIf {it>0}?.let {out+="Something could not be saved to Google"}
        return out.map {it.trim()}.filter {it.isNotBlank()}.take(8)
    }

    /** The lines as the JSON list stored in the answer's receipt (empty text when there is nothing to show). */
    fun receiptJson(lines:List<String>):String=if(lines.isEmpty()) "" else arr(lines).toString()
    // ------------------------------------------------------------------------------------------------------------------------------
    // 2. Claim audit
    // ------------------------------------------------------------------------------------------------------------------------------
    enum class Kind(val word:String) {EMAIL("emailed"),TEXT("texted"),SENT("sent"),DRAFT("saved as an email draft"),SAVED("saved"),MADE("created"),BOOKED("booked"),UPDATED("changed"),DELETED("deleted"),PUBLISHED("published"),PAID("paid for")}
    data class Claim(val kind:Kind,val snippet:String)

    private fun supports(kind:Kind,calls:List<Call>):Boolean {
        val done=effects(calls)
        fun any(vararg tools:String)=done.any {it.name in tools}
        val outside=done.any {changesSomething(it) || actsOnWebsite(it)}
        return when(kind) {
            Kind.EMAIL->any("gmail_send") || outside
            Kind.TEXT->any("sms_send") || outside
            Kind.SENT->any("gmail_send","sms_send") || outside
            Kind.DRAFT->any("gmail_draft") || outside
            Kind.SAVED,Kind.MADE,Kind.UPDATED->done.isNotEmpty()
            Kind.BOOKED->any("calendar_create","routine_create","tasks_create") || outside
            Kind.DELETED->any("routine_delete","skill_delete") || outside
            Kind.PUBLISHED->any("site_publish","vercel_preview") || outside
            Kind.PAID->outside
        }
    }

    // A claim needs someone doing it ("I sent", "I've just saved") or, for sending, saving and making, something done to a thing ("was sent", "is saved"), or a one-word report ("Sent!", "✅ Saved").
    // Words like "booked", "paid" or "updated" are not claims when they only describe a state ("your appointment is booked for 3 pm"): those need "I" or "we".
    private const val ADV="(?:just |already |now |successfully |also |both |all |finally |then |quickly |gone ahead and )*"
    private const val WHO="\\b(?:i|we|alpha)(?:'ve| have| had)? $ADV"
    private const val DONE="\\b(?:was|were|been|got|now|just|already|successfully|all) $ADV"
    private const val LEAD="^\\W*(?:done[ ,:\\-—]+)?"
    private const val LEAD_END="(?=\\s*(?:[.!,:;—\\-]|$|to\\b|in\\b|as\\b|for\\b|at\\b|on\\b|from\\b|via\\b|successfully))"
    private fun active(verbs:String)=Regex("(?:$WHO|$LEAD)(?:$verbs)\\b",RegexOption.IGNORE_CASE)
    private fun passive(verbs:String)=Regex("(?:$WHO|$DONE)(?:$verbs)\\b|$LEAD(?:$verbs)\\b$LEAD_END",RegexOption.IGNORE_CASE)
    private val emailWords=Regex("\\b(e-?mails?|gmail|mail)\\b",RegexOption.IGNORE_CASE)
    private val textWords=Regex("\\b(texts?|sms|text messages?|messages?|imessage)\\b",RegexOption.IGNORE_CASE)
    private val sentClaim=passive("sent|emailed|e-mailed|texted|messaged|mailed|forwarded|replied")
    private val savedClaim=passive("saved|stored|uploaded")
    private val madeClaim=passive("created|made|built|generated|produced|set up")
    private val draftWords=Regex("\\bdrafts?\\b",RegexOption.IGNORE_CASE)
    private val draftPlace=Regex("\\b(gmail|your drafts|drafts folder|in your email|email account)\\b",RegexOption.IGNORE_CASE)
    private val bookedClaim=active("booked|scheduled|reserved")
    private val calendarClaim=Regex("\\b(?:i|we|alpha)(?:'ve| have)? $ADV(?:added|put|placed|set)\\b[^.!?\\n]{0,60}\\b(?:calendar|reminder|appointment)\\b",RegexOption.IGNORE_CASE)
    private val updatedClaim=active("updated|changed|edited|modified|renamed|replaced|fixed")
    private val deletedClaim=active("deleted|removed|cancel+ed|erased|cleared")
    private val publishedClaim=active("published|posted|deployed|went live")
    private val onlineClaim=Regex("\\b(?:i|we|alpha)(?:'ve| have)? $ADV(?:put|made|set)\\b[^.!?\\n]{0,40}\\b(?:online|live)\\b",RegexOption.IGNORE_CASE)
    private val paidClaim=active("paid|purchased|bought|ordered|charged|refunded|transferred")
    private val madeObjects=Regex("\\b(files?|documents?|docs?|pdfs?|invoices?|estimates?|quotes?|spreadsheets?|sheets?|videos?|sounds?|songs?|jingles?|charts?|pictures?|images?|logos?|drawings?|pages?|websites?|sites?|apps?|games?|events?|reminders?|routines?|skills?|bots?|tools?|folders?|flyers?)\\b",RegexOption.IGNORE_CASE)
    private val changedObjects=Regex("\\b(files?|pages?|sites?|websites?|records?|databases?|tables?|rows?|sheets?|spreadsheets?|docs?|documents?|invoices?|estimates?|appointments?|events?|settings?|rules|calendar|contacts?|customers?|listings?|profiles?|bookings?|reservations?|orders?)\\b",RegexOption.IGNORE_CASE)
    private val deletedObjects=Regex("\\b(files?|pages?|records?|events?|appointments?|routines?|skills?|rows?|e-?mails?|messages?|drafts?|accounts?|bookings?|reservations?|orders?|subscriptions?|entry|entries|photos?|posts?)\\b",RegexOption.IGNORE_CASE)
    // Sentences that only offer, ask, deny, look ahead or look back are not claims.
    private val skipSentence=Regex("n't\\b|\\b(not|never|nothing|none|no one|nobody|without|unable|failed|cannot|nope|no|will|would|going to|want me to|if you|if i|when you|once you|let me|ready to|about to|need to|needs to|to be|unless|whether|how to|offer)\\b|'ll\\b|\\?\\s*$",RegexOption.IGNORE_CASE)
    // Things the records say happened at some other time ("the estimate was sent on Monday", "created Oct 3", "2 days ago") are history the bot read, not what this job did.
    private val aboutThePast=Regex("\\b(earlier|previously|before|yesterday|last (?:week|time|night|month|year)|a while ago|ago|back then|in the past)\\b" +
        "|\\b(?:on|since|during|last)\\s+(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday|january|february|march|april|may|june|july|august|september|october|november|december|jan|feb|mar|apr|jun|jul|aug|sept?|oct|nov|dec|\\d)" +
        "|\\b\\d{1,2}/\\d{1,2}\\b",RegexOption.IGNORE_CASE)
    private val startsConditional=Regex("^\\W*(if|when|once|after|before|unless|whenever|since|because|while|until)\\b",RegexOption.IGNORE_CASE)
    private val aboutMemory=Regex("\\b(memory|remember|noted|learn|learned)\\b|\\b(saved|save|saves|saving)\\b[^.]{0,30}\\$\\d|\\b(saved you|save you|saves you|saved time|saves time|saved money)\\b",RegexOption.IGNORE_CASE)
    // "Email sent to Maria." "The text was sent." "Draft saved." (the thing first, then what was done to it)
    private val thingDone=Regex("^\\W*(?:the |your |an? |this |that )?(?:[a-z-]+ ){0,2}?(email|e-mail|text|message|invoice|estimate|draft|file|pdf|flyer|document|reminder|event|appointment|routine|skill|video|sound|page|site|website|game|chart|picture) " +
        "(?:has been |have been |was |were )?$ADV(sent|saved|created|made|booked|scheduled|published|deleted|removed)\\b$LEAD_END",RegexOption.IGNORE_CASE)

    /** The answer without things that only quote or show: code blocks, quoted lines, text inside quotation marks. */
    fun withoutQuotes(answer:String):String {
        var t=answer.replace(Regex("```.*?```",RegexOption.DOT_MATCHES_ALL),"\n")
        t=t.lines().filterNot {it.trimStart().startsWith(">")}.joinToString("\n")
        t=t.replace(Regex("“[^”]{3,500}”")," ").replace(Regex("\"[^\"\\n]{3,300}\"")," ")
        return t
    }

    /** What the answer claims to have done, one entry per claim (sentences that only offer, ask, deny or look back are not claims). */
    fun claims(answer:String):List<Claim> {
        val out=ArrayList<Claim>()
        val sentences=withoutQuotes(answer).split(Regex("(?<=[.!?])\\s+|\\n+")).map {it.replace(Regex("^[\\s\\-*•#]+"),"").trim()}.filter {it.length>=4}
        for(s in sentences) {
            if(skipSentence.containsMatchIn(s) || aboutThePast.containsMatchIn(s) || startsConditional.containsMatchIn(s) || aboutMemory.containsMatchIn(s)) continue
            val snippet=s.take(100)
            sentClaim.find(s)?.let {m->
                val verb=m.value.trim().split(Regex("\\s+")).last().lowercase().trimEnd('.',',','!',';',':')
                val kind=when {
                    verb.startsWith("email") || verb.startsWith("e-mail") || verb=="mailed" -> Kind.EMAIL
                    verb=="texted" || verb=="messaged" -> Kind.TEXT
                    emailWords.containsMatchIn(s) && !textWords.containsMatchIn(s.replace(emailWords,"")) -> Kind.EMAIL
                    textWords.containsMatchIn(s) && !emailWords.containsMatchIn(s) -> Kind.TEXT
                    else -> Kind.SENT
                }
                out+=Claim(kind,snippet)
            }
            savedClaim.find(s)?.let {if(draftWords.containsMatchIn(s)) {if(draftPlace.containsMatchIn(s)) out+=Claim(Kind.DRAFT,snippet)} else if(madeObjects.containsMatchIn(s) || s.length<=40) out+=Claim(Kind.SAVED,snippet)}
            madeClaim.find(s)?.let {m->
                val rest=s.substring(m.range.last)
                if(draftWords.containsMatchIn(rest) && draftPlace.containsMatchIn(s)) out+=Claim(Kind.DRAFT,snippet) else if(madeObjects.containsMatchIn(rest)) out+=Claim(Kind.MADE,snippet)
            }
            bookedClaim.find(s)?.let {out+=Claim(Kind.BOOKED,snippet)}
            calendarClaim.find(s)?.let {if(!bookedClaim.containsMatchIn(s)) out+=Claim(Kind.BOOKED,snippet)}
            updatedClaim.find(s)?.let {m->if(changedObjects.containsMatchIn(s.substring(m.range.last))) out+=Claim(Kind.UPDATED,snippet)}
            deletedClaim.find(s)?.let {m->if(deletedObjects.containsMatchIn(s.substring(m.range.last))) out+=Claim(Kind.DELETED,snippet)}
            (publishedClaim.find(s)?:onlineClaim.find(s))?.let {out+=Claim(Kind.PUBLISHED,snippet)}
            paidClaim.find(s)?.let {out+=Claim(Kind.PAID,snippet)}
            thingDone.find(s)?.let {m->
                val noun=m.groupValues[1].lowercase()
                val kind:Kind?=when(m.groupValues[2].lowercase()) {
                    "sent"->if(noun.startsWith("e")) Kind.EMAIL else if(noun=="text" || noun=="message") Kind.TEXT else Kind.SENT
                    "saved"->if(noun=="draft" && draftPlace.containsMatchIn(s)) Kind.DRAFT else Kind.SAVED
                    "created","made"->Kind.MADE
                    "booked","scheduled"->Kind.BOOKED
                    "published"->Kind.PUBLISHED
                    "deleted","removed"->Kind.DELETED
                    else->null
                }
                if(kind!=null) out+=Claim(kind,snippet)
            }
        }
        return out.distinctBy {it.kind to it.snippet}
    }

    // "It is attached and displayed above." "The chart is below." (said when nothing is attached: the live phone, 2026-10-12, a bot that found an old file and said so)
    private val attachWords=Regex("\\b(attached|attaching|enclosed|displayed|shown|showing)\\b|\\b(is|are|see|find it|find them)\\s+(above|below)\\b",RegexOption.IGNORE_CASE)
    private val fileThings=Regex("\\b(files?|charts?|graphs?|pictures?|images?|photos?|workbooks?|spreadsheets?|documents?|pdfs?|excel|slides?|decks?)\\b|\\.(xlsx|docx|pptx|pdf|png|jpe?g|csv)\\b",RegexOption.IGNORE_CASE)

    /** Whether the answer says a file or picture is attached or shown with it (a sentence that only offers, asks, denies or looks ahead does not count). */
    fun claimsAttachment(answer:String):Boolean = fileThings.containsMatchIn(answer) && withoutQuotes(answer).split(Regex("(?<=[.!?])\\s+|\\n+")).map {it.replace(Regex("^[\\s\\-*•#]+"),"").trim()}.any {s->
        s.length>=6 && attachWords.containsMatchIn(s) && !skipSentence.containsMatchIn(s) && !startsConditional.containsMatchIn(s)
    }

    /** The note that goes back to the model when it said something is attached and nothing is. */
    fun attachRepair():String =
        "Check before you finish: your answer says a file or picture is attached or shown, but nothing is attached to this answer. If the owner asked for a file or picture you already have, " +
        "call files_show with its name (files_list shows the names); your answer keeps everything the owner asked for in it, the file only goes under it. Otherwise change your answer so it does not say that."

    // The owner names a kind of file to be made ("save the final code as a js file", "put them in an Excel review list", "a one page Word briefing"). The live phone, 2026-10-12: a bot
    // ticked "save the code" off its own plan and never saved it. A sentence counts when it has a making word and a kind of file and no "don't"; the kind must then exist among the files this job made.
    data class Wanted(val label:String,val extensions:Set<String>)
    private val makeVerbs=Regex("\\b(save|put|write|make|create|build|export|prepare|produce|draw|plot|turn)\\b",RegexOption.IGNORE_CASE)
    private val refusal=Regex("\\b(don'?t|do not|never|no need|without|instead of|rather than)\\b",RegexOption.IGNORE_CASE)
    private val fileKinds=listOf(
        Wanted("a Word document",setOf("docx")) to Regex("\\bword\\s+(file|doc|docs|document|documents|briefing|brief|report|letter|memo|summary)\\b|\\bdocx\\b",RegexOption.IGNORE_CASE),
        Wanted("an Excel file",setOf("xlsx","csv")) to Regex("\\bexcel\\b|\\bspreadsheet\\b|\\bxlsx\\b|\\bworkbook\\b",RegexOption.IGNORE_CASE),
        Wanted("a PDF",setOf("pdf")) to Regex("\\bpdf\\b",RegexOption.IGNORE_CASE),
        Wanted("a PowerPoint",setOf("pptx")) to Regex("\\bpower\\s?point\\b|\\bslide\\s?deck\\b|\\bpptx\\b|\\bslides\\b|\\bdeck\\b",RegexOption.IGNORE_CASE),
        Wanted("a CSV file",setOf("csv")) to Regex("\\bcsv\\b",RegexOption.IGNORE_CASE),
        Wanted("a JavaScript file",setOf("js")) to Regex("\\b(js|javascript)\\s+file\\b|\\.js\\b",RegexOption.IGNORE_CASE),
        Wanted("a chart",setOf("png","jpg","pdf","docx","pptx","xlsx")) to Regex("\\b(chart|graph|plot)\\b",RegexOption.IGNORE_CASE),
    )
    /** The kinds of file the request asks to be made. */
    fun requestedFiles(request:String):List<Wanted> {
        val sentences=withoutQuotes(request).split(Regex("(?<=[.!?])\\s+|\\n+")).filter {makeVerbs.containsMatchIn(it) && !refusal.containsMatchIn(it)}
        return fileKinds.filter {(_,pattern)->sentences.any {pattern.containsMatchIn(it)}}.map {it.first}
    }
    /** The asked-for kinds that none of the [made] file names is. */
    fun missingFiles(request:String,made:List<String>):List<Wanted> {
        val extensions=made.map {it.substringAfterLast('.',"").lowercase()}.toSet()
        return requestedFiles(request).filter {wanted->wanted.extensions.none {it in extensions}}
    }
    fun fileRepair(missing:List<Wanted>):String =
        "Check before you finish: the owner asked for "+missing.joinToString(" and ") {it.label}+" and none was made in this job. Make it now (docx_create, xlsx_create, pptx_create, document_pdf, chart_make, " +
        "or file_write for text and code files) with what you found, then answer. If you truly cannot, say so plainly in your answer. Don't mark a step done before it is done."

    /** Claims in the answer that no result of the job supports. */
    fun unsupportedClaims(answer:String,history:JSONArray):List<Claim> = unsupportedClaims(answer,calls(history))
    fun unsupportedClaims(answer:String,cs:List<Call>):List<Claim> = claims(answer).filterNot {supports(it.kind,cs)}

    /** The note that goes back to the model: what it claimed, that the phone has no record, and the two ways out. */
    fun claimRepair(problems:List<Claim>):String {
        val said=problems.take(3).joinToString("; ") {"“"+it.snippet.trim().trimEnd('.')+"”"}
        val nothing=problems.map {it.kind.word}.distinct().joinToString(", ")
        return "Check before you finish: your answer says $said. But no result in this job shows that actually happened: nothing was $nothing here. " +
            "Either do it now with the right tool, or change your answer to say plainly what was done and what was not. Never claim something a result doesn't show."
    }

    // ------------------------------------------------------------------------------------------------------------------------------
    // 3. Reading a saved draft back
    // ------------------------------------------------------------------------------------------------------------------------------
    private fun address(raw:String):String = Regex("<([^>]+)>").find(raw)?.groupValues?.get(1)?:raw
    private fun body(text:String)=text.replace("\r\n","\n").trim()

    /** Null when the draft read back from Gmail is exactly what was asked for; otherwise a plain description of what is different. */
    fun draftProblem(asked:JSONObject,back:JSONObject):String? {
        if(!back.optBoolean("draft")) return "Gmail does not show it as a draft"
        if(!address(back.optString("to")).trim().equals(asked.optString("to").trim(),true)) return "the recipient is different"
        if(back.optString("subject").trim()!=asked.optString("subject").trim()) return "the subject is different"
        if(body(back.optString("body"))!=body(asked.optString("body"))) return "the message text is different"
        return null
    }

    /** Puts the outcome of the read-back into the draft's result, so the receipt (and the model) can see it. */
    fun withReadBack(saved:JSONObject,asked:JSONObject,back:JSONObject?):JSONObject {
        if(back==null) return saved
        val problem=draftProblem(asked,back)
        return saved.put("read_back",obj("checked" to true,"matches" to (problem==null),"problem" to (problem?:"")))
    }

    // ------------------------------------------------------------------------------------------------------------------------------
    // 4. The critic: a cheap second opinion
    // ------------------------------------------------------------------------------------------------------------------------------
    sealed class Verdict {object Pass:Verdict();data class Fail(val reason:String):Verdict()}

    const val CRITIC_INSTRUCTIONS="You are a careful reviewer checking an assistant's work for its owner. You are given the owner's request, a record of what the phone really did (it is automatic and complete), " +
        "and the assistant's final answer. Reply with exactly one line. PASS if the answer does what the owner asked and agrees with the record. " +
        "FAIL: followed by one short plain-words reason if the answer leaves out part of the request, claims something done (sent, saved, booked, changed, deleted) that the record does not show, " +
        "gives numbers, names or dates that contradict the record, uses other examples, test cases or numbers than the ones the owner gave, or only says that a file was made or checked without giving " +
        "the result itself in the message (when the owner asked a question or for a decision, a list or figures, the message must carry them; the file is only the full version). " +
        "Files and pictures the assistant made are shown under its answer automatically, so an answer that calls them attached or shown is correct: never fail it for that. " +
        "FAIL an answer that hands the owner a chore (open this, share that, copy or send it themselves, sign in again) that the request told the assistant to do for them or that the assistant's phone tools can do. Screen text from the phone in the record can be cut short: never fail an answer for naming something on the phone's screen that the record does not show; fail it only when the record contradicts it. " +
        "Be strict about claims of things done, about facts and about parts of the request left out; be relaxed about style, length and formatting. When unsure, reply PASS."

    /** A long request (several things asked at once) is the kind that gets a part left out. */
    fun isDemanding(request:String):Boolean = request.trim().length>=250

    /** Whether the second opinion is worth its cost: real work (4 or more tool calls) or any change, or a long multi-part request; a real answer, and time to act on it. */
    fun shouldCritique(workCalls:Int,changes:Int,answer:String,away:Boolean,cutShort:Boolean,demanding:Boolean=false):Boolean =
        !away && !cutShort && answer.trim().length>=20 && (workCalls>=4 || changes>=1 || demanding)

    fun criticPrompt(request:String,receipt:List<String>,steps:List<String>,failed:List<String>,answer:String,extra:List<String> = emptyList()):String =
        "The owner's request:\n"+request.take(1500)+"\n\n"+
        "What the phone recorded (files and pictures it made are shown under the answer automatically):\n"+(receipt.map {"- $it"}+extra.map {"- $it"}+steps.takeLast(10).map {"- $it"}).joinToString("\n").ifBlank {"- (nothing changed; the assistant only looked things up or chatted)"}+
        (if(failed.isEmpty()) "" else "\nSteps that failed: "+failed.distinct().take(8).joinToString())+
        "\n\nThe assistant's final answer:\n"+answer.take(3000)

    fun parseVerdict(reply:String):Verdict {
        val line=reply.lines().map {it.trim()}.firstOrNull {it.isNotBlank()}?.trimStart('*','_','`',' ')?:return Verdict.Pass
        if(!line.startsWith("FAIL",true)) return Verdict.Pass
        val reason=line.drop(4).trimStart(':','-','—',' ','*').trim().replace(Regex("\\s+")," ").take(220)
        return Verdict.Fail(reason.ifBlank {"the answer does not match what was done"})
    }

    fun criticRepair(reason:String):String = "A quick review of your work found a problem: $reason. " +
        "Fix it now with your tools if you can, or change your answer to say plainly what is missing or wasn't done. Don't repeat work that was already done. " +
        "If the review is simply mistaken because you already fixed it, keep your finished answer. Either way your final message must stand on its own: never mention the review, a repair or an earlier mistake."

    // ------------------------------------------------------------------------------------------------------------------------------
    // The recorded outcome
    // ------------------------------------------------------------------------------------------------------------------------------
    /** How a finished job is recorded, in place of "Completed unless a send failed": failed sends, a cut-short job and answers still claiming too much all make it Partial. */
    fun outcome(receipts:List<JSONObject>,answer:String,cutShort:Boolean,unconfirmed:Boolean):JSONObject =
        AgentRules.autoFinish(receipts,answer,cutShort,if(unconfirmed) listOf(UNCONFIRMED_LINE) else emptyList())

    /** Whether a recorded outcome says the job was cut short (the chat then offers Keep going). */
    fun wasCutShort(completion:String):Boolean=runCatching {JSONObject(completion).optBoolean("cut_short")}.getOrDefault(false)

    /** What a Keep going tap tells the model when it continues a job that was cut short or only partly done. */
    fun keepGoingNote(cutShort:Boolean):String = if(cutShort) "The owner tapped Keep going (or the phone carried on for you by itself): you ran out of time earlier. Continue where you left off with what is still unfinished. Don't repeat work that was already done, and don't start over. If the job has a number to reach (for example 50 emails), start from the real count (campaign_report, or what you recorded) and say how many are done and how many are left. When you are finished, give the complete answer."
        else "The owner tapped Keep going. Continue with whatever is unfinished or failed earlier, using another way if the first one didn't work. Don't repeat work that was already done. If nothing more can be done, say plainly what is missing."
}
