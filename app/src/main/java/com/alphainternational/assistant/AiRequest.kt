package com.alphainternational.assistant

import org.json.JSONArray
import org.json.JSONObject

/**
 * What one request to the ChatGPT service contains. Kept apart from [Ai] (which does the talking) so it can be checked without a phone or a network.
 * Every optional piece is dropped by [Ai] for the rest of the session if the service refuses it.
 */
object AiRequest {
    /** Which optional pieces to ask for: hosted search, parallel calls, the reuse key, the intelligence level, and (off by default) the model's own thinking summary. */
    data class Options(val search:Boolean=false,val parallel:Boolean=true,val cacheKey:String?=null,val effort:String?=null,val summary:Boolean=false,
        /** A plain chat with no phone tools that may still search the web (Brainstorm): the hosted search alone is sent. */
        val webOnly:Boolean=false,
        /** True for the ChatGPT plan (tools grouped in one namespace); false sends plain function tools, which any Responses-compatible server accepts. */
        val namespaced:Boolean=true)

    fun build(model:String,input:JSONArray,instructions:String,tools:List<Tool>,options:Options):JSONObject {
        val request=obj("model" to model,"input" to input,"instructions" to instructions,"store" to false,"stream" to true)
        // A request with no phone tools (like picking which bot answers) sends no tools at all.
        if(tools.isNotEmpty()) {
            val hosted=JSONArray()
            if(options.namespaced) hosted.put(obj("type" to "namespace","name" to "alpha","description" to "Tools running on the user's Android phone and authorized accounts.",
                "tools" to arr(tools.map {it.json()})))
            else tools.forEach {hosted.put(it.json())}
            if(options.search) hosted.put(obj("type" to "web_search"))
            request.put("tools",hosted).put("parallel_tool_calls",options.parallel)
        } else if(options.webOnly && options.search) request.put("tools",JSONArray().put(obj("type" to "web_search")))
        options.cacheKey?.takeIf {it.isNotBlank()}?.let {request.put("prompt_cache_key",it)}
        if(options.effort!=null || options.summary) {
            val reasoning=JSONObject()
            options.effort?.let {reasoning.put("effort",it)}
            // The model's own short summary of its thinking (for the live card), and its sealed reasoning so thinking carries across the rounds of a job.
            if(options.summary) {reasoning.put("summary","auto");request.put("include",arr(listOf("reasoning.encrypted_content")))}
            request.put("reasoning",reasoning)
        }
        return request
    }

    /** Which optional piece a refusal from the service is about, from its error text (null if it is about none of them). */
    fun refused(message:String):String? {
        val m=message.lowercase()
        return when {
            m.contains("reasoning.summary") || (m.contains("summary") && m.contains("reasoning")) -> "summary"
            m.contains("encrypted_content") || m.contains("'include'") || m.contains("\"include\"") -> "summary"
            m.contains("reasoning") || m.contains("effort") -> "effort"
            m.contains("parallel_tool_calls") -> "parallel"
            m.contains("prompt_cache_key") -> "cache"
            else -> null
        }
    }
}
