package com.alphainternational.assistant

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** The list of what the bots did: what is kept, what can be put back, and the plain line shown for each. */
class ActionLogTest {
    private fun entry(id:String,tool:String="sms_send",undo:String="")=ActionLog.Entry(id,1_000L,"Alpha",tool,"Sent a text","Camry ready",true,undo)

    @Test fun sendsWritesAndChangesAreLoggedButLooksAreNot() {
        for(t in listOf("sms_send","gmail_send","site_publish","routine_create","browser_click","mcp_call","file_write","screen_do","skill_save"))
            assertTrue(t,ActionLog.shouldLog(t,false))
        for(t in listOf("web_fetch","memory_search","files_list","browser_read","screen_read","task_plan"))
            assertFalse(t,ActionLog.shouldLog(t,false))
        assertTrue("anything that asks for an OK is logged",ActionLog.shouldLog("some_new_tool",true))
    }

    @Test fun onlyWhatABotMadeThatNamesItsIdCanBePutBack() {
        assertEquals("routine:r1",ActionLog.undoFor("routine_create",JSONObject().put("id","r1")))
        assertEquals("skill:s1",ActionLog.undoFor("skill_save",JSONObject().put("id","s1")))
        assertEquals("trigger:t1",ActionLog.undoFor("trigger_create",JSONObject().put("id","t1")))
        assertEquals("a text cannot be taken back","",ActionLog.undoFor("sms_send",JSONObject().put("id","x")))
        assertEquals("no id, nothing to undo","",ActionLog.undoFor("routine_create",JSONObject()))
    }

    @Test fun theLogKeepsTheNewestFourHundredAndSurvivesSaving() {
        var log=JSONArray()
        for(i in 1..450) log=ActionLog.add(log,entry("e$i"))
        val all=ActionLog.parse(log)
        assertEquals(ActionLog.MAX,all.size);assertEquals("e51",all.first().id);assertEquals("e450",all.last().id)
        val back=ActionLog.parse(JSONArray(log.toString()))
        assertEquals(all,back)
    }

    @Test fun puttingSomethingBackMarksItAndItCannotBeUndoneTwice() {
        var log=ActionLog.add(JSONArray(),entry("a","routine_create","routine:r1"))
        log=ActionLog.add(log,entry("b"))
        assertTrue(ActionLog.parse(log).first {it.id=="a"}.canUndo)
        log=ActionLog.markUndone(log,"a")
        val a=ActionLog.parse(log).first {it.id=="a"}
        assertTrue(a.undone);assertFalse(a.canUndo)
        assertFalse("the other entry is untouched",ActionLog.parse(log).first {it.id=="b"}.undone)
        assertFalse("a send has nothing to undo",ActionLog.parse(log).first {it.id=="b"}.canUndo)
    }

    @Test fun theLineIsPlain() {
        assertEquals("Sent a text · Camry ready · Alpha · 3:15 PM",ActionLog.line(entry("a"),"3:15 PM"))
        assertEquals("Sent a text · Alpha · 3:15 PM",ActionLog.line(entry("a").copy(detail=""),"3:15 PM"))
    }
}
