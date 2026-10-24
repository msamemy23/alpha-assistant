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

/** After a chat, the bot learns on its own: a preference from a correction, a person, and where things stand, into its own memory. */
class LearningFlowTest {
    private fun reply(text:String)=obj("output" to JSONArray().put(obj("type" to "message","role" to "assistant","content" to JSONArray().put(obj("type" to "output_text","text" to text)))))

    @Test fun botLearnsFromAConversationIntoItsOwnMemory() {
        val instrument=InstrumentationRegistry.getInstrumentation();val context=instrument.targetContext
        val store=SecureStore(context);val previous=store.load();var made:Controller?=null
        val learnerPrompts=mutableListOf<String>();var botInstructions=""
        val gateway=object:AiGateway {
            override fun cancel() {}
            override suspend fun respond(model:String,history:JSONArray,instructions:String,tools:List<Tool>,onText:suspend (String)->Unit):JSONObject {
                if(instructions.startsWith("You help a personal assistant bot learn its owner")) {
                    learnerPrompts+=history.getJSONObject(0).getString("content")
                    return reply("""{"remember":[{"id":"","kind":"preference","title":"Short texts","text":"Likes texts to customers short, signed Acme Auto Repair","from":"you","private":false},
                        {"id":"","kind":"person","title":"Maria Lopez","text":"2015 Camry; approve brakes by text","from":"you","private":false}],"forget":[],"summary":"Brake estimate for Maria sent; waiting on her OK."}""")
                }
                // (What the bot knows about the owner now comes with the changing notes at the end of each round, not in the fixed instructions.)
                botInstructions=instructions+"\n"+history.objects().joinToString("\n") {it.optString("content")}
                return reply("Got it, short and signed. I'll text Maria.")
            }
        }
        try {
            store.save(JSONObject(previous.toString()).put("model","qa-model"))
            instrument.runOnMainSync {
                made=Controller(context,gateway);made!!.learning=true
                made!!.saveConfig("chatgpt",obj("access_token" to "test-only-not-a-real-token"))
            }
            val c=made!!;val bot=c.workspace
            var id="";instrument.runOnMainSync {id=c.submit("No, keep texts to customers short and sign them Acme Auto Repair. Maria Lopez has the 2015 Camry.")!!}
            // The bot saves what it learned one memory at a time (each save writes the phone's state), so wait for the "where things stand" note too.
            runBlocking {withTimeout(20_000) {while(c.memories.count {it.task==id}<2 || c.memories.none {it.kind=="summary"}) delay(50)}}
            val learned=c.memories.filter {it.task==id}
            assertTrue(learned.all {it.workspace==bot && it.source=="You said"})
            assertEquals(setOf("preference","person"),learned.map {it.kind}.toSet())
            assertTrue(c.memories.any {it.kind=="summary" && it.workspace==bot && it.text.contains("Maria")})
            // The learner saw the owner's words and the bot's reply.
            assertTrue(learnerPrompts.single().contains("keep texts to customers short") && learnerPrompts.single().contains("I'll text Maria"))
            // Next time, the bot is told what it learned.
            var next="";var told=""
            instrument.runOnMainSync {c.learning=false;next=c.submit("Text Maria her car is ready")!!}
            runBlocking {withTimeout(10_000) {while(c.tasks.first {it.id==next}.status!="Completed") delay(20)}}
            told=c.messages.last {it.taskId==next && it.role=="assistant"}.text
            assertTrue(told.isNotBlank())
            assertTrue(botInstructions.contains("How the owner likes things:") && botInstructions.contains("Short texts") && botInstructions.contains("Maria Lopez"))
        } finally {instrument.runOnMainSync {made?.scope?.cancel()};store.save(previous)}
    }
}
