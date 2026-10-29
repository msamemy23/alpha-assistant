package com.alphainternational.assistant

import org.json.JSONArray
import org.json.JSONObject

/** One model from the owner's ChatGPT plan. [listed] models are the plan's main choices; the rest appear under "More models". */
data class AiModel(val slug:String,val name:String,val listed:Boolean,val efforts:List<String> = emptyList(),val defaultEffort:String="") {
    fun json()=obj("slug" to slug,"name" to name,"listed" to listed,"efforts" to arr(efforts),"default_effort" to defaultEffort)
    companion object {
        fun parse(j:JSONObject)=AiModel(j.getString("slug"),j.optString("name",j.getString("slug")),j.optBoolean("listed",true),
            j.optJSONArray("efforts")?.strings().orEmpty(),j.optString("default_effort"))
    }
}

/**
 * AI settings, like a professional app: Auto or a fixed model, Auto or a fixed intelligence level, and what Auto may use.
 * [texting] is how bots send texts: "phone" (from the owner's number), "google_web" (Google Messages for web in Alpha's browser) or "ask".
 * [approvals] is when bots ask first: "bots" (only before making a bot, the owner's choice), "important" (sending, paying, deleting) or "all".
 */
data class AiSettings(val model:String=ModelPlan.AUTO,val effort:String=ModelPlan.AUTO,val quick:String="",val everyday:String="",val hard:String="",
    val routines:String="quick",val escalate:Boolean=true,val label:Boolean=false,val webSearch:Boolean=true,val readOnlyApps:Boolean=true,
    val texting:String="phone",val approvals:String=DEFAULT_APPROVALS,val learn:Boolean=true,val smsDaily:Int=DEFAULT_SMS_DAILY,
    val guard:Map<String,String> = emptyMap(),val emailDaily:Int=DEFAULT_EMAIL_DAILY,val dailyTokens:Int=DEFAULT_DAILY_TOKENS) {
    fun json()=obj("model" to model,"effort" to effort,"quick" to quick,"everyday" to everyday,"hard" to hard,"routines" to routines,
        "escalate" to escalate,"label" to label,"web_search" to webSearch,"read_only_apps" to readOnlyApps,"texting" to texting,"approvals" to approvals,"learn" to learn,"sms_daily" to smsDaily,
        "guard" to JSONObject(guard),"email_daily" to emailDaily,"daily_tokens" to dailyTokens)
    companion object {
        fun parse(j:JSONObject?)=if(j==null) AiSettings() else AiSettings(j.optString("model",ModelPlan.AUTO),j.optString("effort",ModelPlan.AUTO),
            j.optString("quick"),j.optString("everyday"),j.optString("hard"),j.optString("routines","quick"),j.optBoolean("escalate",true),
            j.optBoolean("label",false),j.optBoolean("web_search",true),j.optBoolean("read_only_apps",true),
            j.optString("texting","phone").takeIf {it in setOf("phone","google_web","ask")}?:"phone",
            j.optString("approvals",DEFAULT_APPROVALS).takeIf {it in setOf("bots","important","all")}?:DEFAULT_APPROVALS,j.optBoolean("learn",true),
            j.optInt("sms_daily",DEFAULT_SMS_DAILY).takeIf {it>0}?:DEFAULT_SMS_DAILY,
            j.optJSONObject("guard")?.let {g->g.keys().asSequence().associateWith {k->g.optString(k)}.filterValues {v->v==Guard.ASK || v==Guard.GO}}.orEmpty(),
            j.optInt("email_daily",DEFAULT_EMAIL_DAILY).takeIf {it>0}?:DEFAULT_EMAIL_DAILY,
            j.optInt("daily_tokens",DEFAULT_DAILY_TOKENS).takeIf {it>=0}?:DEFAULT_DAILY_TOKENS)
        /** A fresh public install asks before sending, paying and deleting; the owner's own build keeps the choice they made (bots just get on with it). */
        val DEFAULT_APPROVALS:String get()=if(Profile.generic) "important" else "bots"
        val DEFAULT_SMS_DAILY:Int get()=if(Profile.generic) 30 else 500
        val DEFAULT_EMAIL_DAILY:Int get()=if(Profile.generic) 50 else 300
        /** The most tokens (read plus written) a pay-as-you-go AI service may use in a day before the bots stop; 0 means no stop. */
        val DEFAULT_DAILY_TOKENS:Int get()=if(Profile.generic) 30_000_000 else 0
    }
}

object ModelPlan {
    const val AUTO="auto"
    enum class Level(val label:String) {QUICK("Quick"),EVERYDAY("Everyday"),HARD("Hard")}
    /** Intelligence levels in order; a model's own list is used when the plan reports one. */
    val efforts=listOf("low","medium","high","xhigh")
    fun effortLabel(effort:String)=when(effort) {"minimal"->"Minimal";"low"->"Low";"medium"->"Medium";"high"->"High";"xhigh"->"Extra high";AUTO->"Auto";else->effort.replaceFirstChar {it.uppercase()}}

    /** Reads OpenAI's /v1/models catalog, keeping every model with a slug. Unknown fields are tolerated. */
    fun catalog(raw:JSONArray):List<AiModel> = raw.objects().filter {it.optString("slug").isNotBlank()}.map {m->
        val levels=(m.optJSONArray("supported_reasoning_levels")?:m.optJSONArray("supported_reasoning_efforts")?:m.optJSONArray("reasoning_efforts")
            ?:JSONArray()).let {a->(0 until a.length()).mapNotNull {i->(a.opt(i) as? JSONObject)?.optString("effort")?.takeIf {it.isNotBlank()}?:(a.opt(i) as? String)}}
        AiModel(m.getString("slug"),m.optString("display_name").ifBlank {m.getString("slug")},m.optString("visibility","list")=="list",
            levels.map {it.lowercase()}.distinct(),(m.optString("default_reasoning_level").ifBlank {m.optString("default_reasoning_effort")}).lowercase())
    }.sortedBy {if(it.listed) 0 else 1}

    /**
     * How demanding a request looks. Only real research, comparing, analysis, strategy, contracts and very long requests are Hard (the slow, careful setting);
     * greetings and short questions are Quick; everything else (estimates, invoices, photos, emails, scheduling) is Everyday, which is much faster.
     */
    fun level(prompt:String,files:Int=0,building:Boolean=false):Level {
        val level=levelOfWords(prompt,files,building)
        // A colleague asked for one thing is doing real work (a lookup), never small talk, but not research either unless that one thing is.
        return if(TeamRules.isPacket(prompt)) EffortPlan.higher(level,Level.EVERYDAY) else level
    }
    private fun levelOfWords(prompt:String,files:Int,building:Boolean):Level {
        val text=TeamRules.levelText(prompt).lowercase().trim()
        // Making a website, app or game is the most demanding thing a bot does: design and code need the strongest model and the highest level.
        if(building) return Level.HARD
        val hard=Regex("research|compare|analy[sz]|strateg|forecast|debug|step by step|contract|proposal|campaign|taxes|payroll|" +
            "find (the best|leads|candidates)|negotiat")
        if(text.length>600 || hard.containsMatchIn(text)) return Level.HARD
        // Looking at a photo or PDF is at least everyday work, never a quick one-liner.
        if(files>0) return Level.EVERYDAY
        val quick=Regex("^(hi|hey|hello|yo|thanks|thank you|ok|okay|good (morning|night|afternoon)|what time|what'?s the date|how are you)\\b")
        // Short requests that ask a bot to do something (send, schedule, write…) still get the Everyday model.
        val action=Regex("\\b(email|e-mail|text|call|calls|send|reply|draft|write|create|make|schedule|remind|book|order|post|add|delete|update|cancel|fix|set up|" +
            "use|open|check|look|find|search|get|read|show|pull|go to|sign|log ?in|browser|broswer|website|portal|dashboard|page|site|tell me|who called|called|rang|recording|recordings)\\b")
        if(quick.containsMatchIn(text) && text.length<=60) return Level.QUICK
        if(text.length<=80 && text.split(Regex("\\s+")).size<=8 && !action.containsMatchIn(text)) return Level.QUICK
        return Level.EVERYDAY
    }

    /** Best guesses for Auto's three tiers by name, so it works before the owner adjusts anything. */
    fun defaults(models:List<AiModel>,previous:String):Triple<String,String,String> {
        val listed=models.filter {it.listed}.ifEmpty {models}
        fun named(pattern:String)=listed.firstOrNull {Regex(pattern,RegexOption.IGNORE_CASE).containsMatchIn(it.name+" "+it.slug)}?.slug
        val first=listed.firstOrNull()?.slug.orEmpty()
        val quick=named("luna|mini|nano|lite|fast|spark|flash")?:listed.lastOrNull()?.slug.orEmpty()
        val hard=named("astra|pro|max|ultra|heavy")?:first
        val everyday=previous.takeIf {p->models.any {it.slug==p}}?:named("sol|terra|standard")?:hard
        return Triple(quick,everyday,hard)
    }

    /** The model slug to use for this request. */
    fun model(settings:AiSettings,models:List<AiModel>,level:Level,routine:Boolean=false,fallback:String=""):String {
        val known=models.map {it.slug}.toSet()
        if(settings.model!=AUTO && settings.model in known) return settings.model
        val (dq,de,dh)=defaults(models,fallback)
        fun ok(slug:String,default:String)=slug.takeIf {it in known}?:default
        val tier=if(routine) when(settings.routines) {"hard"->Level.HARD;"everyday"->Level.EVERYDAY;"quick"->Level.QUICK;else->null} else level
        if(routine && tier==null && settings.routines in known) return settings.routines
        return when(tier?:level) {
            Level.QUICK->ok(settings.quick,dq)
            Level.EVERYDAY->ok(settings.everyday,de)
            Level.HARD->ok(settings.hard,dh)
        }.ifBlank {fallback}
    }

    /** Intelligence level for this request, limited to what the model supports. Null means "don't send one" (model default). */
    fun effort(settings:AiSettings,model:AiModel?,level:Level):String? {
        val wanted=if(settings.effort!=AUTO) settings.effort else when(level) {Level.QUICK->"low";Level.EVERYDAY->"medium";Level.HARD->"high"}
        val supported=model?.efforts.orEmpty()
        if(supported.isEmpty()) return wanted.takeIf {it in efforts || it=="minimal"}
        if(wanted in supported) return wanted
        // Closest supported level, preferring the next one up.
        val order=listOf("minimal")+efforts
        val target=order.indexOf(wanted).coerceAtLeast(0)
        return supported.minByOrNull {val i=order.indexOf(it);if(i<0) 99 else kotlin.math.abs(i-target)*2+(if(i<target) 1 else 0)}
    }

    /** One way of asking the AI service: which model, which intelligence level (blank = the model's own), and whether its hosted web search is included. */
    data class Ask(val model:String,val effort:String,val search:Boolean)

    /**
     * What to try, in order, when the AI service answers with its own catch-all error ("An error occurred while processing your request"): without
     * the hosted web search (the bot still has its own page tools), then a lower intelligence level, then the Everyday and Quick models. The first
     * item is always the current way (already tried).
     */
    fun steadier(settings:AiSettings,models:List<AiModel>,current:String,currentEffort:String,currentSearch:Boolean,fallback:String):List<Ask> {
        val out=ArrayList<Ask>()
        fun add(a:Ask) { if(a.model.isNotBlank() && a !in out) out.add(a) }
        fun levelFor(slug:String,wanted:String,tier:Level)=effort(settings.copy(effort=wanted),models.firstOrNull {it.slug==slug},tier).orEmpty()
        add(Ask(current,currentEffort,currentSearch))
        add(Ask(current,currentEffort,false))
        val lower=when(currentEffort) {"xhigh"->"high";"high"->"medium";"medium"->"low";else->""}
        if(lower.isNotBlank()) add(Ask(current,levelFor(current,lower,Level.EVERYDAY),false))
        val auto=settings.copy(model=AUTO)
        val everyday=model(auto,models,Level.EVERYDAY,fallback=fallback)
        add(Ask(everyday,levelFor(everyday,"medium",Level.EVERYDAY),false))
        val quick=model(auto,models,Level.QUICK,fallback=fallback)
        add(Ask(quick,levelFor(quick,"low",Level.QUICK),false))
        return out
    }

    /** Label shown under an answer, e.g. "Astra · High". */
    fun label(models:List<AiModel>,slug:String,effort:String?)=(models.firstOrNull {it.slug==slug}?.name?:slug)+(effort?.let {" · "+effortLabel(it)}?:"")
}
