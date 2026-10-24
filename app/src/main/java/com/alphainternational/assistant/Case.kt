package com.alphainternational.assistant

import org.json.JSONArray
import org.json.JSONObject

/*
 * Teamwork cases. One request from the owner becomes a case: a small plan of steps (which bot does what, and which steps wait for which), a shared
 * file of facts with where each came from, and one merged answer from the lead bot when two or more steps contributed. Everything in this file is
 * plain logic with no phone and no model, so it is tested without either. The Controller does the starting, waiting and posting.
 */

/** One piece of a case: [bot] does [goal]. It waits for the steps in [after]. [status]: waiting, running, done or stopped. [taskId] is its job. */
data class CaseStep(val id:String,val bot:String,val goal:String,val after:List<String> = emptyList(),val status:String="waiting",val taskId:String="",
    val result:String="",
    /** Why a step stopped, or a short remark ("partly done"), in plain words. */
    val note:String="") {
    fun json():JSONObject=obj("id" to id,"bot" to bot,"goal" to goal,"after" to arr(after),"status" to status,"task_id" to taskId,"result" to result,"note" to note)
    companion object {
        fun parse(j:JSONObject)=CaseStep(j.optString("id"),j.optString("bot"),j.optString("goal"),j.optJSONArray("after")?.strings().orEmpty(),j.optString("status","waiting").ifBlank {"waiting"},
            j.optString("task_id"),j.optString("result"),j.optString("note"))
    }
}

/** A fact on the case file and where it came from ("Customer Desk · looked up records"). */
data class CaseFact(val text:String,val source:String) {
    fun json():JSONObject=obj("text" to text,"source" to source)
    companion object {fun parse(j:JSONObject)=CaseFact(j.optString("text"),j.optString("source"))}
}

/**
 * The shared file for one request. [state]: working, merging or done. [mode]: "merge" (the lead writes one answer from the findings), "lead" (same, in the
 * lead's own voice) or "direct" (the owner named the bots, so each posts its own text and nothing is merged). [open] lists what is still missing.
 */
data class Case(val id:String,val goal:String,val ownerText:String,val steps:List<CaseStep>,val facts:List<CaseFact> = emptyList(),val files:List<String> = emptyList(),
    val open:List<String> = emptyList(),val state:String="working",val created:Long=0,val lead:String="",val mode:String="merge",val group:String="") {
    fun json():JSONObject=obj("id" to id,"goal" to goal,"owner_text" to ownerText,"steps" to arr(steps.map {it.json()}),"facts" to arr(facts.map {it.json()}),"files" to arr(files),
        "open" to arr(open),"state" to state,"created" to created,"lead" to lead,"mode" to mode,"group" to group)
    companion object {
        fun parse(j:JSONObject)=Case(j.optString("id"),j.optString("goal"),j.optString("owner_text"),j.optJSONArray("steps")?.objects()?.map(CaseStep::parse).orEmpty(),
            j.optJSONArray("facts")?.objects()?.map(CaseFact::parse).orEmpty(),j.optJSONArray("files")?.strings().orEmpty(),j.optJSONArray("open")?.strings().orEmpty(),
            j.optString("state","working").ifBlank {"working"},j.optLong("created"),j.optString("lead"),j.optString("mode","merge").ifBlank {"merge"},j.optString("group"))
    }
}

object CaseRules {
    const val MAX_STEPS=5
    const val MAX_DIRECT=12
    /** A case older than this no longer swallows the owner's next text ("???" starts fresh work instead). */
    const val OPEN_MS=30*60_000L
    /**
     * A case whose steps are still out after this long is written up with what is in, and those jobs are stopped. It must be longer than any job may run
     * (JobBudget: wraps up by 22 minutes, tools off at 25): at 12 minutes it declared a working job "stopped" and closed the case while the job went on
     * in the background and posted its own answer later, as a second, unconnected message. It is only the backstop for a job that hangs.
     */
    const val WATCHDOG_MS=(JobBudget.CAP_STOP+3)*60_000L
    const val RESULT_CAP=1800
    const val WAITING="waiting";const val RUNNING="running";const val DONE="done";const val STOPPED="stopped"

    data class PlanStep(val id:String,val bot:String,val goal:String,val after:List<String> = emptyList())
    /** [answer]: "lead", "merge" or "direct". [amend]: add these steps to the case that is still open instead of starting a new one. */
    data class Plan(val steps:List<PlanStep>,val answer:String="merge",val amend:Boolean=false) {val usable get()=steps.isNotEmpty()}

    // ---- the plan the planner returns ----

    /** Every balanced `{…}` in [text], outermost first, so a reply with prose or a code fence around the JSON still reads. */
    fun jsonObjects(text:String):List<String> {
        val out=ArrayList<String>()
        var i=0
        while(i<text.length) {
            if(text[i]!='{') {i++;continue}
            var depth=0;var inString=false;var escaped=false;var end=-1
            var j=i
            while(j<text.length) {
                val c=text[j]
                if(inString) {if(escaped) escaped=false else if(c=='\\') escaped=true else if(c=='"') inString=false}
                else if(c=='"') inString=true
                else if(c=='{') depth++
                else if(c=='}') {depth--;if(depth==0) {end=j;break}}
                j++
            }
            if(end<0) break
            out+=text.substring(i,end+1)
            i=end+1
        }
        return out
    }

    private fun norm(s:String)=s.lowercase().filter {it.isLetterOrDigit()}
    private fun distance(a:String,b:String):Int {
        val d=Array(a.length+1) {IntArray(b.length+1)}
        for(i in 0..a.length) d[i][0]=i
        for(j in 0..b.length) d[0][j]=j
        for(i in 1..a.length) for(j in 1..b.length) d[i][j]=minOf(d[i-1][j]+1,d[i][j-1]+1,d[i-1][j-1]+if(a[i-1]==b[j-1]) 0 else 1)
        return d[a.length][b.length]
    }
    /** The real bot a name means: exact, the same ignoring spaces and case, one inside the other ("Customer" for "Customer Desk"), or a small typo. Null when none fits. */
    fun resolveBot(name:String,bots:List<String>):String? {
        val raw=name.trim();if(raw.isBlank()) return null
        bots.firstOrNull {it.equals(raw,true)}?.let {return it}
        val n=norm(raw);if(n.isBlank()) return null
        bots.firstOrNull {norm(it)==n}?.let {return it}
        val inside=bots.filter {b->val k=norm(b);n.length>=4 && k.length>=4 && (k.contains(n) || n.contains(k))}
        if(inside.size==1) return inside[0]
        if(n.length>=5) {
            val near=bots.map {it to distance(n,norm(it))}.filter {it.second<=2}.sortedBy {it.second}
            if(near.isNotEmpty() && (near.size==1 || near[0].second<near[1].second)) return near[0].first
        }
        return null
    }

    private fun stringList(v:Any?):List<String> = when(v) {
        is JSONArray->(0 until v.length()).map {v.opt(it)?.toString().orEmpty()}
        is String->v.split(',',';').map {it.trim()}
        else->emptyList()
    }.filter {it.isNotBlank() && it!="null"}

    /**
     * Reads the planner's reply. Fences and prose around the JSON are fine; unknown bot names are fixed or dropped; a step that waits for itself, for
     * something that isn't there, or in a circle loses that wait; at most [MAX_STEPS] steps; two independent steps for the same bot become one.
     * An unusable reply gives an empty plan, and the caller falls back to the lead bot alone.
     */
    fun parsePlan(reply:String,bots:List<String>,ownerText:String="",takenIds:Set<String> = emptySet()):Plan {
        val parsed=jsonObjects(reply).firstNotNullOfOrNull {raw->
            val j=runCatching {JSONObject(raw)}.getOrNull()
            j?.takeIf {it.optJSONArray("steps")!=null || it.optJSONArray("bots")!=null}
        }?:return Plan(emptyList())
        val list=parsed.optJSONArray("steps")?:parsed.optJSONArray("bots")!!
        class Raw(val rawId:String,val bot:String,val goal:String,val after:List<String>)
        val raws=ArrayList<Raw>()
        for(i in 0 until list.length()) {
            val item=list.opt(i)
            val raw=when(item) {
                is JSONObject->Raw(item.optString("id"),
                    listOf("bot","name","owner","who","assignee").firstNotNullOfOrNull {k->item.optString(k).takeIf {it.isNotBlank()}}.orEmpty().let {resolveBot(it,bots)}.orEmpty(),
                    listOf("do","goal","task","part","what").firstNotNullOfOrNull {k->item.optString(k).takeIf {it.isNotBlank()}}.orEmpty().replace(Regex("\\s+")," ").trim().take(400),
                    listOf("after","depends_on","dependsOn","needs").firstNotNullOfOrNull {k->item.opt(k)?.let {stringList(it)}.takeIf {it!=null}}.orEmpty())
                is String->Raw("",resolveBot(item,bots).orEmpty(),"",emptyList())
                else->null
            }
            if(raw!=null && raw.bot.isNotBlank() && raws.size<MAX_STEPS) raws+=raw
        }
        if(raws.isEmpty()) return Plan(emptyList())
        // Unique, tidy ids; "after" may name a raw id or a bot ("after":["Customer Desk"]).
        val used=HashSet<String>(takenIds)
        val finalIds=raws.mapIndexed {i,r->
            var id=r.rawId.trim().filter {it.isLetterOrDigit() || it=='_' || it=='-'}.take(12)
            if(id.isBlank() || id in used) {var n=i+1;while("s$n" in used) n++;id="s$n"}
            used+=id;id
        }
        val byRaw=HashMap<String,String>().also {m->raws.forEachIndexed {i,r->if(r.rawId.isNotBlank()) m.putIfAbsent(r.rawId.trim(),finalIds[i])}}
        val byBot=HashMap<String,String>().also {m->raws.forEachIndexed {i,r->m.putIfAbsent(r.bot,finalIds[i])}}
        var steps=raws.mapIndexed {i,r->
            val after=r.after.mapNotNull {a->byRaw[a.trim()]?:a.trim().takeIf {it in takenIds}?:resolveBot(a,bots)?.let {byBot[it]}}.filter {it!=finalIds[i]}.distinct()
            PlanStep(finalIds[i],r.bot,r.goal.ifBlank {ownerText.replace(Regex("\\s+")," ").trim().take(400)},after)
        }
        steps=breakCycles(steps)
        steps=mergeSameBot(steps)
        val answer=parsed.optString("answer").lowercase().takeIf {it in setOf("lead","merge")}?:"merge"
        return Plan(steps,answer,parsed.optString("case").equals("amend",true))
    }

    /** Removes the waits that make a circle (the earliest step in a circle stops waiting for the others in it), so every step can start eventually. */
    fun breakCycles(steps:List<PlanStep>):List<PlanStep> {
        val all=steps.associateBy {it.id}.toMutableMap()
        val done=HashSet<String>()
        var left=steps.map {it.id}
        while(left.isNotEmpty()) {
            val ready=left.filter {id->all.getValue(id).after.all {it in done || it !in all}}
            if(ready.isEmpty()) {
                val first=all.getValue(left.first());val stuck=left.toSet()
                all[first.id]=first.copy(after=first.after.filter {it !in stuck})
                continue
            }
            done+=ready;left=left.filter {it !in ready}
        }
        return steps.map {all.getValue(it.id)}
    }
    private fun ancestors(id:String,steps:List<PlanStep>):Set<String> {
        val out=HashSet<String>();val todo=ArrayDeque(listOf(id))
        while(todo.isNotEmpty()) {
            val current=todo.removeFirst()
            steps.firstOrNull {it.id==current}?.after?.forEach {if(out.add(it)) todo.addLast(it)}
        }
        return out
    }
    /** One bot doesn't do two things at once: two steps for the same bot that don't depend on each other become one step with both goals. */
    fun mergeSameBot(steps:List<PlanStep>):List<PlanStep> {
        var cur=steps
        while(true) {
            var pair:Pair<PlanStep,PlanStep>?=null
            search@ for(i in cur.indices) for(j in i+1 until cur.size) {
                val a=cur[i];val b=cur[j]
                if(a.bot==b.bot && b.id !in ancestors(a.id,cur) && a.id !in ancestors(b.id,cur)) {pair=a to b;break@search}
            }
            val (a,b)=pair?:break
            val goal=(a.goal.trimEnd('.')+". Also: "+b.goal).take(600)
            cur=cur.filter {it.id!=b.id}.map {s->
                val renamed=s.after.map {if(it==b.id) a.id else it}.distinct().filter {it!=s.id}
                if(s.id==a.id) s.copy(goal=goal,after=(renamed+b.after).distinct().filter {it!=a.id && it!=b.id}) else s.copy(after=renamed)
            }
        }
        return breakCycles(cur)
    }

    /** What to do when the planner is unusable: the lead bot alone, with the owner's own words. */
    fun fallback(lead:String,ownerText:String)=Plan(listOf(PlanStep("s1",lead,ownerText.replace(Regex("\\s+")," ").trim().take(400))),"lead")
    /** The owner named the bots (or said "everyone"): each gets the owner's words and answers for itself. */
    fun direct(bots:List<String>,ownerText:String)=Plan(bots.take(MAX_DIRECT).mapIndexed {i,b->PlanStep("s"+(i+1),b,ownerText.replace(Regex("\\s+")," ").trim().take(400))},"direct")

    // ---- running a case ----

    /** A new case from a plan: steps with nothing to wait for run now, the rest wait. */
    fun newCase(id:String,plan:Plan,ownerText:String,lead:String,group:String,now:Long):Case = Case(id,ownerText.replace(Regex("\\s+")," ").trim().take(240),ownerText,
        plan.steps.map {CaseStep(it.id,it.bot,it.goal,it.after,if(it.after.isEmpty()) RUNNING else WAITING)},created=now,lead=lead,mode=plan.answer,group=group)

    /** More steps added to a case that is still being worked on. */
    fun amend(case:Case,plan:Plan,ownerText:String):Case = case.copy(
        goal=(case.goal+" Also: "+ownerText.replace(Regex("\\s+")," ").trim()).take(400),
        steps=case.steps+plan.steps.map {CaseStep(it.id,it.bot,it.goal,it.after,if(it.after.all {a->case.steps.firstOrNull {s->s.id==a}?.status in setOf(DONE,STOPPED) || (plan.steps.none {p->p.id==a} && case.steps.none {s->s.id==a})}) RUNNING else WAITING)},
        state="working",mode=if(case.mode=="direct") "merge" else case.mode)

    /** Whether this case ends with the lead writing one message from the findings (two or more steps, and the owner didn't just name the bots). */
    fun synthesizes(case:Case)=case.mode!="direct" && case.steps.size>=2

    fun finished(case:Case)=case.steps.isNotEmpty() && case.steps.all {it.status==DONE || it.status==STOPPED}
    fun unfinished(case:Case)=case.steps.filter {it.status==WAITING || it.status==RUNNING}

    /** Steps that were waiting and can start now: everything they wait for is done or has stopped (a stopped step is reported, not waited for forever). */
    fun ready(case:Case):List<CaseStep> = case.steps.filter {s->
        s.status==WAITING && s.after.all {a->case.steps.firstOrNull {it.id==a}?.status.let {st->st==null || st==DONE || st==STOPPED}}
    }

    /** The jobs that must not start yet: the ones belonging to steps that still wait. The scheduler skips them. */
    fun heldTasks(cases:List<Case>):Set<String> = cases.filter {it.state=="working"}.flatMap {c->c.steps.filter {it.status==WAITING && it.taskId.isNotBlank()}.map {it.taskId}}.toSet()

    fun retrying(t:TaskRecord)=t.detail.startsWith("Connection trouble") || t.detail.startsWith("The app restarted")
    /**
     * Brings the steps up to date with their jobs. A finished job is done (its last text becomes the step's result) unless it is still waiting on a
     * colleague's answer; a cancelled, paused or blocked job is stopped (with why) so the case never waits on it forever; a job that is only retrying a
     * dropped connection is still running.
     */
    fun sync(case:Case,tasks:List<TaskRecord>,textOf:(String)->String,askPending:(String)->Boolean = {false}):Case {
        val byId=tasks.associateBy {it.id}
        return case.copy(steps=case.steps.map {s->
            val t=byId[s.taskId]
            when {
                s.taskId.isBlank() -> s
                t==null -> if(s.status==DONE) s else s.copy(status=STOPPED,note="That job is gone.")
                else -> when(t.status) {
                    "Completed","Partial" -> if(askPending(t.id)) s.copy(status=RUNNING)
                        else s.copy(status=DONE,result=textOf(t.id).ifBlank {s.result}.take(RESULT_CAP),note=if(t.status=="Partial") t.detail.take(160) else "")
                    "Cancelled" -> s.copy(status=STOPPED,note="Cancelled.")
                    "Running" -> s.copy(status=RUNNING,note="")
                    "Queued" -> if(s.status==WAITING) s else s.copy(status=RUNNING)
                    "Paused" -> if(retrying(t)) s.copy(status=RUNNING) else s.copy(status=STOPPED,note=t.detail.take(160))
                    else -> s.copy(status=STOPPED,note=t.detail.take(160))
                }
            }
        })
    }

    /** The newest case still being worked on that is recent enough to count as "the same conversation". */
    fun openCase(cases:List<Case>,now:Long,merging:Boolean=false):Case? =
        cases.filter {now-it.created<OPEN_MS && ((it.state=="working" && unfinished(it).isNotEmpty()) || (merging && it.state=="merging"))}.maxByOrNull {it.created}

    // ---- the owner's next text while a case is open ----

    /** Clear nudges, with or without a question mark. */
    private val pingAlways=Regex("^(any )?(updates?|news|progress)( yet| on (this|that|it))?\$|^(status|status update|what'?s the status|how'?s it going|how is it going|hows it going)\$|" +
        "^(are you|you) (still )?(there|working|on it|working on it|alive)\$|^still (there|working|on it)\$|^(what happened|what'?s happening|what'?s going on)\$|^(any|got) (luck|answers?)\$")
    /** Single words that only count as a nudge with a question mark ("hello?", "done?"); without one they are a greeting or an acknowledgement. */
    private val pingAsked=Regex("^(hello|hi|hey|hellooo*|yo|well|so|hmm+|um+|ok|okay|and|anyone|anybody|done|ready|finished)( there| yet)?\$")
    /** "???", "?", "any update", "hello?": a nudge, not a request. While a case is open it is answered from the case and starts no new job. */
    fun isStatusPing(text:String):Boolean {
        if(text.isBlank()) return false
        val t=text.lowercase().replace(Regex("[\\p{So}\\p{Sk}]")," ").replace(Regex("[!?.,…]+")," ").replace(Regex("\\s+")," ").trim()
        if(t.isEmpty()) return text.any {it=='?'}
        if(t.split(' ').size>6) return false
        return pingAlways.matches(t) || (text.contains('?') && pingAsked.matches(t))
    }

    private fun names(steps:List<CaseStep>)=steps.map {it.bot}.distinct().let {n->when(n.size) {0->"";1->n[0];2->n[0]+" and "+n[1];else->n.dropLast(1).joinToString(", ")+" and "+n.last()}}
    /** What to say to a nudge when no request is open: what is running, or that nothing is. Never starts anything. */
    fun idleText(running:List<String>):String = when {
        running.isEmpty() -> "Nothing is running right now. Ask me for what you need and I'll start on it."
        running.size==1 -> "Still on it: "+running[0]+" is working."
        else -> "Still on it: "+running.dropLast(1).joinToString(", ")+" and "+running.last()+" are working."
    }
    /** What to say back to a nudge: where the case stands, in plain words. */
    fun statusText(case:Case):String {
        val done=case.steps.filter {it.status==DONE};val running=case.steps.filter {it.status==RUNNING};val waiting=case.steps.filter {it.status==WAITING};val stopped=case.steps.filter {it.status==STOPPED}
        if(case.state=="merging") return "Almost there: I'm putting the answer together now."
        val parts=mutableListOf<String>()
        if(done.isNotEmpty() && case.steps.size>1) parts+=names(done)+(if(done.map {it.bot}.distinct().size>1) " are" else " is")+" done"
        if(running.isNotEmpty()) parts+=names(running)+(if(running.map {it.bot}.distinct().size>1) " are" else " is")+(if(case.steps.size>1) " still working" else " working on it")
        if(waiting.isNotEmpty()) {
            val before=waiting.flatMap {w->w.after.mapNotNull {a->case.steps.firstOrNull {it.id==a}}}.ifEmpty {running}
            parts+=names(waiting)+" will start as soon as "+names(before)+(if(before.map {it.bot}.distinct().size>1) " finish" else " finishes")
        }
        if(stopped.isNotEmpty()) parts+=names(stopped)+" got stuck ("+stopped.first().note.ifBlank {"it needs you"}.trimEnd('.').take(80)+")"
        return if(parts.isEmpty()) "Still on it." else "Still on it. "+parts.joinToString("; ").replaceFirstChar {it.uppercase()}+"."
    }

    // ---- how a case shows in the chat ----

    /** The mark next to a bot's name on the case card. */
    fun mark(status:String)=when(status) {DONE->"✓";RUNNING->"⏳";WAITING->"…";else->"⚠"}
    /** The first sentence of a bot's text, plain and short, for the one collapsed line. */
    fun headline(text:String):String {
        val line=text.lines().map {it.trim()}.firstOrNull {it.isNotBlank() && !it.startsWith("```")}.orEmpty()
        val plain=line.replace(Regex("!\\[[^\\]]*]\\([^)]*\\)"),"").replace(Regex("^[#>*\\-\\s]+"),"").replace(Regex("[*_`]"),"").trim()
        var sentence=plain.split(Regex("(?<=[.!?])\\s")).firstOrNull().orEmpty().trimEnd('.')
        // "found The car" reads badly; common sentence starters are lower-cased so it reads "found the car". Names and numbers are left alone.
        if(sentence.substringBefore(' ') in setOf("The","A","An","He","She","It","They","His","Her","Their","This","That","These","There","You","Your")) sentence=sentence.replaceFirstChar {it.lowercase()}
        return if(sentence.length>80) sentence.take(78).trimEnd()+"…" else sentence
    }
    /** "Customer Desk found the 2018 Honda Accord": the one collapsed line for a bot's piece of a case. */
    fun stepLine(bot:String,text:String):String {
        val a=TeamRules.parseAnswer(text)
        val head=headline(a.text)
        return when(a.status) {
            TeamRules.Status.ANSWER->bot+" found "+head.ifBlank {"what was needed"}
            TeamRules.Status.NEED_INPUT->bot+" needs to know "+head.ifBlank {"a detail"}
            TeamRules.Status.CANNOT->bot+" couldn't find it"
            TeamRules.Status.NOT_MINE->bot+" says it isn't theirs"
        }
    }

    // ---- prompts ----

    /** What a step's bot is asked. One step: the owner's own words, as always. Several: its own part, who else is working, and what is already known. */
    fun stepPrompt(case:Case,step:CaseStep):String = rawStepPrompt(case,step)+(if(Chitchat.isGreeting(case.ownerText)) Chitchat.NOTE else "")
    private fun rawStepPrompt(case:Case,step:CaseStep):String {
        val others=case.steps.filter {it.id!=step.id}
        // The owner named the bots (or said "everyone"): each gets their words, and knows who else was called on.
        if(case.mode=="direct") return TeamRules.assignment(case.ownerText,"",others.map {it.bot to ""})
        if(case.steps.size<=1) return case.ownerText
        val upstream=step.after.mapNotNull {id->case.steps.firstOrNull {it.id==id}}
        val sb=StringBuilder(case.ownerText).append("\n\n(Teamwork: this request has ").append(case.steps.size).append(" parts for different bots. YOUR PART: ").append(step.goal.trim().trimEnd('.')).append(". ")
        if(others.isNotEmpty()) sb.append("Other parts: ").append(others.joinToString("; ") {it.bot+" — "+it.goal.take(160)}).append(". Leave their parts to them. ")
        if(upstream.isNotEmpty()) sb.append("Already found by the team: ").append(upstream.joinToString("; ") {u->
            u.bot+": "+(if(u.status==DONE) u.result.replace(Regex("\\s+")," ").take(900) else "(couldn't finish: "+u.note.ifBlank {"no result"}+")")}).append(". Use it; don't look it up again. ")
        sb.append("Reply with your findings only: exact numbers, names and dates, what you checked, and anything you couldn't find. The lead writes the one message to the owner, so no greeting, no follow-up buttons.)")
        return sb.toString()
    }

    /** The case file as a bot reads it: the goal, who is doing what, the facts with their sources, and what is still open. Kept short. */
    fun fileText(case:Case,mine:String=""):String {
        val sb=StringBuilder("CASE FILE (shared by everyone working on this request)\nThe owner asked: “").append(case.ownerText.replace(Regex("\\s+")," ").trim().take(300)).append("”\n")
        if(case.steps.size>1) {
            sb.append("Steps: ").append(case.steps.joinToString(" | ") {s->
                s.id+" "+s.bot+(if(s.id==mine) " (you)" else "")+" — "+s.goal.take(80)+": "+when(s.status) {DONE->"done";RUNNING->"working";WAITING->"waits for "+s.after.joinToString();else->"stopped"}
            }).append('\n')
        }
        if(case.facts.isNotEmpty()) {
            sb.append("Facts so far (with where they came from):\n")
            case.facts.takeLast(10).forEach {sb.append("- ").append(it.text.take(220)).append(" [").append(it.source).append("]\n")}
        }
        case.steps.filter {it.status==DONE && it.id!=mine && it.result.isNotBlank()}.take(3).forEach {sb.append("Finding from ").append(it.bot).append(": ").append(it.result.replace(Regex("\\s+")," ").take(300)).append('\n')}
        if(case.open.isNotEmpty()) sb.append("Still open: ").append(case.open.joinToString("; ").take(240)).append('\n')
        return sb.toString().take(1800).trimEnd()
    }

    // ---- facts, written by the phone from what the tools returned (no extra model call) ----

    private fun cell(v:Any?):String = when(v) {null,JSONObject.NULL->"";is JSONObject,is JSONArray->"";else->v.toString().replace(Regex("\\s+")," ").trim().take(60)}
    private fun rowText(row:JSONObject):String = row.keys().asSequence().filter {it !in setOf("id") && !it.endsWith("_id")}.mapNotNull {k->cell(row.opt(k)).takeIf {it.isNotBlank()}?.let {k.replace('_',' ')+" "+it}}.joinToString(", ").take(220)
    /** Facts from one tool result: records found, a colleague's answer, an invoice made. Returns nothing for tools that found nothing worth keeping. */
    fun factsFrom(bot:String,tool:String,result:JSONObject):List<CaseFact> {
        if(result.has("error")) return emptyList()
        return when(tool) {
            "data_find" -> (result.optJSONArray("matched")?.objects().orEmpty()+result.optJSONArray("related")?.objects().orEmpty()).flatMap {g->
                g.optJSONArray("rows")?.objects().orEmpty().take(3).map {r->CaseFact(g.optString("table")+": "+rowText(r),bot+" · looked up records")}
            }.filter {it.text.length>10}.take(8)
            "ask_colleague","ask_team" -> {
                val one=result.optString("answer").takeIf {it.isNotBlank()}?.let {listOf(CaseFact(it.replace(Regex("\\s+")," ").take(300),bot+" · asked "+result.optString("bot")))}.orEmpty()
                one+(result.optJSONArray("results")?.objects().orEmpty().filter {it.optString("status")=="answer"}.map {r->CaseFact(r.optString("answer").replace(Regex("\\s+")," ").take(300),bot+" · asked "+r.optString("bot"))})
            }
            "invoice_pdf" -> listOf(CaseFact((if(result.optString("number").isNotBlank()) result.optString("number")+": " else "")+"total "+result.opt("total")+" for "+result.optString("customer"),bot+" · made a document"))
            "supabase_management_call" -> runCatching {
                DataMap.rows(result).take(3).map {CaseFact("record: "+rowText(it),bot+" · database")}.filter {it.text.length>10}
            }.getOrDefault(emptyList())
            else -> emptyList()
        }
    }
    fun addFacts(case:Case,facts:List<CaseFact>):Case {
        if(facts.isEmpty()) return case
        val have=case.facts.map {it.text}.toSet()
        return case.copy(facts=(case.facts+facts.filter {it.text !in have}.distinctBy {it.text}).takeLast(24))
    }

    // ---- the one message, and its check ----

    /** Used when the lead's write-up fails or times out: the findings, plainly, with what is missing. Never silent. */
    fun plainMerge(case:Case):String {
        val done=case.steps.filter {it.status==DONE && it.result.isNotBlank()}
        val sb=StringBuilder()
        done.forEach {s->sb.append(if(done.size>1) s.bot+": " else "").append(clean(s.result)).append("\n\n")}
        val missing=case.steps.filter {it.status!=DONE || it.result.isBlank()}
        if(missing.isNotEmpty()) sb.append("Still missing: ").append(missing.joinToString("; ") {it.bot+" didn't finish"+(if(it.note.isNotBlank()) " ("+it.note.trimEnd('.').take(100)+")" else "")}).append(".")
        return sb.toString().trim().ifBlank {"I couldn't get anything back on that yet. Ask me again in a moment."}
    }
    /** A colleague's reply as plain text: no ANSWER: marker. */
    fun clean(text:String):String = TeamRules.parseAnswer(text).text.trim()

    fun synthInstructions(lead:String):String =
        "You are $lead, the lead of the owner's team. Your colleagues did the looking; you write the ONE message that answers the owner.\n"+
        "- Answer what the owner actually asked, first, with the pick or the number in it. Use only facts and numbers that are in the findings. Copy figures exactly as found; never add up, estimate or invent a new number.\n"+
        "- If something is missing or a colleague couldn't finish, say so plainly and say what is still needed. Never pretend.\n"+
        "- Never mention steps, tools, case files, record IDs, markers like ANSWER:, or bots texting each other. You may say who found something only when it helps.\n"+
        "- Style: a quick answer is one to three plain sentences. A real answer starts with \"# \" and one sentence that IS the answer, then a few short sentences or bullets. "+
        "Keep rows blocks, picture lines (![…](file)) and links from the findings exactly as they are. Finish with a followups block only if the findings had one or the owner clearly needs a next step."

    fun synthText(case:Case):String {
        val sb=StringBuilder("The owner asked: ").append(case.ownerText.replace(Regex("\\s+")," ").trim().take(900)).append("\n\nFindings:\n")
        case.steps.forEach {s->
            sb.append("[").append(s.bot).append("] asked to: ").append(s.goal.take(200)).append("\n")
            sb.append(if(s.status==DONE && s.result.isNotBlank()) clean(s.result).take(RESULT_CAP) else "(couldn't finish: "+s.note.ifBlank {"no result"}+")").append("\n\n")
        }
        if(case.facts.isNotEmpty()) {sb.append("Facts on file:\n");case.facts.takeLast(12).forEach {sb.append("- ").append(it.text.take(200)).append(" [").append(it.source).append("]\n")}}
        return sb.toString()
    }
    /** The line sent back to the lead when its write-up has numbers that aren't in the findings. */
    fun figureFix(flagged:List<String>)="These figures are not in the findings: "+flagged.joinToString(", ")+". Write the message again using only figures that appear in the findings. If the owner needs a number that isn't there, say it isn't confirmed."

    /** Every figure the merged message may use: the owner's words, the facts, the findings and anything else passed in. */
    fun figurePool(case:Case,extra:String=""):Set<String> = Figures.all(listOf(case.ownerText,case.goal,extra)+case.facts.map {it.text}+case.steps.map {it.result}+case.steps.map {it.goal})

    /** Files named in the findings that the merged message can show. */
    fun filesIn(case:Case):List<String> = case.files.distinct().take(8)
}

/**
 * The check on numbers: every figure in the lead's merged message must already be somewhere in the findings. Prices, totals, phone numbers, plate and
 * invoice numbers, years: if the lead wrote one the colleagues never reported, it is flagged, then fixed or removed before the owner sees it.
 */
object Figures {
    private val token=Regex("(?<![\\w.])(\\$\\s?)?(\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?|\\d+(?:\\.\\d+)?)(\\s?%)?")
    data class Found(val raw:String,val key:String,val checkable:Boolean)
    fun key(number:String):String {
        var n=number.replace(",","")
        if(n.contains('.')) n=n.trimEnd('0').trimEnd('.')
        val (whole,frac)=if(n.contains('.')) n.substringBefore('.') to n.substringAfter('.') else n to ""
        val w=whole.trimStart('0').ifBlank {"0"}
        return if(frac.isEmpty()) w else "$w.$frac"
    }
    fun find(text:String):List<Found> = token.findAll(text).map {m->
        val number=m.groupValues[2]
        Found(m.value.trim(),key(number),m.groupValues[1].isNotEmpty() || m.groupValues[3].isNotEmpty() || number.contains('.') || number.replace(",","").substringBefore('.').length>=3)
    }.toList()
    /** Every number in the sources, normalised ("$1,250.00" and "1250" are the same figure). */
    fun all(sources:List<String>):Set<String> = sources.flatMap {s->find(s).map {it.key}}.toSet()
    /** The checkable figures in [text] that are not in [pool], as written. */
    fun unsupported(text:String,pool:Set<String>):List<String> = find(text).filter {it.checkable && it.key !in pool}.map {it.raw}.distinct()
    /** The text without the sentences (or list lines) that contain a flagged figure. */
    fun without(text:String,flagged:List<String>):String {
        if(flagged.isEmpty()) return text
        return text.lines().mapNotNull {line->
            if(flagged.none {line.contains(it)}) line
            else if(line.trimStart().startsWith("-") || line.contains('|') || line.trimStart().startsWith("#")) null
            else line.split(Regex("(?<=[.!?])\\s+")).filter {s->flagged.none {s.contains(it)}}.joinToString(" ").ifBlank {null}
        }.joinToString("\n").replace(Regex("\n{3,}"),"\n\n").trim()
    }
}

/** What the planner is asked, and what it sees. */
object CasePlanner {
    fun instructions(bots:List<Bot>,lead:String,open:Case?):String =
        "You are the chief planner for the owner's Teamwork group chat. Turn the owner's new message into the smallest plan of steps and say which bot does each step.\n"+
        "Bots (name: job; accounts; skills):\n"+bots.joinToString("\n") {b->
            "- "+b.name+": "+b.role.ifBlank {"general assistant"}+(if(b.apps.isNotEmpty()) "; works with "+b.apps.joinToString() else "")+(if(b.skills.isNotEmpty()) "; skills: "+b.skills.joinToString().take(80) else "")
        }+"\n"+
        "Rules:\n"+
        "- Use as FEW steps as you can: one step when one bot can do it (that is almost always). Never more than "+CaseRules.MAX_STEPS+".\n"+
        "- \"bot\" must be exactly one of the names above. \"do\" says what that bot must find or make, in plain words, with the owner's details (names, numbers, places) copied in.\n"+
        "- Steps that don't need each other's results run at the same time: leave \"after\" empty. If a step needs another step's result (the car's details before searching parts for that car), put that step's id in \"after\".\n"+
        "- Finding customers, cars, invoices, estimates, appointments or payments in the shop's records belongs to the bot that works with the database. A step must never leave a bot to ask the owner for something a colleague's records already hold.\n"+
        "- Personal bots are for the owner's personal life and shop bots are for the shop: never mix them. If the message follows up on what a bot just said in the chat, use that bot. If no bot's job fits, give one step to "+lead+".\n"+
        (if(open!=null) "- An earlier request is still being worked on (below). If the new message adds to it or changes it, give steps for only the NEW work (new ids; \"after\" may name the old step ids) and set \"case\":\"amend\". If it is a separate matter set \"case\":\"new\".\n" else "")+
        "Reply with only JSON like {\"steps\":[{\"id\":\"s1\",\"bot\":\"Name\",\"do\":\"what to do\",\"after\":[]}],\"answer\":\"lead\",\"case\":\"new\"}. \"answer\" is \"merge\" when two or more steps feed one reply, otherwise \"lead\"."

    fun userText(chat:String,text:String,open:Case?):String =
        (if(chat.isNotBlank()) "Recent group chat:\n"+chat+"\n\n" else "")+
        (if(open!=null) "Request still being worked on:\n"+CaseRules.fileText(open).take(900)+"\n\n" else "")+
        "New message from the owner: "+text
}

/** The Teamwork part of every bot's instructions. One place, so the rules can't contradict each other. Short, plain, numbered: a small model follows it. */
object TeamPrompt {
    fun section(task:TaskRecord,colleagues:List<Bot>,case:Case?,directory:String,dataTools:Boolean):String {
        val sb=StringBuilder()
        val packet=TeamRules.isPacket(task.prompt)
        if(packet) {
            return "A colleague needs something from your department; their request is the message below. Answer them directly and start with exactly one of ANSWER:, NEED-INPUT:, CANNOT: or NOT-MINE:, then the exact numbers, names and dates. "+
                (if(dataTools) "Look in your records with data_find first (a name, phone, plate, VIN, email or invoice number), then your other sources. " else "Look in all your sources first. ")+
                "You cannot ask anyone else, and the owner is not in this chat."
        }
        if(colleagues.isNotEmpty()) {
            sb.append("You and the other bots are one team, like department heads who answer for their own department. Who owns what:\n").append(directory.ifBlank {"(no other bots yet)"}).append('\n')
        }
        sb.append("TEAM RULES\n")
        var n=1
        if(dataTools) sb.append(n++).append(". Look it up yourself first. Customers, cars, invoices, estimates, appointments, phone numbers: call data_find with the name, phone, plate, VIN, email or invoice number (it searches every related record at once). Never ask the owner for something the records can answer.\n")
        if(colleagues.isNotEmpty() && TeamRules.mayAsk(task)) sb.append(n++).append(". If a colleague's department has what you need and you cannot get it yourself (their records, their accounts, their skill), call ask_team (several colleagues at once is fine) with the owner's own words, what you already know and exactly what you need back, as small as you can make it. Never hand a colleague the whole request, and never ask for something your own tools do (searching the web, reading pages, making files or pages, working out numbers): do that yourself. Their answer comes back in the same call; pass their facts on exactly and say who found them. If it says need-input, answer that and ask again. If it says waiting, call ask_team again to keep waiting.\n")
        sb.append(n++).append(". Do only your own part. Never repeat what a colleague is doing or has done.\n")
        sb.append(n++).append(". Use the owner's exact numbers and the numbers you found, as found. Say what you checked. If you couldn't find something, say exactly what is missing and what you tried.\n")
        sb.append(n++).append(". Ask the owner only when nothing you or a colleague can look up has the answer, and then ask one short question. Never ask for a sign-in the owner has already done (see the signed-in websites).\n")
        if(task.team) sb.append(n++).append(". This is the Teamwork group chat. Write no \"On it\", no greeting, no small talk. Don't make a PDF or any file unless the owner asked for one. When the owner greets or talks to the whole team, reply in one short line.\n")
        if(task.team && case!=null && CaseRules.synthesizes(case) && case.steps.any {it.taskId==task.id}) sb.append(n++).append(". Your reply is a finding for the lead, who writes the one message to the owner: give the facts only (exact numbers, names, dates), no follow-up buttons.\n")
        if(TeamRules.isReplyPrompt(task.prompt)) sb.append("A colleague answered while you were waiting: carry on with the owner's request using their answer and finish it.\n")
        if(case!=null && task.team) sb.append(CaseRules.fileText(case,case.steps.firstOrNull {it.taskId==task.id}?.id.orEmpty())).append('\n')
        return sb.toString().trimEnd()
    }
}
