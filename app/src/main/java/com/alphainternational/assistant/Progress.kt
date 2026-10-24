package com.alphainternational.assistant

import org.json.JSONArray
import org.json.JSONObject

/** Where a job is in its plan: step [current] of [total], with [line] saying what that step is (blank when only the count is known). */
data class StepProgress(val current:Int,val total:Int,val line:String="")

/**
 * The one place that says "how far along is this job". Today it reads the job's saved list of requested results; the brain part can point it at
 * its own step list instead without touching any screen. Null means "no plan" (a quick chat), and the screens just show the latest step.
 */
fun Controller.jobProgress(taskId:String):StepProgress? {
    val task=tasks.firstOrNull {it.id==taskId}?:return null
    return ProgressNote.fromPlan(task.plan,task.completion)
}

object ProgressNote {
    /** The text of the ongoing notification and of the Home status strip. */
    data class Note(val title:String,val text:String,
        /** The job the Stop button cancels. */
        val stopId:String,
        /** A job that waits for the owner (its approval card or its question); blank when none. */
        val waitingId:String="",val waitingIsReview:Boolean=false,val waitingBot:String="")

    private fun clip(text:String,max:Int)=if(text.length<=max) text else text.take(max-1).trimEnd()+"…"

    /** Step lines from the job's saved plan: the first unfinished result is the current step. Needs at least two results, or it isn't a plan worth counting. */
    fun fromPlan(plan:String,completion:String):StepProgress? {
        val rows=runCatching {JSONArray(plan).let {a->(0 until a.length()).mapNotNull {a.optJSONObject(it)}}}.getOrDefault(emptyList())
        if(rows.size<2) return null
        val passed=runCatching {JSONObject(completion).optJSONArray("checks")?.let {a->(0 until a.length()).mapNotNull {a.optJSONObject(it)}}.orEmpty()
            .filter {it.optString("status")=="passed"}.map {it.optString("id")}.toSet()}.getOrDefault(emptySet())
        val open=rows.firstOrNull {it.optString("id") !in passed}
        val done=rows.count {it.optString("id") in passed}
        return StepProgress((done+1).coerceAtMost(rows.size),rows.size,open?.optString("description").orEmpty().take(80))
    }

    /** "Step 3 of 6: Reading ebay.com" when the job has a plan, otherwise just the latest step in plain words. */
    fun line(progress:StepProgress?,lastStep:String,detail:String):String {
        val step=lastStep.trim().ifBlank {if(detail.isBlank()) "Getting started" else WorkWords.plain(detail)}
        val text=if(progress!=null && progress.total>=2) "Step ${progress.current} of ${progress.total}: "+progress.line.ifBlank {step} else step
        return clip(text,90)
    }

    /**
     * What the ongoing notification says. [active] are the jobs the working notice protects. One job: "Alpha is working" and its step;
     * several: how many, and the newest one's step. Jobs that wait for the owner are mentioned, and the notice offers to open them.
     * Null when nothing is running (the notice then keeps its plain text).
     */
    fun build(tasks:List<TaskRecord>,active:Set<String>,progressOf:(String)->StepProgress?):Note? {
        val running=tasks.filter {it.id in active && it.status=="Running" && !it.from.startsWith("away")}.sortedByDescending {it.created}
        val primary=running.firstOrNull()?:return null
        val waiting=tasks.filter {it.status in Words.attention && !it.from.startsWith("away")}
        val bots=running.map {it.workspace}.distinct()
        val title=if(bots.size==1) bots[0]+" is working" else if(running.size==1) primary.workspace+" is working" else "${running.size} jobs are running"
        val step=line(progressOf(primary.id),primary.steps.lastOrNull().orEmpty(),primary.detail)
        val text=(if(running.size>1) primary.workspace+": " else "")+step+(if(waiting.isEmpty()) "" else " · "+waiting.size+" waiting for you")
        val wait=waiting.firstOrNull {it.status=="Needs review"}?:waiting.firstOrNull()
        return Note(title,clip(text,140),primary.id,wait?.id.orEmpty(),wait?.status=="Needs review",wait?.workspace.orEmpty())
    }
}

/** The words a bot is writing right now, tidied for the chat. Pure, so the rules are tested without a phone. */
object LiveText {
    /** The streamed text without the follow-up buttons block (even when it is only half written) and without a half-typed code fence at the end. */
    fun clean(raw:String):String {
        val text=ChatMarkdown.withoutFollowups(raw)
        val lines=text.lines()
        val last=lines.lastOrNull()?.trim().orEmpty()
        // Only an opening fence that has just begun (an odd number of fences so far) can be the start of the buttons block; a closing fence stays.
        if(last.startsWith("```") && lines.count {it.trim().startsWith("```")}%2==1) {
            val word=last.removePrefix("```").trim().lowercase()
            if(word.isEmpty() || "followups".startsWith(word) || "follow-ups".startsWith(word)) return lines.dropLast(1).joinToString("\n").trimEnd()
        }
        return text.trimEnd()
    }

    private fun norm(text:String)=text.replace(Regex("[*_`#>|\\s]+")," ").trim()

    /**
     * True when the finished text is already in the chat, so the growing bubble must go: the two start the same way. This keeps the live bubble
     * and the final message from showing at once (a flicker) when the answer lands a moment before the job is marked done.
     */
    fun superseded(live:String,posted:String):Boolean {
        val a=norm(live);val b=norm(posted)
        if(a.isBlank() || b.isBlank()) return false
        val n=minOf(a.length,b.length,60)
        return b.startsWith(a.take(n)) || a.startsWith(b.take(n))
    }

    /** Whether a growing bubble should be drawn: there is text and it isn't already posted. */
    fun show(live:String,lastPosted:String?):Boolean {
        val text=clean(live)
        if(text.isBlank()) return false
        return lastPosted==null || !superseded(text,lastPosted)
    }
}
