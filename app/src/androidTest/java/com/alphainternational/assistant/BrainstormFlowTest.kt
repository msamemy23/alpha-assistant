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

/**
 * Brainstorm with a scripted AI: the owner's text goes in, the answer streams back and is saved as a chat; the AI gets no phone tools and the owner's earlier
 * texts; Stop keeps what was written; a failure is said plainly; the chats survive a restart; and none of it touches the team, the bots or any job.
 */
class BrainstormFlowTest {
    private fun reply(text:String)=obj("output" to JSONArray().put(obj("type" to "message","role" to "assistant","content" to JSONArray().put(obj("type" to "output_text","text" to text)))))

    private class Seen {var tools=-1;var instructions="";val inputs=mutableListOf<String>();var calls=0}

    private fun <T> withController(gateway:AiGateway,body:(Controller)->T):T {
        val instrument=InstrumentationRegistry.getInstrumentation();val context=instrument.targetContext
        val store=SecureStore(context);val previous=store.load();var made:Controller?=null
        try {
            store.save(JSONObject(previous.toString()).put("model","qa-model"))
            instrument.runOnMainSync {made=Controller(context,gateway);made!!.saveConfig("chatgpt",obj("access_token" to "test-only-not-a-real-token"))}
            return body(made!!)
        } finally {instrument.runOnMainSync {made?.scope?.cancel()};store.save(previous)}
    }
    private fun until(what:String,timeoutMs:Long=20_000,cond:()->Boolean) {
        runBlocking {try {withTimeout(timeoutMs) {while(!cond()) delay(20)}} catch(e:kotlinx.coroutines.TimeoutCancellationException) {fail("Timed out waiting for $what")}}
    }
    private fun run(block:()->Unit) {InstrumentationRegistry.getInstrumentation().runOnMainSync(block)}

    @Test fun aTextGetsAStreamedAnswerAndTheChatIsKeptForNextTime() {
        val seen=Seen()
        val gateway=object:AiGateway {
            override fun cancel() {}
            override suspend fun respond(model:String,history:JSONArray,instructions:String,tools:List<Tool>,onText:suspend (String)->Unit):JSONObject {
                seen.calls++;seen.tools=tools.size;seen.instructions=instructions;seen.inputs+=history.toString()
                val answer=if(seen.calls==1) "# Try a Monday special\n\nA small discount on a slow day brings people in." else "Texting past customers works because they already trust you."
                onText(answer.take(10));delay(30);onText(answer.drop(10))
                return reply(answer)
            }
        }
        withController(gateway) {c->
            c.clearChat("all")
            run {c.brainstormChats.forEach {c.brainstormDelete(it.id)}}
            val before=c.tasks.size
            run {assertTrue(c.brainstormSend("Ideas for slow Mondays"))}
            until("the first answer") {c.brainstormChats.firstOrNull()?.messages?.size==2 && !c.brainstormBusy}
            val chat=c.brainstormChat()!!
            assertEquals("Ideas for slow Mondays",chat.title)
            assertEquals(listOf("user","assistant"),chat.messages.map {it.role})
            assertTrue(chat.messages[1].text.contains("Monday special"))
            assertEquals("no phone tools in a brainstorm",0,seen.tools)
            assertTrue(seen.instructions.contains("brainstorming partner"))
            run {assertTrue(c.brainstormSend("What about texting past customers"))}
            until("the second answer") {c.brainstormChat()!!.messages.size==4 && !c.brainstormBusy}
            // the AI saw the whole conversation so far, owner's words and its own answer included
            assertTrue(seen.inputs.last().contains("Ideas for slow Mondays") && seen.inputs.last().contains("Monday special") && seen.inputs.last().contains("texting past customers"))
            // it is not a job, not in any bot's chat and not in the team chat
            assertEquals(before,c.tasks.size)
            assertTrue(c.messages.none {it.text.contains("slow Mondays")} && c.teamMessages.none {it.text.contains("slow Mondays")})
            // it survives a restart: a new controller reads the saved file
            run {c.flushState()}
            Thread.sleep(500)
            var again:Controller?=null
            run {again=Controller(InstrumentationRegistry.getInstrumentation().targetContext,gateway)}
            try {assertEquals(listOf("Ideas for slow Mondays"),again!!.brainstormChats.map {it.title});assertEquals(4,again!!.brainstormChats.single().messages.size)} finally {run {again?.scope?.cancel()}}
        }
    }

    @Test fun stopKeepsWhatWasWrittenAndAFailureIsSaidPlainlyWithTryAgain() {
        var mode="slow"
        val gateway=object:AiGateway {
            override fun cancel() {}
            override suspend fun respond(model:String,history:JSONArray,instructions:String,tools:List<Tool>,onText:suspend (String)->Unit):JSONObject {
                if(mode=="fail") throw java.io.IOException("connection reset")
                onText("Here is the start of a long thought");delay(60_000)
                return reply("never reached")
            }
        }
        withController(gateway) {c->
            run {c.brainstormChats.forEach {c.brainstormDelete(it.id)}}
            run {c.brainstormSend("A long question")}
            until("words to appear") {c.brainstormLive.contains("start of a long thought")}
            assertTrue(c.brainstormBusy)
            run {c.brainstormStop()}
            until("the stop") {!c.brainstormBusy}
            val kept=c.brainstormChat()!!.messages
            assertEquals(listOf("user","assistant"),kept.map {it.role})
            assertTrue(kept[1].text,kept[1].text.startsWith("Here is the start of a long thought") && kept[1].text.endsWith("…"))
            assertEquals("",c.brainstormProblem)
            mode="fail"
            run {c.brainstormSend("And another")}
            until("the failure") {!c.brainstormBusy && c.brainstormProblem.isNotBlank()}
            assertEquals("the owner's text stays, with no answer under it",listOf("user","assistant","user"),c.brainstormChat()!!.messages.map {it.role})
            assertFalse(c.brainstormProblem.contains("IOException"))
            mode="slow"
            run {c.brainstormNew()}
            assertNull(c.brainstormChat())
            run {c.brainstormOpenChat(c.brainstormChats.first().id)}
            assertNotNull(c.brainstormChat())
            run {c.brainstormDelete(c.brainstormChats.first().id)}
            assertTrue(c.brainstormChats.isEmpty())
        }
    }
}
