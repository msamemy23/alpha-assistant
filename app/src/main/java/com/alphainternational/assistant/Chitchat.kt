package com.alphainternational.assistant

/**
 * A greeting or a ping ("Hi", "Hello everyone", "Hey everyone. Say hi to each other too"): a few friendly words and nothing to do.
 *
 * On 2026-10-17, after the plan limit lifted, three of these (plus the earlier "say hi to each other") that had waited hours came back at once: every one of the twelve bots answered every
 * one of them, and some bots texted colleagues to say hello, about 75 hello messages in the team chat. A greeting gets ONE short line from the bot itself, with no tools and no questions
 * to colleagues, and one that is old when its turn finally comes is skipped.
 */
object Chitchat {
    /** Put on a bot's prompt when the owner's words were only a greeting; a bot carrying it may not ask colleagues. */
    const val MARK="(This is just a greeting."
    const val NOTE="\n\n$MARK Reply with ONE short friendly line yourself. Don't use any tool, don't ask a colleague, don't start any work.)"
    /** A greeting that waits longer than this for its turn is not answered any more. */
    const val STALE_MS=15*60_000L
    /** What the app really uses (the tests make it short). */
    @JvmField var staleMs=STALE_MS

    private val greetingWords=setOf(
        "hi","hii","hiii","hey","heyy","hello","hiya","howdy","yo","sup","hola","greetings",
        "good","morning","afternoon","evening","night","everyone","everybody","all","team","guys","folks","there","bots","bot","friends",
        "you","are","how","is","it","going","doing","do","today","whats","what","s","up","say","to","each","other","too","also","and","the","a","again","back","please","you","re","m","i","im","here",
        "anyone","anybody","introduce","yourselves","yourself","greet","welcome","thanks","thank","ok","okay","test","testing","ping","alive","awake","online","work","working","ready","hear","me","can","say","something","meet")

    /** Whether [text] is nothing but a greeting. Any other word (a name, a job, a number) makes it a real request. */
    fun isGreeting(text:String):Boolean {
        val t=text.trim()
        if(t.isEmpty() || t.length>90) return false
        val words=Regex("[A-Za-z]+").findAll(t.lowercase()).map {it.value}.toList()
        if(words.isEmpty() || words.size>14) return false
        if(Regex("\\d").containsMatchIn(t)) return false
        return words.first() in setOf("hi","hii","hiii","hey","heyy","hello","hiya","howdy","yo","sup","hola","greetings","good","test","testing","ping","thanks","thank","ok","okay","say","anyone","anybody","are","you","can") && words.all {it in greetingWords}
    }
}
