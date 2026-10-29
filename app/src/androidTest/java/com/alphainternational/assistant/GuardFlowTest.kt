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

/** Guard on a real Controller: the pause switch, the do-not-contact list, the dials, the approval buttons, and the list of what the bots did with Undo. */
class GuardFlowTest {
    private fun reply(text:String)=obj("output" to JSONArray().put(obj("type" to "message","role" to "assistant","content" to JSONArray().put(obj("type" to "output_text","text" to text)))))
    private fun call(name:String,id:String,args:JSONObject)=obj("output" to JSONArray().put(obj("type" to "function_call","name" to name,"call_id" to id,"arguments" to args.toString())))
    private fun until(ms:Long,what:String,check:()->Boolean) {runBlocking {withTimeout(ms) {while(!check()) delay(25)}}}
    private fun connect(c:Controller) {c.saveConfig("chatgpt",obj("access_token" to "test-only-not-a-real-token"))}
    private val email=obj("to" to "maria@shop.com","subject" to "Your car","body" to "It is ready for pickup.")

    /** A model that asks for [tool] once and then says it is done; [seen] collects what the tool answered. */
    private fun gateway(tool:String,args:JSONObject,seen:MutableList<String>)=object:AiGateway {
        var round=0
        override fun cancel() {}
        override suspend fun respond(model:String,history:JSONArray,instructions:String,tools:List<Tool>,onText:suspend (String)->Unit):JSONObject {
            round++
            if(round==1) return call(tool,"c1",args)
            history.objects().filter {it.optString("type")=="function_call_output"}.forEach {seen+=it.optString("output")}
            return reply("Done.")
        }
    }

    private fun withController(model:AiGateway,test:(Controller)->Unit) {
        val instrument=InstrumentationRegistry.getInstrumentation();val context=instrument.targetContext
        val store=SecureStore(context);val previous=store.load();var made:Controller?=null
        try {
            instrument.runOnMainSync {made=Controller(context,model);connect(made!!)}
            test(made!!)
        } finally {instrument.runOnMainSync {made?.scope?.cancel()};store.save(previous)}
    }
    private fun submit(c:Controller,text:String):String {var id="";InstrumentationRegistry.getInstrumentation().runOnMainSync {id=c.submit(text)!!};return id}
    private fun done(c:Controller,id:String)=until(30_000,"the job to finish") {c.tasks.firstOrNull {it.id==id}?.status in setOf("Completed","Partial")}

    @Test fun whilePausedNothingGoesOutAndTheBotIsToldWhy() {
        val seen=mutableListOf<String>()
        withController(gateway("gmail_send",email,seen)) {c->
            InstrumentationRegistry.getInstrumentation().runOnMainSync {c.updateAi(AiSettings(approvals="bots"));c.setActionsPaused(true)}
            done(c,submit(c,"Email maria@shop.com that her car is ready for pickup."))
            assertTrue(seen.joinToString(),seen.any {it.contains("paused",true)})
            assertTrue("nothing was recorded as done",c.actionLog().none {it.tool=="gmail_send"})
            InstrumentationRegistry.getInstrumentation().runOnMainSync {c.setActionsPaused(false)}
            assertFalse(c.actionsPaused())
        }
    }

    @Test fun nobodyOnTheDoNotContactListIsWrittenTo() {
        val seen=mutableListOf<String>()
        withController(gateway("gmail_send",email,seen)) {c->
            InstrumentationRegistry.getInstrumentation().runOnMainSync {c.updateAi(AiSettings(approvals="bots"));c.addDoNotContact("Maria@Shop.com")}
            assertEquals(setOf("maria@shop.com"),c.doNotContact())
            done(c,submit(c,"Email maria@shop.com that her car is ready for pickup."))
            assertTrue(seen.joinToString(),seen.any {it.contains("do-not-contact")})
        }
    }

    @Test fun aDialSetToAskMakesASendWaitForTheOwnersOk() {
        val seen=mutableListOf<String>()
        withController(gateway("gmail_send",email,seen)) {c->
            // "Bots ask before" says go, but the owner turned the messages dial to ask: the send waits for the card, and the Deny button on the notification ends it.
            InstrumentationRegistry.getInstrumentation().runOnMainSync {c.updateAi(Guard.with(AiSettings(approvals="bots"),Guard.Area.MESSAGES,Guard.ASK))}
            val id=submit(c,"Email maria@shop.com that her car is ready for pickup.")
            until(30_000,"the job to wait for an OK") {c.tasks.firstOrNull {it.id==id}?.status=="Needs review"}
            InstrumentationRegistry.getInstrumentation().runOnMainSync {c.declineFromNotice(id)}
            done(c,id)
            assertTrue(seen.joinToString(),seen.any {it.contains("declined",true)})
            assertTrue(c.actionLog().none {it.tool=="gmail_send"})
        }
    }

    @Test fun aDialSetToGoLetsASendThroughWithoutACard() {
        val seen=mutableListOf<String>()
        withController(gateway("gmail_send",email,seen)) {c->
            // Same settings the other way round: the older setting asks, the messages dial says go. (Gmail is not connected here, so the send itself reports that.)
            InstrumentationRegistry.getInstrumentation().runOnMainSync {c.updateAi(Guard.with(AiSettings(approvals="important"),Guard.Area.MESSAGES,Guard.GO))}
            val id=submit(c,"Email maria@shop.com that her car is ready for pickup.")
            done(c,id)
            assertTrue("no card was needed",seen.isNotEmpty())
        }
    }

    @Test fun whatABotMadeIsListedAndCanBeUndone() {
        val seen=mutableListOf<String>()
        val skill=obj("id" to "qa_greeting","title" to "QA greeting","purpose" to "A test skill","instructions" to "Say hello in one short line.")
        withController(gateway("skill_save",skill,seen)) {c->
            InstrumentationRegistry.getInstrumentation().runOnMainSync {c.updateAi(AiSettings(approvals="bots"))}
            done(c,submit(c,"Save a skill called QA greeting that says hello in one short line."))
            val entry=c.actionLog().firstOrNull {it.tool=="skill_save"}
            assertNotNull(seen.joinToString(),entry)
            assertTrue(entry!!.canUndo)
            var said=""
            InstrumentationRegistry.getInstrumentation().runOnMainSync {said=c.undoAction(entry.id)}
            assertTrue(said,said.contains("removed"))
            assertTrue(c.userSkills.none {it.id=="qa_greeting"})
            assertTrue(c.actionLog().first {it.id==entry.id}.undone)
            assertFalse("it cannot be undone twice",c.actionLog().first {it.id==entry.id}.canUndo)
        }
    }
}
