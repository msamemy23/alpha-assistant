package com.alphainternational.assistant

import org.json.JSONArray
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * How long a job may run. A question gets a few minutes, but a job that is still bringing in new results (pages read, files made, answers from
 * tools) is working, not stuck, so its deadline keeps moving: it must wrap up a couple of minutes after the last new result, up to a ceiling.
 * A job that is cut off by time says so honestly (see [Verify.CUT_SHORT_LINE]) and can be continued with Keep going.
 */
object JobBudget {
    /** The longest a job that is not building a site may run, however well it is going (minutes). */
    const val CAP_WRAP_UP=22
    const val CAP_STOP=25
    /** After the last new result, how many minutes may pass before the job is told to wrap up, and before its tools are taken away. */
    const val GRACE_WRAP_UP=2.0
    const val GRACE_STOP=5.0

    /**
     * The limits for a job right now. [lastResultAt] is when the newest result arrived. A site, app or game build keeps the long fixed budget it
     * always had (it is steady work: make, look, fix, look).
     */
    fun limits(base:AgentRules.Budget,building:Boolean,startedAt:Long,lastResultAt:Long):AgentRules.Budget {
        if(building) return base
        val last=max(0.0,(lastResultAt-startedAt)/60_000.0)
        val wrap=min(CAP_WRAP_UP.toDouble(),max(base.wrapUpMinutes.toDouble(),last+GRACE_WRAP_UP))
        val stop=min(CAP_STOP.toDouble(),max(base.stopMinutes.toDouble(),last+GRACE_STOP))
        return AgentRules.Budget(ceil(wrap).toInt(),ceil(stop).toInt())
    }

    /**
     * A colleague asked for one thing gets a short budget: the bot that asked waits for it (five minutes at most per wait), and the owner waits behind both.
     * On the live phone a colleague asked for "prices at a second store" worked for 17 minutes and 14 rounds. At the end it answers with what it found
     * and says what is missing, which is what the asker needs to carry on.
     */
    const val HELPER_WRAP_UP=6
    const val HELPER_STOP=8
    fun forHelper(limits:AgentRules.Budget)=AgentRules.Budget(min(limits.wrapUpMinutes,HELPER_WRAP_UP),min(limits.stopMinutes,HELPER_STOP))

    /**
     * How many results a job has gathered: tool results that did not fail, and the model's own web searches. When this number goes up, the job
     * is making progress.
     */
    fun results(history:JSONArray):Int {
        var n=0
        val names=HashMap<String,String>()
        for(i in 0 until history.length()) {
            val item=history.optJSONObject(i)?:continue
            when(item.optString("type")) {
                "web_search_call"->if(item.optString("status")=="completed") n++
                "function_call"->names[item.optString("call_id")]=item.optString("name").substringAfterLast('.')
                "function_call_output"->if(names[item.optString("call_id")] !in bookkeeping && !failed(item.optString("output"))) n++
            }
        }
        return n
    }
    /** Tools that only keep the job's own books or list things: calling them again and again is not progress. */
    internal val bookkeeping=setOf("plan_set","plan_update","tools_enable","capabilities","skills_list","task_receipts","tool_list","routine_list","files_list")

    /** A tool result that says it failed (the output is JSON with an "error", or "success":false). */
    fun failed(output:String):Boolean {
        val o=runCatching {org.json.JSONObject(output)}.getOrNull()?:return false
        return o.has("error") || o.optBoolean("isError") || (o.has("success") && !o.optBoolean("success"))
    }
}
