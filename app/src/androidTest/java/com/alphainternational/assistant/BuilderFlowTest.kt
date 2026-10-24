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

/** The bot builder end to end with a scripted model: one question, a tested tool, a plan, then the owner's Create tap. */
class BuilderFlowTest {
    private fun call(name:String,id:String,args:JSONObject)=obj("output" to JSONArray().put(obj("type" to "function_call","name" to "alpha.$name","call_id" to id,"arguments" to args.toString())))
    private fun output(history:JSONArray,id:String)=history.objects().firstOrNull {it.optString("type")=="function_call_output" && it.optString("call_id")==id}?.optString("output")

    @Test fun builderAsksTestsAToolProposesAndCreatesTheBot() {
        val instrument=InstrumentationRegistry.getInstrumentation();val context=instrument.targetContext
        val store=SecureStore(context);val previous=store.load();var made:Controller?=null
        val name="Quote Helper "+(1000..9999).random().toString().take(4)
        val seen=mutableListOf<String>()
        val gateway=object:AiGateway {
            override fun cancel() {}
            override suspend fun respond(model:String,history:JSONArray,instructions:String,tools:List<Tool>,onText:suspend (String)->Unit):JSONObject {
                seen+=instructions
                assertEquals(listOf("ask_owner","test_tool","propose_bot"),tools.map {it.name})
                val asked=output(history,"q1");val tested=output(history,"t1");val proposed=output(history,"p1")
                return when {
                    asked==null->call("ask_owner","q1",obj("question" to "Which email do you use?","choices_json" to "[\"Gmail\",\"Outlook\"]"))
                    tested==null->call("test_tool","t1",obj("kind" to "code","code" to "return input.hours*input.rate","inputs_json" to "[\"hours\",\"rate\"]",
                        "sample_input_json" to "{\"hours\":1.5,\"rate\":120}"))
                    proposed==null-> {
                        assertTrue(asked.contains("Gmail"));assertTrue(tested,tested.contains("180"))
                        call("propose_bot","p1",obj("summary" to "Your quote helper.","name" to name,"role" to "Builds labor quotes and emails drafts.",
                            "rules" to "Drafts only.","logins_json" to "[\"gmail\",\"web:https://www.example.com\"]","skills_json" to "[\"gmail_draft\"]",
                            "new_skills_json" to "[]","new_tools_json" to """[{"id":"qa_labor_quote","title":"Labor quote","description":"Labor cost from hours and rate.","kind":"code","inputs":["hours","rate"],"code":"return input.hours*input.rate"}]""",
                            "routines_json" to "[]","intro" to "Hi! Ask me for a labor quote."))
                    }
                    else->obj("output" to JSONArray().put(obj("type" to "message","role" to "assistant","content" to JSONArray().put(obj("type" to "output_text","text" to "Done.")))))
                }
            }
        }
        try {
            store.save(JSONObject(previous.toString()).put("model","qa-model"))
            instrument.runOnMainSync {made=Controller(context,gateway)}
            val c=made!!
            val b=c.builder
            suspend fun idle()=withTimeout(30_000) {delay(100);while(b.busy) delay(50)}
            instrument.runOnMainSync {b.start(again=true);b.send("A bot that writes labor quotes")}
            runBlocking {idle()}
            assertEquals("Which email do you use?",b.turns.last().text)
            assertEquals(listOf("Gmail","Outlook"),b.turns.last().choices)
            assertTrue(seen.first().contains("gmail_draft") && seen.first().contains("zapier"))

            instrument.runOnMainSync {b.send("Gmail")}
            runBlocking {idle()}
            val plan=b.turns.last().plan
            assertNotNull("Expected a plan, got: "+b.turns.last().text,plan)
            assertEquals(listOf("gmail","web:https://www.example.com"),plan!!.logins)

            var created:String?=null
            instrument.runOnMainSync {created=b.create(plan,emptySet())}
            assertEquals(name,created)
            val bot=c.bots.single {it.name==name}
            assertEquals(listOf("gmail_draft"),bot.skills);assertEquals(listOf("qa_labor_quote"),bot.tools)
            assertTrue(c.customTools.any {it.id=="qa_labor_quote"})
            assertTrue(b.turns.last().created==name)
            // The welcome message appears in the new bot's own chat.
            val welcome=c.tasks.single {it.workspace==name}
            assertTrue(c.messages.any {it.taskId==welcome.id && it.text.contains("labor quote")})
            // The made tool runs like any other.
            val result=runBlocking {kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                c.runCustomTool(c.customTools.single {it.id=="qa_labor_quote"},JSONObject().put("hours",2).put("rate",100))
            }}
            assertEquals(200,result.getInt("result"))
            instrument.runOnMainSync {c.removeBot(name);c.deleteTool("qa_labor_quote")}
        } finally {instrument.runOnMainSync {made?.scope?.cancel()};store.save(previous)}
    }
}
