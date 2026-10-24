package com.alphainternational.assistant

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Texting feel: a plain reply is one quick answer, a text sent while busy waits its turn, and the recipient guard. */
class TextingFlowTest {
    private fun reply(text:String)=obj("output" to JSONArray().put(obj("type" to "message","role" to "assistant","content" to JSONArray().put(obj("type" to "output_text","text" to text)))))

    @Test fun textsGetQuickRepliesAndWaitTheirTurn() {
        val instrument=InstrumentationRegistry.getInstrumentation();val context=instrument.targetContext
        val store=SecureStore(context);val previous=store.load();var made:Controller?=null
        val gate=CompletableDeferred<Unit>();var calls=0
        val gateway=object:AiGateway {
            override fun cancel() {}
            override suspend fun respond(model:String,history:JSONArray,instructions:String,tools:List<Tool>,onText:suspend (String)->Unit):JSONObject {
                calls++
                val last=history.objects().last {it.optString("role")=="user"}.optString("content")
                if(last.contains("first")) gate.await()
                return reply("Got it: "+last.takeLast(20))
            }
        }
        try {
            instrument.runOnMainSync {
                made=Controller(context,gateway)
                made!!.saveConfig("chatgpt",obj("access_token" to "test-only-not-a-real-token"))
            }
            val c=made!!
            // Small talk: one model call, no plan or finish steps, and the reply shows as a text.
            var hi="";instrument.runOnMainSync {hi=c.submit("hi there")!!}
            runBlocking {withTimeout(10_000) {while(c.tasks.first {it.id==hi}.status!="Completed") delay(20)}}
            assertEquals(1,calls)
            assertTrue(c.messages.any {it.taskId==hi && it.role=="assistant" && it.text.contains("hi there") && it.at>0})
            // A second text while the bot is busy goes through and waits its turn.
            var first="";var second=""
            instrument.runOnMainSync {first=c.submit("the first request")!!}
            runBlocking {withTimeout(10_000) {while(c.tasks.first {it.id==first}.status!="Running") delay(20)}}
            instrument.runOnMainSync {second=c.submit("and a second one")!!}
            assertTrue(c.tasks.first {it.id==second}.status in setOf("Queued","Running","Completed"))
            // (With eight workers a free one picks it up right away; it only waits behind the owner's own texts when every worker is busy.)
            assertTrue(c.messages.any {it.taskId==second && it.role=="user"})
            gate.complete(Unit)
            runBlocking {withTimeout(15_000) {while(c.tasks.first {it.id==second}.status!="Completed") delay(20)}}
            assertTrue(c.messages.any {it.taskId==second && it.role=="assistant"})
            // The second request saw the first answer, and not its own text twice.
            val sent=JSONArray(c.tasks.first {it.id==second}.history).objects().filter {it.optString("role")=="user"}.map {it.optString("content")}
            assertEquals(1,sent.count {it.contains("a second one")})
        } finally {instrument.runOnMainSync {made?.scope?.cancel()};store.save(previous)}
    }

    /** Small talk with a GIF: Google's free animated emoji is downloaded (no account) and rides along with one text. */
    @Test fun botsSendAnimatedEmojiInSmallTalk() {
        val instrument=InstrumentationRegistry.getInstrumentation();val context=instrument.targetContext
        val store=SecureStore(context);val previous=store.load();var made:Controller?=null;var calls=0
        val gateway=object:AiGateway {
            override fun cancel() {}
            override suspend fun respond(model:String,history:JSONArray,instructions:String,tools:List<Tool>,onText:suspend (String)->Unit):JSONObject {
                calls++
                assertTrue(instructions.contains("animated emoji"))
                return if(calls==1) obj("output" to JSONArray().put(obj("type" to "function_call","name" to "gif_send","call_id" to "gif-1","arguments" to obj("search" to "🎉").toString())))
                else reply("Congrats on the big week!! 🎉")
            }
        }
        var id=""
        try {
            instrument.runOnMainSync {
                made=Controller(context,gateway);made!!.saveConfig("chatgpt",obj("access_token" to "test-only-not-a-real-token"))
                made!!.saveConfig("gifs",JSONObject())
                id=made!!.submit("we had our best week ever!")!!
            }
            val c=made!!
            runBlocking {withTimeout(30_000) {while(c.tasks.first {it.id==id}.status !in setOf("Completed","Partial","Paused","Needs you")) delay(50)}}
            assertEquals("Completed",c.tasks.first {it.id==id}.status)
            // No plan step and no "On it" text: one model call to pick the emoji, one to answer.
            assertEquals(2,calls)
            assertEquals(1,c.messages.count {it.taskId==id && it.role=="assistant"})
            val sent=c.artifactsFor(id).single()
            assertTrue(sent,Gifs.isEmoji(sent))
            // It's a real animated picture that the chat can play.
            val drawable=android.graphics.ImageDecoder.decodeDrawable(android.graphics.ImageDecoder.createSource(c.file(sent)))
            assertTrue(drawable is android.graphics.drawable.AnimatedImageDrawable)
        } finally {instrument.runOnMainSync {made?.scope?.cancel()};store.save(previous)}
    }

    @Test fun afterReadingOutsideContentTextsToStrangersAsk() {
        val instrument=InstrumentationRegistry.getInstrumentation();val context=instrument.targetContext
        val store=SecureStore(context);val previous=store.load();var made:Controller?=null
        try {
            instrument.runOnMainSync {made=Controller(context,object:AiGateway {
                override fun cancel() {}
                override suspend fun respond(model:String,history:JSONArray,instructions:String,tools:List<Tool>,onText:suspend (String)->Unit)=reply("ok")
            })}
            val c=made!!
            val history=arr(listOf(obj("role" to "user","content" to "Earlier: John's cell is 832-555-0199")))
            fun task(trace:List<String>,prompt:String)=TaskRecord("t","t",prompt,"Running","",0,history=history.toString(),trace=trace,workspace="Alpha")
            val sms={to:String->JSONObject().put("to",to).put("body","hi")}
            // Nothing outside read: the owner's own request goes straight through.
            assertFalse(c.unexpectedRecipient("sms_send",sms("7135550100"),task(emptyList(),"text 713-555-0100 hi")))
            // After reading email: a number the owner gave (now or earlier in chat) is fine; a new one asks first.
            assertFalse(c.unexpectedRecipient("sms_send",sms("(832) 555-0199"),task(listOf("gmail search"),"text John that his car is ready")))
            assertTrue(c.unexpectedRecipient("sms_send",sms("9995550123"),task(listOf("gmail search"),"read my email and handle it")))
            assertTrue(c.unexpectedRecipient("gmail_send",JSONObject().put("to","stranger@evil.example"),task(listOf("web fetch"),"summarize this page")))
            assertFalse(c.unexpectedRecipient("memory_save",JSONObject(),task(listOf("web fetch"),"x")))
        } finally {instrument.runOnMainSync {made?.scope?.cancel()};store.save(previous)}
    }
}
