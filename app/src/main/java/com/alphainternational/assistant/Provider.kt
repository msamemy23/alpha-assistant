package com.alphainternational.assistant

import org.json.JSONArray
import org.json.JSONObject

/**
 * Which AI the bots think with. Alpha began on one brain (the owner's ChatGPT plan); anyone can now choose their own:
 *  - [CHATGPT]: sign in with your ChatGPT plan (the default; no key to paste).
 *  - [OPENAI_KEY]: an OpenAI API key (pay as you go).
 *  - [COMPATIBLE]: any service that speaks the Chat Completions format at a web address you give (OpenRouter, Groq, Together, DeepSeek, Gemini or Anthropic
 *    through their compatible addresses, or a model of your own such as Ollama running in Termux on this phone).
 * The key is stored with the other sign-ins, in the encrypted state, and is scrubbed from logs and chats like them.
 */
data class Provider(val kind:String=CHATGPT,val baseUrl:String="",val apiKey:String="",val model:String="") {
    /** True when the bots do not use the ChatGPT plan sign-in. */
    val custom:Boolean get()=kind==OPENAI_KEY || kind==COMPATIBLE

    /** What the address of the service is, with the usual default for an OpenAI key. */
    val address:String get()=if(kind==OPENAI_KEY) "https://api.openai.com/v1" else baseUrl.trim().trimEnd('/')

    /** Enough is filled in to try a request. (A local model needs no key.) */
    fun ready():Boolean = when(kind) {
        OPENAI_KEY->apiKey.isNotBlank() && model.isNotBlank()
        COMPATIBLE->problem().isEmpty() && model.isNotBlank()
        else->true
    }

    /** What is wrong with the web address, in plain words; empty when it is fine. Only secure (https) addresses, or a model on this very phone (localhost), are allowed. */
    fun problem():String {
        if(kind!=COMPATIBLE) return ""
        val url=baseUrl.trim()
        if(url.isBlank()) return "Enter the service's web address."
        val parsed=runCatching {java.net.URI(url)}.getOrNull() ?: return "That web address is not valid."
        val host=parsed.host.orEmpty().lowercase()
        if(host.isBlank()) return "That web address is not valid."
        val local=host=="localhost" || host=="127.0.0.1" || host=="[::1]"
        return when {
            parsed.scheme=="https"->""
            parsed.scheme=="http" && local->""
            parsed.scheme=="http"->"Use an https:// address. Plain http is only allowed for a model on this phone (localhost)."
            else->"The address must start with https://"
        }
    }

    fun json():JSONObject=obj("kind" to kind,"base_url" to baseUrl.trim(),"api_key" to apiKey.trim(),"model" to model.trim())

    /** One line for Settings. */
    fun summary():String = when(kind) {
        OPENAI_KEY->"OpenAI API key"+(if(model.isBlank()) "" else " · $model")
        COMPATIBLE->(runCatching {java.net.URI(baseUrl.trim()).host}.getOrNull()?:"Other service")+(if(model.isBlank()) "" else " · $model")
        else->"Your ChatGPT plan"
    }

    companion object {
        const val CHATGPT="chatgpt"
        const val OPENAI_KEY="openai_key"
        const val COMPATIBLE="compatible"
        fun parse(j:JSONObject?):Provider {
            if(j==null) return Provider()
            val kind=j.optString("kind").takeIf {it in setOf(OPENAI_KEY,COMPATIBLE)}?:CHATGPT
            return Provider(kind,j.optString("base_url"),j.optString("api_key"),j.optString("model"))
        }
        /** Starting points for the "other service" form. */
        data class Preset(val name:String,val url:String,val note:String)
        val presets=listOf(
            Preset("OpenRouter","https://openrouter.ai/api/v1","Hundreds of models behind one key"),
            Preset("Groq","https://api.groq.com/openai/v1","Very fast open models"),
            Preset("Together AI","https://api.together.xyz/v1","Open models"),
            Preset("DeepSeek","https://api.deepseek.com/v1","DeepSeek models"),
            Preset("Gemini (Google)","https://generativelanguage.googleapis.com/v1beta/openai","Gemini through its compatible address"),
            Preset("Anthropic (Claude)","https://api.anthropic.com/v1","Claude through its compatible address"),
            Preset("On this phone (Ollama)","http://localhost:11434/v1","A model running in Termux on this phone, no key")
        )
    }
}

/**
 * The gateway the bots use: it sends each request to whichever AI is chosen in Settings at that moment, so changing the provider takes effect on the next
 * message. Everything a job might tell the gateway (intelligence level, search off, retry hook, thinking line) is remembered here and handed to whichever
 * service is in use.
 */
class ProviderAi(private val settings:()->Provider,private val chatgpt:TokenSource,private val parent:ProviderAi?=null):AiGateway {
    /** Options only the OpenAI Responses services have (hosted search, thinking summary, reuse key); other services ignore them. */
    @Volatile var webSearchAllowed=true
    @Volatile var reasoningSummary=false
    @Volatile var cacheKey:String?=null
    @Volatile var webOnly=false
    @Volatile private var effort:String?=null
    @Volatile private var searchOff=false
    @Volatile private var onRetry:(()->Unit)?=null
    @Volatile private var thinkingSink:(suspend (String)->Unit)?=null
    @Volatile private var cached:AiGateway?=null
    @Volatile private var cachedKey=""

    /** Whether the hosted web search is in use (only the OpenAI services have it). */
    val webSearch:Boolean get()=(current() as? Ai)?.webSearch ?: false

    private fun keyOf(p:Provider)=p.kind+"|"+p.address+"|"+p.apiKey.hashCode()
    private fun build(p:Provider):AiGateway = when(p.kind) {
        Provider.OPENAI_KEY->Ai(ApiKeySource {settings().apiKey.trim()},p.address,namespaced=false)
        Provider.COMPATIBLE->ChatCompletionsAi(p.address) {settings().apiKey.trim()}
        else->Ai(chatgpt)
    }
    @Synchronized private fun current():AiGateway {
        val p=settings();val key=keyOf(p)
        if(cached==null || key!=cachedKey) {
            // A job's connection starts from what its parent learned about this service (for example that it refuses hosted search).
            cached=parent?.takeIf {it.keyOfCurrent()==key}?.current()?.fork() ?: build(p)
            cachedKey=key
        }
        return cached!!
    }
    private fun keyOfCurrent():String = keyOf(settings())

    private fun hand(g:AiGateway) {
        g.setEffort(effort);g.setSearchOff(searchOff);g.setOnRetry(onRetry);g.setThinkingSink(thinkingSink)
        (g as? Ai)?.let {it.webSearchAllowed=webSearchAllowed;it.reasoningSummary=reasoningSummary;it.cacheKey=cacheKey;it.webOnly=webOnly}
    }
    override fun fork():ProviderAi = ProviderAi(settings,chatgpt,this).also {
        it.webSearchAllowed=webSearchAllowed;it.reasoningSummary=reasoningSummary;it.cacheKey=cacheKey;it.webOnly=webOnly
        it.effort=effort;it.searchOff=searchOff
    }
    override suspend fun respond(model:String,history:JSONArray,instructions:String,tools:List<Tool>,onText:suspend (String)->Unit):JSONObject {
        val gateway=current();hand(gateway)
        return gateway.respond(model,history,instructions,tools,onText)
    }
    override fun cancel() {cached?.cancel()}
    override fun setEffort(effort:String?) {this.effort=effort}
    override fun setSearchOff(off:Boolean) {searchOff=off}
    override fun setOnRetry(callback:(()->Unit)?) {onRetry=callback}
    override fun setThinkingSink(sink:(suspend (String)->Unit)?) {thinkingSink=sink}
}
