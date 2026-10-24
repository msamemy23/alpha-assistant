package com.alphainternational.assistant

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Memory v2 on the phone: a finished job leaves a digest, the next similar request is given it, history_search finds past work, and everything
 * (requests, chats, the owner's "that's wrong") is still there after the app restarts, from the new one-file-per-request storage.
 * Uses a scripted model like the other flow tests.
 */
class MemoryFlowTest {
    private fun reply(text:String)=obj("output" to JSONArray().put(obj("type" to "message","role" to "assistant","content" to JSONArray().put(obj("type" to "output_text","text" to text)))))
    private fun call(name:String,id:String,args:JSONObject)=obj("output" to JSONArray().put(obj("type" to "function_call","name" to name,"call_id" to id,"arguments" to args.toString())))
    private fun done(c:Controller,id:String)=runBlocking {withTimeout(30_000) {while(c.tasks.firstOrNull {it.id==id}?.status !in setOf("Completed","Partial")) delay(25)}}
    private fun connect(c:Controller) {c.saveConfig("chatgpt",obj("access_token" to "test-only-not-a-real-token"))}
    private fun waitForFile(context:android.content.Context,relative:String) =
        runBlocking {withTimeout(15_000) {while(!File(context.noBackupFilesDir,"alpha-data/"+relative).isFile) delay(50)};delay(300)}

    @Test fun aFinishedJobLeavesADigestThatTheNextRequestAndHistorySearchUse() {
        val instrument=InstrumentationRegistry.getInstrumentation();val context=instrument.targetContext
        val store=SecureStore(context);val previous=store.load();var made:Controller?=null
        val seen=mutableListOf<JSONArray>()
        val gateway=object:AiGateway {
            private var round=0
            override fun cancel() {}
            override suspend fun respond(model:String,history:JSONArray,instructions:String,tools:List<Tool>,onText:suspend (String)->Unit):JSONObject {
                seen+=history
                val prompt=history.objects().lastOrNull {it.optString("role")=="user"}?.optString("content").orEmpty()
                return when {
                    prompt.contains("Make Maria an estimate") && history.objects().none {it.optString("type")=="function_call_output"} ->
                        call("file_write","w1",obj("name" to "estimate-maria.txt","text" to "Brakes for Maria's Camry: \$420"))
                    prompt.contains("Make Maria an estimate") -> reply("Estimate for Maria's Camry brakes: \$420. Saved as estimate-maria.txt, a little longer than a quick chat so it is worth a digest. ".repeat(6))
                    prompt.contains("Where is the Maria thing") && history.objects().none {it.optString("type")=="function_call_output"} ->
                        call("history_search","h1",obj("query" to "Maria estimate","where" to ""))
                    else -> reply("Found it.")
                }
            }
        }
        try {
            store.save(JSONObject(previous.toString()).put("model","qa-model"))
            instrument.runOnMainSync {made=Controller(context,gateway);connect(made!!)}
            val c=made!!
            var first="";instrument.runOnMainSync {first=c.submit("Make Maria an estimate for the brakes on her Camry")!!}
            done(c,first)
            val job=c.tasks.first {it.id==first}
            assertTrue(job.digest, job.digest.contains("estimate-maria.txt") && job.digest.startsWith("Asked: Make Maria an estimate"))
            // The next, similar request is given that digest in its first message.
            seen.clear()
            var second="";instrument.runOnMainSync {second=c.submit("Send Maria the estimate for her brakes")!!}
            done(c,second)
            assertTrue(seen.first().objects().any {it.optString("content").contains("Earlier work of yours") && it.optString("content").contains("estimate-maria.txt")})
            // The bot can look back through its own past with history_search.
            seen.clear()
            var third="";instrument.runOnMainSync {third=c.submit("Where is the Maria thing we made last time?")!!}
            done(c,third)
            val output=seen.last().objects().last {it.optString("type")=="function_call_output"}.optString("output")
            assertTrue(output,output.contains("estimate-maria") && output.contains("\"count\""))
        } finally {instrument.runOnMainSync {made?.scope?.cancel()};store.save(previous)}
    }

    @Test fun requestsChatsAndWrongNotesSurviveARestart() {
        val instrument=InstrumentationRegistry.getInstrumentation();val context=instrument.targetContext
        val store=SecureStore(context);val previous=store.load();var made:Controller?=null;var again:Controller?=null
        val gateway=object:AiGateway {
            override fun cancel() {}
            override suspend fun respond(model:String,history:JSONArray,instructions:String,tools:List<Tool>,onText:suspend (String)->Unit):JSONObject = reply("Noted, that is done.")
        }
        try {
            store.save(JSONObject(previous.toString()).put("model","qa-model"))
            instrument.runOnMainSync {made=Controller(context,gateway);connect(made!!)}
            val c=made!!
            var id="";instrument.runOnMainSync {id=c.submit("Remember to order wiper blades for Dave")!!}
            done(c,id)
            instrument.runOnMainSync {
                c.saveMemory("Maria's car","Maria drives a 2012 Civic","You said",java.util.UUID.randomUUID().toString(),c.workspace,false,"person")
                c.markMemoryWrong(c.memories.first {it.title=="Maria's car"}.id)
                c.flushState()
            }
            waitForFile(context,"tasks/$id.enc")
            instrument.runOnMainSync {c.scope.cancel()}
            instrument.runOnMainSync {again=Controller(context,gateway)}
            val d=again!!
            runBlocking {withTimeout(20_000) {while(!d.hydrated) delay(25)}}
            assertEquals("Noted, that is done.",d.messages.last {it.taskId==id && it.role=="assistant"}.text)
            assertEquals("Completed",d.tasks.first {it.id==id}.status)
            assertTrue(d.memories.none {it.title=="Maria's car"})
            assertEquals(1,d.wrongNotes(d.workspace).count {it.text.contains("Civic")})
            // The saved files are separate and sealed: this request has its own, and nothing in the folder holds the words in the clear.
            val dir=File(context.noBackupFilesDir,"alpha-data")
            assertTrue(File(dir,"tasks/$id.enc").isFile && File(dir,"core.enc").isFile)
            val raw=dir.walkTopDown().filter {it.isFile}.joinToString("") {it.readBytes().toString(Charsets.ISO_8859_1)}
            assertFalse(raw.contains("wiper blades") || raw.contains("Civic"))
        } finally {instrument.runOnMainSync {made?.scope?.cancel();again?.scope?.cancel()};store.save(previous)}
    }

    @Test fun theBackupCoversSetupButNeverSignIns() {
        val instrument=InstrumentationRegistry.getInstrumentation();val context=instrument.targetContext
        val store=SecureStore(context);val previous=store.load();var made:Controller?=null
        val gateway=object:AiGateway {
            override fun cancel() {}
            override suspend fun respond(model:String,history:JSONArray,instructions:String,tools:List<Tool>,onText:suspend (String)->Unit):JSONObject = reply("ok")
        }
        try {
            store.save(JSONObject(previous.toString()).put("model","qa-model"))
            instrument.runOnMainSync {made=Controller(context,gateway);connect(made!!);made!!.saveConfig("gifs",obj("off" to true))}
            val c=made!!
            instrument.runOnMainSync {c.saveMemory("Labor rate","\$120 an hour","You",java.util.UUID.randomUUID().toString(),c.workspace,false,"preference")}
            val pass="a long test passphrase".toCharArray()
            val file=runBlocking {c.makeBackup(pass,Backup.Options(history=true,files=false))}
            val parsed=runBlocking {c.openBackup(file,pass)}
            assertTrue(parsed.memories.any {it.title=="Labor rate"})
            assertTrue(parsed.configs.containsKey("gifs"))
            assertFalse(parsed.configs.containsKey("chatgpt"))
            assertFalse(String(file,Charsets.ISO_8859_1).contains("test-only-not-a-real-token"))
            // Restoring into the same phone leaves the sign-in in place and keeps a way back.
            val message=runBlocking {c.restoreBackup(parsed,pass,replace=true)}
            assertTrue(message,message.startsWith("Restored"))
            assertTrue(c.config("chatgpt").optString("access_token").isNotBlank())
            assertTrue(c.restoreCopyDate()>0)
        } finally {instrument.runOnMainSync {made?.scope?.cancel()};store.save(previous);File(context.noBackupFilesDir,"alpha-before-restore"+Backup.EXTENSION).delete()}
    }
}
