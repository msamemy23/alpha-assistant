package com.alphainternational.assistant

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class RecoveryTest {
    private fun call(args:String="{\"name\":\"a.txt\",\"text\":\"Hello\"}",status:String="completed")=obj("id" to "fc","type" to "function_call","call_id" to "call1","name" to "file_write",
        "status" to status,"arguments" to args)
    private fun done(item:JSONObject,index:Int=0)=obj("type" to "response.output_item.done","output_index" to index,"item" to item)
    private fun completed(status:String="completed",reason:String?=null)=obj("type" to "response.completed","response" to
        obj("id" to "r","status" to status,"output" to JSONArray()).also {r->if(reason!=null) r.put("incomplete_details",obj("reason" to reason))})

    // ---- a reply cut off by the length limit ----
    @Test fun aReplyCutOffByTheLengthLimitIsRecognisedSoTheJobCanAskAgainInSmallerPieces() {
        val stream=ResponsesStream()
        val e=assertThrows(ModelCutOff::class.java) {stream.accept(obj("type" to "response.incomplete","response" to obj("incomplete_details" to obj("reason" to "max_output_tokens"))))}
        assertTrue(e is IllegalStateException)
        assertThrows(IllegalStateException::class.java) {stream.result()}
        // Other reasons stay ordinary failures (not cut-offs).
        val other=ResponsesStream()
        val oe=assertThrows(IllegalStateException::class.java) {other.accept(obj("type" to "response.incomplete","response" to obj("incomplete_details" to obj("reason" to "content_filter"))))}
        assertFalse(oe is ModelCutOff)
    }

    @Test fun aCompletedEventThatSaysIncompleteForLengthIsACutOffToo() {
        val stream=ResponsesStream()
        stream.accept(done(call()))
        stream.accept(completed("incomplete","max_output_tokens"))
        assertThrows(ModelCutOff::class.java) {stream.result()}
        val plain=ResponsesStream()
        plain.accept(completed("incomplete","interrupted"))
        assertFalse(assertThrows(IllegalStateException::class.java) {plain.result()} is ModelCutOff)
    }

    @Test fun aToolCallCutOffHalfwayNeverRunsAndIsACutOff() {
        listOf(call("","in_progress"),call("{\"name\":","incomplete"),call("")).forEach {item->
            val stream=ResponsesStream()
            stream.accept(done(item));stream.accept(completed())
            assertThrows(ModelCutOff::class.java) {stream.result()}
        }
    }

    @Test fun aCutOffIsNotMistakenForABadConnectionOrAServiceGlitch() {
        val message="ChatGPT's reply was cut off by its length limit."
        assertFalse(TaskProtocol.transient(message))
        assertFalse(TaskProtocol.serviceGlitch(message))
        assertEquals("my replies kept getting cut off for being too long.",TaskProtocol.plainSnag(message))
        assertEquals("my replies kept getting cut off for being too long.",TaskProtocol.plainSnag("ChatGPT returned an unfinished action. No action was run; you can resume this task."))
        assertTrue(Recovery.isCutOff(ModelCutOff("x")) && !Recovery.isCutOff(IllegalStateException("x")))
        assertTrue(Recovery.MAX_CUT_OFFS in 1..3)
        assertTrue(Recovery.cutOffNote(1).contains("smaller pieces") && Recovery.cutOffNote(1).contains("file_append"))
        assertTrue(Recovery.cutOffNote(2).contains("much smaller"))
    }

    // ---- details that are not valid ----
    @Test fun detailsThatAreNotValidJsonStillComeBackAsAnErrorForTheModelNotAnException() {
        listOf("{\"name\":\"a.txt\",\"text\":\"unterminated","{broken","[1,2]","\"just text\"","{\"a\":").forEach {raw->
            val stream=ResponsesStream()
            stream.accept(done(call(raw)));stream.accept(completed())
            val item=stream.result().getJSONArray("output").getJSONObject(0)
            val args=JSONObject(item.getString("arguments"))
            assertTrue(raw,args.has(ResponsesStream.INVALID))
            assertTrue(args.getString(ResponsesStream.INVALID).length<=300)
            // The call is still pending, so the loop answers it with an error result.
            assertEquals(1,AgentHistory.pending(stream.result().getJSONArray("output")).size)
        }
        assertEquals("{}",ResponsesStream.safeArguments("null"))
        assertEquals("{}",ResponsesStream.safeArguments("  "))
        assertEquals("{\"a\":1}",ResponsesStream.safeArguments(" {\"a\":1} "))
    }

    @Test fun anUnknownToolGetsAnErrorWithTheClosestNamesAndTheTryAnotherWayInstruction() {
        val known=listOf("file_write","file_edit","web_fetch","sms_send","gmail_send","gmail_draft")
        val r=Recovery.unknownTool("file_writes",known)
        assertTrue(r.getString("error").contains("file_writes"))
        assertTrue(r.getString("instruction").contains("file_write"))
        assertTrue(r.getString("instruction").contains(Recovery.TRY_ANOTHER_WAY))
        assertTrue(Recovery.similar("send_email",known).containsAll(listOf("sms_send","gmail_send")))
        assertTrue(Recovery.similar("zzz_qqq",known).isEmpty())
        assertTrue(Recovery.similar("",known).isEmpty())
        assertTrue(Recovery.unknownTool("x".repeat(500),known).getString("error").length<120)
    }

    @Test fun badDetailsTellTheModelNothingWasDoneAndHowToSendThemAgain() {
        val r=Recovery.badArguments("file_write","not a complete JSON object")
        assertTrue(r.getString("error").contains("Nothing was done"))
        assertTrue(r.getString("instruction").contains("file_append"))
    }

    // ---- the words a failed tool result uses ----
    @Test fun aFailedToolSaysTryAnotherWayAndNeverJustExplainTheLimitation() {
        val failed=Recovery.failed("The page didn't load.")
        assertEquals("The page didn't load.",failed.getString("error"))
        assertTrue(failed.getString("instruction").startsWith("Try another way"))
        assertTrue(failed.getString("instruction").contains("Only if there is truly no other way"))
        assertFalse(failed.getString("instruction").contains("Explain the limitation",true))
        assertTrue(failed.getString("instruction").contains("Never invent a result"))
    }

    @Test fun noToolResultInTheAppStillTellsTheModelToExplainTheLimitation() {
        val dir=File("src/main/java/com/alphainternational/assistant")
        assumeTrue("source not found from "+File(".").absolutePath,dir.isDirectory)
        dir.listFiles()!!.filter {it.name.endsWith(".kt")}.forEach {f->
            assertFalse(f.name+" still says to explain the limitation",f.readText().contains("Explain the limitation",true) && !f.name.startsWith("Recovery"))
            assertFalse(f.name,f.readText().contains(", or explain the limitation"))
        }
    }

    // ---- unlisted tools are repaired, not fatal: the fix lives in the loop, the parts are tested here ----
    @Test fun aSavedHistoryWithAnInvalidCallStillRoundTripsAsJson() {
        val stream=ResponsesStream()
        stream.accept(done(call("{broken")));stream.accept(completed())
        val history=JSONArray(stream.result().getJSONArray("output").toString())
        AgentHistory.appendOutput(history,history.getJSONObject(0),Recovery.badArguments("file_write","x").toString())
        assertTrue(AgentHistory.pending(history).isEmpty())
        assertEquals(2,JSONArray(history.toString()).length())
    }
}

class ThinkingLineTest {
    @Test fun theLatestBoldHeadingIsTheLiveLine() {
        assertEquals("Checking prices",Thinking.line("**Planning the search**\n\nI need to look up prices.\n\n**Checking prices**\n\nNow comparing."))
        assertEquals("Planning the search",Thinking.line("**Planning the search**\n\nI need to look up prices."))
    }
    @Test fun withoutHeadingsItIsTheLastShortSentence() {
        assertEquals("Now I will compare the two parts shops.",Thinking.line("Looking at the order first. Now I will compare the two parts shops."))
        assertTrue(Thinking.line("x ".repeat(200)).length<=90)
        assertEquals("",Thinking.line("   "))
    }
    @Test fun theStreamPassesTheSummaryAlongAsItArrives() {
        val stream=ResponsesStream()
        assertNull(stream.thinking(obj("type" to "response.output_text.delta","delta" to "hello")))
        assertNull(stream.thinking(obj("type" to "response.reasoning_summary_text.delta","delta" to "")))
        assertEquals("Planning",stream.thinking(obj("type" to "response.reasoning_summary_text.delta","delta" to "**Planning**\n\nFirst")))
        stream.thinking(obj("type" to "response.reasoning_summary_part.added"))
        assertEquals("Reading the page",stream.thinking(obj("type" to "response.reasoning_summary_text.delta","delta" to "**Reading the page**")))
        // Ordinary text events still come out of accept, and the summary events don't.
        assertEquals("hi",stream.accept(obj("type" to "response.output_text.delta","delta" to "hi")))
        assertNull(stream.accept(obj("type" to "response.reasoning_summary_text.delta","delta" to "x")))
    }
}
