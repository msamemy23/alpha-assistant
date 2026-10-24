package com.alphainternational.assistant

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentRulesTest {
    private fun tool(name:String,review:Boolean=false)=Tool(name,"d",obj("type" to "object","properties" to JSONObject(),"required" to arr(emptyList<String>())),review)
    private fun receipt(tool:String,ok:Boolean)=obj("call_id" to tool+ok,"tool" to tool,"success" to ok,"result" to JSONObject())

    @Test fun theModelNoLongerSeesTheBookkeepingTools() {
        val all=listOf("task_plan","task_finish","task_receipts","web_fetch","file_write","task_need_user").map {tool(it)}
        val shown=AgentRules.visibleTools(all,{true},true).map {it.name}
        assertEquals(listOf("web_fetch","file_write","task_need_user"),shown)
    }

    @Test fun unconnectedServicesAndABusyBrowserAreLeftOut() {
        val all=listOf("gmail_search","calendar_events","drive_list","vercel_projects","supabase_read","mcp_call","browser_open","browser_signin","web_fetch","sms_send").map {tool(it)}
        val onlyGmail=AgentRules.visibleTools(all,{it=="gmail"},true).map {it.name}
        assertEquals(listOf("gmail_search","browser_open","browser_signin","web_fetch","sms_send"),onlyGmail)
        // While another bot has the one phone browser, this bot works without it. (Alpha being in the background does not matter: the browser works there.)
        val busy=AgentRules.visibleTools(all,{true},false).map {it.name}
        assertFalse(busy.any {it.startsWith("browser_")})
        assertTrue("web_fetch" in busy && "sms_send" in busy)
    }

    @Test fun onlyReadOnlyToolsRunTogether() {
        listOf("web_fetch","file_read","gmail_search","calendar_events","supabase_read").forEach {assertTrue(it,it in AgentRules.parallelSafe)}
        // Anything that sends, changes or uses the one shared browser page stays one at a time.
        listOf("sms_send","gmail_send","file_write","memory_save","invoice_pdf","browser_open","browser_click","supabase_update","routine_create","bot_update","task_plan")
            .forEach {assertFalse(it,it in AgentRules.parallelSafe)}
        assertTrue(AgentRules.parallelSafe.intersect(AgentRules.unsureIfFailed).isEmpty())
    }

    @Test fun aFailedSendStopsAtCheckResultButAFailedLookupDoesNot() {
        assertTrue("sms_send" in AgentRules.unsureIfFailed && "gmail_send" in AgentRules.unsureIfFailed && "browser_click" in AgentRules.unsureIfFailed)
        // Looking things up, writing a local file or saving a memory can't have sent anything.
        listOf("web_fetch","file_write","memory_save","invoice_pdf","skill_save","routine_create").forEach {assertFalse(it,it in AgentRules.unsureIfFailed)}
    }

    @Test fun anAnsweredJobIsRecordedWithoutAChecklist() {
        val done=AgentRules.autoFinish(listOf(receipt("web_fetch",true),receipt("web_fetch",false),receipt("invoice_pdf",true)),"Your estimate is ready: $412.")
        assertEquals("completed",done.getString("outcome"))
        assertEquals("Your estimate is ready: $412.",done.getString("summary"))
        // A text that never went out is recorded as partial.
        val failedSend=AgentRules.autoFinish(listOf(receipt("web_fetch",true),receipt("sms_send",false)),"I couldn't send it.")
        assertEquals("partial",failedSend.getString("outcome"))
        // A send that failed and then worked is fine.
        assertEquals("completed",AgentRules.autoFinish(listOf(receipt("sms_send",false),receipt("sms_send",true)),"Sent.").getString("outcome"))
        // Everything failed: partial. No tools at all: completed.
        assertEquals("partial",AgentRules.autoFinish(listOf(receipt("web_fetch",false)),"Couldn't load it.").getString("outcome"))
        assertEquals("completed",AgentRules.autoFinish(emptyList(),"").getString("outcome"))
        assertEquals("Done.",AgentRules.autoFinish(emptyList(),"").getString("summary"))
    }

    @Test fun theRequestIsSavedAsThePlanItself() {
        val plan=AgentRules.implicitPlan("  Make me an estimate\nfor brakes  ")
        assertEquals(1,plan.length())
        assertEquals("Make me an estimate for brakes",plan.getJSONObject(0).getString("description"))
        assertEquals("live",plan.getJSONObject(0).getString("kind"))
        assertEquals("The owner's request",AgentRules.implicitPlan("   ").getJSONObject(0).getString("description"))
        assertEquals(280,AgentRules.implicitPlan("x".repeat(1000)).getJSONObject(0).getString("description").length)
    }
}

class SchedulerTest {
    private fun t(id:String,bot:String,status:String="Queued",from:String="",created:Long=0,team:Boolean=false,routine:String="")=
        TaskRecord(id,id,id,status,"",created,workspace=bot,from=from,team=team,routineId=routine)

    @Test fun everythingStartsAtTheSameTimeWithNoTurns() {
        // A bot busy in its private chat still takes a group message at once, and so does a second text to the same bot.
        val tasks=listOf(t("private1","Alpha","Running"),t("group1","Alpha",team=true),t("private2","Alpha"),t("personal","Personal"))
        val running=setOf("private1")
        tasks.filter {it.status=="Queued"}.forEach {assertTrue(it.id,AgentRules.canStart(it,tasks,running))}
    }

    @Test fun everyBotCalledInTheGroupWorksTogether() {
        // "Hi everyone": six bots, one running already, all the rest start in the same pass.
        val bots=listOf("Alpha","Personal","Customer","Estimate","Text","Advisor")
        val group=bots.mapIndexed {i,b->t("g"+i,b,team=true,from=if(i==0) "" else "g0",created=i.toLong())}
        var running=emptySet<String>();var left=group
        val started=mutableListOf<String>()
        while(true) {
            val next=AgentRules.nextToStart(left,running)?:break
            started+=next.id;running=running+next.id;left=left.filter {it.id!=next.id}
        }
        assertEquals(bots.size,started.size)
        assertTrue(left.isEmpty())
    }

    @Test fun thePhoneRunsAtMostEightJobsAtOnceAndTheOwnersTextsGetTheFirstFreeOne() {
        val running=(1..AgentRules.MAX_JOBS).map {"run"+it}.toSet()
        val tasks=running.map {t(it,"Bot"+it,"Running")}+t("late","Alpha",from="origin",created=1)+t("owner","Personal",from=OWNER_QUEUE,created=9)
        assertNull(AgentRules.nextToStart(tasks,running))
        // One finishes: the owner's text goes before the older hand-off.
        assertEquals("owner",AgentRules.nextToStart(tasks.filter {it.id!="run1"},running-"run1")?.id)
    }

    @Test fun theOwnersTextsGoFirstAndAJobThatSteppedAsideGoesLast() {
        val tasks=listOf(t("long","A",from=YIELDED,created=1),t("hand","B",from="origin",created=2),t("text","C",from=OWNER_QUEUE,created=5),t("text2","D",from=OWNER_QUEUE,created=6))
        val order=mutableListOf<String>();val running=emptySet<String>()
        var left=tasks
        repeat(4) {
            val next=AgentRules.nextToStart(left,running,max=1)!!
            order+=next.id;left=left.filter {it.id!=next.id}
        }
        assertEquals(listOf("text","text2","hand","long"),order)
        // The time limit leaves room for a real answer.
        assertTrue(WRAP_UP_MINUTES<STOP_MINUTES && STOP_MINUTES<=10)
    }

    @Test fun routinesRunThemselvesAndNeverStartFromTheQueue() {
        val tasks=listOf(t("routine1","A",routine="r1"))
        assertNull(AgentRules.nextToStart(tasks,emptySet()))
    }
}
