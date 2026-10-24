package com.alphainternational.assistant

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class TextingTest {
    @Test fun repliesBecomeSeparateTextsLikeAPerson() {
        assertEquals(listOf("Done! The estimate is ready.","Want me to text it to Maria?"),TextThread.texts("Done! The estimate is ready.\n\nWant me to text it to Maria?"))
        // Lists and tables stay together in one bubble.
        assertEquals(1,TextThread.texts("Here you go:\n\n| Part | Price |\n|---|---|\n| Pads | 89 |").size)
        // A Sources block becomes link chips, not text.
        val (body,links)=TextThread.links("It's open till 6.\n\n**Sources**\n- [Yelp](https://www.yelp.com/biz/x)")
        assertEquals("It's open till 6.",body.trim())
        assertEquals(listOf("https://www.yelp.com/biz/x"),links)
        assertEquals(listOf("It's open till 6."),TextThread.texts("It's open till 6.\n\n**Sources**\n- [Yelp](https://www.yelp.com/biz/x)"))
    }
    @Test fun timesShowAfterABreakLikeMessages() {
        assertTrue(TextThread.showStamp(null,1_000))
        assertFalse(TextThread.showStamp(1_000,1_000+10*60_000))
        assertTrue(TextThread.showStamp(1_000,1_000+31*60_000))
        assertFalse(TextThread.showStamp(1_000,0))
        val today=LocalDate.of(2026,10,4)
        val noon=today.atTime(13,35).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals("Today 1:35 PM",TextThread.stamp(noon,today))
        assertEquals("Yesterday 1:35 PM",TextThread.stamp(noon,today.plusDays(1)))
    }
    @Test fun workIsDescribedInPlainWords() {
        assertEquals("Using the web browser",WorkWords.plain("browser click"))
        assertEquals("Sending a text",WorkWords.plain("sms send"))
        assertEquals("Working on it",WorkWords.plain("something new"))
        assertEquals("Gmail",WorkWords.connectionLabel("gmail"))
    }
    private fun call(id:String,name:String)=JSONObject().put("type","function_call").put("call_id",id).put("name","alpha.$name").put("arguments","{}")
    private fun output(id:String,result:JSONObject)=JSONObject().put("type","function_call_output").put("call_id",id).put("output",result.toString())
    @Test fun longRequestsShrinkOldResultsButKeepFilesAndReceipts() {
        val history=JSONArray()
        for(i in 0 until 12) {
            history.put(call("c$i","web_fetch"))
            history.put(output("c$i",JSONObject().put("text","x".repeat(20_000)).put("_receipt",JSONObject().put("id","r$i"))))
        }
        history.put(call("pdf","document_pdf")).put(output("pdf",JSONObject().put("files",JSONArray().put("flyer.pdf")).put("_receipt",JSONObject().put("id","rp"))))
        val small=TaskProtocol.compact(history,limit=50_000,keep=2)
        assertTrue(small.toString().length<history.toString().length/3)
        val first=JSONObject(small.getJSONObject(1).getString("output"))
        assertTrue(first.has("trimmed"));assertEquals("r0",first.getJSONObject("_receipt").getString("id"))
        assertTrue(small.toString().contains("flyer.pdf"))
        // Old finished work keeps only what made files.
        val slim=JSONArray(TaskProtocol.slim(history.toString()))
        assertEquals(2,slim.length());assertTrue(slim.toString().contains("flyer.pdf"))
    }
    @Test fun voiceTextIsCleanedForEachService() {
        assertEquals("Got it [laughs] see you at 5.",CloudVoice.prepare("Got it [laugh] see you at **5**.",provider="elevenlabs"))
        assertEquals("Got it see you at 5. Details here:",CloudVoice.prepare("Got it [chuckle] see you at 5. [Details](https://x.com) here: https://x.com/a","openai").replace(Regex("\\s+")," "))
        assertEquals(listOf(0.5f,-0.5f),CloudVoice.pcm16(byteArrayOf(0x00,0x40,0x00,0xC0.toByte())).toList())
        assertNull(CloudVoice.active(JSONObject().put("provider","elevenlabs")))
        assertNotNull(CloudVoice.active(JSONObject().put("provider","openai").put("key","sk-"+"a".repeat(30))))
        assertTrue(runCatching {CloudVoice.checkKey("openai","abc")}.isFailure)
        assertEquals("sk_"+"b".repeat(30),CloudVoice.checkKey("elevenlabs"," sk_"+"b".repeat(30)+" "))
    }
    @Test fun voiceKeysNeverShowInText() {
        assertFalse(Rules.redact("key sk_"+"c".repeat(40)).contains("ccccc"))
    }
    @Test fun approvalsDefaultToTheBuildsChoice() {
        assertEquals(AiSettings.DEFAULT_APPROVALS,AiSettings.parse(JSONObject()).approvals)
        assertEquals("all",AiSettings.parse(JSONObject(AiSettings(approvals="all").json().toString())).approvals)
        assertEquals(AiSettings.DEFAULT_APPROVALS,AiSettings.parse(JSONObject().put("approvals","whatever")).approvals)
    }
}

class SnagTest {
    @Test fun emptyActionDetailsAreNotAnError() {
        assertEquals("{}",ResponsesStream.arguments("null"))
        assertEquals("{}",ResponsesStream.arguments("  "))
        assertEquals("{\"id\":\"3\"}",ResponsesStream.arguments("{\"id\":\"3\"}"))
        assertTrue(runCatching {ResponsesStream.arguments("[1]")}.isFailure)
    }
    @Test fun stopsAreExplainedPlainly() {
        assertEquals("something went wrong on my end.",TaskProtocol.plainSnag("Value null of type org.json.JSONObject\$1 cannot be converted to JSONObject"))
        assertEquals("I couldn't reach ChatGPT just now (connection trouble).",TaskProtocol.plainSnag("ChatGPT returned HTTP 503: busy"))
        assertEquals("I got stuck: the site is down.",TaskProtocol.plainSnag("the site is down."))
    }
}
