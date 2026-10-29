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

/** Start mode on a real Controller: a bot saves the business, every bot then knows it, and the Start door makes the Founder bot and asks the first question. */
class BusinessFlowTest {
    private fun reply(text:String)=obj("output" to JSONArray().put(obj("type" to "message","role" to "assistant","content" to JSONArray().put(obj("type" to "output_text","text" to text)))))
    private fun call(name:String,id:String,args:JSONObject)=obj("output" to JSONArray().put(obj("type" to "function_call","name" to name,"call_id" to id,"arguments" to args.toString())))
    private fun until(ms:Long,check:()->Boolean) {runBlocking {withTimeout(ms) {while(!check()) delay(25)}}}
    private fun main(block:()->Unit) {InstrumentationRegistry.getInstrumentation().runOnMainSync(block)}
    private fun withController(model:AiGateway,test:(Controller)->Unit) {
        val instrument=InstrumentationRegistry.getInstrumentation();val context=instrument.targetContext
        val store=SecureStore(context);val previous=store.load();var made:Controller?=null
        try {
            instrument.runOnMainSync {made=Controller(context,model);made!!.saveConfig("chatgpt",obj("access_token" to "test-only-not-a-real-token"))}
            test(made!!)
        } finally {instrument.runOnMainSync {made?.scope?.cancel()};store.save(previous)}
    }
    private fun submit(c:Controller,text:String):String {var id="";main {id=c.submit(text)!!};return id}
    private fun done(c:Controller,id:String) {until(30_000) {c.tasks.firstOrNull {it.id==id}?.status in setOf("Completed","Partial")}}

    @Test fun aBotSavesTheBusinessAndTheNextJobStartsKnowingIt() {
        var round=0;val seen=mutableListOf<String>()
        val model=object:AiGateway {
            override fun cancel() {}
            override suspend fun respond(model:String,history:JSONArray,instructions:String,tools:List<Tool>,onText:suspend (String)->Unit):JSONObject {
                seen+=history.toString()
                round++
                return if(round==1) call("business_update","b1",obj("set_json" to obj("idea" to "Mobile car detailing in my town","kind" to "service","name" to "Shine On","price" to "80 dollars a car").toString(),
                    "step_json" to obj("id" to "fit","status" to "done").toString(),"decision_json" to obj("question" to "Which business?","choice" to "Car detailing","why" to "I like cars").toString()))
                else reply("Noted.")
            }
        }
        withController(model) {c->
            assertFalse(c.business.exists)
            done(c,submit(c,"Save my business: mobile car detailing called Shine On, 80 dollars a car."))
            val b=c.business
            assertEquals("Shine On",b.name);assertEquals("service",b.kind)
            assertTrue("the steps were made for a service business",b.steps.any {it.id=="quote"})
            assertEquals(Journey.DONE,b.steps.first {it.id=="fit"}.status)
            assertEquals(1,b.decisions.size)
            assertEquals("numbers",b.next()!!.id)
            // a different request, later: the bot is told about the business without asking for it
            seen.clear()
            done(c,submit(c,"Write a short post for my page."))
            assertTrue("the business was in the job's notes",seen.joinToString().contains("Shine On"))
            // and the profile tool reads it back
            var read:JSONObject?=null
            main {read=JSONObject(c.business.json().toString())}
            assertEquals("Mobile car detailing in my town",read!!.getString("idea"))
        }
    }

    @Test fun theStartDoorMakesTheFounderBotAndAsksTheFirstQuestion() {
        val seen=mutableListOf<String>()
        val model=object:AiGateway {
            override fun cancel() {}
            override suspend fun respond(model:String,history:JSONArray,instructions:String,tools:List<Tool>,onText:suspend (String)->Unit):JSONObject {
                seen+=history.toString();return reply("Tell me what you are good at.")
            }
        }
        withController(model) {c->
            assertTrue(c.bots.none {it.name=="Founder"})
            var name=""
            main {name=c.startBusinessChat()}
            assertEquals("Founder",name);assertEquals("Founder",c.workspace)
            assertTrue(c.bots.any {it.name=="Founder" && it.rules.contains("business_profile")})
            until(30_000) {c.tasks.any {it.workspace=="Founder" && it.status=="Completed"}}
            assertTrue(seen.joinToString(),seen.joinToString().contains("I want to start a business"))
            // the start skill was put in front of the model (it matched the words)
            assertTrue(seen.joinToString().contains("Find the business that fits"))
        }
    }

    @Test fun forgettingTheBusinessClearsItAndAnythingMadeUpIsRefused() {
        var round=0;val seen=mutableListOf<String>()
        val model=object:AiGateway {
            override fun cancel() {}
            override suspend fun respond(model:String,history:JSONArray,instructions:String,tools:List<Tool>,onText:suspend (String)->Unit):JSONObject {
                round++
                if(round==1) return call("business_update","b1",obj("set_json" to "not json at all","step_json" to "{}","decision_json" to "{}"))
                history.objects().filter {it.optString("type")=="function_call_output"}.forEach {seen+=it.optString("output")}
                return reply("ok")
            }
        }
        withController(model) {c->
            main {c.updateBusiness(JSONObject().put("idea","Pet sitting"),null,null)}
            assertTrue(c.business.exists)
            done(c,submit(c,"Update my business with this: not json."))
            assertTrue(seen.joinToString(),seen.any {it.contains("must be a JSON object")})
            assertEquals("a bad call changes nothing","Pet sitting",c.business.idea)
            main {c.forgetBusiness()}
            assertFalse(c.business.exists);assertTrue(c.business.steps.isEmpty())
        }
    }
}
