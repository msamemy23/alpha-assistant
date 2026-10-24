package com.alphainternational.assistant

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** The safety and cost limits a public install relies on: the usage meter, the job cap, the daily text limit, and the send, delete and Enter gates on the phone. */
class PublicSafetyTest {
    // ---- usage meter ----

    @Test fun usageIsCountedPerDayAndOldDaysAreDropped() {
        val days=JSONObject()
        UsageMeter.add(days,"2026-10-24",1000,400,200)
        UsageMeter.add(days,"2026-10-24",500,0,100)
        val d=UsageMeter.of(days,"2026-10-24")
        assertEquals(2,d.calls);assertEquals(1500L,d.input);assertEquals(400L,d.cached);assertEquals(300L,d.output);assertEquals(1800L,d.total)
        assertEquals(0,UsageMeter.of(days,"2026-10-23").calls)
        // thirty-five days later the first day is gone
        for(i in 1..35) UsageMeter.add(days,"z%02d".format(i),1,0,1)
        assertTrue(days.length()<=30)
        assertFalse(days.has("2026-10-24"))
    }

    @Test fun negativeCountsAreIgnoredAndNumbersAreShort() {
        val days=JSONObject();UsageMeter.add(days,"d",-5,-1,-2)
        assertEquals(0L,UsageMeter.of(days,"d").total)
        assertEquals("999",UsageMeter.short(999));assertEquals("1.2K",UsageMeter.short(1234));assertEquals("3.4M",UsageMeter.short(3_400_000))
        assertEquals("Nothing yet today",UsageMeter.summary(UsageMeter.Day()))
        assertTrue(UsageMeter.summary(UsageMeter.Day(3,2000,0,500)).contains("3 AI calls"))
    }

    @Test fun aMeteredJobStopsAtItsShareButThePlanIsNotCapped() {
        assertFalse(UsageMeter.jobOver(true,UsageMeter.JOB_CAP-1))
        assertTrue(UsageMeter.jobOver(true,UsageMeter.JOB_CAP))
        assertFalse("the ChatGPT plan has its own limits",UsageMeter.jobOver(false,UsageMeter.JOB_CAP*10))
    }

    @Test fun theDailyWarningComesOnceAndOnlyForMeteredServices() {
        val big=UsageMeter.Day(10,UsageMeter.DAY_WARN,0,0)
        assertTrue(UsageMeter.shouldWarn(true,big,false))
        assertFalse(UsageMeter.shouldWarn(true,big,true))
        assertFalse(UsageMeter.shouldWarn(false,big,false))
        assertFalse(UsageMeter.shouldWarn(true,UsageMeter.Day(1,10,0,0),false))
    }

    @Test fun tokenCountsFromOtherServicesAreReadUnderTheResponsesNames() {
        val c=ChatCompletions.Collector()
        c.accept(JSONObject("{\"choices\":[{\"delta\":{\"content\":\"Hi\"},\"finish_reason\":\"stop\"}]}"))
        c.accept(JSONObject("{\"choices\":[],\"usage\":{\"prompt_tokens\":120,\"completion_tokens\":7}}"))
        val usage=c.result().getJSONObject("usage")
        assertEquals(120,usage.getInt("input_tokens"));assertEquals(7,usage.getInt("output_tokens"))
        // a service that reports nothing gives a reply with no usage
        val none=ChatCompletions.Collector();none.accept(JSONObject("{\"choices\":[{\"delta\":{\"content\":\"Hi\"}}]}"))
        assertFalse(none.result().has("usage"))
        assertTrue(ChatCompletions.request("m",JSONArray(),"",emptyList()).getJSONObject("stream_options").getBoolean("include_usage"))
    }

    // ---- daily text limit ----

    @Test fun textsStopAtTheDailyLimitAndPerPerson() {
        val now=1_000_000_000_000L
        var log=JSONArray()
        assertNull(SmsCap.refusal(log,"(713) 555-0142",now,30,3))
        repeat(3) {log=SmsCap.record(log,"(713) 555-0142",now+it)}
        // the same person written another way is still the same person
        assertNotNull(SmsCap.refusal(log,"713-555-0142",now+10,30,3))
        assertNull("someone else is fine",SmsCap.refusal(log,"7135550199",now+10,30,3))
        // the whole day's limit
        var many=JSONArray();repeat(10) {many=SmsCap.record(many,"713555%04d".format(it),now)}
        assertTrue(SmsCap.refusal(many,"7135559999",now+1,10,3)!!.contains("daily text limit"))
    }

    @Test fun aDayLaterTheTextsAreAllowedAgain() {
        val now=1_000_000_000_000L
        var log=JSONArray();repeat(3) {log=SmsCap.record(log,"7135550142",now)}
        val later=now+25*3_600_000L
        assertNull(SmsCap.refusal(log,"7135550142",later,30,3))
        assertEquals("old entries are dropped when a new one is recorded",1,SmsCap.record(log,"7135550142",later).length())
    }

    // ---- settings defaults ----

    @Test fun theDefaultsMatchWhoTheBuildIsFor() {
        val s=AiSettings()
        assertEquals(AiSettings.DEFAULT_APPROVALS,s.approvals)
        assertEquals(if(Profile.generic) "important" else "bots",s.approvals)
        assertEquals(AiSettings.DEFAULT_SMS_DAILY,s.smsDaily)
        // a saved choice is kept, and a made-up value falls back to the default
        assertEquals("all",AiSettings.parse(JSONObject().put("approvals","all")).approvals)
        assertEquals(AiSettings.DEFAULT_APPROVALS,AiSettings.parse(JSONObject().put("approvals","whatever")).approvals)
        assertEquals(100,AiSettings.parse(AiSettings(smsDaily=100).json()).smsDaily)
        assertEquals(AiSettings.DEFAULT_SMS_DAILY,AiSettings.parse(JSONObject().put("sms_daily",0)).smsDaily)
    }

    // ---- what the phone will do on its own ----

    @Test fun deleteButtonsAreHeldForTheOwnersOk() {
        for(label in listOf("Delete","Remove","Delete all","Delete forever","Empty trash","Move to trash","Delete message","Permanently delete","Clear history"))
            assertEquals(label,PhoneSafety.Risk.DESTRUCTIVE,PhoneSafety.risk(label))
        // a sentence that only mentions deleting, or a button that is something else, is left alone
        assertEquals(PhoneSafety.Risk.NONE,PhoneSafety.risk("Remove filter"))
        assertEquals(PhoneSafety.Risk.NONE,PhoneSafety.risk("Undelete"))
        assertEquals(PhoneSafety.Risk.NONE,PhoneSafety.risk("Tap here to see how to delete a photo from the cloud storage"))
        val ask=PhonePolicy(approvals="bots",ownerAskedToSend=true)
        assertTrue(PhoneSafety.judgeTap("Delete","com.google.android.apps.photos",ask) is PhoneSafety.Verdict.Confirm)
        assertTrue(PhoneSafety.judgeTap("Delete","com.google.android.apps.photos",PhonePolicy(approved=true)) is PhoneSafety.Verdict.Go)
    }

    @Test fun enterIsASendInTheAppsPeopleTalkIn() {
        val messages="com.google.android.apps.messaging"
        assertTrue("the screen suggested it",PhoneSafety.judgeEnter(messages,PhonePolicy("bots",false)) is PhoneSafety.Verdict.Confirm)
        assertTrue("the owner asked for the send",PhoneSafety.judgeEnter(messages,PhonePolicy("bots",true)) is PhoneSafety.Verdict.Go)
        assertTrue("asked to confirm sends",PhoneSafety.judgeEnter(messages,PhonePolicy("important",true)) is PhoneSafety.Verdict.Confirm)
        assertTrue("the owner approved this key press",PhoneSafety.judgeEnter(messages,PhonePolicy("important",false,approved=true)) is PhoneSafety.Verdict.Go)
        for(pkg in listOf("com.whatsapp","org.telegram.messenger","com.google.android.gm","com.slack","com.instagram.android"))
            assertTrue(pkg,PhoneSafety.judgeEnter(pkg,PhonePolicy("bots",false)) is PhoneSafety.Verdict.Confirm)
        // a search box in a browser or the Clock app needs no card
        assertTrue(PhoneSafety.judgeEnter("com.android.chrome",PhonePolicy("bots",false)) is PhoneSafety.Verdict.Go)
        assertTrue(PhoneSafety.judgeEnter("com.sec.android.app.clockpackage",PhonePolicy("important",false)) is PhoneSafety.Verdict.Go)
        assertTrue("never in a password app",PhoneSafety.judgeEnter("com.x.bitwarden",PhonePolicy()) is PhoneSafety.Verdict.Block)
    }

    @Test fun onlyPlainSendWordsCountAsAskingToSend() {
        for(ok in listOf("Text Mom that I am late","Reply to Dad","Send the invoice","Ask Mike if the car is ready","Tell Sam I will call back","Let Maria know it is done","Email the quote","Post this on my page"))
            assertTrue(ok,PhoneSafety.ownerAskedToSend(ok))
        for(no in listOf("Tell me what is on my screen","Ask me before you do anything","What does the answer say","Open the comment settings","Submit nothing, just look","Let me know what you find"))
            assertFalse(no,PhoneSafety.ownerAskedToSend(no))
    }

    @Test fun whatIsReadFromTheScreenOrTheTubeCountsAsOutsideText() {
        fun history(vararg tools:String)=JSONArray().also {a->tools.forEach {a.put(JSONObject().put("type","function_call").put("name",it))}}.toString()
        for(t in listOf("screen_read","screen_look","youtube_transcript","image_search","page_screenshot","telnyx_messages","web_fetch","gmail_read"))
            assertTrue(t,SupabaseRules.tainted(history(t)))
        assertFalse(SupabaseRules.tainted(history("memory_search","file_write","task_plan")))
    }
}
