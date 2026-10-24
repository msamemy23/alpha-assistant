package com.alphainternational.assistant

import org.json.JSONArray
import org.json.JSONObject

/**
 * Brainstorm: a plain chat with the AI, like talking to ChatGPT, to think an idea through. It is its own thing (its own screen, not part of the team and not on
 * top of Home): no tools, no bots, nothing is sent, saved to the shop or changed. The owner asked for exactly this on 2026-10-11 ("like talking to ChatGPT. but i
 * dont want that at the top. i want it as its own thing"). Pure rules here, so they are tested on a computer; the screen is in BrainstormUi.kt and the
 * network call in the Controller.
 */
object Brainstorm {
    /** How many chats are kept, how many texts a chat keeps, and the longest text. */
    const val MAX_CHATS=40
    const val MAX_MESSAGES=120
    const val MAX_CHARS=40_000
    /** About how much of a chat goes to the AI each time: the newest texts that fit. */
    const val CONTEXT_CHARS=60_000

    data class Msg(val role:String,val text:String,val at:Long)
    data class Chat(val id:String,val title:String,val updated:Long,val messages:List<Msg>) {
        val isBlank get()=messages.isEmpty()
    }

    const val UNTITLED="New chat"

    /** A chat's name from its first words: one short line, no marks. */
    fun titleOf(text:String):String {
        val line=text.lines().firstOrNull {it.isNotBlank()}.orEmpty().replace(Regex("[*_`#>]"),"").replace(Regex("\\s+")," ").trim()
        if(line.isBlank()) return UNTITLED
        return if(line.length<=48) line else line.take(48).substringBeforeLast(' ',line.take(48)).trimEnd(',',';',':','-')+"…"
    }

    fun newChat(id:String,now:Long)=Chat(id,UNTITLED,now,emptyList())

    /** [chat] with one more text. The first thing the owner says names the chat. */
    fun add(chat:Chat,role:String,text:String,now:Long):Chat {
        val kept=text.trim().take(MAX_CHARS)
        if(kept.isBlank()) return chat
        val messages=(chat.messages+Msg(role,kept,now)).takeLast(MAX_MESSAGES)
        val title=if(chat.title==UNTITLED && role=="user") titleOf(kept) else chat.title
        return chat.copy(title=title,updated=now,messages=messages)
    }

    /** Newest first, empty chats dropped (an empty one is only the screen waiting for a first word), and no more than [MAX_CHATS]. */
    fun tidy(chats:List<Chat>):List<Chat> = chats.filterNot {it.isBlank}.sortedByDescending {it.updated}.take(MAX_CHATS)

    // ---- saved as one small file ----
    fun toJson(chats:List<Chat>):String = JSONObject().put("chats",JSONArray(tidy(chats).map {c->
        JSONObject().put("id",c.id).put("title",c.title).put("updated",c.updated).put("messages",JSONArray(c.messages.map {m->
            JSONObject().put("role",m.role).put("text",m.text).put("at",m.at)}))})).toString()

    fun parse(text:String?):List<Chat> {
        if(text.isNullOrBlank()) return emptyList()
        return runCatching {
            val array=JSONObject(text).optJSONArray("chats")?:return emptyList()
            (0 until array.length()).mapNotNull {i->
                val c=array.optJSONObject(i)?:return@mapNotNull null
                val id=c.optString("id").takeIf {it.isNotBlank()}?:return@mapNotNull null
                val messages=c.optJSONArray("messages")?.let {a->(0 until a.length()).mapNotNull {j->
                    a.optJSONObject(j)?.let {m->m.optString("text").takeIf {t->t.isNotBlank()}?.let {t->Msg(m.optString("role").takeIf {r->r=="assistant"}?:"user",t.take(MAX_CHARS),m.optLong("at"))}}
                }}.orEmpty()
                Chat(id,c.optString("title").ifBlank {UNTITLED},c.optLong("updated"),messages.takeLast(MAX_MESSAGES))
            }
        }.getOrDefault(emptyList())
    }

    // ---- what goes to the AI ----
    /** The conversation as the AI takes it: the newest texts that fit [CONTEXT_CHARS], oldest first, always ending with the owner's latest. */
    fun input(messages:List<Msg>):JSONArray {
        val kept=ArrayList<Msg>();var size=0
        for(m in messages.asReversed()) {
            if(kept.isNotEmpty() && size+m.text.length>CONTEXT_CHARS) break
            kept+=m;size+=m.text.length
        }
        return JSONArray(kept.asReversed().map {m->JSONObject().put("role",m.role).put("content",m.text)})
    }

    /** A brainstorm is real thinking, never small talk: at least the Everyday level, and the hard one for research, comparing and strategy. */
    fun level(text:String):ModelPlan.Level = EffortPlan.higher(ModelPlan.level(text),ModelPlan.Level.EVERYDAY)

    fun instructions(shop:String,today:String):String =
        "You are the owner's brainstorming partner inside Alpha, like talking to ChatGPT: they think an idea through with you.\n"+
        "- Be warm, direct and concrete. Build on what they say instead of repeating it. Give options with a view on which is best and why, point out what could go wrong, and ask one good question when it would move things on.\n"+
        "- Match their length: a quick thought gets a short reply, a big question a thorough one. Plain words, short paragraphs, a short list when it helps. No headings unless the answer is long.\n"+
        "- You have no phone tools in this chat. You cannot send, save, look up the shop's records or change anything, and you are not their team of bots. When something needs doing, say what to ask their team and offer to word the message.\n"+
        "- You can search the web for current facts; say when you did. Never invent facts, prices, quotes or sources: say when you are guessing.\n"+
        "- Today is $today."+(if(shop.isBlank()) "" else "\n- The owner runs $shop. Use that when it helps, but this chat is for any idea, personal or business.")
}
