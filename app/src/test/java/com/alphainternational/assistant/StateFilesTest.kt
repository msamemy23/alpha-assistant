package com.alphainternational.assistant

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The storage engine behind Alpha's memory: one encrypted file per request, rolling backups, archives, and the move from the old single-file save. */
class StateFilesTest {
    @get:Rule val folder=TemporaryFolder()
    private val key=ByteArray(32) {(it*7+3).toByte()}
    private var now=System.currentTimeMillis()
    private fun store(dir:File=File(folder.root,"alpha-data"),crypto:DataCrypto=AesCrypto(key)) = StateFiles(dir,crypto) {now}
    private fun task(id:String,created:Long=1_000,history:String="[]",status:String="Completed",workspace:String="Alpha") =
        TaskRecord(id,"Title $id","Prompt $id",status,"done",created,history=history,workspace=workspace)
    private fun msg(i:Int,task:String="t1",team:Boolean=false) = Message("m$i","assistant","text number $i",task,team=team,bot="Alpha",at=1_000L+i)
    private fun core(extra:String="a")=obj("workspace" to "Alpha","bots" to arr(listOf(obj("name" to "Alpha","role" to "general"))),"mark" to extra).toString()
    private val dir get()=File(folder.root,"alpha-data")

    @Test fun everythingRoundTripsThroughTheFiles() {
        val s=store()
        s.loadCore();s.loadBulk()
        val tasks=listOf(task("t2",created=2_000,history="[{\"type\":\"function_call\"}]"),task("t1"))
        val messages=(1..5).map {msg(it)}
        s.write(StateFiles.Snapshot(core("x"),tasks,messages))
        val again=store()
        val loaded=again.loadCore()
        assertEquals("x",loaded.core.getString("mark"))
        assertEquals("Alpha",loaded.core.getJSONArray("bots").getJSONObject(0).getString("name"))
        assertTrue(loaded.notices.isEmpty())
        val bulk=again.loadBulk()
        assertEquals(listOf("t2","t1"),bulk.tasks.map {it.id})
        assertEquals(tasks,bulk.tasks)
        assertEquals(messages,bulk.messages)
        assertEquals(0,bulk.lost)
    }
    @Test fun aSmallSeparateFileIsSealedAndComesBack() {
        val s=store()
        assertNull(s.readSide("brainstorm"))
        s.writeSide("brainstorm","{\"chats\":[]} with a secret idea")
        assertEquals("{\"chats\":[]} with a secret idea",store().readSide("brainstorm"))
        s.writeSide("brainstorm","newer")
        assertEquals("newer",store().readSide("brainstorm"))
        val file=File(dir,"side/brainstorm.enc")
        assertTrue(file.isFile);assertFalse(String(file.readBytes(),Charsets.ISO_8859_1).contains("newer"))
        // a damaged file reads as nothing, and a wrong key too, never as an error
        file.writeBytes(ByteArray(40) {1})
        assertNull(store().readSide("brainstorm"))
        s.writeSide("brainstorm","again");assertNull(store(crypto=AesCrypto(ByteArray(32) {9})).readSide("brainstorm"))
        // a name can't climb out of the folder
        s.writeSide("../../evil","x");assertTrue(File(dir,"side").listFiles()!!.all {it.parentFile.name=="side"})
    }
    @Test fun nothingIsStoredAsPlainText() {
        val s=store();s.loadCore();s.loadBulk()
        s.write(StateFiles.Snapshot(core("secret-marker-in-core"),listOf(task("t1",history="secret-marker-in-task".repeat(80))),listOf(msg(1).copy(text="secret-marker-in-chat"))))
        dir.walkTopDown().filter {it.isFile}.forEach {f->
            val text=f.readBytes().toString(Charsets.ISO_8859_1)
            assertFalse(f.name,text.contains("secret-marker"))
            assertFalse(f.name,text.contains("bots"))
        }
    }
    @Test fun aTaskFileIsOnlyRewrittenWhenThatTaskChanged() {
        val s=store();s.loadCore();s.loadBulk()
        val a=task("a");val b=task("b",created=2_000)
        val first=s.write(StateFiles.Snapshot(core(),listOf(b,a),emptyList()))
        assertEquals(2,first.tasksWritten)
        val fileA=File(dir,"tasks/a.enc");val before=fileA.readBytes()
        fileA.setLastModified(1_000_000L)
        val same=s.write(StateFiles.Snapshot(core(),listOf(b,a),emptyList()))
        assertEquals(0,same.tasksWritten);assertFalse(same.coreWritten)
        val changed=s.write(StateFiles.Snapshot(core(),listOf(b.copy(detail="more"),a),emptyList()))
        assertEquals(1,changed.tasksWritten)
        // The untouched file is byte for byte what it was, and was never opened for writing.
        assertArrayEquals(before,fileA.readBytes());assertEquals(1_000_000L,fileA.lastModified())
        // Even an equal-looking copy of the same task does not cause a write.
        assertEquals(0,s.write(StateFiles.Snapshot(core(),listOf(b.copy(detail="more"),a.copy()),emptyList())).tasksWritten)
    }
    @Test fun aRemovedTaskLosesItsFile() {
        val s=store();s.loadCore();s.loadBulk()
        s.write(StateFiles.Snapshot(core(),listOf(task("a"),task("b",created=5)),emptyList()))
        val report=s.write(StateFiles.Snapshot(core(),listOf(task("b",created=5)),emptyList()))
        assertEquals(1,report.tasksDeleted)
        assertFalse(File(dir,"tasks/a.enc").exists());assertTrue(File(dir,"tasks/b.enc").exists())
        assertEquals(listOf("b"),store().also {it.loadCore()}.loadBulk().tasks.map {it.id})
    }
    @Test fun aSaveBeforeLoadingTouchesNoRequestFiles() {
        val s=store();s.loadCore();s.loadBulk()
        s.write(StateFiles.Snapshot(core(),listOf(task("a")),listOf(msg(1))))
        // The app is still loading its chats: a save that only has the small core data must not delete or change the rest.
        val early=store();early.loadCore()
        val report=early.write(StateFiles.Snapshot(core("changed"),null,null))
        assertTrue(report.coreWritten);assertEquals(0,report.tasksDeleted)
        assertTrue(File(dir,"tasks/a.enc").exists())
        val bulk=store().also {it.loadCore()}.loadBulk()
        assertEquals(1,bulk.tasks.size);assertEquals(1,bulk.messages.size)
    }
    @Test fun aDamagedCoreFallsBackToTheNewestGoodBackupAndSaysSo() {
        val s=store();s.loadCore();s.loadBulk()
        s.write(StateFiles.Snapshot(core("one"),emptyList(),emptyList()))
        now+=StateFiles.BACKUP_EVERY_MS+1
        s.write(StateFiles.Snapshot(core("two"),emptyList(),emptyList()))
        now+=StateFiles.BACKUP_EVERY_MS+1
        s.write(StateFiles.Snapshot(core("three"),emptyList(),emptyList()))
        assertTrue(s.backupFile(1).isFile && s.backupFile(2).isFile)
        // Cut the current file in half, like a write that was interrupted.
        val core=File(dir,"core.enc");core.writeBytes(core.readBytes().copyOf(20))
        val loaded=store().loadCore()
        assertTrue(loaded.restoredFromBackup)
        assertEquals("two",loaded.core.getString("mark"))
        assertEquals(1,loaded.notices.size)
        assertTrue(loaded.notices[0].contains("damaged") && loaded.notices[0].contains("copy"))
        assertFalse(loaded.notices[0].contains("JSON",true))
        // The damaged file was set aside, not deleted.
        assertTrue(File(dir,"unreadable").listFiles()!!.isNotEmpty())
    }
    @Test fun ifEveryCopyIsDamagedItStartsFreshAndTellsTheOwnerButKeepsTheChats() {
        val s=store();s.loadCore();s.loadBulk()
        s.write(StateFiles.Snapshot(core("one"),listOf(task("a")),listOf(msg(1),msg(2))))
        File(dir,"core.enc").writeBytes(ByteArray(64) {1})
        val again=store();val loaded=again.loadCore()
        assertEquals(0,loaded.core.length())
        assertTrue(loaded.notices.single().contains("couldn't read"))
        val bulk=again.loadBulk()
        assertEquals(1,bulk.tasks.size);assertEquals(2,bulk.messages.size)
    }
    @Test fun aChangedFileIsRefusedAndAFileWithTheWrongKeyToo() {
        val s=store();s.loadCore();s.loadBulk()
        s.write(StateFiles.Snapshot(core("a"),listOf(task("a",history="x".repeat(2000)),task("b")),listOf(msg(1))))
        // One flipped bit in a request file: that request is skipped and counted, the rest is untouched.
        val file=File(dir,"tasks/a.enc");val bytes=file.readBytes();bytes[bytes.size/2]=(bytes[bytes.size/2].toInt() xor 1).toByte();file.writeBytes(bytes)
        val bulk=store().also {it.loadCore()}.loadBulk()
        assertEquals(listOf("b"),bulk.tasks.map {it.id});assertEquals(1,bulk.lost)
        assertTrue(bulk.notices.single().contains("skipped"))
        assertEquals(1,bulk.messages.size)
        assertThrows(Exception::class.java) {AesCrypto(ByteArray(32) {9}).open(File(dir,"tasks/b.enc").readBytes())}
    }
    @Test fun leftoverPartialWritesAreIgnored() {
        val s=store();s.loadCore();s.loadBulk()
        s.write(StateFiles.Snapshot(core("a"),listOf(task("a")),listOf(msg(1))))
        File(dir,"tasks/zzz.enc.tmp").writeBytes(byteArrayOf(1,2,3))
        File(dir,"core.enc.tmp").writeBytes(byteArrayOf(9))
        val again=store()
        assertEquals("a",again.loadCore().core.getString("mark"))
        assertEquals(listOf("a"),again.loadBulk().tasks.map {it.id})
        assertFalse(File(dir,"tasks/zzz.enc.tmp").exists())
    }
    @Test fun backupsRotateAtMostOnceInAWhileAndKeepThree() {
        val s=store();s.loadCore();s.loadBulk()
        s.write(StateFiles.Snapshot(core("v1"),emptyList(),emptyList()))
        s.write(StateFiles.Snapshot(core("v2"),emptyList(),emptyList()))
        // Two saves a moment apart make one backup at most, not a pile of near-identical ones.
        assertFalse(s.backupFile(2).exists())
        repeat(6) {i->
            now+=StateFiles.BACKUP_EVERY_MS+1
            s.write(StateFiles.Snapshot(core("w$i"),emptyList(),emptyList()))
        }
        assertTrue(s.backupFile(1).isFile && s.backupFile(2).isFile && s.backupFile(3).isFile)
        assertFalse(File(dir,"core.bak4.enc").exists())
        val marks=(1..3).map {store().let {st->JSONObject(Packed.unpack(AesCrypto(key),s.backupFile(it).readBytes())).getString("mark")}}
        assertEquals(listOf("w4","w3","w2"),marks)
    }
    @Test fun aDamagedCoreIsNeverCopiedOverAGoodBackup() {
        val s=store();s.loadCore();s.loadBulk()
        s.write(StateFiles.Snapshot(core("good"),emptyList(),emptyList()))
        now+=StateFiles.BACKUP_EVERY_MS+1
        s.write(StateFiles.Snapshot(core("good2"),emptyList(),emptyList()))
        File(dir,"core.enc").writeBytes(ByteArray(40) {7})
        now+=StateFiles.BACKUP_EVERY_MS+1
        s.write(StateFiles.Snapshot(core("after"),emptyList(),emptyList()))
        val backup=JSONObject(Packed.unpack(AesCrypto(key),s.backupFile(1).readBytes()))
        assertEquals("good",backup.getString("mark"))
    }
    @Test fun oldChatTextsMoveToTheArchiveAndStaySearchable() {
        val s=store();s.loadCore();s.loadBulk()
        val all=(1..250).map {msg(it)}
        s.write(StateFiles.Snapshot(core(),emptyList(),all))
        assertEquals(3,File(dir,"messages").listFiles()!!.size)
        // The app drops the first 100 texts from memory and hands them to the writer in the same save.
        val first=all.take(StateFiles.SEGMENT);val rest=all.drop(StateFiles.SEGMENT)
        val report=s.write(StateFiles.Snapshot(core(),emptyList(),rest,archivedMessages=listOf(first)))
        assertEquals(100,report.archivedTexts);assertEquals(0,report.segmentsWritten)
        assertEquals(2,File(dir,"messages").listFiles()!!.size);assertEquals(1,File(dir,"archive").listFiles()!!.size)
        assertEquals(first,s.archivedMessages().asReversed())
        val again=store();again.loadCore()
        assertEquals(rest,again.loadBulk().messages)
        assertEquals(100,again.archivedMessages().size)
        // The newest archived text comes first, so a search shows recent history before old.
        assertEquals("m100",again.archivedMessages().first().id)
        // New texts after a restart keep numbering forward and never overwrite the archive.
        again.write(StateFiles.Snapshot(core(),emptyList(),rest+msg(251)))
        assertEquals(100,again.archivedMessages().size)
        val third=store();third.loadCore()
        assertEquals((rest+msg(251)),third.loadBulk().messages)
    }
    @Test fun archivingEverythingStillNumbersNewFilesPastTheArchive() {
        val s=store();s.loadCore();s.loadBulk()
        val hundred=(1..100).map {msg(it)}
        s.write(StateFiles.Snapshot(core(),emptyList(),hundred))
        s.write(StateFiles.Snapshot(core(),emptyList(),emptyList(),archivedMessages=listOf(hundred)))
        val restart=store();restart.loadCore();assertTrue(restart.loadBulk().messages.isEmpty())
        restart.write(StateFiles.Snapshot(core(),emptyList(),listOf(msg(500))))
        restart.write(StateFiles.Snapshot(core(),emptyList(),emptyList(),archivedMessages=listOf(listOf(msg(500)))))
        assertEquals(2,File(dir,"archive").listFiles()!!.size)
        val found=restart.archivedMessages()
        assertEquals("m500",found.first().id);assertEquals(101,found.size);assertTrue(found.any {it.id=="m100"})
    }
    @Test fun middleRemovalRewritesOnlyWhatMoved() {
        val s=store();s.loadCore();s.loadBulk()
        val all=(1..300).map {msg(it)}
        s.write(StateFiles.Snapshot(core(),emptyList(),all))
        // One new text only touches the last file.
        assertEquals(1,s.write(StateFiles.Snapshot(core(),emptyList(),all+msg(301))).segmentsWritten)
        // A reaction on an old text touches just its file.
        val reacted=(all+msg(301)).map {if(it.id=="m5") it.copy(react="👍") else it}
        assertEquals(1,s.write(StateFiles.Snapshot(core(),emptyList(),reacted)).segmentsWritten)
        // Clearing everything removes the files.
        s.write(StateFiles.Snapshot(core(),emptyList(),emptyList()))
        assertEquals(0,File(dir,"messages").listFiles()!!.size)
    }
    @Test fun oldFinishedRequestsCanLeaveMemoryButStaySearchable() {
        val s=store();s.loadCore();s.loadBulk()
        val old=task("old").copy(digest="Asked: brakes for Maria\nResult: quote sent")
        s.write(StateFiles.Snapshot(core(),listOf(old,task("new",created=9_000)),emptyList()))
        val report=s.write(StateFiles.Snapshot(core(),listOf(task("new",created=9_000)),emptyList(),retired=listOf(RetiredTask.of(old))))
        assertEquals(1,report.retired);assertEquals(1,report.tasksDeleted)
        val found=store().retiredTasks().single()
        assertEquals("old",found.id);assertTrue(found.digest.contains("brakes for Maria"))
        // Retiring the same one twice (a crash and retry) does not duplicate it.
        s.write(StateFiles.Snapshot(core(),listOf(task("new",created=9_000)),emptyList(),retired=listOf(RetiredTask.of(old))))
        assertEquals(1,store().retiredTasks().size)
    }

    // ---- Moving from the old single-file save ----

    /** What 0.15 wrote: one JSON object with everything inside. */
    private fun oldFormatBlob():JSONObject {
        val tasks=(1..12).map {i->task("old$i",created=1_000L+i,history=arr((1..30).map {n->obj("type" to "function_call_output","call_id" to "c$n","output" to "result $n ".repeat(40))}).toString(),
            status=if(i==12) "Running" else "Completed").json()}
        val messages=(1..250).map {msg(it,task="old${1+it%12}").json()}
        return obj("workspace" to "Personal","bots" to arr(listOf(obj("name" to "Alpha","role" to "general"),obj("name" to "Personal","role" to "me"))),
            "memories" to arr(listOf(Memory("mem1","Labor rate","\$120 an hour","You said",5,"Alpha",kind="preference").json())),
            "connections" to obj("chatgpt" to obj("access_token" to "tok","email" to "o@x.com"),"gifs" to obj("off" to true)),
            "ai" to obj("model" to "auto"),"host_id" to "urn:uuid:abc","tasks" to arr(tasks),"messages" to arr(messages),"routines" to arr(emptyList<Any>()),
            "notified_events" to arr(listOf("e1")),"some_future_key" to obj("kept" to true))
    }
    @Test fun theOldSingleFileSaveMovesIntoTheNewFilesWithNothingLost() {
        val blob=oldFormatBlob()
        val s=store()
        val loaded=s.loadCore {blob.toString()}
        assertTrue(loaded.fromOldFormat)
        assertEquals(12,loaded.legacyTasks!!.length());assertEquals(250,loaded.legacyMessages!!.length())
        assertFalse(loaded.core.has("tasks"));assertFalse(loaded.core.has("messages"))
        assertEquals("Personal",loaded.core.getString("workspace"))
        val tasks=loaded.legacyTasks.objects().map(TaskRecord::parse);val messages=loaded.legacyMessages.objects().map(Message::parse)
        s.write(StateFiles.Snapshot(loaded.core.toString(),tasks,messages))
        assertTrue(s.verify(12,250))
        // A fresh start (the old file now "retired") reads exactly the same from the new files.
        val after=store();val core=after.loadCore {null}
        assertFalse(core.fromOldFormat)
        assertEquals("tok",core.core.getJSONObject("connections").getJSONObject("chatgpt").getString("access_token"))
        assertTrue(core.core.getJSONObject("some_future_key").getBoolean("kept"))
        assertEquals("e1",core.core.getJSONArray("notified_events").getString(0))
        assertEquals("mem1",Memory.parse(core.core.getJSONArray("memories").getJSONObject(0)).id)
        val bulk=after.loadBulk()
        assertEquals(tasks.sortedByDescending {it.created},bulk.tasks);assertEquals(messages,bulk.messages)
        assertEquals("Running",bulk.tasks.first {it.id=="old12"}.status)
    }
    @Test fun verifyCatchesAnIncompleteMove() {
        val blob=oldFormatBlob();val s=store()
        val loaded=s.loadCore {blob.toString()}
        val tasks=loaded.legacyTasks!!.objects().map(TaskRecord::parse);val messages=loaded.legacyMessages!!.objects().map(Message::parse)
        s.write(StateFiles.Snapshot(loaded.core.toString(),tasks,messages))
        File(dir,"tasks").listFiles()!!.first().delete()
        assertFalse(s.verify(12,250))
        File(dir,"messages").listFiles()!!.first().writeBytes(byteArrayOf(1,2,3))
        assertFalse(s.verify(11,250))
    }
    @Test fun anUnreadableOldSaveStartsFreshWithAPlainNoticeAndNoCrash() {
        val s=store()
        val loaded=s.loadCore {throw IllegalStateException("bad tag")}
        assertEquals(0,loaded.core.length());assertFalse(loaded.fromOldFormat)
        assertTrue(loaded.notices.single().contains("started fresh"))
        val junk=store().loadCore {"{not json"}
        assertTrue(junk.notices.single().contains("damaged"))
    }
    @Test fun whenTheKeyStoreIsBusyNothingIsTouched() {
        val s=store();s.loadCore();s.loadBulk()
        s.write(StateFiles.Snapshot(core("a"),listOf(task("a")),listOf(msg(1))))
        val before=dir.walkTopDown().filter {it.isFile}.associate {it.path to it.readBytes().toList()}
        val locked=store(crypto=object:DataCrypto {
            override fun seal(plain:ByteArray):ByteArray=throw StoreUnavailable("busy")
            override fun open(sealed:ByteArray):ByteArray=throw StoreUnavailable("busy")
        })
        assertThrows(StoreUnavailable::class.java) {locked.loadCore()}
        assertThrows(StoreUnavailable::class.java) {locked.loadBulk()}
        locked.readOnly=true
        assertTrue(locked.loadBulk().tasks.isEmpty())
        assertEquals(0,locked.write(StateFiles.Snapshot(core("overwrite"),emptyList(),emptyList())).tasksDeleted)
        val after=dir.walkTopDown().filter {it.isFile}.associate {it.path to it.readBytes().toList()}
        assertEquals(before,after)
    }
    @Test fun wholeStateHelpersKeepTheOldShapeForTests() {
        val s=store()
        val blob=oldFormatBlob()
        s.saveAll(blob)
        val back=store().loadAll {null}
        assertEquals(12,back.getJSONArray("tasks").length());assertEquals(250,back.getJSONArray("messages").length())
        assertEquals("Personal",back.getString("workspace"))
        // Saving a smaller state replaces the bigger one.
        s.saveAll(obj("workspace" to "Alpha","tasks" to arr(emptyList<Any>()),"messages" to arr(emptyList<Any>())))
        val small=store().loadAll {null}
        assertEquals(0,small.getJSONArray("tasks").length());assertEquals(0,small.getJSONArray("messages").length())
        assertFalse(small.has("host_id"))
    }
    @Test fun bigHistoriesAreSqueezedBeforeTheyAreSealed() {
        val history=arr((1..200).map {obj("type" to "function_call_output","output" to "the same words again and again ".repeat(30))}).toString()
        val s=store();s.loadCore();s.loadBulk()
        s.write(StateFiles.Snapshot(core(),listOf(task("a",history=history)),emptyList()))
        val onDisk=File(dir,"tasks/a.enc").length()
        assertTrue("$onDisk vs ${history.length}",onDisk<history.length/5)
        assertEquals(history,store().also {it.loadCore()}.loadBulk().tasks.single().history)
    }
    @Test fun sealedBytesDifferEveryTimeAndRefuseChanges() {
        val c=AesCrypto(key)
        val a=Packed.pack(c,"hello");val b=Packed.pack(c,"hello")
        assertFalse(a.contentEquals(b))
        assertEquals("hello",Packed.unpack(c,a))
        a[a.size-1]=(a[a.size-1].toInt() xor 1).toByte()
        assertThrows(Exception::class.java) {Packed.unpack(c,a)}
        assertThrows(Exception::class.java) {Packed.unpack(c,ByteArray(5))}
        assertEquals("é✓ ".repeat(300),Packed.unpack(c,Packed.pack(c,"é✓ ".repeat(300))))
    }
    @Test fun bulkSizeCountsTheChatAndRequestFiles() {
        val s=store();s.loadCore();s.loadBulk()
        assertEquals(0L,s.bulkBytes())
        s.write(StateFiles.Snapshot(core(),listOf(task("a",history="x".repeat(5000))),(1..3).map {msg(it)}))
        assertTrue(s.bulkBytes()>100)
    }
}
