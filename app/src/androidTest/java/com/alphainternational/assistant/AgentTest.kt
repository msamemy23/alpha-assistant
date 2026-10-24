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

class AgentTest {
    /** Fake model follows the same plan/receipt protocol as the live model; no harness bypass. */
    private fun protocolGateway(base:AiGateway):AiGateway=object:AiGateway {
        private var saved:JSONObject?=null
        private var planned=false
        override fun cancel(){base.cancel()}
        private fun call(name:String,id:String,args:JSONObject)=obj("output" to JSONArray().put(obj("type" to "function_call","name" to name,"call_id" to id,"arguments" to args.toString())))
        override suspend fun respond(model:String,history:JSONArray,instructions:String,tools:List<Tool>,onText:suspend(String)->Unit):JSONObject {
            if(!planned) {
                val first=base.respond(model,history,instructions,tools,onText);saved=first;planned=true
                val live=first.getJSONArray("output").objects().any {it.optString("type")=="function_call"}
                return call("task_plan","qa-plan",obj("requirements_json" to arr(listOf(obj("id" to "test","description" to "Requested test result","kind" to if(live)"live" else "answer"))).toString()))
            }
            val pending=saved
            if(pending!=null && pending.getJSONArray("output").objects().any {it.optString("type")=="function_call"}){saved=null;return pending}
            val response=pending?:base.respond(model,history,instructions,tools,onText)
            if(response.getJSONArray("output").objects().any {it.optString("type")=="function_call"})return response
            if(history.objects().any {it.optString("type")=="function_call_output" && it.optString("call_id")=="qa-finish"}){saved=null;return response}
            saved=response
            val receipts=TaskProtocol.receipts(history);val success=receipts.all {it.getBoolean("success")}
            return call("task_finish","qa-finish",obj("outcome" to if(success)"completed" else "partial","summary" to if(success)"Test result verified." else "The reviewed action was blocked.",
                "checks_json" to arr(listOf(obj("id" to "test","status" to if(success)"passed" else "blocked","evidence" to arr(receipts.filter {it.getBoolean("success")}.map {it.getString("call_id")})))).toString()))
        }
    }
    @Test fun RoutineWithNothingImportantDoesNotMessageTheOwner() {
        val instrument=InstrumentationRegistry.getInstrumentation();val context=instrument.targetContext
        val store=SecureStore(context);val previous=store.load();var made:Controller?=null
        val gateway=object:AiGateway {
            override fun cancel(){}
            override suspend fun respond(model:String,history:JSONArray,instructions:String,tools:List<Tool>,onText:suspend (String)->Unit):JSONObject =
                obj("output" to JSONArray().put(obj("type" to "message","role" to "assistant","content" to JSONArray().put(obj("type" to "output_text","text" to "Nothing important changed.")))))
        }
        val routine=Routine(java.util.UUID.randomUUID().toString(),"Quiet check","Quiet check "+java.util.UUID.randomUUID(),1,"Alpha")
        try {
            instrument.runOnMainSync {made=Controller(context,protocolGateway(gateway))}
            val c=made!!
            runBlocking {kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {c.runRoutine(routine)}}
            val task=c.tasks.single {it.routineId==routine.id}
            assertEquals("Completed",task.status)
            assertFalse(c.messages.any {it.taskId==task.id})
        }finally{instrument.runOnMainSync{made?.scope?.cancel()};store.save(previous)}
    }
    /** A failed run tells the owner once; it does not stop the routine, so the next scheduled run still happens. */
    @Test fun PausedRoutineRunReportsOnceAndDoesNotBlockTheNextRun() {
        val instrument=InstrumentationRegistry.getInstrumentation();val context=instrument.targetContext
        val store=SecureStore(context);val previous=store.load();var calls=0
        val gateway=object:AiGateway {
            override fun cancel(){}
            override suspend fun respond(model:String,history:JSONArray,instructions:String,tools:List<Tool>,onText:suspend (String)->Unit):JSONObject {
                calls++;throw IllegalStateException("Test account needs attention.")
            }
        }
        var made:Controller?=null
        val routine=Routine(java.util.UUID.randomUUID().toString(),"Test routine","Test routine "+java.util.UUID.randomUUID(),1,"Alpha")
        try {
            instrument.runOnMainSync {made=Controller(context,protocolGateway(gateway))}
            val c=made!!
            runBlocking {kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                c.runRoutine(routine);c.reportRoutineAttention(routine);c.reportRoutineAttention(routine)
            }}
            assertEquals(1,calls)
            val task=c.tasks.single {it.prompt==routine.prompt}
            assertEquals("Paused",task.status)
            assertEquals(1,c.messages.count {it.taskId==task.id && it.role=="update"})
            runBlocking {kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {c.runRoutine(routine)}}
            assertEquals(2,calls)
            assertEquals(2,c.tasks.count {it.prompt==routine.prompt})
        }finally{instrument.runOnMainSync{made?.scope?.cancel()};store.save(previous)}
    }
    @Test fun BrowserActionWaitsForApprovalAndBlocksAChangedTarget() {
        val instrument=InstrumentationRegistry.getInstrumentation();val context=instrument.targetContext
        val store=SecureStore(context);val previous=store.load()
        lateinit var browser:BrowserEngine;var browserMade=false;var made:Controller?=null;var button="";var requests=0;var id=""
        val gateway=object:AiGateway {
            override fun cancel(){}
            override suspend fun respond(model:String,history:JSONArray,instructions:String,tools:List<Tool>,onText:suspend (String)->Unit):JSONObject {
                delay(1);requests++
                if(requests==1)return obj("output" to JSONArray().put(obj("type" to "function_call","call_id" to "review-click","name" to "browser_click",
                    "arguments" to obj("id" to button).toString())))
                val result=history.objects().single {it.optString("type")=="function_call_output" && it.optString("call_id")=="review-click"}.getString("output")
                assertTrue(result.contains("Blocked"))
                return obj("output" to JSONArray().put(obj("type" to "message","role" to "assistant","content" to JSONArray().put(obj("type" to "output_text","text" to "The page changed; no click ran.")))))
            }
        }
        try {
            instrument.runOnMainSync {
                browser=BrowserEngine(context,{it},{_,_->});browserMade=true
                browser.view.loadDataWithBaseURL("https://review.example","<html><body><button onclick='window.didClick=true'>Test action</button></body></html>","text/html","UTF-8",null)
                made=Controller(context,protocolGateway(gateway));made!!.uiActive=true;made!!.attachBrowser(browser)
                made!!.saveConfig("chatgpt",obj("access_token" to "test-only-not-a-real-token"))
                // This checks the approval guard itself, so approvals are switched on for everything.
                made!!.updateAi(made!!.aiSettings.copy(approvals="all"))
            }
            val c=made!!
            runBlocking {withTimeout(10_000){while(browser.currentUrl.isBlank())delay(20)}
                button=JSONObject(browser.read()).getJSONArray("elements").objects().first().getString("id")
            }
            instrument.runOnMainSync {id=c.submit("Review this local test button.")!!}
            runBlocking {withTimeout(10_000){while(c.tasks.first {it.id==id}.status!="Needs review")delay(20)}}
            assertNotNull(c.approval)
            var clicked="";instrument.runOnMainSync {browser.view.evaluateJavascript("Boolean(window.didClick)") {clicked=it}}
            runBlocking {withTimeout(3000){while(clicked.isBlank())delay(20)}};assertEquals("false",clicked)
            instrument.runOnMainSync {browser.view.evaluateJavascript("document.querySelector('button').innerText='Changed test action'",null)}
            runBlocking {delay(100)}
            instrument.runOnMainSync {c.approve()}
            runBlocking {withTimeout(10_000){while(c.tasks.first {it.id==id}.status!="Partial")delay(20)}}
            assertEquals(2,requests)
            clicked="";instrument.runOnMainSync {browser.view.evaluateJavascript("Boolean(window.didClick)") {clicked=it}}
            runBlocking {withTimeout(3000){while(clicked.isBlank())delay(20)}};assertEquals("false",clicked)
        }finally{instrument.runOnMainSync{made?.scope?.cancel();if(browserMade)browser.destroy()};store.save(previous)}
    }
    @Test fun ImportantUpdatesAreSavedOncePerSourceEvent() {
        val instrument=InstrumentationRegistry.getInstrumentation();val context=instrument.targetContext
        val store=SecureStore(context);val previous=store.load();var n=0
        val event="test-event-"+java.util.UUID.randomUUID()
        val gateway=object:AiGateway {
            override fun cancel(){}
            override suspend fun respond(model:String,history:JSONArray,instructions:String,tools:List<Tool>,onText:suspend (String)->Unit):JSONObject {
                delay(1);n++
                if(n<=2)return obj("output" to JSONArray().put(obj("type" to "function_call","call_id" to ("notice-"+n),"name" to "notify_important",
                    "arguments" to obj("event_key" to event,"title" to "Test appointment update","text" to "This is an automated local test.").toString())))
                val results=history.objects().filter {it.optString("type")=="function_call_output" && it.optString("call_id").startsWith("notice-")}.map {JSONObject(it.getString("output"))}
                assertTrue(results[0].getBoolean("notified"));assertFalse(results[1].getBoolean("notified"))
                return obj("output" to JSONArray().put(obj("type" to "message","role" to "assistant","content" to JSONArray().put(obj("type" to "output_text","text" to "Checked.")))))
            }
        }
        var made:Controller?=null;var id=""
        try {
            instrument.runOnMainSync {
                made=Controller(context,protocolGateway(gateway));made!!.saveConfig("chatgpt",obj("access_token" to "test-only-not-a-real-token"))
                id=made!!.submit("Check a test appointment update.")!!
            }
            val c=made!!
            runBlocking {withTimeout(10_000){while(c.tasks.first {it.id==id}.status!="Completed")delay(20)}}
            assertEquals(1,c.messages.count {it.taskId==id && it.role=="update"})
        }finally{instrument.runOnMainSync{made?.scope?.cancel()};store.save(previous)}
    }
    @Test fun ModelToolLoopExecutesARealFileAndReplaysItsResult() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val store=SecureStore(context);val previous=store.load()
        var calls=0
        val gateway=object:AiGateway {
            override fun cancel(){}
            override suspend fun respond(model:String,history:JSONArray,instructions:String,tools:List<Tool>,onText:suspend (String)->Unit):JSONObject {
                delay(1);calls++
                assertTrue(Profile.generic || instructions.contains(Profile.shopName))
                return if(calls==1) obj("output" to JSONArray().put(obj("type" to "reasoning","id" to "reasoning-1","summary" to JSONArray()))
                    .put(obj("type" to "function_call","name" to "file_write","call_id" to "file-1",
                        "arguments" to obj("name" to "agent-test.txt","text" to "Created by the task engine.").toString())))
                else {
                    assertTrue(history.objects().any {it.optString("type")=="reasoning"})
                    assertTrue(history.objects().any {it.optString("type")=="function_call_output" && it.optString("output").contains("File saved")})
                    obj("output" to JSONArray().put(obj("type" to "message","role" to "assistant","content" to JSONArray().put(obj("type" to "output_text","text" to "File saved.")))))
                }
            }
        }
        lateinit var c:Controller;var made=false;var id=""
        try {
            instrumentation.runOnMainSync {
                c=Controller(context,protocolGateway(gateway));made=true;c.switchWorkspace("Alpha");c.saveConfig("chatgpt",obj("access_token" to "test-only-not-a-real-token"))
                id=c.submit("Create a local test note.")!!
            }
            runBlocking {withTimeout(10_000){while(c.tasks.first {it.id==id}.status!="Completed")delay(20)}}
            assertEquals(2,calls)
            assertEquals("Created by the task engine.",c.file("agent-test.txt").readText())
            assertTrue(AgentHistory.pending(JSONArray(c.tasks.first {it.id==id}.history)).isEmpty())
            // Like a person: "On it, I'll text you" when the work starts, then the answer when it's done.
            val texts=c.messages.filter {it.taskId==id && it.role=="assistant"}.map {it.text}
            assertEquals(2,texts.size)
            assertEquals(Acknowledge.line("Create a local test note."),texts.first())
            assertTrue(texts.last().contains("File saved"))
        }finally{
            instrumentation.runOnMainSync {if(made){c.scope.cancel();c.file("agent-test.txt").delete()}}
            store.save(previous)
        }
    }
}
