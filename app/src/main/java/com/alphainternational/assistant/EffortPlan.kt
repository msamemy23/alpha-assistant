package com.alphainternational.assistant

/**
 * How hard a job should think, over the life of the job and not from one sentence alone. Chosen once from the last message, "yes do it" looked
 * like a greeting and got the smallest model; now a short reply continues the job it answers (and keeps its level), checking rounds think one notch
 * harder than the quickest setting, and a job that keeps hitting errors steps up. Only Auto adapts: a model or intelligence level the owner (or the
 * bot's own setting) pinned is never changed here.
 */
object EffortPlan {
    private val yes=Regex("^(yes|yeah|yep|yup|sure|ok|okay|alright|go ahead|do it|go for it|please do|sounds good|that works|that'?s fine|continue|keep going|carry on|proceed|try again|again|retry|do that|go on|" +
        "the (first|second|third|last|other) one|that one|this one|(do|make|send|use|fix|try) (it|that|this|them)( now| again)?|no( thanks)?|not that( one)?|thanks, do|i agree|correct|(use|pick|take|choose|go with) (the )?(first|second|third|last|other|cheaper|cheapest|best)( one)?)\\b")

    /** A short reply that carries on the previous job ("yes do it", "the second one", an answer to the bot's question) rather than starting a new one. */
    fun isContinuation(prompt:String,lastBotText:String=""):Boolean {
        val t=prompt.lowercase().trim().trimEnd('.','!','?',' ')
        if(t.isBlank()) return false
        val words=t.split(Regex("\\s+")).count {it.isNotBlank()}
        if(words<=8 && yes.containsMatchIn(t)) return true
        // A short answer to a question the bot just asked ("2016 Camry").
        return words<=14 && lastBotText.trimEnd().endsWith("?")
    }

    /** The level an intelligence setting stands for (the reverse of what Auto picks), or null when it says nothing. */
    fun levelOfEffort(effort:String):ModelPlan.Level? = when(effort.lowercase()) {"minimal","low"->ModelPlan.Level.QUICK;"medium"->ModelPlan.Level.EVERYDAY;"high","xhigh"->ModelPlan.Level.HARD;else->null}

    fun higher(a:ModelPlan.Level,b:ModelPlan.Level)=if(a.ordinal>=b.ordinal) a else b

    /** The level for a new job: its own request, but never below the job it continues. */
    fun initial(own:ModelPlan.Level,continuation:Boolean,previous:ModelPlan.Level?):ModelPlan.Level = if(continuation && previous!=null) higher(own,previous) else own

    /** The level a finished job worked at, from what it was asked and the intelligence it ran with. */
    fun levelOfJob(prompt:String,effort:String,building:Boolean):ModelPlan.Level {
        val byRequest=ModelPlan.level(prompt,0,building)
        val byEffort=levelOfEffort(effort)
        return if(byEffort==null) byRequest else higher(byRequest,byEffort)
    }

    fun bump(level:ModelPlan.Level):ModelPlan.Level = when(level) {ModelPlan.Level.QUICK->ModelPlan.Level.EVERYDAY;else->ModelPlan.Level.HARD}

    /** Checking rounds (a repair after a check, the self-review) are at least Everyday: one notch above the quickest setting. */
    fun verifyLevel(level:ModelPlan.Level)=higher(level,ModelPlan.Level.EVERYDAY)

    private val order=listOf("minimal","low","medium","high","xhigh")
    /**
     * One notch more thinking, among what the model supports (empty = it lists none, so any normal level). Unchanged at the top, and never past [ceiling]:
     * Auto stops at "high". The deepest setting writes about 1,000 to 2,000 thinking tokens a round at roughly 13 a second (a minute or two per round on
     * the live phone), and a job that keeps failing on blocked pages or slow sites does not get better by thinking longer. It stays the owner's own choice.
     */
    fun nextEffort(current:String,supported:List<String>,ceiling:String="high"):String {
        val at=order.indexOf(current.lowercase().ifBlank {"low"}).let {if(it<0) 2 else it}
        val cap=order.indexOf(ceiling).let {if(it<0) order.lastIndex else it}
        if(at>=cap) return current
        val target=(at+1).coerceAtMost(cap)
        val pool=if(supported.isEmpty()) order.drop(1) else supported.filter {it in order}
        return pool.filter {order.indexOf(it) in target..cap}.minByOrNull {order.indexOf(it)}?:current
    }
    /** For a checking round: a quick job's "low" becomes "medium" (when supported); anything higher stays, and "the model's own" (blank) stays blank. */
    fun verifyEffort(current:String,supported:List<String>):String =
        if(levelOfEffort(current)==ModelPlan.Level.QUICK) nextEffort(current,supported) else current

    /** Whether the last tool results show the job struggling: three failures in the last five, or the last three all failed. */
    fun struggling(outcomes:List<Boolean>):Boolean {
        if(outcomes.size>=3 && outcomes.takeLast(3).none {it}) return true
        return outcomes.takeLast(5).count {!it}>=3
    }

    /** What may change on its own for this job: the model (only if Auto and the bot has none of its own), the intelligence level (same). */
    data class Adaptable(val model:Boolean,val effort:Boolean) { val any:Boolean get()=model || effort }
    fun adaptable(settings:AiSettings,bot:Bot)=Adaptable(settings.model==ModelPlan.AUTO && bot.model.isBlank(),settings.effort==ModelPlan.AUTO && bot.effort.isBlank())

    /** Which of Auto's three tiers the slug currently is. */
    fun tierOf(slug:String,settings:AiSettings,models:List<AiModel>,fallback:String):ModelPlan.Level {
        val auto=settings.copy(model=ModelPlan.AUTO)
        return when(slug) {
            ModelPlan.model(auto,models,ModelPlan.Level.HARD,fallback=fallback)->ModelPlan.Level.HARD
            ModelPlan.model(auto,models,ModelPlan.Level.EVERYDAY,fallback=fallback)->ModelPlan.Level.EVERYDAY
            else->ModelPlan.Level.QUICK
        }
    }
}
