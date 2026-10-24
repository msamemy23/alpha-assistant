package com.alphainternational.assistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Where a request gets its bearer token: the ChatGPT plan sign-in, or a plain API key. */
interface TokenSource { suspend fun accessToken():String }
/** A plain API key as a token source. */
class ApiKeySource(private val key:()->String):TokenSource {
    override suspend fun accessToken():String = key().also {require(it.isNotBlank()) {"Add your API key in Settings, AI provider."}}
}

interface AiGateway {
    /** A fresh connection for one job (bots work at the same time, so they cannot share one). Gateways with no per-job state return themselves. */
    fun fork():AiGateway = this
    suspend fun respond(model:String,history:JSONArray,instructions:String,tools:List<Tool>,onText:suspend (String)->Unit):JSONObject
    fun cancel()
    /** Intelligence level (reasoning effort) for the next request; null leaves the model's default. */
    fun setEffort(effort:String?) {}
    /** Leaves the hosted web search out of the next requests (the bot keeps its own page tools). Used when the service's search itself keeps failing. */
    fun setSearchOff(off:Boolean) {}
    /** Called just before a request is sent again after a dropped connection or a busy service, so text already streamed from the failed try can be cleared. */
    fun setOnRetry(callback:(()->Unit)?) {}
    /** Where the model's own thinking summary goes while it arrives (only if the service was asked for one). */
    fun setThinkingSink(sink:(suspend (String)->Unit)?) {}
}
/**
 * Talks to the OpenAI Responses API. With the default settings it is the ChatGPT plan (sign-in token, tools grouped in a namespace); with an API key
 * ([namespaced]=false) the tools are sent flat, which every Responses-compatible server accepts.
 */
class Ai(private val auth:TokenSource,private val base:String="https://api.openai.com/v1",private val namespaced:Boolean=true):AiGateway {
    @Volatile private var active:HttpURLConnection?=null
    /** OpenAI's hosted web search depends on account/workspace policy; it is switched off for this session if refused. */
    @Volatile var webSearch=true
    @Volatile var webSearchAllowed=true
    /** Set false for the session if the plan refuses a reasoning level. */
    @Volatile var effortAccepted=true
    /** Asking for several tool calls at once, and a stable key so a long job's repeated prefix can be reused. Each is dropped for the session if the plan refuses it. */
    @Volatile var parallelAccepted=true
    @Volatile var cacheKeyAccepted=true
    @Volatile var cacheKey:String?=null
    /**
     * Ask for the model's own thinking summary (shown as the live "thinking" line) and its sealed reasoning. OFF unless switched on (the Controller reads the
     * "brain" setting); if the service refuses either, it is dropped for the session without anyone noticing.
     */
    /** A plain chat with no phone tools (Brainstorm) that may still use the hosted web search. */
    @Volatile var webOnly=false
    @Volatile var reasoningSummary=false
    @Volatile var summaryAccepted=true
    @Volatile private var effort:String?=null
    override fun setEffort(effort:String?) {this.effort=effort}
    @Volatile private var searchOff=false
    override fun setSearchOff(off:Boolean) {searchOff=off}
    @Volatile private var onRetry:(()->Unit)?=null
    override fun setOnRetry(callback:(()->Unit)?) {onRetry=callback}
    @Volatile private var thinkingSink:(suspend (String)->Unit)?=null
    override fun setThinkingSink(sink:(suspend (String)->Unit)?) {thinkingSink=sink}
    /** A fresh connection for one job, keeping what this one already learned about the plan (search, effort, parallel calls). Bots work at the same time, so they can't share one. */
    override fun fork():Ai=Ai(auth,base,namespaced).also {it.webSearch=webSearch;it.webSearchAllowed=webSearchAllowed;it.effortAccepted=effortAccepted;it.parallelAccepted=parallelAccepted;it.cacheKeyAccepted=cacheKeyAccepted
        it.reasoningSummary=reasoningSummary;it.summaryAccepted=summaryAccepted}
    override fun cancel() { active?.disconnect(); active=null }
    override suspend fun respond(model:String,history:JSONArray,instructions:String,tools:List<Tool>,onText:suspend (String)->Unit):JSONObject {
        var search=webSearch && webSearchAllowed && (tools.isNotEmpty() || webOnly) && !searchOff
        var level=effort.takeIf {effortAccepted}
        var blips=0
        while(true) {
            try { return request(model,history,instructions,tools,onText,search,level) }
            catch(e:ApiFailure) {
                val message=e.message.orEmpty()
                // A busy or briefly failing service gets two more tries, a few seconds apart, before the owner hears about it.
                if((e.code==429 || e.code in 500..504) && blips<2) {blips++;onRetry?.invoke();kotlinx.coroutines.delay(2500L*blips);continue}
                if(e.code !in 400..422) throw e
                if(reasoningSummary && summaryAccepted && AiRequest.refused(message)=="summary") summaryAccepted=false
                else if(level!=null && (message.contains("reasoning",true) || message.contains("effort",true))) {effortAccepted=false;level=null}
                else if(parallelAccepted && message.contains("parallel_tool_calls",true)) parallelAccepted=false
                else if(cacheKeyAccepted && message.contains("prompt_cache_key",true)) cacheKeyAccepted=false
                // Only an error about web search itself turns it off (a complaint about some other tool must not quietly lose the owner the good search).
                else if(search && (message.contains("web_search",true) || message.contains("web search",true))) {webSearch=false;search=false}
                else throw e
                onRetry?.invoke()
            }
            catch(e:java.io.IOException) {
                // A dropped connection (signal, Wi-Fi switch) is retried the same way; anything it had already streamed is cleared first.
                if(blips<2) {blips++;onRetry?.invoke();kotlinx.coroutines.delay(2500L*blips);continue}
                throw e
            }
        }
    }
    private suspend fun request(model:String,history:JSONArray,instructions:String,tools:List<Tool>,onText:suspend (String)->Unit,search:Boolean,effort:String?):JSONObject {
        require(model.isNotBlank()) { "Choose an available ChatGPT model in Marketplace." }
        val request=AiRequest.build(model,history,instructions,tools,AiRequest.Options(search,parallelAccepted,cacheKey?.takeIf {cacheKeyAccepted},effort,reasoningSummary && summaryAccepted,webOnly,namespaced))
        return post(request,onText)
    }
    private suspend fun post(request:JSONObject,onText:suspend (String)->Unit):JSONObject = withContext(Dispatchers.IO) {
        val connection=URL(base.trimEnd('/')+"/responses").openConnection() as HttpURLConnection
        active=connection
        connection.requestMethod="POST"; connection.doOutput=true; connection.connectTimeout=25_000; connection.readTimeout=150_000; connection.instanceFollowRedirects=false
        connection.setRequestProperty("Authorization","Bearer "+auth.accessToken()); connection.setRequestProperty("Content-Type","application/json")
        connection.setRequestProperty("Accept","text/event-stream")
        try {
            connection.outputStream.use { it.write(request.toString().toByteArray()) }
            if(connection.responseCode !in 200..299) {
                val raw=connection.errorStream?.bufferedReader()?.use { it.readText().take(1200) }.orEmpty()
                val message=runCatching { val body=JSONObject(raw);body.optJSONObject("error")?.optString("message")?.takeIf {it.isNotBlank()}
                    ?:body.optString("detail").takeIf {it.isNotBlank()}?:raw }.getOrDefault(raw)
                val requestId=connection.getHeaderField("x-request-id").orEmpty().take(160)
                val retryAfter=connection.getHeaderField("Retry-After").orEmpty().trim().toLongOrNull()
                // The plan's sharing limit says when it clears in its own words; keep every word of it in the log.
                if(connection.responseCode==429) android.util.Log.w("AlphaAi","HTTP 429: "+Rules.redact(message).take(700)+" | Retry-After="+retryAfter)
                throw ApiFailure(connection.responseCode,"ChatGPT returned HTTP "+connection.responseCode+": "+Rules.redact(message)+
                    (if(retryAfter!=null) " (retry-after "+retryAfter+"s)" else "")+
                    if(requestId.isBlank()) "" else " (request "+requestId+")")
            }
            val reader=connection.inputStream.bufferedReader()
            var data=StringBuilder()
            val stream=ResponsesStream()
            suspend fun consume() {
                if(data.isEmpty()) return
                val raw=data.toString(); data=StringBuilder()
                if(raw=="[DONE]") return
                val event=JSONObject(raw)
                val type=event.optString("type")
                val failing=type=="error" || type=="response.failed"
                val headers=if(failing) connection.headerFields.filterKeys {it!=null}.entries.associate {it.key.lowercase() to it.value.firstOrNull().orEmpty()} else emptyMap()
                // The plan's sharing limit arrives here, as a stream event. Keep every word and every limit header of it in the log: the reset time may be in either.
                if(failing) android.util.Log.w("AlphaAi","stream "+type+": "+Rules.redact(raw).take(900)+" | headers: "+headers.filterKeys {Regex("(?i)limit|codex|retry|reset|openai-").containsMatchIn(it)}.entries.joinToString(", ") {it.key+"="+it.value.take(80)})
                try { stream.accept(event)?.let {onText(it)} }
                catch(e:IllegalStateException) {
                    val hint=if(failing && e !is ModelCutOff) PlanLimit.secondsFrom(event,headers,System.currentTimeMillis()) else null
                    if(hint!=null && !e.message.orEmpty().contains("retry-after")) throw IllegalStateException(e.message+" (retry-after "+hint+"s)",e) else throw e
                }
                thinkingSink?.let {sink->stream.thinking(event)?.let {sink(it)}}
            }
            while(true) {
                currentCoroutineContext().ensureActive()
                val line=reader.readLine()?:break
                if(line.isBlank()) consume()
                else if(line.startsWith("data:")) { if(data.isNotEmpty()) data.append('\n'); data.append(line.substring(5).trimStart()) }
            }
            consume()
            android.util.Log.i("AlphaAi",stream.diagnostic())
            stream.result()
        } finally { connection.disconnect(); if(active===connection) active=null }
    }
}
