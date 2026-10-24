package com.alphainternational.assistant

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId

/**
 * Calls on a number that sits on a Telnyx AI assistant. Telnyx's detail-records filter lists "ai-voice-assistant" as its own record type, and the
 * columns of the owner's call report (Originating Number, Terminating number, Start Timestamp, Call duration, Direction, Hangup cause, Connection name)
 * are not named like the API's fields. The connector must read all of these shapes, list each call once, and still never send or change anything.
 */
class TelnyxAiTest {
    private val zone=ZoneId.of("UTC")

    /** A row of the owner's call report, as the report names its columns. */
    private fun reportRow(origin:String="+17135550142",dest:String="+18325550100",start:String="2026-10-10 18:02:11",length:String="00:01:23",direction:String="Incoming",hangup:String="normal_clearing",connection:String="ai-assistant-3f9a") =
        JSONObject().put("Originating Number",origin).put("Terminating number",dest).put("Start Timestamp",start).put("Call duration",length)
            .put("Direction",direction).put("Hangup cause",hangup).put("Connection name",connection)

    @Test fun theAiAssistantRecordTypeIsAskedFor() {
        assertEquals(listOf("call-control","sip-trunking","ai-voice-assistant"),Telnyx.callRecordTypes)
    }

    @Test fun aRowOfTheCallReportIsUnderstood() {
        val c=Telnyx.normalizeCall("ai-voice-assistant",reportRow(),zone)
        assertEquals("inbound",c.getString("direction"))
        assertEquals("+17135550142",c.getString("from"));assertEquals("(713) 555-0142",c.getString("from_pretty"))
        assertEquals("+18325550100",c.getString("to"));assertEquals("(832) 555-0100",c.getString("to_pretty"))
        assertEquals(83,c.getInt("seconds"));assertTrue(c.getBoolean("answered"))
        assertEquals(Telnyx.parseTime("2026-10-10T18:02:11Z"),Telnyx.parseTime(c.getString("started_at")))
        assertEquals("Sat Oct 10, 6:02 PM",c.getString("when"))
        assertEquals("ai-assistant-3f9a",c.getString("connection"));assertEquals("normal_clearing",c.getString("hangup_cause"))
        assertTrue(c.getBoolean("ai_assistant"));assertEquals("ai-voice-assistant",c.getString("source"))
    }

    @Test fun theConnectionNameAloneMarksAnAiCall() {
        // Even listed under the ordinary record type, a connection named ai-assistant-… is the AI assistant's.
        val c=Telnyx.normalizeCall("call-control",reportRow(),zone)
        assertTrue(c.getBoolean("ai_assistant"))
        val plain=Telnyx.normalizeCall("call-control",reportRow(connection="Shop line"),zone)
        assertFalse(plain.getBoolean("ai_assistant"))
    }

    @Test fun keysAreReadWhateverTheirSpelling() {
        val camel=JSONObject("""{"originatingNumber":"7135550142","terminatingNumber":"8325550100","startTimestamp":"2026-10-10T18:02:11Z","callDuration":45,"direction":"inbound","hangupCause":"originator_cancel","connectionName":"Shop"}""")
        val a=Telnyx.normalizeCall("call-control",camel,zone)
        assertEquals("7135550142",a.getString("from"));assertEquals("8325550100",a.getString("to"));assertEquals(45,a.getInt("seconds"));assertEquals("originator_cancel",a.getString("hangup_cause"))
        val nested=JSONObject("""{"id":"r1","record_type":"ai-voice-assistant","attributes":{"cli":"+17135550142","cld":"+18325550100","started_at":"2026-10-10T18:02:11Z","call_sec":12}}""")
        val b=Telnyx.normalizeCall("ai-voice-assistant",nested,zone)
        assertEquals("+17135550142",b.getString("from"));assertEquals(12,b.getInt("seconds"));assertEquals("r1",b.getString("id"))
        assertEquals("originating_number",Telnyx.snake("Originating Number"));assertEquals("call_duration",Telnyx.snake("callDuration"));assertEquals("hangup_cause",Telnyx.snake(" Hangup cause "))
    }

    @Test fun directionWordsAreMappedAndAnAiRecordWithoutOneIsInbound() {
        fun dir(word:String,type:String="call-control")=Telnyx.normalizeCall(type,JSONObject().put("cli","1").put("Direction",word),zone).getString("direction")
        assertEquals("inbound",dir("Incoming"));assertEquals("inbound",dir("inbound"));assertEquals("outbound",dir("Outgoing"));assertEquals("outbound",dir("OUTBOUND"));assertEquals("inbound",dir("1"));assertEquals("outbound",dir("2"))
        assertEquals("",dir(""))
        assertEquals("inbound",dir("","ai-voice-assistant"))
    }

    @Test fun callLengthsComeInManyForms() {
        assertEquals(45,Telnyx.seconds("45"));assertEquals(45,Telnyx.seconds("45.7"));assertEquals(83,Telnyx.seconds("00:01:23"));assertEquals(83,Telnyx.seconds("1:23"))
        assertEquals(3723,Telnyx.seconds("1h 2m 3s"));assertEquals(180,Telnyx.seconds("3m"));assertEquals(0,Telnyx.seconds(""));assertEquals(0,Telnyx.seconds("n/a"));assertEquals(0,Telnyx.seconds("-5"))
    }

    @Test fun timesComeInManyForms() {
        val expected=Telnyx.parseTime("2026-10-10T18:02:11Z")
        assertEquals(expected,Telnyx.parseTime("2026-10-10 18:02:11"))
        assertEquals(expected,Telnyx.parseTime("2026-10-10 18:02:11 UTC"))
        assertEquals(expected,Telnyx.parseTime("2026-10-10 13:02:11-05"))
        assertEquals(expected,Telnyx.parseTime("2026-10-10 13:02:11-05:00"))
        assertEquals(expected,Telnyx.parseTime("2026-10-10 13:02:11 -0500"))
        assertEquals(expected,Telnyx.parseTime((expected/1000).toString()))
        assertEquals(expected,Telnyx.parseTime(expected.toString()))
        assertEquals(expected+120,Telnyx.parseTime("2026-10-10 18:02:11.120"))
        assertEquals(expected-11_000,Telnyx.parseTime("2026-10-10 18:02"))
        assertEquals(0L,Telnyx.parseTime(""));assertEquals(0L,Telnyx.parseTime("yesterday-ish"))
    }

    @Test fun theSameCallFromTwoRecordTypesIsListedOnce() {
        val callControl=Telnyx.normalizeCall("call-control",JSONObject("""{"id":"cc1","telnyx_session_id":"s-1","direction":"inbound","cli":"+17135550142","cld":"+18325550100","started_at":"2026-10-10T18:02:11Z","call_sec":80,"hangup_cause":"normal_clearing"}"""),zone)
        val ai=Telnyx.normalizeCall("ai-voice-assistant",JSONObject("""{"id":"ai1","call_session_id":"s-1","assistant_id":"assistant-9","created_at":"2026-10-10T18:02:09Z","duration":83}"""),zone)
        val out=Telnyx.answer(listOf(callControl,ai),"today","all")
        assertEquals(1,out.getInt("count"))
        val call=out.getJSONArray("calls").getJSONObject(0)
        assertEquals("+17135550142",call.getString("from"));assertEquals("inbound",call.getString("direction"));assertEquals(83,call.getInt("seconds"))
        assertTrue(call.getBoolean("ai_assistant"));assertEquals("normal_clearing",call.getString("hangup_cause"))
        assertEquals("call-control+ai-voice-assistant",call.getString("source"))
        assertEquals(Telnyx.parseTime("2026-10-10T18:02:09Z"),Telnyx.parseTime(call.getString("started_at")))
        assertEquals(1,out.getInt("ai_assistant_calls"));assertTrue(out.getString("note").contains("AI assistant took the call"))
        // Without a session id, the same two numbers a few minutes apart are the same call; a different caller is another call.
        val a=Telnyx.normalizeCall("call-control",JSONObject("""{"id":"x","cli":"+17135550142","cld":"+18325550100","started_at":"2026-10-10T18:02:11Z","direction":"inbound"}"""),zone)
        val b=Telnyx.normalizeCall("ai-voice-assistant",JSONObject("""{"id":"y","cli":"(713) 555-0142","cld":"+18325550100","started_at":"2026-10-10T18:02:30Z"}"""),zone)
        val c=Telnyx.normalizeCall("call-control",JSONObject("""{"id":"z","cli":"+19995550000","cld":"+18325550100","started_at":"2026-10-10T18:03:00Z","direction":"inbound"}"""),zone)
        assertEquals(2,Telnyx.merge(listOf(a,b,c)).size)
    }

    @Test fun recordsThatAreNotCallsAreLeftOutAndTheFilterWorks() {
        val usage=Telnyx.normalizeCall("ai-voice-assistant",JSONObject("""{"id":"u1","assistant_id":"a1","cost":"0.02"}"""),zone)
        val inbound=Telnyx.normalizeCall("call-control",JSONObject("""{"id":"i1","direction":"inbound","cli":"+17135550142","cld":"+18325550100","started_at":"2026-10-10T18:02:11Z"}"""),zone)
        val outbound=Telnyx.normalizeCall("call-control",JSONObject("""{"id":"o1","direction":"outbound","cli":"+18325550100","cld":"+17135550142","started_at":"2026-10-10T19:00:00Z"}"""),zone)
        val all=Telnyx.answer(listOf(usage,inbound,outbound),"today","all")
        assertEquals(2,all.getInt("count"));assertEquals("o1",all.getJSONArray("calls").getJSONObject(0).getString("id"))
        assertEquals(listOf("i1"),Telnyx.answer(listOf(usage,inbound,outbound),"today","inbound").getJSONArray("calls").let {a->(0 until a.length()).map {a.getJSONObject(it).getString("id")}})
        assertTrue(Telnyx.answer(emptyList(),"today","all").getString("note").contains("no calls"))
        assertTrue(Telnyx.answer(listOf(inbound),"today","all",listOf("ai-voice-assistant: HTTP 400")).getString("note").contains("could not be read"))
    }

    @Test fun anAiCallIsToldToTheOwnerOnlyOnce() {
        val first=Telnyx.normalizeCall("ai-voice-assistant",JSONObject("""{"id":"ai1","call_session_id":"s-1","created_at":"2026-10-10T18:02:09Z","cli":"+17135550142","cld":"+18325550100"}"""),zone)
        val answer=Telnyx.answer(listOf(first),"today","inbound")
        val since=Telnyx.parseTime("2026-10-10T17:00:00Z")
        val fresh=Telnyx.freshCalls(answer,since,emptySet())
        assertEquals(1,fresh.size)
        val seen=fresh.map {Telnyx.seenKey(it)}.toSet()
        // A later poll finds the phone-call record of the same call too: still the same call, not announced again.
        val callControl=Telnyx.normalizeCall("call-control",JSONObject("""{"id":"cc1","telnyx_session_id":"s-1","direction":"inbound","cli":"+17135550142","cld":"+18325550100","started_at":"2026-10-10T18:02:11Z","call_sec":80}"""),zone)
        assertTrue(Telnyx.freshCalls(Telnyx.answer(listOf(callControl,first),"today","inbound"),since,seen).isEmpty())
        // Calls from before the cut-off, and ids saved by the older version of the check, are not announced.
        assertTrue(Telnyx.freshCalls(answer,Telnyx.parseTime("2026-10-10T19:00:00Z"),emptySet()).isEmpty())
        assertTrue(Telnyx.freshCalls(answer,since,setOf(first.optString("id")+first.optString("started_at"))).isEmpty())
    }

    @Test fun theConnectorStaysReadOnly() {
        telnyxTools.forEach {assertFalse(it.name,it.review)}
        assertTrue(telnyxTools.first {it.name=="telnyx_calls"}.description.contains("AI assistant"))
        // Only listing and looking up: no tool name suggests sending, buying or changing anything.
        telnyxTools.forEach {assertFalse(it.name,Regex("send|buy|order|create|update|delete|call_make|dial").containsMatchIn(it.name))}
    }

    // ------------------------------------------------------------ the words of the AI assistant's calls (Telnyx's conversations, as its docs describe them)

    private val conversation=JSONObject("""{"id":"c-1","name":"Voice call","created_at":"2026-10-10T18:02:09.123Z","last_message_at":"2026-10-10T18:03:41.000Z",
        "metadata":{"telnyx_conversation_channel":"phone_call","telnyx_agent_target":"+18325550100","telnyx_end_user_target":"+17135550142","assistant_id":"assistant-9"}}""")

    @Test fun aConversationIsDescribedForTheBot() {
        val n=Telnyx.normalizeConversation(conversation,zone)
        assertEquals("c-1",n.getString("id"));assertEquals("(713) 555-0142",n.getString("caller_pretty"));assertEquals("+17135550142",n.getString("caller"))
        assertEquals("(832) 555-0100",n.getString("number_called"));assertEquals("assistant-9",n.getString("assistant_id"));assertEquals("phone_call",n.getString("channel"))
        assertEquals(91L,n.getLong("about_seconds"));assertEquals("Sat Oct 10, 6:02 PM",n.getString("when"))
        // Nothing in it is a problem when Telnyx leaves out most of the fields.
        assertEquals("",Telnyx.normalizeConversation(JSONObject(),zone).getString("caller"))
    }

    @Test fun onlyTheWordsOfThePeopleAreKept() {
        val messages=JSONArray("""[{"role":"user","text":"Hi, is my Camry ready?"},{"role":"assistant","text":"Let me check."},
            {"role":"tool","text":null,"tool_calls":[{"id":"t1","type":"function","function":{"name":"lookup","arguments":"{}"}}]},
            {"role":"assistant","text":"  It's ready.  \n Come by anytime. "},{"role":"user","text":""},{"role":"assistant","text":"null"}]""")
        val rows=(0 until messages.length()).map {messages.getJSONObject(it)}
        assertEquals("Caller: Hi, is my Camry ready?\nAssistant: Let me check.\nAssistant: It's ready. Come by anytime.",Telnyx.transcript(rows))
        // A very long call is cut, turn by turn and as a whole.
        val long=(1..200).map {JSONObject().put("role","user").put("text","word ".repeat(300))}
        assertTrue(Telnyx.transcript(long).length<=2_000);assertTrue(Telnyx.transcript(long).lines().first().length<=420)
        assertEquals("",Telnyx.transcript(emptyList()))
    }

    @Test fun onlyPhoneConversationsInThePeriodAreListedNewestFirst() {
        fun conv(id:String,at:String,channel:String?)=JSONObject().put("id",id).put("created_at",at).put("last_message_at",at).put("metadata",JSONObject().apply {if(channel!=null) put("telnyx_conversation_channel",channel)})
        val list=listOf(conv("old","2026-10-09T10:00:00Z","phone_call"),conv("text","2026-10-10T15:00:00Z","sms"),conv("call-a","2026-10-10T16:00:00Z","phone_call"),
            conv("call-b","2026-10-10T19:00:00Z",null),conv("web","2026-10-10T17:00:00Z","web_chat"),conv("future","2026-10-11T01:00:00Z","phone_call"))
        val (from,to)=Telnyx.window("today",Telnyx.parseTime("2026-10-10T20:00:00Z"),zone)
        assertEquals(listOf("call-b","call-a"),Telnyx.conversationsIn(list,from,to).map {it.getString("id")})
        val (yFrom,yTo)=Telnyx.window("yesterday",Telnyx.parseTime("2026-10-10T20:00:00Z"),zone)
        assertEquals(listOf("old"),Telnyx.conversationsIn(list,yFrom,yTo).map {it.getString("id")})
    }

    @Test fun periodsBecomeWindowsInTheOwnersTimeZone() {
        val now=Telnyx.parseTime("2026-10-10T20:00:00Z")      // a Saturday
        val day=86_400_000L
        val today=Telnyx.parseTime("2026-10-10T00:00:00Z")
        assertEquals(today to today+day,Telnyx.window("today",now,zone))
        assertEquals(today-day to today,Telnyx.window("Yesterday",now,zone))
        assertEquals(Telnyx.parseTime("2026-10-05T00:00:00Z") to today+day,Telnyx.window("this_week",now,zone))      // weeks start on Monday
        assertEquals(today-2*day to today+day,Telnyx.window("last_3_days",now,zone))
        assertEquals(today-6*day to today+day,Telnyx.window("last 7 days",now,zone))
        assertEquals(today to today+day,Telnyx.window("whenever",now,zone))
        // The same moment is a different calendar day in Chicago before 5 AM UTC... but not at 8 PM UTC, which is 3 PM there.
        val chicago=Telnyx.window("today",now,ZoneId.of("America/Chicago"))
        assertEquals(Telnyx.parseTime("2026-10-10T05:00:00Z") to Telnyx.parseTime("2026-10-11T05:00:00Z"),chicago)
    }

    @Test fun theNewToolIsReadOnlyAndRunsAlongsideOtherReads() {
        val tool=telnyxTools.first {it.name=="telnyx_ai_calls"}
        assertFalse(tool.review);assertEquals("telnyx",AgentRules.connectionFor(tool.name))
        assertTrue(tool.description.contains("untrusted") && tool.description.contains("Read-only"))
        assertTrue("telnyx_ai_calls" in AgentRules.parallelSafe)
        assertTrue(allTools.any {it.name=="telnyx_ai_calls"})
    }
}
