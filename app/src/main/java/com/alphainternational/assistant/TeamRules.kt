package com.alphainternational.assistant

/**
 * Bots work like department heads in a company group chat. A planner hands each request to the bot(s) whose job fits (see Case.kt); each answers for its
 * own department, and when one needs something another department has it asks that colleague and waits for the answer. (Pure rules, so they're tested
 * without a phone.)
 */
object TeamRules {
    /**
     * Which bots the owner called on without needing a planner: the ones they named ("@Marketing", "Bookkeeper, log this"), or everyone for "hi everyone".
     * Empty means the planner decides.
     */
    fun addressed(text:String,bots:List<String>):List<String> {
        Team.mentioned(text,bots).takeIf {it.isNotEmpty()}?.let {return it.take(8)}
        if(Team.addressesEveryone(text)) return bots.take(12)
        return emptyList()
    }

    /**
     * What a bot reads for a group message it was picked for: the owner's words, plus (when several bots were picked) which part is its own
     * and who else was called on, so it does only its part and doesn't repeat the others.
     */
    fun assignment(text:String,part:String,others:List<Pair<String,String>>):String {
        if(part.isBlank() && others.isEmpty()) return text
        val also=if(others.isEmpty()) "" else " Also called on for other parts: "+others.joinToString("; ") {it.first+(if(it.second.isBlank()) "" else " for "+it.second)}+". Leave their parts to them."
        return text+"\n\n(Teamwork: you were picked for this message"+(if(part.isBlank()) "." else ": "+part+".")+also+")"
    }

    /**
     * The job of [target] that [asker] can wait for instead of asking again: a colleague already called on for the same message (or a step of the same
     * case) and still working. The asker waits for its result and uses it; nobody does the same work twice.
     */
    fun runningFor(asker:TaskRecord,target:String,tasks:List<TaskRecord>):TaskRecord? =
        if(asker.group.isBlank()) null else tasks.firstOrNull {it.id!=asker.id && it.group==asker.group && it.workspace.equals(target,true) && !isPacket(it.prompt) && it.status in setOf("Running","Queued")}

    /** The marker saved on a group job: every job answering one group message shares it (and one notification). */
    fun groupId(id:String)="named:"+id

    /** What a bot is told when the coworker it texted answers. It carries on with the owner's request without waiting around. */
    fun reply(from:String,answer:String,askerRequest:String)=
        "[Update from your coworker] "+from+" texted back: “"+answer.replace(Regex("\\s+")," ").trim().take(1500)+"”\n"+
        "Carry on with what the owner asked ("+askerRequest.replace(Regex("\\s+")," ").trim().take(400)+") and tell the owner the result in plain words. " +
        "If the reply doesn't have what you need, say exactly what is still missing."

    fun isReplyPrompt(prompt:String)=prompt.startsWith("[Update from your coworker]")

    /** One text from a bot to a bot. Kept short: it stands on its own, with no greeting. */
    fun question(from:String,request:String)="From "+from+": "+request.trim()

    /** The most bot-to-bot texts that can lead to one another (A asks B, B asks C…); beyond that a bot answers the owner with what it has. */
    const val MAX_HOPS=3

    /** The line each bot gets about each colleague: its name, its job, and which accounts it works with. */
    fun colleague(bot:Bot)=bot.name+" ("+bot.role.ifBlank {"general assistant"}+(if(bot.apps.isNotEmpty()) "; works with "+bot.apps.joinToString() else "")+")"

    /** The model and intelligence level for a bot: its own choice when it has one, otherwise the main setting. Never switches to Auto on its own. */
    fun pick(bot:Bot,known:Set<String>,main:String,mainEffort:String):Pair<String,String> =
        (bot.model.takeIf {it.isNotBlank() && it in known}?:main) to (bot.effort.takeIf {it.isNotBlank()}?:mainEffort)

    // ---- Asking colleagues and waiting for the answers (ask_team, ask_colleague) ----

    /** What a colleague answers with. The first word says which. */
    enum class Status(val label:String,val word:String) {ANSWER("answer","ANSWER"),NEED_INPUT("need-input","NEED-INPUT"),CANNOT("cannot","CANNOT"),NOT_MINE("not-mine","NOT-MINE")}
    data class Answer(val status:Status,val text:String)

    /**
     * The text a colleague gets: the owner's own words, what the asker already knows, what is needed back, and how to answer. It stands on its own,
     * so the colleague needs nothing else, and it can't pass the question on to someone else.
     */
    fun packet(asker:String,goal:String,known:String,deliverable:String,caseId:String):String =
        "From "+asker+" (case "+caseId.take(8)+"). A colleague needs something from your department.\n"+
        "What the owner asked, in their words: “"+goal.replace(Regex("\\s+")," ").trim().take(900)+"”\n"+
        "What "+asker+" already knows: "+(known.replace(Regex("\\s+")," ").trim().take(900).ifBlank {"nothing yet"})+"\n"+
        "What "+asker+" needs back: "+(deliverable.replace(Regex("\\s+")," ").trim().take(300).ifBlank {PACKET_ANYTHING})+"\n"+
        "Do only that part. The rest of the owner's request belongs to "+asker+" and the other colleagues; don't research, compare or write up anything beyond what "+asker+" needs back, and be quick: a few minutes at most.\n"+
        "Look in all your own sources first (records: data_find with a name, phone, plate, VIN, email or invoice number). Start your reply with exactly one of: ANSWER: (with what you found, including the exact numbers, names and dates); "+
        "NEED-INPUT: (the one thing you need to know to answer); CANNOT: (what you checked and why you can't); NOT-MINE: (which bot owns this). "+
        "Don't text another bot and don't ask the owner; just answer "+asker+"."

    fun isPacket(prompt:String)=prompt.startsWith("From ") && prompt.contains("What the owner asked, in their words")
    /** What a packet says it needs back when the asker did not say. */
    const val PACKET_ANYTHING="the answer in plain words"

    private val leadGood=listOf(Regex("(?i)\\b(chief of staff|general|all-?purpose|everything)\\b") to 3,Regex("(?i)\\b(advis[eo]r|business expert|manager)\\b") to 3,Regex("(?i)\\b(operations|decisions|day-to-day)\\b") to 2,
        Regex("(?i)\\b(assistant|personal)\\b") to 1)
    private val leadBad=listOf(Regex("(?i)\\b(texts?|texting|sms|messages?|messaging)\\b") to 5,Regex("(?i)\\b(e-?mails?|inbox)\\b") to 3,Regex("(?i)\\b(calls?|phone|dial)\\b") to 3,
        Regex("(?i)\\b(scout|shopper|prices?|games?|roblox|recruit\\w*|bookkeep\\w*)\\b") to 2)
    /**
     * The bot that writes the one merged answer and takes anything no other bot owns. "Alpha" when there is one; otherwise the bot whose job reads like a
     * general advisor or chief of staff. Never a bot whose job is texting, email or calls (it would be the one answering by default), and the first bot only
     * as a last resort. The owner can pick one in Settings; this is only the automatic choice.
     */
    fun pickLead(bots:List<Bot>):String {
        if(bots.isEmpty()) return ""
        bots.firstOrNull {it.name.equals("Alpha",true)}?.let {return it.name}
        bots.firstOrNull {it.name.equals("Chief of Staff",true) || it.name.equals("Assistant",true)}?.let {return it.name}
        fun score(b:Bot):Int {
            val text=b.name+" "+b.role
            return leadGood.sumOf {(r,w)->if(r.containsMatchIn(text)) w else 0}+(if(b.name.contains("alpha",true)) 2 else 0)-leadBad.sumOf {(r,w)->if(r.containsMatchIn(text)) w else 0}
        }
        val best=bots.maxByOrNull {score(it)}!!
        if(score(best)>0) return best.name
        return (bots.firstOrNull {score(it)==0}?:best).name
    }

    /**
     * The words that say how demanding a request is. A job's request often carries wrapper text (a team step's notes, a colleague's packet), which is long
     * and says "exact numbers, names and dates": it must not make an easy lookup look like research and send it to the slowest model.
     */
    fun levelText(prompt:String):String {
        if(isPacket(prompt)) {
            // A colleague is asked for one thing ("the price at Walmart"), however big the owner's request is: what is needed back says how hard it is. Only when
            // that was left vague does the owner's whole request stand in for it.
            val needs=Regex("(?m)^What .{1,80}? needs back: (.*)$").find(prompt)?.groupValues?.get(1)?.trim().orEmpty()
            if(needs.isNotBlank() && needs!=PACKET_ANYTHING) return needs
            return Regex("What the owner asked, in their words: “(.*?)”",RegexOption.DOT_MATCHES_ALL).find(prompt)?.groupValues?.get(1)?.trim().orEmpty().ifBlank {prompt.take(300)}
        }
        val at=prompt.indexOf("\n\n(Teamwork:")
        if(at<0) return prompt
        val part=Regex("YOUR PART: (.*?)\\. (?:Other parts|Already found|Reply with)",RegexOption.DOT_MATCHES_ALL).find(prompt.substring(at))?.groupValues?.get(1).orEmpty()
        return (prompt.substring(0,at)+" "+part).trim()
    }

    /** Reads a colleague's reply into a status and its text. A reply with no marker counts as an answer. */
    fun parseAnswer(reply:String):Answer {
        val text=reply.trim()
        for(s in Status.values()) {
            val m=Regex("^\\W*"+Regex.escape(s.word)+"\\s*[:\\-–]?\\s*",RegexOption.IGNORE_CASE).find(text)
            if(m!=null) return Answer(s,text.substring(m.range.last+1).trim())
        }
        return Answer(Status.ANSWER,text)
    }

    /** Most colleagues one job may ask in all; beyond that the bot answers the owner with what it has. */
    const val MAX_ASKS=6
    /** New colleague jobs one job may start in all (asking the same colleague again after "cannot" or "need-input" counts each time). The live phone: eight in nine minutes. */
    const val MAX_HELPER_JOBS=5
    /** Most colleagues in one ask_team call. */
    const val MAX_PER_CALL=4
    const val DEFAULT_WAIT_S=120
    const val MAX_WAIT_S=300

    /** Colleagues answering a packet only answer back: no asking onward, so questions can't chain or loop. */
    fun mayAsk(task:TaskRecord)=!task.prompt.startsWith("From ")

    /** Whether a colleague reply is one the bot can ask again about: only a "need-input" answer (or a failure) reopens the question; a real answer is final. */
    fun mayAskAgain(lastStatus:String?)=lastStatus==null || lastStatus in setOf("need-input","cannot","no answer")

    /** What each account means in plain words, so a bot knows who to ask. */
    private val owns=mapOf("supabase" to "the shop database (customers, vehicles, invoices, estimates, payments, appointments, leads)","gmail" to "email","calendar" to "the calendar",
        "drive" to "Google Drive files","tasks" to "Google Tasks","contacts" to "contacts","vercel" to "websites","stripe" to "Stripe payments","paypal" to "PayPal payments",
        "zapier" to "Zapier apps","notion" to "Notion","trello" to "Trello boards")
    /** A directory line for each colleague: what it owns and can reach, built from its real accounts, so a bot asks the right one instead of the owner. */
    fun directory(bots:List<Bot>,self:String):String =
        bots.filter {it.name!=self}.joinToString("\n") {b->
            "- "+b.name+": "+b.role.ifBlank {"general assistant"}+(if(b.apps.isNotEmpty()) ". Has access to "+b.apps.joinToString {owns[it.lowercase()]?:it} else "")
        }

    /** The colleague's answer as the asker should relay it: labelled, with the numbers and names untouched. */
    fun relay(colleague:String,a:Answer)=when(a.status) {
        Status.ANSWER->colleague+" says: "+a.text
        Status.NEED_INPUT->colleague+" needs to know: "+a.text
        Status.CANNOT->colleague+" couldn't find it: "+a.text
        Status.NOT_MINE->colleague+" says this isn't theirs: "+a.text
    }
}

/** Whether a running job should show as "typing…". */
fun TaskRecord.showsAsTyping()=status=="Running"

/**
 * The owner-question guard. A bot's final reply is checked in code before it is posted: if it asks the owner for something the records or a colleague
 * could answer (and the bot never looked), asks for a sign-in the owner already did, or the bot made PDFs nobody asked for, the bot is sent back ONCE
 * with a short note naming the right tool. It never loops (one nudge per job) and never applies to Away jobs.
 */
object AskGuard {
    data class Nudge(val reason:String,val text:String)
    /** Having called one of these means the bot did look (or asked the colleague who knows). */
    val lookups=setOf("data_find","ask_colleague","ask_team")
    private val pdfTools=setOf("document_pdf","invoice_pdf")

    // The sentence has to ask the owner for something ("what year is it?", "send me the make and model", "I need the phone number").
    private val cue=Regex("\\?|\\b(please|can you|could you|would you|will you|tell me|send me|give me|let me know|share|provide|confirm|i need|i'll need|i will need|need (the|his|her|their|a|an|to know)|what|which|do you (know|have)|have you got)\\b",RegexOption.IGNORE_CASE)
    private val findable=listOf(
        Regex("\\b(year|make|model)\\b.{0,25}\\b(year|make|model)\\b",RegexOption.IGNORE_CASE),
        Regex("\\b(what|which)\\s+(car|vehicle|truck|suv|van)\\b",RegexOption.IGNORE_CASE),
        Regex("\\b(vehicle|car|truck)'?s?\\s+(year|make|model|vin|plate|license)\\b",RegexOption.IGNORE_CASE),
        Regex("\\b(his|her|their|the customer'?s?|customer'?s?|client'?s?)\\s+(phone|number|name|email|address|vehicle|car)\\b",RegexOption.IGNORE_CASE),
        Regex("\\b(invoice|estimate)\\s+(number|no\\.?|total|date|amount|details?|id)\\b",RegexOption.IGNORE_CASE),
        Regex("\\bwhich\\s+(invoice|estimate|customer)\\b",RegexOption.IGNORE_CASE))
    private val signIn=Regex("\\b(sign|log)\\s?(in|into|on)\\b|\\blogin\\b",RegexOption.IGNORE_CASE)
    private val fileWords=Regex("\\b(pdf|printable|print|printed|attach|attachment|download|export|as a file|file|document|letter|flyer|checklist|price list|report)\\b",RegexOption.IGNORE_CASE)

    private fun sentences(text:String)=text.replace(Regex("[*_`#>]")," ").split(Regex("(?<=[.!?])\\s+|\\n+")).map {it.trim()}.filter {it.isNotBlank()}

    /** The sentence in [reply] where the bot asks the owner for something findable, or null. */
    fun findableAsk(reply:String):String? = sentences(reply).firstOrNull {s->cue.containsMatchIn(s) && findable.any {it.containsMatchIn(s)}}

    /** The signed-in site a sign-in request in [reply] is about, or null. [signedIn] is the sites the owner already signed in to ("ebay.com"). */
    fun signInAsk(reply:String,signedIn:List<String>):String? {
        if(signedIn.isEmpty()) return null
        return sentences(reply).firstNotNullOfOrNull {s->
            if(!signIn.containsMatchIn(s) || !cue.containsMatchIn(s)) null
            else signedIn.firstOrNull {site->
                val host=site.lowercase().removePrefix("https://").removePrefix("http://").removePrefix("www.").substringBefore('/')
                host.isNotBlank() && (s.contains(host,true) || s.contains(host.substringBefore('.'),true))
            }
        }
    }

    fun madeUnaskedPdf(tools:Collection<String>,ownerRequest:String)=tools.any {it in pdfTools} && !fileWords.containsMatchIn(ownerRequest)

    /**
     * Whether to send the bot back before it posts [reply]. [tools] are the tools the job called; [haveLookups] says data_find or a colleague exists to
     * point at; [already] is true once the job has been nudged (never twice); [away] jobs are never nudged.
     */
    fun check(reply:String,tools:Collection<String>,signedIn:List<String>,ownerRequest:String,haveLookups:Boolean,already:Boolean,away:Boolean,packet:Boolean=false):Nudge? {
        if(away || already || packet || reply.isBlank()) return null
        if(haveLookups && tools.none {it in lookups}) findableAsk(reply)?.let {
            return Nudge("lookup","Before you ask the owner: look it up. Call data_find with the name, phone, plate, VIN, email or invoice number you have (it searches the customer, vehicle and invoice records together), or ask the colleague who owns it with ask_team. "+
                "Ask the owner only if that finds nothing, and then say what you searched. Write your answer again.")
        }
        signInAsk(reply,signedIn)?.let {site->
            return Nudge("signin","The owner already signed in to $site in Alpha's browser. Don't ask them to sign in again: open the site and carry on. Only if the page itself shows a login form, say so. Write your answer again.")
        }
        if(madeUnaskedPdf(tools,ownerRequest)) return Nudge("pdf","The owner didn't ask for a PDF. Answer in plain text and don't mention or offer any file. Write your answer again.")
        return null
    }
}
