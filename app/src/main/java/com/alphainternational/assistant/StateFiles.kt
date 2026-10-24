package com.alphainternational.assistant

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Seals and opens saved files. The real one (see SecureStore) keeps its key behind the phone's Keystore; tests use [AesCrypto] with a plain key,
 * so the whole storage engine below runs on a computer without a phone.
 */
interface DataCrypto {
    fun seal(plain:ByteArray):ByteArray
    fun open(sealed:ByteArray):ByteArray
}

/** AES-256-GCM, a fresh random IV for every file: [marker][12-byte IV][encrypted bytes + tag]. A changed or cut-off file fails to open. */
class AesCrypto(key:ByteArray):DataCrypto {
    private val spec:SecretKeySpec
    private val random=SecureRandom()
    init {require(key.size==32) {"The key must be 32 bytes."};spec=SecretKeySpec(key,"AES")}
    override fun seal(plain:ByteArray):ByteArray {
        val iv=ByteArray(12).also {random.nextBytes(it)}
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply {init(Cipher.ENCRYPT_MODE,spec,GCMParameterSpec(128,iv))}
        return byteArrayOf(MARKER)+iv+cipher.doFinal(plain)
    }
    override fun open(sealed:ByteArray):ByteArray {
        require(sealed.size>1+12+16 && sealed[0]==MARKER) {"Saved file is incomplete."}
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply {init(Cipher.DECRYPT_MODE,spec,GCMParameterSpec(128,sealed.copyOfRange(1,13)))}
        return cipher.doFinal(sealed,13,sealed.size-13)
    }
    companion object {const val MARKER:Byte=0x41}
}

/** Text in, sealed bytes out (squeezed first when it's worth it: task histories are mostly repeated JSON). The squeeze flag sits inside the sealed part. */
object Packed {
    private const val RAW:Byte=1
    private const val SQUEEZED:Byte=2
    fun pack(crypto:DataCrypto,text:String):ByteArray {
        val raw=text.toByteArray(Charsets.UTF_8)
        if(raw.size<512) return crypto.seal(byteArrayOf(RAW)+raw)
        val out=ByteArrayOutputStream(raw.size/3+16)
        out.write(SQUEEZED.toInt())
        GZIPOutputStream(out).use {it.write(raw)}
        return crypto.seal(out.toByteArray())
    }
    fun unpack(crypto:DataCrypto,data:ByteArray):String {
        val plain=crypto.open(data)
        require(plain.isNotEmpty()) {"Saved file is empty."}
        return when(plain[0]) {
            RAW->String(plain,1,plain.size-1,Charsets.UTF_8)
            SQUEEZED->GZIPInputStream(java.io.ByteArrayInputStream(plain,1,plain.size-1)).use {limited(it)}.toString(Charsets.UTF_8)
            else->throw IllegalArgumentException("Saved file has an unknown format.")
        }
    }
    private fun limited(stream:java.io.InputStream):ByteArray {
        val out=ByteArrayOutputStream();val buffer=ByteArray(64*1024);var total=0L
        while(true) {
            val n=stream.read(buffer);if(n<0) break
            total+=n;require(total<=512L*1024*1024) {"Saved file is too large."}
            out.write(buffer,0,n)
        }
        return out.toByteArray()
    }
}

/** Writes a file so a crash or power cut leaves either the whole old file or the whole new one. */
object SafeFile {
    fun write(target:File,bytes:ByteArray) {
        target.parentFile?.mkdirs()
        val temp=File(target.parentFile,target.name+"."+System.nanoTime().toString(36)+".tmp")   // (unique, so two writers never trip over one temporary file)
        FileOutputStream(temp).use {it.write(bytes);it.fd.sync()}
        try {Files.move(temp.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)}
        catch(e:java.nio.file.AtomicMoveNotSupportedException) {Files.move(temp.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING)}
    }
}

/** An old finished request that left memory to keep storage small: just enough to find it again by search. */
data class RetiredTask(val id:String,val title:String,val prompt:String,val workspace:String,val created:Long,val status:String,val detail:String,val digest:String) {
    fun json()=obj("id" to id,"title" to title,"prompt" to prompt,"workspace" to workspace,"created" to created,"status" to status,"detail" to detail,"digest" to digest)
    companion object {
        fun of(t:TaskRecord)=RetiredTask(t.id,t.title.take(120),t.prompt.take(600),t.workspace,t.created,t.status,t.detail.take(300),t.digest)
        fun parse(j:JSONObject)=RetiredTask(j.getString("id"),j.optString("title"),j.optString("prompt"),j.optString("workspace","Alpha"),j.optLong("created"),
            j.optString("status"),j.optString("detail"),j.optString("digest"))
    }
}

/** Thrown when the saved data can't be opened right now (the phone's key store isn't answering). Nothing is changed or written; the app tries again next time. */
class StoreUnavailable(message:String,cause:Throwable?=null):Exception(message,cause)

/**
 * Everything Alpha remembers, kept as separate encrypted files in one folder so a save only rewrites what changed and one bad file never takes
 * the rest with it:
 *   core.enc (+ core.bak1..3.enc)  bots, routines, memories, settings, connections and other small state; three older good copies are kept.
 *   tasks/<id>.enc                 one request with its full working history; written only when that request changed.
 *   messages/m-<n>.enc             the chat, 100 texts per file, in order; only the file that changed is rewritten.
 *   archive/a-<n>.enc              older chat texts that no longer load into memory but are still searched; archive/t-<n>.enc old finished requests.
 * Pure Kotlin and org.json, so it is tested on a computer.
 */
class StateFiles(val dir:File,private val crypto:DataCrypto,private val clock:()->Long={System.currentTimeMillis()}) {
    companion object {
        /** Chat texts per file. Files are dropped from the front whole, so the others never move. */
        const val SEGMENT=100
        const val KEEP_BACKUPS=3
        /** A new backup copy of the core file is made at most this often (otherwise all three would be minutes old). */
        const val BACKUP_EVERY_MS=10*60_000L
        const val RETIRED_PER_FILE=200
        /** The old single-file save is kept this long after the new files verify, then deleted. */
        const val MIGRATED_KEEP_MS=14L*24*3600*1000
        val BULK_KEYS=setOf("tasks","messages")
    }
    private val taskDir=File(dir,"tasks")
    private val messageDir=File(dir,"messages")
    private val archiveDir=File(dir,"archive")
    private val badDir=File(dir,"unreadable")
    private val coreFile=File(dir,"core.enc")
    fun backupFile(n:Int)=File(dir,"core.bak$n.enc")

    // What is on disk right now, so a save writes only what is different.
    private val diskTasks=HashMap<String,TaskRecord>()
    private val diskSegments=HashMap<Int,List<Message>>()
    private var diskCore=""
    private var baseSeq=0
    private var baselineKnown=false
    /** When set (the key store couldn't be opened), nothing is written, so nothing good is ever overwritten by an empty start. */
    @Volatile var readOnly=false
    /** How many of the chat files handed to the last [write] reached the archive, so a failed save can hand the rest back without writing any twice. */
    @Volatile var archivedSegmentsDone=0

    class Loaded(val core:JSONObject,val notices:List<String>,val legacyTasks:JSONArray?=null,val legacyMessages:JSONArray?=null,val restoredFromBackup:Boolean=false) {
        val fromOldFormat get()=legacyTasks!=null
    }
    class Bulk(val tasks:List<TaskRecord>,val messages:List<Message>,val lost:Int,val notices:List<String>)
    class Snapshot(val core:String,val tasks:List<TaskRecord>?,val messages:List<Message>?,
        /** Whole files of chat texts that left memory; saved to the archive before their chat files are deleted. */
        val archivedMessages:List<List<Message>> = emptyList(),val retired:List<RetiredTask> = emptyList()) {
        fun merged(newer:Snapshot)=Snapshot(newer.core,newer.tasks,newer.messages,archivedMessages+newer.archivedMessages,retired+newer.retired)
    }
    class Report(val coreWritten:Boolean=false,val tasksWritten:Int=0,val tasksDeleted:Int=0,val segmentsWritten:Int=0,val archivedTexts:Int=0,val retired:Int=0)

    private fun taskFile(id:String)=File(taskDir,id.replace(Regex("[^A-Za-z0-9_-]"),"_").take(100)+".enc")
    private fun messageFile(seq:Int)=File(messageDir,"m-%06d.enc".format(seq))
    private fun archiveFile(seq:Int)=File(archiveDir,"a-%06d.enc".format(seq))
    private fun readJson(file:File):JSONObject? = try {JSONObject(Packed.unpack(crypto,file.readBytes()))} catch(e:StoreUnavailable) {throw e} catch(e:Exception) {null}
    private fun day(time:Long)=java.time.format.DateTimeFormatter.ofPattern("MMM d").withZone(java.time.ZoneId.systemDefault()).format(java.time.Instant.ofEpochMilli(time))
    /** A file that can't be opened is moved aside (not deleted), so it can still be looked at or rescued later. */
    private fun quarantine(file:File) {
        runCatching {
            badDir.mkdirs()
            Files.move(file.toPath(),File(badDir,file.name+"."+clock()).toPath(),StandardCopyOption.REPLACE_EXISTING)
        }.onFailure {runCatching {file.delete()}}
    }
    private fun clearTemps() {
        listOf(dir,taskDir,messageDir,archiveDir).forEach {d->d.listFiles()?.filter {it.name.endsWith(".tmp")}?.forEach {runCatching {it.delete()}}}
    }

    // ---- Loading ----

    /**
     * The core file, or the newest good backup of it if it is damaged (the owner is told in plain words), or the old single-file save if this is the
     * first start since the update. [oldFormat] returns the old save's text (null when there is none) and throws if it can't be opened.
     */
    @Synchronized fun loadCore(oldFormat:(()->String?)?=null):Loaded {
        dir.mkdirs();clearTemps()
        val notices=ArrayList<String>()
        var damaged=0
        val candidates=listOf(coreFile)+(1..KEEP_BACKUPS).map {backupFile(it)}
        for((i,file) in candidates.withIndex()) {
            if(!file.isFile) continue
            val core=readJson(file)
            if(core==null) {damaged++;quarantine(file);continue}
            if(i>0) notices+="Alpha's saved settings were damaged, so it went back to a copy from "+day(file.lastModified())+". Anything changed since then may be missing."
            diskCore=""
            return Loaded(core,notices,restoredFromBackup=i>0)
        }
        val old=try {oldFormat?.invoke()} catch(e:StoreUnavailable) {throw e} catch(e:Exception) {
            notices+="Alpha couldn't open its older saved data, so it started fresh. The old file is kept on this phone."
            null
        }
        if(old!=null) {
            val all=try {JSONObject(old)} catch(e:Exception) {null}
            if(all!=null) {
                val tasks=all.optJSONArray("tasks")?:JSONArray();val messages=all.optJSONArray("messages")?:JSONArray()
                BULK_KEYS.forEach {all.remove(it)}
                diskCore=""
                return Loaded(all,notices,tasks,messages)
            }
            notices+="Alpha's older saved data was damaged, so it started fresh. The old file is kept on this phone."
        }
        if(damaged>0) notices+="Alpha couldn't read its saved settings and bots, so they were reset. Your chats and requests were kept if they could be read."
        diskCore=""
        return Loaded(JSONObject(),notices)
    }

    /** Total size of the chat and request files, to decide whether loading them is quick enough to do before the first screen. */
    fun bulkBytes():Long = listOf(taskDir,messageDir).sumOf {d->d.listFiles()?.sumOf {it.length()}?:0L}

    /** Every request and chat text on disk. Files that can't be read are set aside and counted. Safe to run off the main thread. */
    @Synchronized fun loadBulk():Bulk {
        if(readOnly) return Bulk(emptyList(),emptyList(),0,emptyList())
        var lost=0
        val tasks=ArrayList<TaskRecord>()
        taskDir.listFiles()?.filter {it.isFile && it.name.endsWith(".enc")}?.forEach {file->
            val task=try {TaskRecord.parse(JSONObject(Packed.unpack(crypto,file.readBytes())))} catch(e:StoreUnavailable) {throw e} catch(e:Exception) {null}
            if(task==null) {lost++;quarantine(file)} else tasks+=task
        }
        tasks.sortWith(compareByDescending<TaskRecord> {it.created}.thenBy {it.id})
        diskTasks.clear();tasks.forEach {diskTasks[it.id]=it}
        val segments=segmentFiles()
        val messages=ArrayList<Message>();val seen=HashSet<String>()
        diskSegments.clear()
        for((seq,file) in segments) {
            val part=try {JSONArray(Packed.unpack(crypto,file.readBytes())).objects().map(Message::parse)} catch(e:StoreUnavailable) {throw e} catch(e:Exception) {null}
            if(part==null) {lost++;quarantine(file);continue}
            diskSegments[seq]=part
            part.forEach {if(seen.add(it.id)) messages+=it}
        }
        // New files always take a number nobody has used, so a chat file never collides with an archive file.
        baseSeq=segments.firstOrNull()?.first?:(maxOf(archiveNumbers("a-").maxOrNull()?:-1,-1)+1)
        baselineKnown=true
        val notices=if(lost>0) listOf(if(lost==1) "One old request or chat file couldn't be read and was skipped." else "$lost old request or chat files couldn't be read and were skipped.") else emptyList()
        return Bulk(tasks,messages,lost,notices)
    }
    private fun segmentFiles():List<Pair<Int,File>> = messageDir.listFiles()?.mapNotNull {f->
        Regex("m-(\\d+)\\.enc").matchEntire(f.name)?.let {it.groupValues[1].toInt() to f}
    }?.sortedBy {it.first}.orEmpty()
    private fun archiveNumbers(prefix:String):List<Int> = archiveDir.listFiles()?.mapNotNull {f->Regex(Regex.escape(prefix)+"(\\d+)\\.enc").matchEntire(f.name)?.groupValues?.get(1)?.toInt()}.orEmpty()

    // ---- Saving ----

    /**
     * Writes what changed since the last save: new or changed requests, the chat files that changed, and the core file (first keeping a fresh backup
     * copy when the newest is old enough). Chat files that left memory go to the archive before their chat files are removed. Order matters: a
     * crash in between can leave an extra copy somewhere, never a missing one.
     */
    @Synchronized fun write(s:Snapshot):Report {
        if(readOnly) return Report()
        dir.mkdirs()
        if(!baselineKnown && (s.tasks!=null || s.messages!=null)) adoptDisk(s)
        archivedSegmentsDone=0
        var tasksWritten=0;var tasksDeleted=0;var segmentsWritten=0;var archived=0;var retired=0
        s.tasks?.let {tasks->
            if(s.retired.isNotEmpty()) {appendRetired(s.retired);retired=s.retired.size}
            val ids=HashSet<String>()
            for(task in tasks) {
                ids+=task.id
                if(diskTasks[task.id]!=task) {
                    SafeFile.write(taskFile(task.id),Packed.pack(crypto,task.json().toString()))
                    diskTasks[task.id]=task;tasksWritten++
                }
            }
            for(id in diskTasks.keys.toList()) if(id !in ids) {taskFile(id).delete();diskTasks.remove(id);tasksDeleted++}
        }
        s.messages?.let {messages->
            for(segment in s.archivedMessages) {
                SafeFile.write(archiveFile(baseSeq),Packed.pack(crypto,arr(segment.map {it.json()}).toString()))
                messageFile(baseSeq).delete();diskSegments.remove(baseSeq)
                baseSeq++;archived+=segment.size;archivedSegmentsDone++
            }
            val chunks=messages.chunked(SEGMENT)
            chunks.forEachIndexed {i,chunk->
                val seq=baseSeq+i
                if(diskSegments[seq]!=chunk) {
                    SafeFile.write(messageFile(seq),Packed.pack(crypto,arr(chunk.map {it.json()}).toString()))
                    diskSegments[seq]=chunk;segmentsWritten++
                }
            }
            for(seq in diskSegments.keys.toList()) if(seq>=baseSeq+chunks.size) {messageFile(seq).delete();diskSegments.remove(seq)}
        }
        var coreWritten=false
        if(s.core!=diskCore) {
            rotateBackups()
            SafeFile.write(coreFile,Packed.pack(crypto,s.core))
            diskCore=s.core;coreWritten=true
        }
        return Report(coreWritten,tasksWritten,tasksDeleted,segmentsWritten,archived,retired)
    }

    /**
     * A save that arrives before anything was read from disk (the first start after an update, or a test) is the whole truth: files left by an
     * interrupted earlier try are cleared out, and new chat files take numbers the archive hasn't used.
     */
    private fun adoptDisk(s:Snapshot) {
        s.tasks?.let {tasks->
            val keep=tasks.map {taskFile(it.id).name}.toSet()
            taskDir.listFiles()?.filter {it.isFile && it.name.endsWith(".enc") && it.name !in keep}?.forEach {runCatching {it.delete()}}
        }
        s.messages?.let {
            segmentFiles().forEach {runCatching {it.second.delete()}}
            baseSeq=(archiveNumbers("a-").maxOrNull()?:-1)+1
        }
        baselineKnown=true
    }

    /** Keeps up to three older good copies of the core file. A damaged core is never copied over a good backup. */
    private fun rotateBackups() {
        if(!coreFile.isFile) return
        val newest=backupFile(1)
        if(newest.isFile && clock()-newest.lastModified()<BACKUP_EVERY_MS) return
        if(readJson(coreFile)==null) return
        for(n in KEEP_BACKUPS downTo 2) backupFile(n-1).takeIf {it.isFile}?.let {SafeFile.write(backupFile(n),it.readBytes());backupFile(n).setLastModified(it.lastModified())}
        SafeFile.write(newest,coreFile.readBytes());newest.setLastModified(clock())
    }

    private fun appendRetired(batch:List<RetiredTask>) {
        val numbers=archiveNumbers("t-")
        var n=numbers.maxOrNull()?:0
        val current=File(archiveDir,"t-%06d.enc".format(n))
        var existing=if(current.isFile) runCatching {JSONArray(Packed.unpack(crypto,current.readBytes())).objects().map(RetiredTask::parse)}.getOrDefault(emptyList()) else emptyList()
        if(existing.size>=RETIRED_PER_FILE) {n++;existing=emptyList()}
        val known=existing.map {it.id}.toSet()
        val all=existing+batch.filter {it.id !in known}
        SafeFile.write(File(archiveDir,"t-%06d.enc".format(n)),Packed.pack(crypto,arr(all.map {it.json()}).toString()))
    }

    // ---- Searching what left memory ----

    /** Chat texts that left memory, newest file first; [maxFiles] bounds the work. Opens and reads files, so run it off the main thread. */
    @Synchronized fun archivedMessages(maxFiles:Int=40):List<Message> =
        archiveNumbers("a-").sortedDescending().take(maxFiles).flatMap {n->
            runCatching {JSONArray(Packed.unpack(crypto,archiveFile(n).readBytes())).objects().map(Message::parse)}.getOrDefault(emptyList()).asReversed()
        }.distinctBy {it.id}
    @Synchronized fun retiredTasks(maxFiles:Int=40):List<RetiredTask> =
        archiveNumbers("t-").sortedDescending().take(maxFiles).flatMap {n->
            runCatching {JSONArray(Packed.unpack(crypto,File(archiveDir,"t-%06d.enc".format(n)).readBytes())).objects().map(RetiredTask::parse)}.getOrDefault(emptyList()).asReversed()
        }.distinctBy {it.id}

    /**
     * "Clear chat" reaches the archive too, or the old words would live on there. [scope] is "all", the Teamwork room's name, or a bot's name (its
     * private chat and its old requests go; Teamwork stays). Returns how many chat texts were removed.
     */
    @Synchronized fun purgeArchive(scope:String,teamRoom:String="Teamwork",knownTasks:Set<String> = emptySet()):Int {
        if(readOnly) return 0
        var removed=0
        val numbers=archiveNumbers("a-").sorted()
        if(scope=="all") {
            numbers.forEach {runCatching {removed+=JSONArray(Packed.unpack(crypto,archiveFile(it).readBytes())).length()};archiveFile(it).delete()}
            archiveNumbers("t-").forEach {File(archiveDir,"t-%06d.enc".format(it)).delete()}
            return removed
        }
        val parts=numbers.associateWith {n->runCatching {JSONArray(Packed.unpack(crypto,archiveFile(n).readBytes())).objects().map(Message::parse)}.getOrNull()}
        val botTasks=if(scope==teamRoom) emptySet() else knownTasks+parts.values.filterNotNull().flatten().filter {!it.team && it.role=="assistant" && it.bot==scope}.map {it.taskId}
        for((n,messages) in parts) {
            if(messages==null) continue
            val keep=messages.filterNot {if(scope==teamRoom) it.team else !it.team && it.taskId in botTasks}
            if(keep.size==messages.size) continue
            removed+=messages.size-keep.size
            if(keep.isEmpty()) archiveFile(n).delete() else SafeFile.write(archiveFile(n),Packed.pack(crypto,arr(keep.map {it.json()}).toString()))
        }
        if(scope!=teamRoom) for(n in archiveNumbers("t-")) {
            val file=File(archiveDir,"t-%06d.enc".format(n))
            val all=runCatching {JSONArray(Packed.unpack(crypto,file.readBytes())).objects().map(RetiredTask::parse)}.getOrNull()?:continue
            val keep=all.filter {it.workspace!=scope}
            if(keep.size==all.size) continue
            if(keep.isEmpty()) file.delete() else SafeFile.write(file,Packed.pack(crypto,arr(keep.map {it.json()}).toString()))
        }
        return removed
    }

    // ---- Whole-state helpers (first start after an update, tests, the self-check) ----

    /** Checks the saved files really hold what was just saved: the core opens and the counts of requests and chat texts match. */
    @Synchronized fun verify(tasks:Int,messages:Int,archivedTexts:Int=0):Boolean = try {
        val core=readJson(coreFile)!=null
        val t=taskDir.listFiles()?.count {f->f.isFile && f.name.endsWith(".enc") && runCatching {JSONObject(Packed.unpack(crypto,f.readBytes()))}.isSuccess}?:0
        val m=segmentFiles().sumOf {(_,f)->JSONArray(Packed.unpack(crypto,f.readBytes())).length()}
        core && t==tasks && m+archivedTexts==messages
    } catch(e:Exception) {false}

    // ---- small separate files (the Brainstorm chats): sealed like everything else, written safely, never part of the chat or request files ----
    private val sideDir get()=File(dir,"side")
    private fun sideFile(name:String)=File(sideDir,name.replace(Regex("[^A-Za-z0-9_-]"),"_").take(60)+".enc")
    @Synchronized fun writeSide(name:String,text:String) {
        if(readOnly) return
        SafeFile.write(sideFile(name),Packed.pack(crypto,text))
    }
    /** The text last written under [name], or null when there is none or it cannot be opened (the key store itself being unavailable still throws). */
    @Synchronized fun readSide(name:String):String? {
        val file=sideFile(name).takeIf {it.isFile}?:return null
        return try {Packed.unpack(crypto,file.readBytes())} catch(e:StoreUnavailable) {throw e} catch(e:Exception) {null}
    }

    /** Reads everything back as the old single JSON state (the shape tests and older code expect). */
    @Synchronized fun loadAll(oldFormat:(()->String?)?=null):JSONObject {
        val loaded=loadCore(oldFormat)
        if(loaded.fromOldFormat) {
            saveAll(JSONObject(loaded.core.toString()).put("tasks",loaded.legacyTasks).put("messages",loaded.legacyMessages))
            return JSONObject(loaded.core.toString()).put("tasks",loaded.legacyTasks).put("messages",loaded.legacyMessages)
        }
        val bulk=loadBulk()
        return loaded.core.put("tasks",arr(bulk.tasks.map {it.json()})).put("messages",arr(bulk.messages.map {it.json()}))
    }
    /** Replaces everything on disk with this state (tasks and messages arrays inside it, everything else is the core). */
    @Synchronized fun saveAll(state:JSONObject) {
        val core=JSONObject(state.toString());BULK_KEYS.forEach {core.remove(it)}
        write(Snapshot(core.toString(),state.optJSONArray("tasks")?.objects()?.map(TaskRecord::parse).orEmpty(),
            state.optJSONArray("messages")?.objects()?.map(Message::parse).orEmpty()))
    }
    /** Seals, writes, reads back and opens a small test file, to show the encrypted storage works. The real saved data is never touched. */
    @Synchronized fun selfCheck(probe:String):Boolean {
        val file=File(dir,"selfcheck.enc")
        return try {SafeFile.write(file,Packed.pack(crypto,probe));Packed.unpack(crypto,file.readBytes())==probe} finally {file.delete()}
    }
    /** Old single-file saves older than [MIGRATED_KEEP_MS] are no longer needed. */
    fun deleteOld(file:File) {if(file.isFile && clock()-file.lastModified()>MIGRATED_KEEP_MS) runCatching {file.delete()}}
}
