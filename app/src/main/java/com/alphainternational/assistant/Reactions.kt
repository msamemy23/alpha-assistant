package com.alphainternational.assistant

/**
 * Tap-backs: one emoji on a text, like in Messages. The owner taps a bot's text to react; a bot reacts to the owner's latest text
 * with its react tool. A reaction never starts work by itself; the bot just reads it as the owner's answer.
 */
object Reactions {
    /** The row that pops up when the owner taps a text. */
    val quick=listOf("👍","❤️","😂","😮","😢","🙏","🎉","🔥","✅","👎","❓")

    /** The first emoji in [text] (with its joiners and variation selector), or "" when there isn't one. */
    fun clean(text:String):String=Gifs.firstEmoji(text.trim().take(40)).take(16)

    /** What the owner reacted to lately, for the bot's instructions: "👍 on “Your estimate is ready”". */
    fun recent(messages:List<Message>,limit:Int=5):String {
        val reacted=messages.filter {it.role=="assistant" && it.react.isNotBlank()}.sortedBy {it.at}.takeLast(limit)
        if(reacted.isEmpty()) return ""
        return "The owner recently reacted to your texts: "+reacted.joinToString("; ") {
            it.react+" on “"+it.text.replace(Regex("\\s+")," ").trim().take(70)+"”"
        }+"."
    }
}
