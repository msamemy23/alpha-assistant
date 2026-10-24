package com.alphainternational.assistant

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ResponsesStreamTest {
    private fun call()=obj("id" to "fc_test","type" to "function_call","call_id" to "call_test","name" to "file_write",
        "namespace" to "alpha","status" to "completed","arguments" to "{\"name\":\"check.txt\",\"text\":\"Hello\"}")
    private fun done(item:JSONObject,index:Int=0)=obj("type" to "response.output_item.done","output_index" to index,"item" to item)
    private fun completed(output:JSONArray=JSONArray())=obj("type" to "response.completed","response" to obj("id" to "resp_test","status" to "completed","output" to output))
    private fun message(text:String)=obj("id" to "msg_test","type" to "message","role" to "assistant","status" to "completed",
        "content" to JSONArray().put(obj("type" to "output_text","text" to text)))

    @Test fun toolOnlyStreamRetainsFinishedActionWhenTerminalOutputIsEmpty() {
        val stream=ResponsesStream()
        stream.accept(done(call()));stream.accept(completed())
        val history=stream.result().getJSONArray("output")
        assertEquals(1,AgentHistory.pending(history).size)
        assertEquals("Hello",JSONObject(history.getJSONObject(0).getString("arguments")).getString("text"))
        assertEquals("alpha",history.getJSONObject(0).getString("namespace"))
        AgentHistory.appendOutput(history,history.getJSONObject(0),"File saved")
        assertTrue(AgentHistory.pending(history).isEmpty())
    }
    @Test fun terminalAndStreamCopiesDoNotExecuteAnActionTwice() {
        val stream=ResponsesStream()
        stream.accept(done(call()));stream.accept(completed(JSONArray().put(call())))
        assertEquals(1,stream.result().getJSONArray("output").length())
    }
    @Test fun preservesReasoningAndOutputOrderForTheNextTurn() {
        val stream=ResponsesStream()
        stream.accept(done(call(),1))
        stream.accept(done(obj("id" to "rs_test","type" to "reasoning","summary" to JSONArray(),"encrypted_content" to "opaque-fixture"),0))
        stream.accept(completed())
        val output=stream.result().getJSONArray("output")
        assertEquals("opaque-fixture",output.getJSONObject(0).getString("encrypted_content"))
        assertEquals("call_test",output.getJSONObject(1).getString("call_id"))
    }
    @Test fun messagesAreRetainedEvenWithoutTextDeltaEvents() {
        val stream=ResponsesStream()
        stream.accept(done(message("Hello")));stream.accept(completed())
        assertEquals("Hello",stream.result().getJSONArray("output").getJSONObject(0).getJSONArray("content").getJSONObject(0).getString("text"))
    }
    @Test fun acceptsAFullTerminalResponseWithoutItemEvents() {
        val stream=ResponsesStream();stream.accept(completed(JSONArray().put(message("hi"))))
        assertEquals(1,stream.result().getJSONArray("output").length())
    }
    @Test fun disconnectBeforeCompletionCannotReturnAnAction() {
        val stream=ResponsesStream();stream.accept(done(call()))
        assertThrows(IllegalStateException::class.java) {stream.result()}
    }
    @Test fun incompleteResponseCannotReturnEvenAFinishedAction() {
        val stream=ResponsesStream();stream.accept(done(call()))
        assertThrows(IllegalStateException::class.java) {stream.accept(obj("type" to "response.incomplete","response" to obj("incomplete_details" to obj("reason" to "interrupted"))))}
        assertThrows(IllegalStateException::class.java) {stream.result()}
    }
    @Test fun unfinishedArgumentsCannotExecute() {
        val stream=ResponsesStream()
        stream.accept(obj("type" to "response.output_item.added","output_index" to 0,"item" to call().put("arguments","")))
        stream.accept(obj("type" to "response.function_call_arguments.delta","output_index" to 0,"delta" to "{\"name\":"))
        assertThrows(IllegalStateException::class.java) {stream.result()}
        stream.accept(completed(JSONArray().put(call().put("arguments",""))))
        assertThrows(IllegalStateException::class.java) {stream.result()}
    }
}
