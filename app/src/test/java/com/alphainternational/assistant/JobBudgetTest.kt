package com.alphainternational.assistant

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class JobBudgetTest {
    private val base=AgentRules.budget(false)
    private fun minutes(m:Double)=(m*60_000).toLong()

    @Test fun aJobThatBringsNothingNewKeepsTheUsualTime() {
        assertEquals(AgentRules.Budget(6,9),JobBudget.limits(base,false,0,0))
        // Its last new result came early: the usual time still applies.
        assertEquals(AgentRules.Budget(6,9),JobBudget.limits(base,false,0,minutes(2.0)))
    }

    @Test fun theDeadlineMovesWhileNewResultsKeepArriving() {
        // A result at minute 8 gives it until about minute 13; at minute 14, until about 19.
        assertEquals(AgentRules.Budget(10,13),JobBudget.limits(base,false,0,minutes(8.0)))
        assertEquals(AgentRules.Budget(16,19),JobBudget.limits(base,false,0,minutes(14.0)))
        assertEquals(AgentRules.Budget(11,14),JobBudget.limits(base,false,0,minutes(8.2)))
        // It never moves backwards.
        val a=JobBudget.limits(base,false,0,minutes(5.0));val b=JobBudget.limits(base,false,0,minutes(9.0))
        assertTrue(b.wrapUpMinutes>=a.wrapUpMinutes && b.stopMinutes>=a.stopMinutes)
    }

    @Test fun thereIsAlwaysARoomyButFiniteCeiling() {
        assertEquals(AgentRules.Budget(JobBudget.CAP_WRAP_UP,JobBudget.CAP_STOP),JobBudget.limits(base,false,0,minutes(20.0)))
        assertEquals(AgentRules.Budget(JobBudget.CAP_WRAP_UP,JobBudget.CAP_STOP),JobBudget.limits(base,false,0,minutes(60.0)))
        assertTrue(JobBudget.CAP_STOP in 20..30 && JobBudget.CAP_WRAP_UP<JobBudget.CAP_STOP)
    }

    @Test fun aBuildKeepsItsLongFixedBudget() {
        assertEquals(AgentRules.budget(true),JobBudget.limits(AgentRules.budget(true),true,0,minutes(10.0)))
        assertEquals(AgentRules.budget(true),JobBudget.limits(AgentRules.budget(true),true,0,0))
    }

    private fun call(i:Int,name:String)=obj("type" to "function_call","call_id" to "c$i","name" to name,"arguments" to "{}")
    private fun out(i:Int,json:String)=obj("type" to "function_call_output","call_id" to "c$i","output" to json)

    @Test fun onlyWorkingResultsCountAsProgress() {
        val h=JSONArray()
        h.put(call(1,"web_fetch"));h.put(out(1,"{\"text\":\"page\"}"))
        h.put(call(2,"web_fetch"));h.put(out(2,"{\"error\":\"won't load\"}"))
        h.put(call(3,"file_write"));h.put(out(3,"{\"verified\":true}"))
        h.put(call(4,"plan_update"));h.put(out(4,"{\"result\":\"Progress: 1 of 2 done\"}"))
        h.put(call(5,"tools_enable"));h.put(out(5,"{\"result\":\"on\"}"))
        h.put(call(6,"files_list"));h.put(out(6,"{\"files\":[]}"))
        h.put(call(7,"sms_send"));h.put(out(7,"{\"success\":false}"))
        h.put(obj("type" to "web_search_call","id" to "ws1","status" to "completed"))
        h.put(obj("type" to "web_search_call","id" to "ws2","status" to "in_progress"))
        // Counted: the page read, the file saved, one finished web search.
        assertEquals(3,JobBudget.results(h))
        assertEquals(0,JobBudget.results(JSONArray()))
    }

    @Test fun failuresAreRecognised() {
        listOf("{\"error\":\"x\"}","{\"isError\":true}","{\"success\":false}").forEach {assertTrue(it,JobBudget.failed(it))}
        listOf("{\"ok\":1}","{\"success\":true}","not json","").forEach {assertFalse(it,JobBudget.failed(it))}
    }

    @Test fun theJobStateCountsOnlyRealProgressAndKeepsASmallWindow() {
        val s=JobState(1000)
        assertFalse(s.noteResults(0,2000))
        assertEquals(1000,s.lastResultAt)
        assertTrue(s.noteResults(3,5000))
        assertEquals(5000,s.lastResultAt)
        assertFalse(s.noteResults(3,9000))
        assertEquals(5000,s.lastResultAt)
        repeat(20) {s.noteOutcome(it%2==0)}
        assertEquals(8,s.outcomes.size)
    }

    @Test fun aJobIsCutShortOnlyWhenTheLimitHitWithWorkLeft() {
        val s=JobState(0)
        assertFalse(s.cutShort(false));assertFalse(s.cutShort(true))
        s.wrapUpSent=true
        assertFalse("told to wrap up, but nothing was left to do",s.cutShort(false))
        assertTrue("told to wrap up with plan steps open",s.cutShort(true))
        val t=JobState(0);t.toolsTaken=true
        assertTrue("the tools were taken away at the stop time",t.cutShort(false))
    }
}
