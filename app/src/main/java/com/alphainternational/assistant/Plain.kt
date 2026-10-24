package com.alphainternational.assistant

import org.json.JSONArray
import org.json.JSONObject

/**
 * Plain words for the owner. Everything here is pure (no screens, no phone), so it is tested on a computer.
 * The stored words the logic compares ("Needs review", "Check result"...) never change; only what is drawn on screen does.
 */
object Words {
    /** What the group chat is called on screen. (The internal key Team.ROOM stays "Teamwork" because saved chats and clearing use it.) */
    const val TEAM_CHAT="Team chat"

    /** The stored states that mean "a person has to do something". (Shown with [status]; compared by logic as they are.) */
    val attention=setOf("Needs review","Needs you","Needs connection","Check result")

    /** What the owner reads for a job's state. */
    fun status(stored:String):String = when(stored) {
        "Needs review"->"Waiting for your OK"
        "Needs you"->"Waiting for you"
        "Needs connection"->"Needs a sign-in"
        "Check result"->"Please check"
        "Partial"->"Partly done"
        "Queued"->"Waiting its turn"
        "Running"->"Working"
        "Completed"->"Done"
        "Cancelled"->"Stopped"
        else->stored
    }
}

private const val GENERIC="Something went wrong. Please try again in a moment."

/** Plain words for an error: what happened and what to do next. Never a stack trace or a class name. */
fun plainError(e:Throwable?):String {
    if(e==null) return GENERIC
    val chain=generateSequence(e) {it.cause}.take(4).toList()
    val kinds=chain.joinToString(" ") {it.javaClass.name}
    val message=chain.mapNotNull {it.message}.firstOrNull {it.isNotBlank()}.orEmpty()
    return plainError(kinds,message)
}

/** The same rules for a message that is already text (a task's saved detail, a connection error). [kind] is an optional exception class name. */
fun plainError(kind:String,message:String,longest:Int=220):String {
    val k=kind.lowercase()
    val m=message.lowercase()
    fun code(n:Int)=Regex("(?:http|status|code|error|response)\\D{0,3}$n(?!\\d)|^\\s*$n(?!\\d)").containsMatchIn(m)
    return when {
        k.contains("cancellation") -> "Stopped."
        k.contains("outofmemory") || m.contains("out of memory") -> "The phone ran low on memory. Close a few other apps, then try again."
        m.contains("enospc") || m.contains("no space left") || m.contains("disk full") || m.contains("not enough space") ->
            "This phone is out of storage. Free up some space (old photos, videos or apps), then try again."
        k.contains("securityexception") || m.contains("permission denied") || m.contains("permission_denied") || m.contains("eacces") || m.contains("operation not permitted") ->
            "Android didn't allow that. Open your phone's Settings, then Apps, then Alpha Assistant, then Permissions, and allow it."
        m.contains("rate limit") || m.contains("rate_limit") || m.contains("usage limit") || m.contains("usage_limit") || m.contains("too many requests") || code(429) ->
            "I've hit a limit for now (too many requests). Wait a few minutes and try again, or check your ChatGPT usage."
        code(401) || m.contains("unauthorized") || m.contains("invalid_token") || m.contains("token expired") || m.contains("expired token") || m.contains("sign in again") ->
            "Your sign-in has run out. Open Marketplace and sign in again."
        code(403) || m.contains("forbidden") || m.contains("access denied") ->
            "That account isn't allowed to do this. Check that you're signed in to the right one."
        k.contains("sockettimeout") || k.contains("timeoutcancellation") || m.contains("timed out") || m.contains("timeout") ->
            "That took too long. Try again in a minute."
        k.contains("ssl") || m.contains("sslhandshake") || m.contains("certificate") ->
            "The secure connection failed. Check your connection and that your phone's date and time are right, then try again."
        k.contains("unknownhost") || k.contains("connectexception") || k.contains("noroutetohost") || k.contains("socketexception") ||
            m.contains("unable to resolve host") || m.contains("failed to connect") || m.contains("network is unreachable") || m.contains("connection reset") ||
            m.contains("connection abort") || m.contains("broken pipe") || m.contains("unexpected end of stream") ->
            "I can't reach the internet right now. Check your Wi-Fi or mobile data, then try again."
        code(500) || code(502) || code(503) || code(504) || m.contains("server_error") || m.contains("service unavailable") || m.contains("bad gateway") ||
            m.contains("error occurred while processing your request") || m.contains("had an error while processing") ->
            "The service had a problem on its side. Try again in a few minutes."
        k.contains("filenotfound") || k.contains("nosuchfile") || m.contains("no such file") ->
            "I can't find that file any more. It may have been deleted."
        k.contains("jsonexception") || m.contains("org.json") || m.contains("jsonexception") ->
            "Something unexpected came back. Please try again."
        else -> if(looksPlain(message,longest)) message.trim().let {if(it.last() in ".!?") it else "$it."} else GENERIC
    }
}

/** A message that was written for a person (a "use a shorter name" rule, say) can be shown as it is; anything that smells of code cannot. */
internal fun looksPlain(message:String,longest:Int=220):Boolean {
    val text=message.trim()
    if(text.isBlank() || text.length>longest || text.contains('\n')) return false
    val tell=listOf("exception","{","}","[object","stack trace","null","error:","errno")
    if(tell.any {text.contains(it,ignoreCase=true)}) return false
    // A class or package name ("java.io.IOException"), a stack line ("at com.x.Y.z(File.kt:12)") or a memory address.
    return !Regex("\\b(?:java|kotlin|android|javax|org|com)\\.[a-z]|\\bat [\\w.$]+\\(|\\b0x[0-9a-fA-F]{4,}").containsMatchIn(text)
}

/**
 * The few plain lines of what a job really did ("3 drafts saved", "0 sent", "File invoice-12.pdf made"), kept on the answer as [Message.receipt].
 * Tolerant on purpose: an old or odd value draws nothing instead of failing.
 */
object Receipts {
    const val MAX_LINES=8
    fun parse(raw:String):List<String> {
        val text=raw.trim()
        if(text.isBlank() || text=="[]" || text=="{}") return emptyList()
        val json=text.startsWith("[") || text.startsWith("{")
        // Plain text that isn't JSON at all is one line per row; JSON that can't be read draws nothing.
        val rows:List<String> = if(!json) text.lines() else runCatching {
            val array:JSONArray? = if(text.startsWith("[")) JSONArray(text) else JSONObject(text).optJSONArray("lines")
            if(array==null) emptyList() else (0 until array.length()).mapNotNull {i->
                when(val item=array.opt(i)) {
                    is String->item
                    is JSONObject->item.optString("text").ifBlank {item.optString("line").ifBlank {item.optString("label")}}
                    else->null
                }
            }
        }.getOrDefault(emptyList())
        return rows.map {it.replace(Regex("\\s+")," ").trim().take(120)}.filter {it.isNotBlank()}.distinct().take(MAX_LINES)
    }

    /** True for a line that tells what did NOT happen or was left for the owner ("0 sent", "couldn't open"), drawn with an info mark instead of a check. */
    fun isInfo(line:String)=Regex("(?i)^(0|no|none|nothing)\\b|couldn'?t|could not|didn'?t|did not|failed|not sent|skipped|missing|waiting").containsMatchIn(line.trim())
}

/** A job's saved detail line for the owner to read: raw trouble text becomes a plain sentence, ordinary sentences stay as they are. */
fun plainDetail(detail:String):String = if(detail.isBlank()) "" else plainError("",detail.trim(),longest=600)
