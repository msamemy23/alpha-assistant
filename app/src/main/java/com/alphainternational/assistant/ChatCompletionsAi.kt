package com.alphainternational.assistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * The OpenAI "Chat Completions" format that almost every other AI service speaks (OpenRouter, Ollama, LM Studio, Groq, Together, DeepSeek, Gemini's and
 * Anthropic's compatible endpoints, and many more), translated to and from the Responses-style items the rest of Alpha works in. Kept apart from the network
 * code so it can be checked without a phone.
 */
object ChatCompletions {
    /** Responses-style history items to chat messages: the instructions first, a model's tool calls grouped into one assistant message, each result a tool message. */
    fun messages(instructions:String,history:JSONArray):JSONArray {
        val out=JSONArray()
        if(instructions.isNotBlank()) out.put(obj("role" to "system","content" to instructions))
        val calls=JSONArray()
        var text=StringBuilder()
        fun flush() {
            if(calls.length()==0 && text.isEmpty()) return
            val message=obj("role" to "assistant","content" to if(text.isEmpty()) JSONObject.NULL else text.toString())
            if(calls.length()>0) message.put("tool_calls",JSONArray(calls.toString()))
            out.put(message)
            while(calls.length()>0) calls.remove(0)
            text=StringBuilder()
        }
        history.objects().forEach {item->
            when(item.optString("type")) {
                "function_call"-> calls.put(obj("id" to item.optString("call_id"),"type" to "function",
                    "function" to obj("name" to item.optString("name").substringAfterLast('.'),"arguments" to item.optString("arguments").ifBlank {"{}"})))
                "function_call_output"-> {flush();out.put(obj("role" to "tool","tool_call_id" to item.optString("call_id"),"content" to item.optString("output")))}
                "message"-> if(item.optString("role")=="assistant") {
                    // An answer the model wrote earlier. Text before tool calls and text after them are separate turns.
                    if(calls.length()>0) flush()
                    val said=outputText(item)
                    if(said.isNotBlank()) text.append(said)
                } else {flush();contentMessage(roleOf(item.optString("role")),item.opt("content"))?.let {out.put(it)}}
                "reasoning","web_search_call"->Unit
                ""-> {flush();contentMessage(roleOf(item.optString("role")),item.opt("content"))?.let {out.put(it)}}
                else->Unit
            }
        }
        flush()
        return out
    }

    private fun roleOf(role:String)=when(role) {"developer","system"->"system";"assistant"->"assistant";else->"user"}

    private fun outputText(item:JSONObject):String {
        val content=item.opt("content")
        if(content is String) return content
        return (content as? JSONArray)?.objects().orEmpty().filter {it.optString("type") in setOf("output_text","text")}.joinToString("") {it.optString("text")}
    }

    /** A user or system message: plain text stays text; pictures become image_url parts for models that can see. */
    private fun contentMessage(role:String,content:Any?):JSONObject? = when(content) {
        is String->if(content.isBlank()) null else obj("role" to role,"content" to content)
        is JSONArray-> {
            val parts=JSONArray()
            content.objects().forEach {part->
                when(part.optString("type")) {
                    "input_text","text","output_text"->if(part.optString("text").isNotBlank()) parts.put(obj("type" to "text","text" to part.optString("text")))
                    "input_image"->part.optString("image_url").takeIf {it.isNotBlank()}?.let {parts.put(obj("type" to "image_url","image_url" to obj("url" to it)))}
                }
            }
            if(parts.length()==0) null
            // A message that is only text goes as one string (the most widely accepted form); a system message cannot carry pictures.
            else if((0 until parts.length()).all {parts.getJSONObject(it).optString("type")=="text"} || role=="system")
                obj("role" to role,"content" to (0 until parts.length()).mapNotNull {parts.getJSONObject(it).optString("text").takeIf {t->t.isNotBlank()}}.joinToString("\n"))
            else obj("role" to role,"content" to parts)
        }
        else->null
    }

    /** Tools as plain functions. */
    fun tools(tools:List<Tool>):JSONArray = JSONArray().also {a->
        tools.forEach {t->a.put(obj("type" to "function","function" to obj("name" to t.name,"description" to t.description,"parameters" to t.parameters)))}
    }

    /** The request body for one turn. */
    fun request(model:String,history:JSONArray,instructions:String,tools:List<Tool>):JSONObject {
        // include_usage asks for the token counts in the last chunk (the usage meter); services that do not know the option ignore it.
        val request=obj("model" to model,"messages" to messages(instructions,history),"stream" to true,"stream_options" to obj("include_usage" to true))
        if(tools.isNotEmpty()) request.put("tools",tools(tools))
        return request
    }

    /** Collects the streamed pieces of one reply: the words, and each tool call's name and details, which arrive in fragments. */
    class Collector {
        private val words=StringBuilder()
        private class Call(var id:String="",var name:String="",val arguments:StringBuilder=StringBuilder())
        private val calls=sortedMapOf<Int,Call>()
        var finish:String=""
        var failure:String=""
        private var usage:JSONObject?=null
        /** Feeds one `data:` payload; returns the new words in it, if any. */
        fun accept(event:JSONObject):String? {
            event.optJSONObject("error")?.let {failure=it.optString("message").ifBlank {it.toString().take(300)}}
            event.optJSONObject("usage")?.let {usage=it}
            val choice=event.optJSONArray("choices")?.optJSONObject(0)?:return null
            choice.optString("finish_reason").takeIf {it.isNotBlank() && it!="null"}?.let {finish=it}
            val delta=choice.optJSONObject("delta")?:return null
            delta.optJSONArray("tool_calls")?.objects()?.forEach {t->
                val call=calls.getOrPut(t.optInt("index",calls.size)) {Call()}
                t.optString("id").takeIf {it.isNotBlank()}?.let {call.id=it}
                t.optJSONObject("function")?.let {f->
                    f.optString("name").takeIf {it.isNotBlank()}?.let {call.name=it}
                    f.optString("arguments").takeIf {it.isNotBlank() && it!="null"}?.let {call.arguments.append(it)}
                }
            }
            val said=delta.optString("content").takeIf {it.isNotEmpty() && it!="null"}
            if(said!=null) words.append(said)
            return said
        }
        /** The finished reply in the shape the rest of Alpha reads (a Responses-style output list). */
        fun result():JSONObject {
            check(failure.isBlank()) {Rules.redact(failure)}
            if(finish=="length") throw ModelCutOff("The AI service cut its reply off by its length limit.")
            val output=JSONArray()
            if(words.isNotBlank()) output.put(obj("type" to "message","role" to "assistant","status" to "completed",
                "content" to arr(listOf(obj("type" to "output_text","text" to words.toString())))))
            calls.entries.forEachIndexed {i,(_,call)->
                if(call.name.isBlank()) throw ModelCutOff("The AI service returned an unfinished action. No action was run; you can resume this task.")
                output.put(obj("type" to "function_call","status" to "completed","call_id" to call.id.ifBlank {"call_"+System.nanoTime()+"_"+i},"name" to call.name,
                    "arguments" to ResponsesStream.safeArguments(call.arguments.toString())))
            }
            // Token counts, renamed to the Responses names the rest of Alpha reads (recordUsage also understands the original names).
            val counts=usage?.let {u->obj("input_tokens" to u.optInt("prompt_tokens"),"output_tokens" to u.optInt("completion_tokens"))}
            return obj("status" to "completed","output" to output).also {r->counts?.let {r.put("usage",it)}}
        }
    }
}

/**
 * Talks to any service that speaks Chat Completions at [baseUrl] (for example https://openrouter.ai/api/v1). No hosted web search and no reasoning-level
 * setting: the bots keep their own page tools, and the model uses its own default thinking.
 */
class ChatCompletionsAi(private val baseUrl:String,private val apiKey:()->String):AiGateway {
    @Volatile private var active:HttpURLConnection?=null
    @Volatile private var onRetry:(()->Unit)?=null
    override fun setOnRetry(callback:(()->Unit)?) {onRetry=callback}
    override fun fork():AiGateway=ChatCompletionsAi(baseUrl,apiKey)
    override fun cancel() {active?.disconnect();active=null}

    override suspend fun respond(model:String,history:JSONArray,instructions:String,tools:List<Tool>,onText:suspend (String)->Unit):JSONObject {
        require(model.isNotBlank()) {"Choose a model in Settings, AI provider."}
        var blips=0
        while(true) {
            try {return post(ChatCompletions.request(model,history,instructions,tools),onText)}
            catch(e:ApiFailure) {
                if((e.code==429 || e.code in 500..504) && blips<2) {blips++;onRetry?.invoke();kotlinx.coroutines.delay(2500L*blips);continue}
                throw e
            }
            catch(e:java.io.IOException) {
                if(blips<2) {blips++;onRetry?.invoke();kotlinx.coroutines.delay(2500L*blips);continue}
                throw e
            }
        }
    }

    private suspend fun post(request:JSONObject,onText:suspend (String)->Unit):JSONObject = withContext(Dispatchers.IO) {
        val connection=URL(baseUrl.trimEnd('/')+"/chat/completions").openConnection() as HttpURLConnection
        active=connection
        connection.requestMethod="POST";connection.doOutput=true;connection.connectTimeout=25_000;connection.readTimeout=150_000;connection.instanceFollowRedirects=false
        apiKey().takeIf {it.isNotBlank()}?.let {connection.setRequestProperty("Authorization","Bearer "+it)}
        connection.setRequestProperty("Content-Type","application/json");connection.setRequestProperty("Accept","text/event-stream")
        try {
            connection.outputStream.use {it.write(request.toString().toByteArray())}
            if(connection.responseCode !in 200..299) {
                val raw=connection.errorStream?.bufferedReader()?.use {it.readText().take(1200)}.orEmpty()
                val message=runCatching {JSONObject(raw).let {b->b.optJSONObject("error")?.optString("message")?.takeIf {m->m.isNotBlank()}?:b.optString("message").takeIf {m->m.isNotBlank()}?:raw}}.getOrDefault(raw)
                throw ApiFailure(connection.responseCode,"The AI service returned HTTP "+connection.responseCode+": "+Rules.redact(message))
            }
            val collector=ChatCompletions.Collector()
            connection.inputStream.bufferedReader().use {reader->
                while(true) {
                    currentCoroutineContext().ensureActive()
                    val line=reader.readLine()?:break
                    if(!line.startsWith("data:")) continue
                    val data=line.substring(5).trim()
                    if(data.isEmpty() || data=="[DONE]") continue
                    val event=runCatching {JSONObject(data)}.getOrNull()?:continue
                    collector.accept(event)?.let {onText(it)}
                }
            }
            collector.result()
        } finally {connection.disconnect();if(active===connection) active=null}
    }
}
