package com.alphainternational.assistant

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * The model's reply was cut off before it finished: it hit the length limit, or a tool call stopped halfway. Nothing from it was used, and the job can
 * ask again in smaller pieces (see [Recovery]). (It is an IllegalStateException like the other stream failures.)
 */
class ModelCutOff(message:String):IllegalStateException(message)

/** Keeps finished output items; nothing is returned until the response itself completes. */
internal class ResponsesStream {
    private val items=sortedMapOf<Int,JSONObject>()
    private var completed:JSONObject?=null
    private var failure:String?=null
    private val events=linkedMapOf<String,Int>()
    private val summary=StringBuilder()

    fun accept(event:JSONObject):String? {
        val type=event.optString("type")
        events[type]=(events[type]?:0)+1
        when(type) {
            "response.output_text.delta"->return event.optString("delta")
            "response.output_item.done"->{
                val index=event.getInt("output_index")
                require(index>=0) {"ChatGPT returned an invalid output item."}
                items[index]=JSONObject(event.getJSONObject("item").toString())
            }
            "response.completed"->completed=JSONObject(event.getJSONObject("response").toString())
            "response.failed","response.incomplete"->{
                val response=event.optJSONObject("response")
                val reason=response?.optJSONObject("incomplete_details")?.optString("reason").orEmpty()
                failure=response?.optJSONObject("error")?.optString("message")?.takeIf {it.isNotBlank()}
                    ?:reason.takeIf {it.isNotBlank()}
                    ?:"ChatGPT did not finish this response."
                if(type=="response.incomplete" && isLengthLimit(reason)) throw ModelCutOff("ChatGPT's reply was cut off by its length limit.")
                error(Rules.redact(failure!!))
            }
            "error"->{
                failure=event.optString("message").ifBlank {event.optJSONObject("error")?.optString("message").orEmpty()}
                    .ifBlank {"ChatGPT streaming failed."}
                error(Rules.redact(failure!!))
            }
        }
        return null
    }

    /**
     * The model's own short summary of what it is thinking about, when the service sends one (only if asked for): the latest line of it,
     * for the live card. Null for every other event.
     */
    fun thinking(event:JSONObject):String? {
        when(event.optString("type")) {
            "response.reasoning_summary_part.added"->if(summary.isNotEmpty()) summary.append("\n\n")
            "response.reasoning_summary_text.delta"->summary.append(event.optString("delta"))
            else->return null
        }
        return Thinking.line(summary.toString()).ifBlank {null}
    }

    fun result():JSONObject {
        check(failure==null) {Rules.redact(failure.orEmpty())}
        val response=completed?:error("The connection ended before ChatGPT confirmed completion. You can resume this task.")
        val status=response.optString("status")
        if(status=="incomplete" && isLengthLimit(response.optJSONObject("incomplete_details")?.optString("reason").orEmpty())) throw ModelCutOff("ChatGPT's reply was cut off by its length limit.")
        check(status !in setOf("failed","incomplete","in_progress")) {"ChatGPT did not finish this response."}
        val output=response.optJSONArray("output")?:JSONArray()
        val combined=items.toSortedMap()
        for(index in 0 until output.length()) {
            val item=output.getJSONObject(index)
            val id=item.optString("id")
            val matching=combined.entries.firstOrNull {id.isNotBlank() && it.value.optString("id")==id}?.key
            if(matching!=null) combined[matching]=item
            else if(combined[index]==null || items[index]?.optString("id").isNullOrBlank()) combined[index]=item
            else combined[(combined.keys.maxOrNull()?:-1)+1]=item
        }
        val merged=JSONArray()
        combined.values.forEach { item->
            if(item.optString("type")=="function_call") {
                // Blank details mean the action was cut off mid-stream, so it never runs (and the job asks again in smaller pieces).
                if(!(item.optString("call_id").isNotBlank() && item.optString("name").isNotBlank()
                    && item.optString("arguments").isNotBlank() && item.optString("status") !in setOf("in_progress","incomplete")))
                    throw ModelCutOff("ChatGPT returned an unfinished action. No action was run; you can resume this task.")
                // A tool that takes no details sometimes arrives with "null" instead of {}; that's the same as no details.
                item.put("arguments",safeArguments(item.optString("arguments")))
            }
            merged.put(JSONObject(item.toString()))
        }
        return JSONObject(response.toString()).put("output",merged)
    }

    companion object {
        /** An action's details as a JSON object: "null" or blank means none; anything else must be a complete JSON object. */
        fun arguments(raw:String):String {
            val text=raw.trim()
            if(text.isEmpty() || text=="null") return "{}"
            return JSONObject(text).toString()
        }
        /** Key a tool call's details carry when they were not a valid JSON object; the job tells the model and it sends them again. */
        const val INVALID="_invalid_arguments"
        /**
         * Like [arguments], but details that aren't a valid JSON object don't throw: they become {"_invalid_arguments": "<the start of what was sent>"},
         * which is still valid JSON for the history and tells the job to send an error back to the model instead of stopping.
         */
        fun safeArguments(raw:String):String = try {arguments(raw)} catch(e:JSONException) {obj(INVALID to raw.trim().take(300)).toString()}
        /** Reasons a reply stops early because of its length. */
        fun isLengthLimit(reason:String)=reason.lowercase() in setOf("max_output_tokens","max_tokens","length","max_output_length")
    }

    fun diagnostic():String = "events="+events.entries.joinToString(",") {it.key+":"+it.value}+
        " done_items="+items.size+" terminal_items="+(completed?.optJSONArray("output")?.length()?:0)+
        " types="+items.values.map {it.optString("type")}.joinToString(",")
}

/** The model's thinking summary, cut down to the one short line that goes on the live card. */
object Thinking {
    /** The newest bold heading of the summary ("**Checking prices**"), or else the newest short sentence; at most 90 characters; no markup. */
    fun line(summary:String):String {
        val text=summary.trim()
        if(text.isEmpty()) return ""
        val headings=Regex("\\*\\*([^*\\n]{3,80})\\*\\*").findAll(text).map {it.groupValues[1].trim()}.toList()
        if(headings.isNotEmpty()) return headings.last().take(90)
        val last=text.split(Regex("\n\\s*\n")).last().replace(Regex("\\s+")," ").trim()
        val sentence=Regex("[^.!?]{12,}[.!?]?").findAll(last).map {it.value.trim()}.lastOrNull()?:last
        return sentence.take(90)
    }
}
