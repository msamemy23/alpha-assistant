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
 * Alpha is closed and opened again WITH chats in it. 0.19.10 crashed on every start on the owner's phone ("Unable to create application", a NullPointerException in
 * `Controller.getCases` from `dropStaleGreetings` from `hydrate`): a small history is read while the constructor is still running, and `cases` is declared further down. Every other
 * test starts from empty data, where that code never ran. These start the app a second time on top of saved jobs.
 */
class RestartWithChatsTest {
    private val limit=IllegalStateException("The ChatGPT user has reached their Subscription Sharing usage limit. Ask the user to try again after their usage limit resets or use an API key instead.")
    private val gateway=object:AiGateway {
        override fun cancel() {}
        override suspend fun respond(model:String,history:JSONArray,instructions:String,tools:List<Tool>,onText:suspend (String)->Unit):JSONObject {throw limit}
    }
    private val instrument get()=InstrumentationRegistry.getInstrumentation()
    private fun until(ms:Long,what:String,cond:()->Boolean) {runBlocking {try {withTimeout(ms) {while(!cond()) delay(25)}} catch(e:kotlinx.coroutines.TimeoutCancellationException) {fail("Timed out waiting for $what")}}}
    private fun waitForFile(name:String) {
        val file=File(File(instrument.targetContext.noBackupFilesDir,"alpha-data"),name)
        until(20_000,"$name to be saved") {file.isFile}
    }

    /** A bot's greeting that was held by the plan limit, the app closed for a while, then opened again: it starts, and the old greeting is dropped instead of answered. */
    @Test fun anAppOpenedAgainWithAnOldHeldGreetingStartsAndDropsIt() {
        val context=instrument.targetContext
        val store=SecureStore(context);val previous=store.load();var first:Controller?=null;var second:Controller?=null
        try {
            instrument.runOnMainSync {first=Controller(context,gateway);first!!.saveConfig("chatgpt",obj("access_token" to "test-only-not-a-real-token"));first!!.clearChat("all")}
            val a=first!!
            instrument.runOnMainSync {a.submitTeam("hello everyone")}
            until(30_000,"the greeting to be held by the limit") {a.limitedUntil>0L && a.tasks.any {it.team && it.status=="Paused"}}
            val held=a.tasks.first {it.team && it.status=="Paused"}
            instrument.runOnMainSync {a.flushState()}
            waitForFile("tasks/${held.id}.enc")
            instrument.runOnMainSync {a.scope.cancel()}
            Chitchat.staleMs=0      // "a long time later"
            instrument.runOnMainSync {second=Controller(context,gateway)}
            val b=second!!
            until(20_000,"the second start to finish loading") {b.hydrated}
            val again=b.tasks.firstOrNull {it.id==held.id}
            assertNotNull("the old job is still on the list",again)
            assertEquals("the old greeting is dropped, not answered: "+again!!.status+" "+again.detail,"Cancelled",again.status)
            assertTrue(b.cases.none {it.state=="working"})
        } finally {Chitchat.staleMs=Chitchat.STALE_MS;instrument.runOnMainSync {first?.scope?.cancel();second?.scope?.cancel()};store.save(previous)}
    }

    /** A real job held by the plan limit, the app closed past the reset time, then opened again: it starts and the job is picked up again (and held again, the limit being still on). */
    @Test fun anAppOpenedAgainWithAHeldJobAndAPassedLimitStarts() {
        val context=instrument.targetContext
        val store=SecureStore(context);val previous=store.load();var first:Controller?=null;var second:Controller?=null
        try {
            instrument.runOnMainSync {first=Controller(context,gateway);first!!.saveConfig("chatgpt",obj("access_token" to "test-only-not-a-real-token"));first!!.clearChat("all");first!!.retryDelayScale=0.0001}
            val a=first!!
            var id="";instrument.runOnMainSync {id=a.submit("Write me a short thank-you note")!!}
            until(30_000,"the job to be held") {a.tasks.first {it.id==id}.status=="Paused"}
            instrument.runOnMainSync {a.flushState()}
            waitForFile("tasks/$id.enc")
            instrument.runOnMainSync {a.scope.cancel()}
            runBlocking {delay(500)}      // the saved reset time is now in the past
            instrument.runOnMainSync {second=Controller(context,gateway)}
            val b=second!!
            until(20_000,"the second start to finish loading") {b.hydrated}
            assertNotNull(b.tasks.firstOrNull {it.id==id})
        } finally {instrument.runOnMainSync {first?.scope?.cancel();second?.scope?.cancel()};store.save(previous)}
    }

    /** Plain restart with a finished job and an old unfinished one that is not a greeting. */
    @Test fun anAppOpenedAgainWithAnUnfinishedJobStarts() {
        val context=instrument.targetContext
        val store=SecureStore(context);val previous=store.load();var first:Controller?=null;var second:Controller?=null
        try {
            instrument.runOnMainSync {first=Controller(context,gateway);first!!.saveConfig("chatgpt",obj("access_token" to "test-only-not-a-real-token"));first!!.clearChat("all")}
            val a=first!!
            var id="";instrument.runOnMainSync {id=a.submit("Write me a short thank-you note")!!}
            until(30_000,"the job to be held") {a.tasks.first {it.id==id}.status=="Paused"}
            instrument.runOnMainSync {a.flushState()}
            waitForFile("tasks/$id.enc")
            instrument.runOnMainSync {a.scope.cancel()}
            Chitchat.staleMs=0
            instrument.runOnMainSync {second=Controller(context,gateway)}
            val b=second!!
            until(20_000,"the second start to finish loading") {b.hydrated}
            val again=b.tasks.first {it.id==id}
            assertNotEquals("a real job is never dropped as a greeting","Cancelled",again.status)
        } finally {Chitchat.staleMs=Chitchat.STALE_MS;instrument.runOnMainSync {first?.scope?.cancel();second?.scope?.cancel()};store.save(previous)}
    }
}
