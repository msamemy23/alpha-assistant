package com.alphainternational.assistant

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Settings > Backup. One file, locked with a passphrase the owner types, that holds what makes Alpha theirs: bots, memories, skills, routines,
 * the tools bots made, settings, other apps' saved data, and (if chosen) chats, past jobs and the text files bots made. It never holds a sign-in:
 * ChatGPT, Google, Supabase, Vercel, Telnyx, connected apps, voice keys and the login vault are left out, so after a restore the owner signs in again.
 *
 * The file: "ALPHABK" + format number, then PBKDF2-HMAC-SHA256 settings (iterations, a random salt), a random IV, and the packed content sealed with
 * AES-256-GCM. The header is part of what the seal checks, so changing any byte (or the wrong passphrase) makes it refuse to open. Everything here is
 * plain Kotlin, tested on a computer. The passphrase is never stored or logged.
 */
object Backup {
    const val EXTENSION=".alphabackup"
    const val FORMAT=1
    /** Slow on purpose: each guess at the passphrase costs this much work. Fine on a phone (about half a second), painful for an attacker. */
    const val ITERATIONS=210_000
    const val MIN_PASSPHRASE=8
    private val magic="ALPHABK".toByteArray(Charsets.US_ASCII)
    private const val HEADER=7+1+1+4+16+12
    /** The most that goes into one backup file before the oldest chats are left out. */
    const val MAX_CONTENT_BYTES=24*1024*1024
    const val MAX_MESSAGES=4000
    const val MAX_FILE_CHARS=100_000
    const val MAX_FILES_CHARS=8*1024*1024

    class BackupProblem(message:String):Exception(message)

    data class Options(val history:Boolean=true,val files:Boolean=false)

    // ---- The locked file ----

    fun seal(content:String,passphrase:CharArray,iterations:Int=ITERATIONS,random:SecureRandom=SecureRandom()):ByteArray {
        require(passphrase.size>=MIN_PASSPHRASE) {"Use a passphrase of at least $MIN_PASSPHRASE characters."}
        val salt=ByteArray(16).also {random.nextBytes(it)};val iv=ByteArray(12).also {random.nextBytes(it)}
        val header=ByteBuffer.allocate(HEADER).put(magic).put(FORMAT.toByte()).put(1.toByte()).putInt(iterations).put(salt).put(iv).array()
        val packed=ByteArrayOutputStream().also {out->GZIPOutputStream(out).use {it.write(content.toByteArray(Charsets.UTF_8))}}.toByteArray()
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply {init(Cipher.ENCRYPT_MODE,key(passphrase,salt,iterations),GCMParameterSpec(128,iv));updateAAD(header)}
        return header+cipher.doFinal(packed)
    }

    /** The content, or a [BackupProblem] in plain words: not a backup, a newer backup, or the wrong passphrase / a changed file. */
    fun open(data:ByteArray,passphrase:CharArray):String {
        if(data.size<HEADER+16 || !data.copyOfRange(0,7).contentEquals(magic)) throw BackupProblem("That file isn't an Alpha backup.")
        val format=data[7].toInt()
        if(format>FORMAT) throw BackupProblem("This backup was made by a newer Alpha. Update Alpha, then try again.")
        val buffer=ByteBuffer.wrap(data,9,4);val iterations=buffer.int
        if(data[8].toInt()!=1 || iterations !in 1_000..5_000_000) throw BackupProblem("That file isn't an Alpha backup.")
        val salt=data.copyOfRange(13,29);val iv=data.copyOfRange(29,41)
        val packed=try {
            val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply {init(Cipher.DECRYPT_MODE,key(passphrase,salt,iterations),GCMParameterSpec(128,iv));updateAAD(data,0,HEADER)}
            cipher.doFinal(data,HEADER,data.size-HEADER)
        } catch(e:Exception) {throw BackupProblem("That passphrase doesn't open this backup, or the file was changed.")}
        return try {GZIPInputStream(packed.inputStream()).use {limited(it)}.toString(Charsets.UTF_8)} catch(e:Exception) {throw BackupProblem("That backup is damaged.")}
    }
    private fun limited(stream:java.io.InputStream):ByteArray {
        val out=ByteArrayOutputStream();val buffer=ByteArray(64*1024);var total=0L
        while(true) {
            val n=stream.read(buffer);if(n<0) break
            total+=n;if(total>256L*1024*1024) throw IllegalStateException("too large")
            out.write(buffer,0,n)
        }
        return out.toByteArray()
    }
    private fun key(passphrase:CharArray,salt:ByteArray,iterations:Int):SecretKeySpec {
        val spec=PBEKeySpec(passphrase,salt,iterations,256)
        try {return SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded,"AES")} finally {spec.clearPassword()}
    }

    // ---- What goes in ----

    /** Sign-ins and keys: left out whole, however they are stored. After a restore the owner signs in again. */
    private val secretConfigs=setOf("chatgpt","google","supabase","supabase_management","vercel","telnyx","mcp","mcp_servers","voice_cloud","sms_senders")
    private val secretKey=Regex("(?i)(token|secret|passw|api[-_ ]?key|^key$|^key[_-]|[_-]key$|authorization|bearer|cookie|credential|private[_-]?key|signature|session|otp|pin_?code)")
    /** Whether a saved setting is a sign-in or key that must not go into a backup file. */
    fun isSecretConfig(name:String)=name in secretConfigs || secretKey.containsMatchIn(name)

    /**
     * One saved setting made safe: null when it is a sign-in or key as a whole; otherwise a copy with every key-like field removed and every value
     * that looks like a secret blanked. (Other parts of Alpha keep their own data here, e.g. team cases, goals and follow-ups; those are kept.)
     */
    fun scrubConfig(name:String,value:JSONObject):JSONObject? {
        if(isSecretConfig(name)) return null
        val clean=scrub(value) as JSONObject
        return clean.takeIf {it.length()>0}
    }
    private fun scrub(value:Any?):Any? = when(value) {
        is JSONObject-> JSONObject().also {out->value.keys().forEach {k->if(!secretKey.containsMatchIn(k)) scrub(value.opt(k)).let {v->if(v!=null) out.put(k,v)}}}
        is JSONArray-> JSONArray().also {out->(0 until value.length()).forEach {i->scrub(value.opt(i))?.let {out.put(it)}}}
        is String-> if(Rules.redact(value)!=value || value.startsWith("ya29.") || value.startsWith("AIza") || value.startsWith("xox")) "" else value
        else->value
    }

    /** A task as it goes in a backup: what it asked and found, not the long working notes or screenshots (they can't be used on another phone). */
    fun compactTask(t:TaskRecord)=t.copy(history="[]",trace=emptyList(),effects="{}",evidence="",plan="[]",waitingConnection="",
        status=if(t.status in setOf("Completed","Partial","Cancelled")) t.status else "Paused")

    /**
     * Gathers everything into the backup's content. [files]: bot → file name → text (only when chosen). The chats are capped at the newest
     * [MAX_MESSAGES]; if the whole is still too big, the oldest chats are dropped until it fits.
     */
    fun collect(now:Long,appVersion:String,options:Options,ai:JSONObject,bots:List<Bot>,memories:List<Memory>,skills:List<UserSkill>,routines:List<Routine>,
        tools:List<CustomTool>,blocked:List<Learning.Blocked>,configs:Map<String,JSONObject>,tasks:List<TaskRecord>,messages:List<Message>,files:Map<String,Map<String,String>>):JSONObject {
        val keptConfigs=JSONObject().also {out->configs.forEach {(name,value)->scrubConfig(name,value)?.let {out.put(name,it)}}}
        var chat=if(options.history) messages.takeLast(MAX_MESSAGES) else emptyList()
        fun build():JSONObject {
            val referenced=chat.map {it.taskId}.toSet()
            val jobs=if(options.history) tasks.filter {it.id in referenced || it.digest.isNotBlank()}.map(::compactTask) else emptyList()
            return obj("kind" to "alpha-backup","format" to FORMAT,"created" to now,"app_version" to appVersion,
                "includes" to obj("history" to options.history,"files" to options.files),
                "ai" to ai,"bots" to arr(bots.map {it.json()}),"memories" to arr(memories.map {it.json()}),"skills" to arr(skills.map {it.json()}),
                "routines" to arr(routines.map {it.json()}),"custom_tools" to arr(tools.map {it.json()}),"blocklist" to arr(blocked.map {it.json()}),
                "configs" to keptConfigs,"tasks" to arr(jobs.map {it.json()}),
                "messages" to arr(chat.map {it.copy(evidence="").json()}),
                "files" to JSONObject().also {out->if(options.files) {
                    var budget=MAX_FILES_CHARS
                    files.forEach {(bot,list)->out.put(bot,JSONObject().also {inner->list.forEach {(name,text)->if(text.length<=MAX_FILE_CHARS && budget>=text.length) {inner.put(name,text);budget-=text.length}}})}
                }})
        }
        var content=build()
        while(content.toString().length>MAX_CONTENT_BYTES && chat.size>100) {chat=chat.drop(chat.size/4);content=build()}
        return content
    }

    /** Plain lines for the owner to read before they restore: when it was made and what is inside. */
    fun summary(content:JSONObject,zone:java.time.ZoneId=java.time.ZoneId.systemDefault()):List<String> {
        fun count(key:String)=content.optJSONArray(key)?.length()?:0
        fun many(n:Int,one:String,more:String)=if(n==1) "1 $one" else "$n $more"
        val made=java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy 'at' h:mm a").withZone(zone).format(java.time.Instant.ofEpochMilli(content.optLong("created")))
        val lines=mutableListOf("Made on $made"+content.optString("app_version").takeIf {it.isNotBlank()}?.let {" by Alpha $it"}.orEmpty())
        lines+=listOf(many(count("bots"),"bot","bots"),many(count("memories"),"memory","memories"),many(count("skills"),"skill","skills"),
            many(count("routines"),"routine","routines"),many(count("custom_tools"),"tool your bots made","tools your bots made")).filterNot {it.startsWith("0 ")}.joinToString(" · ").ifBlank {null}?.let {listOf(it)}.orEmpty()
        if(count("messages")>0 || count("tasks")>0) lines+=many(count("messages"),"chat text","chat texts")+" · "+many(count("tasks"),"past job","past jobs")
        val fileCount=content.optJSONObject("files")?.let {f->f.keys().asSequence().sumOf {f.optJSONObject(it)?.length()?:0}}?:0
        if(fileCount>0) lines+=many(fileCount,"text file","text files")
        lines+="No sign-ins are inside: you sign in to ChatGPT and your other accounts again after restoring."
        return lines
    }

    // ---- Putting it back ----

    /** A backup's content as typed lists. A damaged entry is skipped, never the whole backup. */
    class Parsed(val content:JSONObject,val ai:JSONObject?,val bots:List<Bot>,val memories:List<Memory>,val skills:List<UserSkill>,val routines:List<Routine>,
        val tools:List<CustomTool>,val blocked:List<Learning.Blocked>,val configs:Map<String,JSONObject>,val tasks:List<TaskRecord>,val messages:List<Message>,
        val files:Map<String,Map<String,String>>)
    fun parse(text:String):Parsed {
        val content=try {JSONObject(text)} catch(e:Exception) {throw BackupProblem("That backup is damaged.")}
        if(content.optString("kind")!="alpha-backup") throw BackupProblem("That file isn't an Alpha backup.")
        fun <T> list(key:String,parse:(JSONObject)->T)=parseList(content,key).mapNotNull {runCatching {parse(it)}.getOrNull()}
        val configs=content.optJSONObject("configs")?.let {c->c.keys().asSequence().mapNotNull {k->c.optJSONObject(k)?.let {k to it}}.toMap()}.orEmpty()
        // Whatever a file says, a sign-in or key in it is never put back (a backup from somewhere else is not trusted with the phone's accounts).
        val safeConfigs=configs.mapNotNull {(k,v)->scrubConfig(k,v)?.let {k to it}}.toMap()
        val files=content.optJSONObject("files")?.let {f->f.keys().asSequence().associateWith {bot->
            f.optJSONObject(bot)?.let {inner->inner.keys().asSequence().associateWith {inner.optString(it)}}.orEmpty()
        }}.orEmpty()
        return Parsed(content,content.optJSONObject("ai"),list("bots",Bot::parse),list("memories",Memory::parse),list("skills",UserSkill::parse),list("routines",Routine::parse),
            list("custom_tools",CustomTool::parse),list("blocklist",Learning.Blocked::parse),safeConfigs,list("tasks",TaskRecord::parse),list("messages",Message::parse),files)
    }

    /** Notes from a backup added to what is there: a note is added if it is new, and replaces the current one only if the backup's is newer. */
    fun mergeMemories(current:List<Memory>,incoming:List<Memory>,bots:Set<String>):List<Memory> {
        val have=current.associateBy {it.id}
        val add=incoming.filter {it.workspace in bots && (have[it.id]==null || have[it.id]!!.updated<it.updated)}
        return current.filterNot {c->add.any {it.id==c.id}}+add
    }
    fun mergeSkills(current:List<UserSkill>,incoming:List<UserSkill>):List<UserSkill> {
        val have=current.associateBy {it.id}
        val add=incoming.filter {have[it.id]==null || have[it.id]!!.updated<it.updated}
        return current.filterNot {c->add.any {it.id==c.id}}+add
    }
    fun mergeTools(current:List<CustomTool>,incoming:List<CustomTool>):List<CustomTool> = current+incoming.filter {n->current.none {it.id==n.id}}
    /** Chats and past jobs from a backup that this phone doesn't already have (by id), in time order. */
    fun mergeMessages(current:List<Message>,incoming:List<Message>):List<Message> {
        val have=current.map {it.id}.toSet()
        return (current+incoming.filter {it.id !in have}).sortedBy {it.at}
    }
    fun mergeTasks(current:List<TaskRecord>,incoming:List<TaskRecord>):List<TaskRecord> {
        val have=current.map {it.id}.toSet()
        return current+incoming.filter {it.id !in have}.sortedByDescending {it.created}
    }
    fun parseList(content:JSONObject,key:String)=content.optJSONArray(key)?.objects().orEmpty()
}
