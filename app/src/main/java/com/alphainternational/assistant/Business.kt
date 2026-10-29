package com.alphainternational.assistant

import org.json.JSONArray
import org.json.JSONObject

/** One step on the way to a running business: what it is, why it matters in plain words, where it stands, and what only the person can do. */
data class BusinessStep(val id:String,val title:String,val why:String,val status:String="todo",val note:String="",val needsYou:String="") {
    val open:Boolean get()=status==Journey.TODO || status==Journey.DOING
    fun json():JSONObject=JSONObject().put("id",id).put("title",title).put("why",why).put("status",status).put("note",note).put("needs_you",needsYou)
}

/** A choice the person made, in their own words, so it is remembered and can be looked at again. */
data class BusinessDecision(val at:Long,val question:String,val choice:String,val why:String) {
    fun json():JSONObject=JSONObject().put("at",at).put("question",question).put("choice",choice).put("why",why)
}

/**
 * The business a person is starting or running with Alpha: what it is, who it is for, where it stands and the steps ahead. It lives on the phone and every bot
 * knows it, so a bot asked "what is next" or "write a post" already knows the business and does not start from nothing.
 */
data class Business(val name:String="",val idea:String="",val kind:String="",val stage:String=Journey.IDEA,val goal:String="",val customers:String="",val offer:String="",
    val price:String="",val place:String="",val limits:String="",val steps:List<BusinessStep> = emptyList(),val decisions:List<BusinessDecision> = emptyList(),val startedAt:Long=0) {

    /** Whether there is a business here at all (the person said what it is, or named it). */
    val exists:Boolean get()=idea.isNotBlank() || name.isNotBlank()

    fun next():BusinessStep? = steps.firstOrNull {it.status==Journey.DOING} ?: steps.firstOrNull {it.status==Journey.TODO}

    /** Steps finished (or skipped on purpose) out of all steps. */
    fun progress():Pair<Int,Int> = steps.count {!it.open} to steps.size

    fun json():JSONObject=JSONObject().put("name",name).put("idea",idea).put("kind",kind).put("stage",stage).put("goal",goal).put("customers",customers).put("offer",offer)
        .put("price",price).put("place",place).put("limits",limits).put("started_at",startedAt)
        .put("steps",JSONArray().also {a->steps.forEach {a.put(it.json())}}).put("decisions",JSONArray().also {a->decisions.forEach {a.put(it.json())}})

    /** What a bot reads about this business at the start of each round: short, plain, and only what is filled in. */
    fun note():String {
        if(!exists) return ""
        val (done,all)=progress()
        val lines=listOfNotNull(
            "The owner's business: "+(name.ifBlank {"not named yet"})+(if(kind.isNotBlank()) " ($kind)" else "")+", stage: $stage.",
            idea.takeIf {it.isNotBlank()}?.let {"Idea: $it"},goal.takeIf {it.isNotBlank()}?.let {"Goal: $it"},customers.takeIf {it.isNotBlank()}?.let {"For: $it"},
            offer.takeIf {it.isNotBlank()}?.let {"Offer: $it"},price.takeIf {it.isNotBlank()}?.let {"Price: $it"},place.takeIf {it.isNotBlank()}?.let {"Where: $it"},
            limits.takeIf {it.isNotBlank()}?.let {"Their limits (money, time, skills): $it"},
            if(all>0) "Steps done: $done of $all."+(next()?.let {" Next: "+it.title+(if(it.needsYou.isNotBlank()) " (needs the owner: "+it.needsYou+")" else "")+"."}.orEmpty()) else null)
        return lines.joinToString("\n")
    }

    companion object {
        fun parse(j:JSONObject?):Business {
            if(j==null) return Business()
            fun s(k:String,max:Int=400)=j.optString(k).trim().take(max)
            val steps=j.optJSONArray("steps")?.objects().orEmpty().mapNotNull {o->
                val id=o.optString("id").trim().lowercase().filter {c->c.isLetterOrDigit() || c=='_'}.take(40).ifBlank {return@mapNotNull null}
                val title=o.optString("title").trim().take(120).ifBlank {return@mapNotNull null}
                BusinessStep(id,title,o.optString("why").trim().take(300),o.optString("status").takeIf {it in Journey.STATUSES}?:Journey.TODO,
                    o.optString("note").trim().take(400),o.optString("needs_you").trim().take(300))
            }.distinctBy {it.id}.take(60)
            val decisions=j.optJSONArray("decisions")?.objects().orEmpty().map {o->
                BusinessDecision(o.optLong("at"),o.optString("question").trim().take(200),o.optString("choice").trim().take(200),o.optString("why").trim().take(300))
            }.takeLast(100)
            return Business(s("name",80),s("idea",600),s("kind",80),s("stage").takeIf {it in Journey.STAGES}?:Journey.IDEA,s("goal"),s("customers"),s("offer"),s("price",120),s("place",120),
                s("limits"),steps,decisions,j.optLong("started_at"))
        }
    }
}

/**
 * The way from "I want to make money" to a running business, as plain steps. The steps are the same for everyone at the start and grow a little for the kind of
 * business. Nothing here promises income: it lists the work, says what only the person can do (an ID check, a signature, a payment), and keeps track.
 */
object Journey {
    const val TODO="todo";const val DOING="doing";const val DONE="done";const val SKIPPED="skipped"
    val STATUSES=setOf(TODO,DOING,DONE,SKIPPED)
    const val IDEA="idea";const val CHECKING="checking";const val SETTING_UP="setting_up";const val LAUNCHED="launched";const val RUNNING="running"
    val STAGES=listOf(IDEA,CHECKING,SETTING_UP,LAUNCHED,RUNNING)

    private val storeWords=Regex("(?i)store|shop|e-?commerce|dropship|print|sell(ing)? (online|products)|resale|flip|merch|product")
    private val serviceWords=Regex("(?i)service|clean|lawn|detail|handyman|repair|pet|mow|paint|plumb|hvac|salon|barber|hair|nail|tutor|photograph|moving|landscap|car wash")

    /** The steps a business starts with. [kind] is the person's own words for it, if they have said. */
    fun starterSteps(kind:String=""):List<BusinessStep> {
        val steps=mutableListOf(
            BusinessStep("fit","Pick the business that fits you","The right business matches your skills, your money and your time. We compare two or three honestly and pick one."),
            BusinessStep("numbers","Check the real numbers","What it costs to start, what you would charge, what is left after costs, and how long until the first sale. No guesses dressed up as promises."),
            BusinessStep("name","Choose a name and check it is free","A name people can remember, with the web address, the state's business records and the social names checked.","todo","","You make the final choice."))
        if(storeWords.containsMatchIn(kind)) steps+=BusinessStep("products","Choose what you sell and who supplies it","The products, the supplier, the real cost of each one and how it reaches the customer.")
        if(serviceWords.containsMatchIn(kind)) steps+=BusinessStep("quote","Write your price list and a quote you can send","A clear price list and a short message that turns a question into a booked job.")
        steps+=listOf(
            BusinessStep("plan","Write a one-page plan","Who it is for, what you sell, the price, and how you will find your first ten customers."),
            BusinessStep("legal","Set up the legal side","Which kind of company, registering it with the state, the tax number (EIN), and any licence or permit for your kind of work. Alpha prepares every answer and walks you through; it is not legal advice.",
                "todo","","Your ID, tax number and signature, and the filing fees. A professional for anything that is unclear."),
            BusinessStep("money","Open a business bank account and a way to get paid","Business money stays apart from your own. You can take card payments before you have a shop.","todo","","Your ID and your approval."),
            BusinessStep("site","Make a simple website or page","One page that says what you do, shows the price and lets people pay or book.","todo","","Your OK before it goes online."),
            BusinessStep("test","Test it with real people","A payment link or a waiting list. The first three customers tell you more than any plan.","todo","","Your OK before anything is sent or charged."),
            BusinessStep("customers","Get your first three customers","Messages to the right people, local groups and the people you already know, written for you to approve.","todo","","You approve every message before it goes out."),
            BusinessStep("books","Set up simple bookkeeping","Every sale and cost written down, and money set aside for taxes."),
            BusinessStep("cover","Insurance and taxes basics","What you may need cover for and when taxes are due.","todo","","A short call with an insurance agent and an accountant."),
            BusinessStep("brief","Start the weekly founder brief","Every Monday: what happened, the numbers, the next three steps and one decision explained in plain words."))
        return steps
    }

    /** The business with these changes applied. Only fields that are present change; text is trimmed and kept short; an unknown status or stage is ignored. */
    fun apply(b:Business,set:JSONObject?,step:JSONObject?,decision:JSONObject?,now:Long):Business {
        var out=b
        if(set!=null) {
            fun pick(key:String,old:String,max:Int=400)=if(set.has(key)) set.optString(key).trim().take(max) else old
            out=out.copy(name=pick("name",out.name,80),idea=pick("idea",out.idea,600),kind=pick("kind",out.kind,80),goal=pick("goal",out.goal),customers=pick("customers",out.customers),
                offer=pick("offer",out.offer),price=pick("price",out.price,120),place=pick("place",out.place,120),limits=pick("limits",out.limits),
                stage=set.optString("stage").takeIf {it in STAGES}?:out.stage)
        }
        // The first time there is a business, it gets its steps (shaped by what kind of business it is) and a start date.
        if(!b.exists && out.exists) out=out.copy(startedAt=now,steps=if(out.steps.isEmpty()) starterSteps(out.kind+" "+out.idea) else out.steps)
        if(step!=null) {
            val id=step.optString("id").trim().lowercase().filter {it.isLetterOrDigit() || it=='_'}.take(40)
            if(id.isNotBlank()) {
                val old=out.steps.firstOrNull {it.id==id}
                val merged=BusinessStep(id,step.optString("title").trim().take(120).ifBlank {old?.title.orEmpty()}.ifBlank {id.replace('_',' ')},
                    step.optString("why").trim().take(300).ifBlank {old?.why.orEmpty()},step.optString("status").takeIf {it in STATUSES}?:old?.status?:TODO,
                    if(step.has("note")) step.optString("note").trim().take(400) else old?.note.orEmpty(),
                    if(step.has("needs_you")) step.optString("needs_you").trim().take(300) else old?.needsYou.orEmpty())
                out=out.copy(steps=if(old==null) (out.steps+merged).take(60) else out.steps.map {if(it.id==id) merged else it})
            }
        }
        if(decision!=null && decision.optString("question").isNotBlank() && decision.optString("choice").isNotBlank())
            out=out.copy(decisions=(out.decisions+BusinessDecision(now,decision.optString("question").trim().take(200),decision.optString("choice").trim().take(200),
                decision.optString("why").trim().take(300))).takeLast(100))
        return out
    }
}
