package com.alphainternational.assistant

import org.json.JSONArray
import org.json.JSONObject

/** An answer is not a completion signal. Completion references actual persisted tool receipts. */
object TaskProtocol {
    val controlTools=setOf("task_plan","task_finish","task_receipts","task_need_connection","task_need_user","capabilities","skills_list","skill_read","plan_set","plan_update","tools_enable")
    fun plan(raw:String):JSONArray {
        val rows=JSONArray(raw).objects()
        require(rows.size in 1..16) {"List 1 to 16 requested results."}
        require(rows.map {it.getString("id")}.distinct().size==rows.size) {"Each requested result needs a unique id."}
        rows.forEach {row->
            require(row.getString("id").matches(Regex("[a-zA-Z0-9_-]{1,40}")))
            require(row.getString("description").isNotBlank() && row.getString("description").length<=300)
            require(row.getString("kind") in setOf("live","answer")) {"Use live for work needing current sources, files or account actions; answer for explanation."}
        }
        return arr(rows.map {obj("id" to it.getString("id"),"description" to it.getString("description"),"kind" to it.getString("kind"))})
    }
    fun finish(task:TaskRecord,outcome:String,summary:String,raw:String):JSONObject {
        require(outcome in setOf("completed","partial","blocked"))
        require(summary.isNotBlank() && summary.length<=2000)
        val required=JSONArray(task.plan).objects();require(required.isNotEmpty()) {"Save a task_plan before reporting its result."}
        val checks=JSONArray(raw).objects()
        require(checks.size==required.size && checks.map {it.getString("id")}.toSet()==required.map {it.getString("id")}.toSet()) {"Report each original requested result once. Do not drop unfinished requirements."}
        require(checks.map {it.getString("id")}.distinct().size==checks.size)
        val receipts=receipts(JSONArray(task.history)).associateBy {it.getString("call_id")}
        checks.forEach {check->
            require(check.getString("status") in setOf("passed","blocked","unfinished"))
            val ids=check.getJSONArray("evidence").strings()
            require(ids.size<=12 && ids.distinct().size==ids.size)
            ids.forEach {id->require(receipts[id]?.optBoolean("success")==true) {"Evidence must reference a successful tool call from this task: $id"}}
            if(check.getString("status")=="passed" && required.first {it.getString("id")==check.getString("id")}.getString("kind")=="live")
                require(ids.isNotEmpty()) {"Live work needs a tool receipt. A written claim is not proof."}
        }
        if(outcome=="completed")require(checks.all {it.getString("status")=="passed"}) {"Unfinished work cannot be marked completed."}
        if(outcome=="completed")verifyDrafts(task,receipts.values.toList())
        return obj("outcome" to outcome,"summary" to summary,"checks" to arr(checks.map {obj("id" to it.getString("id"),"status" to it.getString("status"),"evidence" to it.getJSONArray("evidence"))}))
    }
    private fun verifyDrafts(task:TaskRecord,receipts:List<JSONObject>) {
        val calls=JSONArray(task.history).objects().filter {it.optString("type")=="function_call"}.associateBy {it.getString("call_id")}
        val reads=receipts.filter {it.getString("tool")=="gmail_read_draft" && it.getBoolean("success")}.map {it.getJSONObject("result")}
        receipts.filter {it.getString("tool")=="gmail_draft" && it.getBoolean("success")}.forEach {receipt->
            val args=JSONObject(calls.getValue(receipt.getString("call_id")).getString("arguments"))
            val id=receipt.getJSONObject("result").getString("id")
            require(reads.any {it.optString("id")==id && it.optBoolean("draft") && it.optString("to").equals(args.getString("to"),true) &&
                it.optString("subject")==args.getString("subject") && it.optString("body").trimEnd()==args.getString("body").trimEnd()}) {
                "Read back the Gmail draft id, recipient, subject, body and DRAFT label before reporting completion. Do not create another draft."
            }
        }
    }
    fun receipts(history:JSONArray):List<JSONObject> {
        val calls=history.objects().filter {it.optString("type")=="function_call"}.associateBy {it.optString("call_id")}
        // OpenAI's hosted web search runs server-side; its completed call item is the evidence.
        val searches=history.objects().filter {it.optString("type")=="web_search_call" && it.optString("id").isNotBlank()}.map {
            obj("call_id" to it.getString("id"),"tool" to "web_search","success" to (it.optString("status")=="completed"),
                "result" to obj("action" to (it.optJSONObject("action")?:JSONObject()),"status" to it.optString("status")))
        }
        return searches+history.objects().filter {it.optString("type")=="function_call_output"}.mapNotNull {output->
            val call=calls[output.optString("call_id")]?:return@mapNotNull null
            val tool=call.getString("name").substringAfterLast('.')
            if(tool in controlTools)return@mapNotNull null
            val result=runCatching {JSONObject(output.getString("output"))}.getOrNull()
            // A model slip (a tool that does not exist, details that were not valid) did nothing, so it is neither a result nor a failure of the job.
            if(result?.optBoolean("slip")==true) return@mapNotNull null
            obj("call_id" to output.getString("call_id"),"tool" to tool,"success" to successful(result),
                "result" to (result?:JSONObject().put("error","Unreadable receipt")))
        }
    }
    /** Tools that only look at what is already there: a list of the folder is not a file the job made. */
    private val onlyLooks=setOf("files_list","file_read","file_view","history_search","memory_search","web_fetch","browser_read","browser_open","apps_list","screen_read","screen_look")
    /**
     * The files a job made, from its receipts: what a tool says it made ("files", or a verified "name"). A listing of the bot's folder is left out:
     * it used to count, so an answer that merely looked in the folder came with every old picture and file in it (a screenshot of the owner's
     * Telnyx page under a stock-index answer).
     */
    fun madeFileNames(receipts:List<JSONObject>):List<String> = receipts.filter {it.optBoolean("success") && it.optString("tool") !in onlyLooks}.flatMap {receipt->
        val result=receipt.optJSONObject("result")?:return@flatMap emptyList<String>()
        result.optJSONArray("files")?.strings().orEmpty()+if(result.optBoolean("verified") && result.has("name")) listOf(result.getString("name")) else emptyList()
    }.distinct()
    internal fun successful(result:JSONObject?)=result!=null && !result.has("error") && !result.optBoolean("isError") && !result.optBoolean("partial") && result.optBoolean("success",true)
    fun withReceipt(call:JSONObject,raw:String):String {
        val bounded=boundedOutput(raw)
        val name=call.getString("name").substringAfterLast('.')
        if(name in controlTools)return bounded
        val result=runCatching {JSONObject(bounded)}.getOrElse {obj("error" to "Unreadable tool result")}
        // Responses does not guarantee the model can see function_call_output.call_id. Put the host-owned id in its content.
        result.put("_receipt",obj("id" to call.getString("call_id"),"tool" to name,"success" to successful(result)))
        return result.toString()
    }
    fun receiptIndex(history:JSONArray)=obj("receipts" to arr(receipts(history).map {
        obj("id" to it.getString("call_id"),"tool" to it.getString("tool"),"success" to it.getBoolean("success"))
    }),"instruction" to "Use successful receipt ids as task_finish evidence. Failed or incomplete results do not prove completion. This index does not run actions again.")
    /** Web search citations arrive as annotations; list them under the answer as tappable links. */
    fun sources(parts:List<JSONObject>,answer:String):String {
        val links=parts.flatMap {it.optJSONArray("annotations")?.objects().orEmpty()}.filter {it.optString("type")=="url_citation"}
            .mapNotNull {a->
                val url=a.optString("url").trim().replace(Regex("[?&]utm_source=openai$"),"")
                if(!url.startsWith("https://") || answer.contains(url)) null else url to a.optString("title").ifBlank {runCatching {java.net.URI(url).host}.getOrNull().orEmpty()}
            }.distinctBy {it.first}.take(6)
        if(links.isEmpty()) return ""
        return "\n\n**Sources**\n"+links.joinToString("\n") {(url,title)->"- ["+title.replace("[","(").replace("]",")").take(90)+"]("+url+")"}
    }
    fun boundedOutput(raw:String,limit:Int=40000):String {
        if(raw.length<=limit)return raw
        // Keep valid JSON; expose omitted data rather than silently cutting a JSON object in half.
        return obj("partial" to true,"total_characters" to raw.length,"preview" to raw.take(limit-1000),
            "instruction" to "This tool result is incomplete. Narrow the query or read a page/range/file chunk; do not claim omitted content was checked.").toString()
    }
    private fun madeFiles(output:JSONObject?)=output!=null && (output.has("files") || (output.optBoolean("verified") && output.has("name")))
    /**
     * Keeps a long request inside the model's memory: once the history passes [limit] characters, older tool results
     * (all but the newest [keep]) shrink to their receipt and success, with a note to read again if needed.
     * With a [stride] above 1 the number kept grows by one for each new result and drops back every [stride] results, so the same old results stay
     * shortened for several rounds in a row: the start of the request stays the same, and the AI service can keep reusing it.
     */
    fun compact(history:JSONArray,limit:Int=160_000,keep:Int=8,stride:Int=1):JSONArray {
        if(history.toString().length<=limit) return history
        val outputs=history.objects().withIndex().filter {it.value.optString("type")=="function_call_output"}.map {it.index}
        val shrink=outputs.dropLast(keep+if(stride>1) outputs.size%stride else 0).toSet()
        val out=JSONArray()
        history.objects().forEachIndexed {i,item->
            if(i !in shrink) {out.put(item);return@forEachIndexed}
            val result=runCatching {JSONObject(item.optString("output"))}.getOrNull()
            if(result==null || madeFiles(result) || item.optString("output").length<600) {out.put(item);return@forEachIndexed}
            val small=JSONObject().put("trimmed","Older result shortened to save space. Read it again if you need the details.")
            listOf("_receipt","success","url","title","name").forEach {key->if(result.has(key)) small.put(key,result.get(key))}
            out.put(JSONObject(item.toString()).put("output",small.toString()))
        }
        return out
    }
    /** For old finished requests: only the results that made files (and their calls), so chat attachments still open. */
    fun slim(history:String):String {
        val items=runCatching {JSONArray(history)}.getOrElse {return "[]"}.objects()
        val kept=items.filter {it.optString("type")=="function_call_output" && madeFiles(runCatching {JSONObject(it.optString("output"))}.getOrNull())}
        val ids=kept.map {it.optString("call_id")}.toSet()
        return arr(items.filter {(it.optString("type")=="function_call" && it.optString("call_id") in ids) || it in kept}).toString()
    }
    /** A stopped request explained like a person would, never with programmer error text. */
    /**
     * A snag that a few more seconds can fix (the phone lost its connection, the AI service hiccuped), as opposed to a limit or a real problem.
     * Jobs that stop for one of these try again by themselves before the owner is ever asked to tap anything.
     */
    fun transient(detail:String):Boolean {
        val d=detail.lowercase()
        if(d.contains("usage limit") || d.contains("usage_limit") || d.contains("429") || d.contains("taking me a while")) return false
        return serviceGlitch(detail) || listOf("http 5","unavailable","timeout","timed out","unable to resolve","connection","network","unexpected end of stream","ssl","reset by peer","broken pipe","failed to connect","eof").any {d.contains(it)}
    }
    /**
     * The AI service's own catch-all failure ("An error occurred while processing your request ... include the request ID req_..."). Nothing is wrong
     * with the phone or the request's format; asking a little differently (no hosted web search, a lower intelligence level, another model) often gets through.
     */
    fun serviceGlitch(detail:String):Boolean {
        val d=detail.lowercase()
        if(d.contains("usage limit") || d.contains("usage_limit") || d.contains("429")) return false
        return d.contains("error occurred while processing your request") || d.contains("currently overloaded") || d.contains("server_error") || d.contains("had an error while processing") ||
            d.contains("you can retry your request")
    }
    /** The plan's usage limit was hit (a 429): a wait of some minutes fixes it, so the job carries on by itself instead of waiting for a tap. */
    fun isUsageLimit(detail:String):Boolean = detail.lowercase().let {it.contains("usage limit") || it.contains("usage_limit") || it.contains("429")}
    /** The job stopped itself after very many rounds to "check in": nobody has to answer, it simply carries on. */
    fun isCheckIn(detail:String):Boolean = detail.lowercase().contains("taking me a while")
    /** ChatGPT says its servers are overloaded ("Our servers are currently overloaded. Please try again later."): on 2026-10-14 this paused a live job twice and waited for a tap. */
    fun isOverloaded(detail:String):Boolean = detail.lowercase().let {it.contains("overloaded") || it.contains("try again later") || it.contains("at capacity")}
    fun plainSnag(detail:String):String {
        val d=detail.lowercase()
        return when {
            serviceGlitch(detail)->"ChatGPT had a problem on its side and couldn't finish this one."
            d.contains("cut off by its length limit") || d.contains("unfinished action")->"my replies kept getting cut off for being too long."
            d.contains("taking me a while")->"this is taking me a while, so I paused to check in."
            d.contains("usage limit") || d.contains("usage_limit") || d.contains("429")->"I've hit the ChatGPT usage limit for now."
            d.contains("overloaded") || d.contains("try again later") || d.contains("at capacity")->"ChatGPT's servers were overloaded."
            d.contains("http 5") || d.contains("unavailable") || d.contains("timeout") || d.contains("timed out") || d.contains("unable to resolve") || d.contains("connection")->
                "I couldn't reach ChatGPT just now (connection trouble)."
            d.contains("org.json") || d.contains("exception") || d.contains("null") || d.contains("kotlin.") || d.contains("java.")->"something went wrong on my end."
            else->"I got stuck: "+detail.trim().trimEnd('.').take(200)+"."
        }
    }
}
