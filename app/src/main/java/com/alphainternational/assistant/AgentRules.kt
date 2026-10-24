package com.alphainternational.assistant

import org.json.JSONArray
import org.json.JSONObject

/**
 * The small rules that keep a job fast and honest. A job should be: answer or act, in as few rounds as possible, and always end
 * with a text. (Pure functions, so they're tested without a phone.)
 */
object AgentRules {
    /** The bookkeeping tools the model no longer sees. The phone keeps the plan and the receipts itself; the model just does the work and answers. */
    val hidden=setOf("task_plan","task_finish","task_receipts")

    /** Read-only tools that can run at the same time: they send nothing, change nothing and don't share the browser page. */
    val parallelSafe=setOf("web_fetch","youtube_transcript","file_read","files_list","files_show","studio_photos","studio_icons","media_probe","video_contact_sheet","audio_guide","memory_search","history_search","skills_list","skill_read","capabilities","tool_list","mcp_list",
        "gmail_search","gmail_drafts_search","gmail_read","gmail_read_draft","calendar_list","calendar_events","drive_list","docs_read","sheets_read",
        "tasks_lists","tasks_read","contacts_read","vercel_projects","vercel_deployments","vercel_deployment","vercel_logs","supabase_read","data_find","ask_team","ask_colleague","image_show","image_search","telnyx_calls","telnyx_messages","telnyx_lookup","telnyx_numbers")+ReachTools.parallelSafe

    /**
     * If one of these fails after it started, nobody knows whether the text went out, the event was made or the button was pressed,
     * so the job stops at "Check result". Everything else that fails simply failed, and the bot tries another way.
     */
    val unsureIfFailed=setOf("sms_send","gmail_send","gmail_draft","calendar_create","drive_create_text","docs_create","sheets_create","sheets_append",
        "tasks_create","vercel_preview","site_publish","supabase_update","supabase_management_call","supabase_sample_invoice","browser_click","browser_press_enter","mcp_call")

    /** The requested result, saved for the owner's records when the model didn't write a plan (it no longer has to). */
    fun implicitPlan(prompt:String):JSONArray=arr(listOf(obj("id" to "request","kind" to "live",
        "description" to prompt.replace(Regex("\\s+")," ").trim().take(280).ifBlank {"The owner's request"})))

    /**
     * How a job that ended with an answer is recorded. Done, unless a send or change failed and never worked later (then the answer should have said so, and
     * the record says "partial"), or the job was cut short by the time limit ([cutShort]: the chat then offers Keep going), or the finish gate found the answer
     * still claiming more than the job did ([notes], the first one becomes the summary).
     */
    fun autoFinish(receipts:List<JSONObject>,answer:String,cutShort:Boolean=false,notes:List<String> = emptyList()):JSONObject {
        val work=receipts.filter {it.optString("tool")!="web_search"}
        val failedWrites=work.filter {!it.optBoolean("success") && it.optString("tool") in unsureIfFailed}.map {it.optString("tool")}.toSet()
        val neverWorked=failedWrites.filter {tool->work.none {it.optString("tool")==tool && it.optBoolean("success")}}
        val partial=neverWorked.isNotEmpty() || (work.isNotEmpty() && work.none {it.optBoolean("success")}) || cutShort || notes.isNotEmpty()
        val first=answer.replace(Regex("\\s+")," ").trim().take(240)
        val summary=when {
            cutShort->"Ran out of time before finishing. Tap Keep going to continue."
            notes.isNotEmpty()->notes.first()
            else->first.ifBlank {if(partial) "Some steps didn't work." else "Done."}
        }
        return obj("outcome" to if(partial) "partial" else "completed","summary" to summary,"checks" to arr(emptyList<Any>())).also {if(cutShort) it.put("cut_short",true)}
    }

    /**
     * The jobs that were running when the app was stopped (by an update, a restart, or Android) and should carry on by themselves: recent chat requests that are
     * paused, newest first. Routines and old work wait for the owner; an action that may have half-happened still stops at "Check result" later.
     */
    fun resumable(tasks:List<TaskRecord>,interrupted:Collection<String>,now:Long,maxAgeMs:Long=3_600_000L):List<TaskRecord> =
        tasks.filter {it.id in interrupted && it.status=="Paused" && it.routineId.isBlank() && now-it.created<maxAgeMs}.sortedByDescending {it.created}

    /** Who runs next when a worker frees up: the owner's own texts, then hand-offs, then a long job that stepped aside for them. */
    fun queueRank(from:String)=when(from) {OWNER_QUEUE->0;YIELDED->2;else->1}

    /**
     * Everything the owner asks for runs at the same time, up to this many jobs on the phone (every bot in a group message, a private chat
     * and the group together). The limit only exists to stay inside the ChatGPT plan's rate limits; the owner never waits for a turn below it.
     */
    const val MAX_JOBS=8
    /**
     * How long a job may run before it must wrap up, and when its tools are taken away. A question needs minutes; making a website, app or game
     * (build it, look at the screenshots, fix it, look again) needs most of half an hour, and cutting it off early is what left sites unfinished and basic.
     */
    data class Budget(val wrapUpMinutes:Int,val stopMinutes:Int)
    fun budget(building:Boolean):Budget = if(building) Budget(26,34) else Budget(WRAP_UP_MINUTES,STOP_MINUTES)
    /** A job that stops on a dropped connection tries again by itself this many times, waiting longer each time (30 s, 1 min, 2 min). */
    const val MAX_RETRIES=3
    fun retryDelayMs(attempt:Int):Long=30_000L*(1L shl attempt.coerceIn(0,6))
    /**
     * A job that hit the plan's usage limit waits and tries again by itself (5, 10, 15, 20, 25, then 30 minutes), instead of sitting paused until the owner taps.
     * A job that stopped to "check in" after very many rounds carries on after a few seconds. Both give up after [MAX_AUTO_RETRIES] tries.
     */
    const val MAX_AUTO_RETRIES=6
    fun limitDelayMs(attempt:Int):Long=minOf(30,5*(attempt+1))*60_000L
    /** The plan's sharing limit holds everyone together and may last hours (the plan only says "try again after your usage limit resets"): a bot stays on hold for up to a day of 30-minute checks. */
    const val MAX_LIMIT_HOLDS=48
    const val CHECK_IN_DELAY_MS=4_000L
    /** An overloaded AI service clears in seconds to minutes: 30 s, 1, 2, 4, 8, then 10 minutes. */
    fun overloadDelayMs(attempt:Int):Long=minOf(10*60_000L,30_000L*(1L shl attempt.coerceIn(0,5)))
    /** A job cut short by its time budget that was still making progress carries on by itself this many times (about 2 hours of work in all). */
    const val MAX_AUTO_CONTINUES=6
    /** Whether a job cut short by its time budget carries on by itself: it was getting results this time, an owner's job (not an automatic one), and has not already carried on [MAX_AUTO_CONTINUES] times. */
    fun autoContinue(results:Int,alreadyContinued:Int,automaticJob:Boolean):Boolean = !automaticJob && results>=2 && alreadyContinued<MAX_AUTO_CONTINUES

    /**
     * Whether [task] may start now: nothing waits for another bot, another chat or its own bot's other work. Only a full phone makes it wait, and a
     * step of a team case waits for the steps it depends on ([held] is the set of jobs that must not start yet; see CaseRules.heldTasks).
     */
    fun canStart(task:TaskRecord,@Suppress("UNUSED_PARAMETER") tasks:List<TaskRecord>,running:Set<String>,max:Int=MAX_JOBS,held:Set<String> = emptySet()):Boolean =
        running.size<max && task.id !in held

    /** The waiting job to start next, or null: the owner's texts first, then hand-offs, then a stepped-aside job, each in the order it came. Routines run themselves. */
    fun nextToStart(tasks:List<TaskRecord>,running:Set<String>,max:Int=MAX_JOBS,held:Set<String> = emptySet()):TaskRecord? =
        tasks.filter {it.status=="Queued" && it.id !in running && it.routineId.isBlank() && canStart(it,tasks,running,max,held)}
            .minWithOrNull(compareBy({queueRank(it.from)},{it.created}))

    /** The connected service a tool needs, or null when it always works (files, memory, web, texting, documents…). */
    fun connectionFor(tool:String):String? = when {
        tool.startsWith("gmail_") -> "gmail"
        tool.startsWith("calendar_") -> "calendar"
        tool.startsWith("drive_") || tool.startsWith("docs_") || tool.startsWith("sheets_") -> "drive"
        tool.startsWith("tasks_") -> "tasks"
        tool.startsWith("contacts_") -> "contacts"
        tool.startsWith("vercel_") || tool=="site_publish" -> "vercel"
        tool.startsWith("telnyx_") -> "telnyx"
        tool.startsWith("supabase_") || tool=="data_find" -> "supabase"
        tool.startsWith("mcp_") -> "mcp"
        tool=="run_shell" || tool=="install_package" -> "computer"
        else -> null
    }

    /**
     * The tools worth sending the model this round. Unconnected services are left out (the model is told what's connected, and
     * task_need_connection still tells the owner where to connect), and the browser is left out while another bot is using it (there is one
     * browser for the whole phone; it works with Alpha in the background). Fewer tools means a smaller, faster, easier-to-follow request.
     */
    fun visibleTools(all:List<Tool>,connected:(String)->Boolean,browserFree:Boolean,extraHidden:Set<String> = emptySet()):List<Tool> = all.filter {tool->
        tool.name !in hidden && tool.name !in extraHidden &&
            (browserFree || !tool.name.startsWith("browser_")) &&
            (connectionFor(tool.name)?.let(connected) ?: true)
    }
}
