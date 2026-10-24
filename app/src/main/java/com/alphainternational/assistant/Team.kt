package com.alphainternational.assistant


/**
 * Teamwork: one group chat with every bot in it. A message goes to the bot(s) whose job fits — by name when the owner
 * names them, otherwise by the steps of a plan made by the chief planner (see Case.kt). Bots speak only when they have work, a result or a hand-off.
 */
object Team {
    const val ROOM="Teamwork"

    /**
     * Bots the owner clearly called on ("@Marketing", "Bookkeeper, log this", "ask Alpha to…"), in the order they appear.
     * A name used as an ordinary word ("my personal email") is left to the router.
     */
    fun mentioned(text:String,bots:List<String>):List<String> {
        val lower=text.lowercase().trim()
        return bots.mapNotNull {name->
            val n=Regex.escape(name.lowercase())
            listOf("@$n(?![a-z0-9])","^(?:hey |hi |ok |okay )?$n\\s*[,:]","(?<![a-z0-9])(?:ask|tell|have|get|let) $n(?![a-z0-9])")
                .mapNotNull {Regex(it).find(lower)?.range?.first}.minOrNull()?.let {name to it}
        }.sortedBy {it.second}.map {it.first}
    }

    /** "hi everyone", "all of you", "team, …": the owner is talking to the whole group, so every bot answers. */
    fun addressesEveryone(text:String):Boolean {
        val lower=text.lowercase().trim()
        // "team"/"guys" only count as a greeting or address ("hey team", "guys, …"), not "plan my team lunch".
        return Regex("(?<![a-z])(everyone|everybody|all of you|you all|y'?all|both of you)(?![a-z])").containsMatchIn(lower) ||
            Regex("^(?:(?:hey|hi|hello|yo|ok|okay|morning|good morning) )?(?:team|guys)(?![a-z])").containsMatchIn(lower) ||
            Regex("(?<![a-z])(?:hey|hi|hello|yo|thanks|thank you) (?:team|guys)(?![a-z])").containsMatchIn(lower)
    }

    /** Recent group conversation given to a bot as context, labelled by speaker. Private chats are never included, and neither are a bot's pieces of a case or its texts to colleagues (the case file carries those). */
    fun context(messages:List<Message>,limit:Int=14):String =
        messages.filter {it.team && it.kind !in setOf("step","ask")}.takeLast(limit).joinToString("\n") {m->
            (if(m.role=="user") "Owner" else m.bot.ifBlank {"Alpha"})+": "+m.text.replace(Regex("\\s+")," ").take(700)
        }
}
