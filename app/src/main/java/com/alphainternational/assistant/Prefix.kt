package com.alphainternational.assistant

import org.json.JSONArray

/**
 * Keeps the start of every request to the AI identical from one round of a job to the next (and from one job of a bot to the next), because the AI
 * service reuses what it already read when a request starts with exactly the same text, and only charges full price for what comes after. So: the
 * instructions and the tool list are fixed for the whole job (the list only ever grows at its end), the saved history only ever grows at its end,
 * and everything that changes from round to round (the time, what the bot remembers, connections, the plan's progress line, a time warning) goes
 * into one last message that is never saved into the history.
 */
object Prefix {
    /** One request: [history] is the saved conversation, [tail] the changing notes added after it for this round only. */
    class Round(val instructions:String,val tools:List<Tool>,val history:JSONArray,val tail:String) {
        /** What is sent as the input: the saved history plus the last (changing) message. */
        fun input():JSONArray {
            val out=JSONArray()
            for(i in 0 until history.length()) out.put(history.get(i))
            if(tail.isNotBlank()) out.put(obj("role" to "developer","content" to tail))
            return out
        }
        fun toolsText():String=Prefix.toolsText(tools)
        /** True when this request starts exactly like [earlier]: same instructions, same tools in the same order at the start, same earlier history. */
        fun startsLike(earlier:Round):Boolean {
            if(instructions!=earlier.instructions) return false
            // (The tool list is sent before the history, so a list that grew changes the start; this compares rounds of one job whose list did not grow.)
            if(toolsText()!=earlier.toolsText()) return false
            if(history.length()<earlier.history.length()) return false
            for(i in 0 until earlier.history.length()) if(history.get(i).toString()!=earlier.history.get(i).toString()) return false
            return true
        }
    }

    fun toolsText(tools:List<Tool>):String=tools.joinToString("\n") {it.json().toString()}

    /** A short fingerprint of some text, for the round log: when two rounds show the same fingerprint, the AI service could reuse the start. */
    fun hash(text:String):String=digest(text).take(8)

    /** The key that groups a bot's requests so the AI service sends them to the same place and finds the saved start. One per bot, not per task. */
    fun cacheKey(bot:String):String="alpha-"+bot.lowercase().filter {it.isLetterOrDigit()}.take(24).ifBlank {"bot"}

    /** The round log line's fingerprint part: instructions, tools, and how many saved items the start has. */
    fun fingerprint(instructions:String,tools:List<Tool>,historyItems:Int):String="pfx="+hash(instructions)+" tl="+hash(toolsText(tools))+" hist="+historyItems
}
