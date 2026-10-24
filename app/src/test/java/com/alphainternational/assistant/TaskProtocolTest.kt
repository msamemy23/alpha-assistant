package com.alphainternational.assistant

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TaskProtocolTest {
    private val plan="""[{"id":"saved","description":"Saved database invoice","kind":"live"},{"id":"pdf","description":"PDF file","kind":"live"}]"""
    private fun task(history:JSONArray=JSONArray())=TaskRecord("t","Invoice","Invoice","Running","",0,history=history.toString(),plan=plan)
    private fun receipt(history:JSONArray,id:String,name:String,result:JSONObject,args:JSONObject=JSONObject()) {
        history.put(obj("type" to "function_call","call_id" to id,"name" to name,"arguments" to args.toString()))
        history.put(obj("type" to "function_call_output","call_id" to id,"output" to result.toString()))
    }
    private fun checks(id:String,status:String="passed")=arr(listOf("saved","pdf").map {obj("id" to it,"status" to status,"evidence" to arr(if(id.isBlank())emptyList()else listOf(id)))})
    @Test fun unfinishedRequirementCannotDisappearOrBecomeCompleted() {
        assertThrows(IllegalArgumentException::class.java){TaskProtocol.finish(task(),"completed","Done",checks("","unfinished").toString())}
        assertThrows(IllegalArgumentException::class.java){TaskProtocol.finish(task(),"completed","Done","""[{"id":"saved","status":"passed","evidence":[]}]""")}
        assertEquals("partial",TaskProtocol.finish(task(),"partial","Supabase connection required",checks("","blocked").toString()).getString("outcome"))
    }
    @Test fun inventedFailedAndTruncatedReceiptsCannotProveWork() {
        assertThrows(IllegalArgumentException::class.java){TaskProtocol.finish(task(),"completed","Done",checks("invented").toString())}
        for(result in listOf(obj("error" to "No access"),obj("isError" to true),obj("partial" to true),obj("success" to false))) {
            val h=JSONArray();receipt(h,"sql","supabase_sample_invoice",result)
            assertThrows(IllegalArgumentException::class.java){TaskProtocol.finish(task(h),"completed","Done",checks("sql").toString())}
        }
    }
    @Test fun actualTypedReceiptCanSupportEachRequirement() {
        val h=JSONArray();receipt(h,"invoice","supabase_sample_invoice",obj("verified" to true,"files" to arr(listOf("sample.pdf"))))
        assertEquals("completed",TaskProtocol.finish(task(h),"completed","Verified invoice and PDF",checks("invoice").toString()).getString("outcome"))
    }
    @Test fun hostReceiptIsVisibleInContentAndOldReceiptIdsCanBeRecoveredWithoutRepeatingActions() {
        val call=obj("type" to "function_call","call_id" to "host-id","name" to "browser_read","arguments" to "{}")
        val result=JSONObject(TaskProtocol.withReceipt(call,obj("title" to "Product","_receipt" to obj("id" to "page-invented")).toString()))
        assertEquals("host-id",result.getJSONObject("_receipt").getString("id"))
        assertTrue(result.getJSONObject("_receipt").getBoolean("success"))
        val failed=JSONObject(TaskProtocol.withReceipt(call,obj("partial" to true).toString()))
        assertFalse(failed.getJSONObject("_receipt").getBoolean("success"))
        val h=JSONArray();receipt(h,"old-id","browser_read",obj("title" to "Product"))
        receipt(h,"plan-id","task_plan",obj("requirements" to JSONArray()))
        val index=TaskProtocol.receiptIndex(h).getJSONArray("receipts")
        assertEquals(1,index.length());assertEquals("old-id",index.getJSONObject(0).getString("id"))
    }
    @Test fun savedGmailDraftNeedsExactReadBack() {
        val h=JSONArray();val args=obj("to" to "sample@example.com","subject" to "Test","body" to "Hello")
        receipt(h,"draft","gmail_draft",obj("id" to "draft1"),args)
        val draftTask=task(h).copy(plan="""[{"id":"draft","description":"Gmail draft","kind":"live"}]""")
        val check="""[{"id":"draft","status":"passed","evidence":["draft"]}]"""
        assertThrows(IllegalArgumentException::class.java){TaskProtocol.finish(draftTask,"completed","Saved",check)}
        receipt(h,"read","gmail_read_draft",obj("id" to "draft1","to" to "wrong@example.com","subject" to "Test","body" to "Hello","draft" to true))
        assertThrows(IllegalArgumentException::class.java){TaskProtocol.finish(draftTask.copy(history=h.toString()),"completed","Saved",check)}
        receipt(h,"verified","gmail_read_draft",obj("id" to "draft1","to" to "sample@example.com","subject" to "Test","body" to "Hello\r\n","draft" to true))
        assertEquals("completed",TaskProtocol.finish(draftTask.copy(history=h.toString()),"completed","Verified",check).getString("outcome"))
    }
    @Test fun longToolOutputRemainsValidAndExplicitlyPartial() {
        val raw=obj("text" to "x".repeat(60000)).toString();val result=JSONObject(TaskProtocol.boundedOutput(raw))
        assertTrue(result.getBoolean("partial"));assertEquals(raw.length,result.getInt("total_characters"));assertTrue(result.toString().length<40000)
    }
    @Test fun taskCheckpointSurvivesSerialization() {
        val original=task().copy(completion="{\"outcome\":\"partial\"}",waitingConnection="supabase")
        assertEquals(original,TaskRecord.parse(original.json()))
        assertThrows(IllegalArgumentException::class.java){TaskProtocol.plan("""[{"id":"x","description":"One","kind":"live"},{"id":"x","description":"Two","kind":"live"}]""")}
    }
    @Test fun onlySafeTransientReadsAreRetriedAndRetryAfterIsHonored() {
        assertEquals(2000L,ReadRetry.delayMs("GET",0,ApiFailure(429,"busy",2000)))
        assertNull(ReadRetry.delayMs("POST",0,ApiFailure(503,"busy")))
        assertNull(ReadRetry.delayMs("PATCH",0,java.io.IOException("lost")))
        assertNull(ReadRetry.delayMs("GET",2,java.io.IOException("lost")))
        assertNull(ReadRetry.delayMs("GET",0,ApiFailure(403,"denied")))
        assertNull(ReadRetry.delayMs("GET",0,ApiFailure(429,"busy",60000)))
    }
}
