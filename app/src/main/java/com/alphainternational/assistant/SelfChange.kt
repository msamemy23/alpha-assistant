package com.alphainternational.assistant

import org.json.JSONObject

/**
 * Actions that change what Alpha itself will do from now on (a routine that runs every day, new standing rules, a saved skill, a new tool), and clicks that
 * look like paying. A web page, email or file that a bot has read can't be trusted to steer these, and what they plant lasts. So in the default
 * mode they ask (the usual approval card) when the job has read anything from outside, unless the owner's own request is clearly about that
 * very thing; when nothing outside was read they stay silent, like everything else the owner asked for.
 * (The "Always ask" and "Important only" modes of Settings already ask for these or are stricter, and are not changed.)
 */
object SelfChange {
    /** The tools that make Alpha do something later, or differently, on their own. */
    val persistent=setOf("routine_create","bot_update","skill_save","tool_create")

    private fun r(pattern:String)=Regex(pattern,RegexOption.IGNORE_CASE)
    /** Words in the owner's own request that name each kind of change, so a change they asked for is not questioned. */
    private val named:Map<String,Regex> = mapOf(
        "routine_create" to r("routines?|every (day|morning|night|evening|week|month|hour|monday|tuesday|wednesday|thursday|friday|saturday|sunday|\\d+)|\\bdaily\\b|\\bweekly\\b|\\bmonthly\\b|each (day|morning|week|night)|recurring|schedule|remind me|automat"),
        "bot_update" to r("from now on|always|never|standing rules?|rules?\\b|change (how|your|the) |update (your|the) (job|rules|role)|your job"),
        "skill_save" to r("skills?\\b|remember how|save (this|these|that|it) (as|for)|teach|next time|learn (how|this)"),
        "tool_create" to r("\\btools?\\b|calculator|converter|formula|make (a|me a) (helper|shortcut)")
    )

    /**
     * Whether [tool] should wait for the owner's OK in the default mode: only when something from outside was read ([tainted], see [SupabaseRules.tainted]) and the owner's request
     * [prompt] does not itself ask for that kind of change. [checkout] says a browser click looks like paying or placing an order.
     */
    fun needsCard(tool:String,prompt:String,tainted:Boolean,checkout:Boolean=false):Boolean {
        if(!tainted) return false
        if(tool=="browser_click") return checkout
        val ask=named[tool]?:return false
        return !ask.containsMatchIn(prompt)
    }

    private val paying=r("\\b(buy now|buy it now|place (your |my |the )?order|complete (your |my |the )?(order|purchase)|submit (your |my |the )?order|pay now|pay with|pay \\$|checkout|check out|purchase|confirm (your |my |the )?(order|payment|purchase)|subscribe|donate|add payment|proceed to pay|book now|reserve now)\\b")
    /** Whether a browser element, as the browser describes it (label, button type, the form's address), looks like paying or placing an order. */
    fun checkoutLike(target:JSONObject?):Boolean {
        if(target==null) return false
        val label=target.optString("label");val action=target.optString("action");val href=target.optString("href")
        if(paying.containsMatchIn(label)) return true
        // A submit button of a form that goes to a checkout, payment or order address.
        val destination=(action+" "+href).lowercase()
        return Regex("checkout|payment|/pay\\b|/order|purchase|/buy\\b|cart/confirm").containsMatchIn(destination) && target.optString("type").lowercase() in setOf("submit","button","")
    }
}
