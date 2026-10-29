package com.alphainternational.assistant

import org.json.JSONArray
import org.json.JSONObject

/**
 * Guard: the owner's own limits on what the bots may do alone, kept in plain code so no prompt can talk its way around them.
 *  - Three dials ([Area]): texts and emails to people, clicks and forms on websites, and changes in connected apps. Each one is "ask first" or "go ahead"
 *    (within the other limits), or follows the older single setting "Bots ask before" when the owner has not set it.
 *  - One ledger of everything sent ([SendLedger]): a daily cap per kind, a cap per person, no repeating the same message, and a do-not-contact list.
 *  - A pause switch: while it is on, nothing that sends, posts, changes or taps goes ahead.
 * Paying, erasing and security switches always ask, whatever the dials say (see [PhoneSafety] and the rules in the controller).
 */
object Guard {
    enum class Area(val key:String,val title:String,val note:String) {
        MESSAGES("messages","Texts and emails to people","Sending a text or an email to a customer or anyone else."),
        WEB("web","Clicks and forms on websites","Buttons, Enter and sending in the bot's own browser."),
        APPS("apps","Changes in connected apps","Anything a bot writes, creates or changes in an app you connected.")
    }
    const val ASK="ask"
    const val GO="go"

    /** Which dial a tool belongs to (null: it has none, the older setting decides). [mcpWrite]: a connected-app call that changes something. */
    fun areaOf(tool:String,mcpWrite:Boolean=false):Area? = when {
        tool=="sms_send" || tool=="gmail_send"->Area.MESSAGES
        tool=="browser_click" || tool=="browser_press_enter"->Area.WEB
        tool=="mcp_call" && mcpWrite->Area.APPS
        else->null
    }

    private val optOut=Regex("(?i)^\\s*(stop|stop all|stopall|unsubscribe|cancel|end|quit|opt ?out|remove me|do not text me|don'?t text me|baja|parar|no m[aá]s|cancelar)[.! ]*$")
    /** Whether a text is a customer asking not to be written to again (English and Spanish stop words, on their own). */
    fun isOptOut(text:String)=optOut.matches(text.trim())

    /** What a dial says right now: the owner's choice, or what the older "Bots ask before" setting implies. */
    fun level(area:Area,settings:AiSettings):String =
        settings.guard[area.key]?.takeIf {it==ASK || it==GO} ?: if(settings.approvals=="bots") GO else ASK

    /** Whether the owner set this dial themselves (so Settings can show "Same as above" otherwise). */
    fun overridden(area:Area,settings:AiSettings)=settings.guard[area.key].let {it==ASK || it==GO}

    /** A copy of [settings] with one dial set; [level] other than ask/go puts it back to following the older setting. */
    fun with(settings:AiSettings,area:Area,level:String):AiSettings {
        val next=settings.guard.toMutableMap()
        if(level==ASK || level==GO) next[area.key]=level else next.remove(area.key)
        return settings.copy(guard=next)
    }
}

/**
 * Everything the bots send, in one log (texts and emails), kept for 30 days, and the rules read from it. The caller records a send after it goes out.
 * Entries look like {"k":"sms","to":"7135550142","at":1700000000000,"h":"<hash of the words>"}.
 */
object SendLedger {
    const val DAY=24*3_600_000L
    const val KEEP=30*DAY
    const val SMS="sms"
    const val EMAIL="email"

    /** Who a send went to, written the same way however it was typed: the last ten digits of a phone number, or the lower-case address. */
    fun key(to:String):String {
        val t=to.trim().lowercase()
        if(t.contains('@')) return t
        return t.filter {it.isDigit()}.takeLast(10).ifBlank {t}
    }

    private fun hash(words:String)=words.trim().lowercase().replace(Regex("\\s+")," ").hashCode().toString()

    private fun entries(log:JSONArray)=(0 until log.length()).mapNotNull {log.optJSONObject(it)}

    /**
     * Why this send may not go now (a sentence the AI reads and passes on), or null if it may.
     * [daily]: the most of this kind in a day; [perPerson]: the most to one person in a day; [doNotContact]: keys (see [key]) nobody may write to.
     * The same words to the same person within 30 days are refused, because a bot that lost track of what it did would otherwise say it twice.
     */
    fun refusal(log:JSONArray,kind:String,to:String,words:String,now:Long,daily:Int,perPerson:Int,doNotContact:Set<String> = emptySet()):String? {
        val who=key(to)
        if(who in doNotContact) return "That person is on the do-not-contact list, so nothing may be sent to them. Do not try another way to reach them; tell the owner."
        val mine=entries(log).filter {it.optString("k")==kind && now-it.optLong("at")<KEEP}
        val today=mine.filter {now-it.optLong("at")<DAY}
        val label=if(kind==SMS) "text" else "email"
        if(today.size>=daily) return "The daily $label limit ($daily) is used up. Tell the owner; they can raise it in Settings. Do not try another way to send."
        val sameToday=today.count {it.optString("to")==who}
        if(sameToday>=perPerson) return "That person has already had $sameToday ${label}s today (the limit is $perPerson). Do not write to them again today; tell the owner if it matters."
        val h=hash(words)
        if(words.isNotBlank() && mine.any {it.optString("to")==who && it.optString("h")==h})
            return "This exact $label already went to that person in the last 30 days. Do not send it again; write something new or tell the owner."
        return null
    }

    /** The log with this send added and anything older than 30 days dropped. */
    fun record(log:JSONArray,kind:String,to:String,words:String,now:Long):JSONArray {
        val out=JSONArray()
        entries(log).filter {now-it.optLong("at")<KEEP}.forEach {out.put(it)}
        out.put(JSONObject().put("k",kind).put("to",key(to)).put("at",now).put("h",hash(words)))
        return out
    }

    /** How many of this kind went out in the last day. */
    fun today(log:JSONArray,kind:String,now:Long)=entries(log).count {it.optString("k")==kind && now-it.optLong("at")<DAY}
}
