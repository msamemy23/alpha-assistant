package com.alphainternational.assistant

import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

fun obj(vararg pairs: Pair<String, Any?>) = JSONObject().apply { pairs.forEach { (k,v) -> put(k, v ?: JSONObject.NULL) } }
fun arr(items: Iterable<Any?>) = JSONArray().apply { items.forEach { put(it) } }
fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
fun JSONArray.strings(): List<String> = (0 until length()).map { optString(it) }
fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")
fun digest(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
fun randomUrl(bytes: Int = 32): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(bytes).also { SecureRandom().nextBytes(it) })

object Rules {
    fun httpsUrl(value: String): String {
        val uri = URI(value.trim())
        require(uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() && uri.userInfo == null) { "Use a complete HTTPS address without embedded credentials." }
        require(uri.port == -1 || uri.port == 443) { "Only standard HTTPS ports are supported." }
        return uri.toASCIIString()
    }
    fun origin(value: String): String {
        val uri = URI(httpsUrl(value))
        return "https://" + uri.host.lowercase()
    }
    fun fileName(value: String): String {
        require(value.matches(Regex("[A-Za-z0-9][A-Za-z0-9._ -]{0,99}")) && !value.contains("..")) { "Use a simple file name without folders or '..'." }
        require(value.substringAfterLast('.').lowercase() in setOf("txt","md","csv","json","html","css","js","svg","xml","webmanifest","pdf","docx","xlsx","pptx","jpg","png","webp","gif","woff2","mp3","wav","ogg","m4a","mp4","webm")) { "Choose a supported text, PDF, Word, Excel, PowerPoint, picture, sound or video file." }
        return value
    }
    fun textFileName(value:String):String {
        val name=fileName(value)
        require(name.substringAfterLast('.').lowercase() in setOf("txt","md","csv","json","html","css","js","svg","xml","webmanifest")) {"Choose .txt, .md, .csv, .json, .html, .css, .js, .svg, .xml or .webmanifest for text."}
        return name
    }
    fun tableName(value: String): String {
        require(value.matches(Regex("[a-zA-Z_][a-zA-Z0-9_]{0,62}"))) { "Invalid table name." }
        return value
    }
    fun validateCallback(params: Map<String,String>, state: String, returningId: String?): String {
        require(params["state"] == state && state.isNotBlank()) { "Sign-in state did not match. Start a new sign-in." }
        require(params["error"] == null) { "Sign-in was declined or failed." }
        require(!params["code"].isNullOrBlank()) { "No authorization code was returned." }
        val id = params["client_id"] ?: returningId
        require(!id.isNullOrBlank() && id != "dynamic_agent_client") { "The app registration did not finish." }
        require(returningId == null || returningId == id) { "Sign-in returned a different app registration." }
        return id
    }
    fun redact(value: String, secrets: Iterable<String> = emptyList()): String {
        var text = value
        secrets.filter { it.length >= 4 }.sortedByDescending { it.length }.forEach { text = text.replace(it, "[secret removed]") }
        text = text.replace(Regex("(?i)(Bearer\\s+)[A-Za-z0-9._~+/=-]+"), "$1[secret removed]")
        text = text.replace(Regex("\\beyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\b"), "[token removed]")
        text = text.replace(Regex("\\b(?:sk-|sk_|sb_secret_|sbp_|vcp_)[A-Za-z0-9_-]{12,}\\b"), "[key removed]")
        // Google sign-in tokens: access tokens (ya29.…), refresh tokens (1//…) and API keys (AIza…).
        text = text.replace(Regex("\\bya29\\.[A-Za-z0-9_.-]{20,}"), "[token removed]")
        text = text.replace(Regex("(?<![A-Za-z0-9/])1//[A-Za-z0-9_-]{30,}"), "[token removed]")
        text = text.replace(Regex("\\bAIza[A-Za-z0-9_-]{35}\\b"), "[key removed]")
        return text
    }
}

/** [team] messages live in the Teamwork group chat; [bot] is the bot that wrote an assistant message there. */
data class Message(val id:String, val role:String, val text:String, val taskId:String = "", val files:List<String> = emptyList(),
    val team:Boolean=false, val bot:String="", val model:String="", val at:Long=0,
    /** One emoji tapped on this text, like a tap-back: on a bot's text it's the owner's, on the owner's text it's the bot's. */
    val react:String="",
    /** What the bot looked at for this answer (pages with screenshots), as a JSON list; its steps as a JSON list of plain lines; and the follow-up buttons it offered. */
    val evidence:String="",val steps:String="",val followups:List<String> = emptyList(),
    /** What the job really did, as a JSON list of plain lines ("3 drafts saved", "0 sent"), shown as a strip under the answer. */
    val receipt:String="",
    /** "" for an ordinary text; "step" for one bot's piece of a team case (kept in the case file, not shown as its own text in the group chat). */
    val kind:String="",
    /** The team case this text belongs to, if any. */
    val caseId:String="") {
    fun json() = obj("id" to id,"role" to role,"text" to text,"taskId" to taskId,"files" to arr(files),"team" to team,"bot" to bot,"model" to model,"at" to at,"react" to react,"evidence" to evidence,"steps" to steps,"followups" to arr(followups),"receipt" to receipt,"kind" to kind,"case_id" to caseId)
    companion object { fun parse(j:JSONObject) = Message(j.optString("id"),j.optString("role"),j.optString("text"),j.optString("taskId"),
        j.optJSONArray("files")?.strings().orEmpty(),j.optBoolean("team"),j.optString("bot"),j.optString("model"),j.optLong("at"),j.optString("react"),
        j.optString("evidence"),j.optString("steps"),j.optJSONArray("followups")?.strings().orEmpty(),j.optString("receipt"),j.optString("kind"),j.optString("case_id")) }
}
/** A [private] memory is something the owner asked this bot to keep from the other bots. */
/**
 * [kind]: fact, person, preference (how the owner likes things), habit (what they usually do), lesson (what worked on a job),
 * or summary (where things stand). [task] is the request a learned memory came from, so the chat can show and undo it.
 */
data class Memory(val id:String, val title:String, val text:String, val source:String, val updated:Long, val workspace:String = "Alpha",val private:Boolean=false,
    val kind:String="fact",val task:String="") {
    fun json() = obj("id" to id,"title" to title,"text" to text,"source" to source,"updated" to updated,"workspace" to workspace,"private" to private,"kind" to kind,"task" to task)
    companion object { fun parse(j:JSONObject) = Memory(j.getString("id"),j.getString("title"),j.getString("text"),j.optString("source"),j.optLong("updated"),
        j.optString("workspace","Alpha"),j.optBoolean("private"),j.optString("kind","fact").ifBlank {"fact"},j.optString("task")) }
}
data class TaskRecord(val id:String,val title:String,val prompt:String,val status:String,val detail:String,val created:Long,
    val history:String="[]",val trace:List<String> = emptyList(),val effects:String="{}", val workspace:String="Alpha",val routineId:String="",
    val plan:String="[]",val completion:String="",val waitingConnection:String="",val from:String="",val team:Boolean=false,
    val model:String="",val effort:String="",
    /** How many bot-to-bot texts led here (a bot texting a bot texting a bot…); capped so two bots can't ask each other forever. */
    val hops:Int=0,
    /** Which group message this job answers: "open:<id>" when the owner didn't name anyone (each bot decides for itself), "named:<id>" when they did. */
    val group:String="",
    /** Plain lines for what this job has done so far ("Searched for ...", "Read ebay.com"), shown live and under the finished answer. */
    val steps:List<String> = emptyList(),
    /** The pages this job has looked at so far (a JSON list of [Source]), with the screenshots taken of them. */
    val evidence:String="",
    /** The team case this job is a step of, and which step; and a short digest of what the job found and did, for later requests to build on. */
    val caseId:String="",val step:String="",val digest:String="") {
    fun json() = obj("id" to id,"title" to title,"prompt" to prompt,"status" to status,"detail" to detail,"created" to created,"case_id" to caseId,"step" to step,"digest" to digest,
        "history" to history,"trace" to arr(trace),"effects" to effects,"workspace" to workspace,"routineId" to routineId,
        "plan" to plan,"completion" to completion,"waiting_connection" to waitingConnection,"from" to from,"team" to team,"model" to model,"effort" to effort,"hops" to hops,"group" to group,"steps" to arr(steps),"evidence" to evidence)
    companion object { fun parse(j:JSONObject) = TaskRecord(j.getString("id"),j.getString("title"),j.getString("prompt"),j.getString("status"),
        j.optString("detail"),j.optLong("created"),j.optString("history","[]"),j.optJSONArray("trace")?.strings()?:emptyList(),j.optString("effects","{}"),j.optString("workspace","Alpha"),j.optString("routineId"),
        j.optString("plan","[]"),j.optString("completion"),j.optString("waiting_connection"),j.optString("from"),j.optBoolean("team"),
        j.optString("model"),j.optString("effort"),j.optInt("hops"),j.optString("group"),j.optJSONArray("steps")?.strings()?:emptyList(),j.optString("evidence"),
        j.optString("case_id"),j.optString("step"),j.optString("digest")) }
}
/** A routine runs either every [hours] or, when [time] is set, at that local time on [days]. Reporting routines post their answer in Chat. */
data class Routine(val id:String,val title:String,val prompt:String,val hours:Int,val workspace:String="Alpha",
    val time:String="",val days:String="daily",val report:Boolean=false) {
    fun json() = obj("id" to id,"title" to title,"prompt" to prompt,"hours" to hours,"workspace" to workspace,"time" to time,"days" to days,"report" to report)
    companion object { fun parse(j:JSONObject) = Routine(j.getString("id"),j.getString("title"),j.getString("prompt"),j.getInt("hours"),j.optString("workspace","Alpha"),
        j.optString("time"),j.optString("days","daily"),j.optBoolean("report",false)) }
}
/**
 * A named assistant with its own chat, memory, files, routines and standing rules (Grok Bot's "Bots").
 * [apps] are the sign-ins its job uses, [skills] the skills it uses first and [tools] the tools made for it; every bot can still use everything.
 */
data class Bot(val name:String,val role:String,val rules:String="",val apps:List<String> = emptyList(),val skills:List<String> = emptyList(),val tools:List<String> = emptyList(),
    /** This bot's own model and intelligence level, chosen by the owner. Blank means "use the main setting". */
    val model:String="",val effort:String="") {
    fun json() = obj("name" to name,"role" to role,"rules" to rules,"apps" to arr(apps),"skills" to arr(skills),"tools" to arr(tools),"model" to model,"effort" to effort)
    companion object {
        fun parse(j:JSONObject) = Bot(j.getString("name"),j.optString("role"),j.optString("rules"),j.optJSONArray("apps")?.strings().orEmpty(),
            j.optJSONArray("skills")?.strings().orEmpty(),j.optJSONArray("tools")?.strings().orEmpty(),j.optString("model"),j.optString("effort"))
        /** The bots a new install starts with (the friends build starts with one general bot and nothing about the owner's shop). */
        val defaults:List<Bot> get()=Profile.starterBots()
        fun name(value:String):String {
            val name=value.trim().replace(Regex("\\s+")," ")
            require(name.matches(Regex("[A-Za-z][A-Za-z0-9 ]{0,19}"))) {"Use a short bot name: letters, numbers and spaces, up to 20 characters."}
            return name
        }
    }
}
/** One-tap starting points for new bots, modeled on Grok Bot's specialist roles. */
data class BotTemplate(val name:String,val role:String,val rules:String)
object BotTemplates {
    val all=listOf(
        BotTemplate("Chief of Staff","Runs my day: morning briefs, priorities, follow-ups, and hands work to my other bots.",
            "Keep briefs under 15 lines. Never send, accept, delete or buy anything without asking me."),
        BotTemplate("Researcher","Finds, checks and summarizes information from the web with sources.","Always cite sources with links and say when facts were checked."),
        BotTemplate("Marketing","Plans and writes social posts, flyers, offers and ads.","Match my brand voice. Drafts only; I post."),
        BotTemplate("Sales","Finds leads and drafts follow-ups to win customers.","Never contact anyone without my approval."),
        BotTemplate("Bookkeeper","Tracks receipts, expenses and invoices and makes simple money summaries.","Use exact numbers from receipts. Never move money."),
        BotTemplate("Recruiter","Writes job posts, screens applicants and organizes hiring.","Treat applicants fairly. Never contact them without my approval."),
        BotTemplate("Shopper","Compares products and prices and watches for deals.","Never buy or add to cart; give me links."),
        BotTemplate("Travel","Plans trips and outings with times, costs and links.","Never book or pay; give me booking links."),
        BotTemplate("Writer","Writes and polishes letters, emails, posts and documents.","Keep it short and in my voice."),
        BotTemplate("Tutor","Explains things simply and helps me learn new skills.","Use plain language and everyday examples.")
    )
}
/** A workflow the owner taught Alpha. Shared by all bots, like Grok Bot's skill library. */
data class UserSkill(val id:String,val title:String,val purpose:String,val instructions:String,val updated:Long) {
    fun json() = obj("id" to id,"title" to title,"purpose" to purpose,"instructions" to instructions,"updated" to updated)
    companion object { fun parse(j:JSONObject) = UserSkill(j.getString("id"),j.getString("title"),j.optString("purpose"),j.getString("instructions"),j.optLong("updated")) }
}
data class Tool(val name:String,val description:String,val parameters:JSONObject,val review:Boolean=false) {
    fun json() = obj("type" to "function","name" to name,"description" to description,"parameters" to parameters,"strict" to true)
}
fun schema(vararg fields:Pair<String,String>):JSONObject = obj("type" to "object","properties" to JSONObject().apply {
    fields.forEach { (name,type) -> put(name,obj("type" to type)) }
},"required" to arr(fields.map { it.first }),"additionalProperties" to false)
data class Approval(val taskId:String,val tool:String,val arguments:String,val preview:String="")

/** Hand-offs to the phone's own apps. Alpha fills in details; the owner always taps Call or Send. */
object PhoneActions {
    data class Target(val action:String,val uri:String,val app:String)
    private fun encode(value:String)=URLEncoder.encode(value,"UTF-8").replace("+","%20")
    fun target(action:String,to:String,subject:String="",body:String=""):Target {
        val kind=action.trim().lowercase()
        fun phone():String {
            val number=to.filter {it.isDigit() || it=='+'}
            require(number.count {it.isDigit()} in 3..15 && number.lastIndexOf('+')<=0) {"Give a phone number with 3 to 15 digits."}
            return number
        }
        require(subject.length<=200 && body.length<=2000) {"Keep the subject under 200 and the message under 2,000 characters."}
        return when(kind) {
            "call"->Target(kind,"tel:"+phone(),"the phone dialer")
            "text"->Target(kind,"smsto:"+phone(),"Messages")
            "email"-> {
                val address=to.trim()
                require(address.matches(Regex("[^@\\s?&]+@[^@\\s?&]+\\.[^@\\s?&]+"))) {"Give a complete email address."}
                Target(kind,"mailto:"+address+"?subject="+encode(subject)+"&body="+encode(body),"the email app")
            }
            "map"-> {require(to.isNotBlank() && to.length<=200) {"Give a place or address."};Target(kind,"geo:0,0?q="+encode(to.trim()),"Maps")}
            "web"->Target(kind,Rules.httpsUrl(to),"the web browser")
            else->throw IllegalArgumentException("Use call, text, email, map or web.")
        }
    }
}
