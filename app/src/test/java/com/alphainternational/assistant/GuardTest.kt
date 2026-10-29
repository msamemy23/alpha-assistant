package com.alphainternational.assistant

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Guard: the three dials, the send ledger (caps, repeats, do-not-contact) and the stop words. */
class GuardTest {
    private val now=1_800_000_000_000L
    private fun ask(vararg pairs:Pair<String,String>)=AiSettings(approvals="bots",guard=mapOf(*pairs))

    // ---- the dials ----

    @Test fun eachToolBelongsToOneDial() {
        assertEquals(Guard.Area.MESSAGES,Guard.areaOf("sms_send"));assertEquals(Guard.Area.MESSAGES,Guard.areaOf("gmail_send"))
        assertEquals(Guard.Area.WEB,Guard.areaOf("browser_click"));assertEquals(Guard.Area.WEB,Guard.areaOf("browser_press_enter"))
        assertEquals(Guard.Area.APPS,Guard.areaOf("mcp_call",mcpWrite=true))
        assertNull("a look in a connected app has no dial",Guard.areaOf("mcp_call",mcpWrite=false))
        assertNull(Guard.areaOf("file_write"));assertNull(Guard.areaOf("web_fetch"))
    }

    @Test fun aDialFollowsTheOlderSettingUntilTheOwnerSetsIt() {
        val bots=AiSettings(approvals="bots");val important=AiSettings(approvals="important");val all=AiSettings(approvals="all")
        for(area in Guard.Area.values()) {
            assertEquals(Guard.GO,Guard.level(area,bots));assertEquals(Guard.ASK,Guard.level(area,important));assertEquals(Guard.ASK,Guard.level(area,all))
            assertFalse(Guard.overridden(area,bots))
        }
        val own=ask(Guard.Area.MESSAGES.key to Guard.ASK)
        assertEquals("the owner's choice wins",Guard.ASK,Guard.level(Guard.Area.MESSAGES,own));assertTrue(Guard.overridden(Guard.Area.MESSAGES,own))
        assertEquals("the other dials still follow",Guard.GO,Guard.level(Guard.Area.WEB,own))
        val loose=AiSettings(approvals="important",guard=mapOf(Guard.Area.WEB.key to Guard.GO))
        assertEquals(Guard.GO,Guard.level(Guard.Area.WEB,loose));assertEquals(Guard.ASK,Guard.level(Guard.Area.MESSAGES,loose))
    }

    @Test fun settingADialAndPuttingItBack() {
        val s=Guard.with(AiSettings(approvals="bots"),Guard.Area.APPS,Guard.ASK)
        assertEquals(Guard.ASK,Guard.level(Guard.Area.APPS,s))
        val back=Guard.with(s,Guard.Area.APPS,"")
        assertEquals(Guard.GO,Guard.level(Guard.Area.APPS,back));assertTrue(back.guard.isEmpty())
    }

    @Test fun theDialsSurviveSavingAndAMadeUpValueIsDropped() {
        val s=AiSettings(guard=mapOf("messages" to "ask","web" to "go"),emailDaily=40,dailyTokens=5_000_000)
        val back=AiSettings.parse(JSONObject(s.json().toString()))
        assertEquals(s,back)
        val bad=AiSettings.parse(JSONObject().put("guard",JSONObject().put("messages","whatever").put("web","go")))
        assertEquals(mapOf("web" to "go"),bad.guard)
        assertEquals(AiSettings.DEFAULT_EMAIL_DAILY,AiSettings.parse(JSONObject().put("email_daily",0)).emailDaily)
        assertEquals("0 means no stop",0,AiSettings.parse(JSONObject().put("daily_tokens",0)).dailyTokens)
    }

    // ---- the ledger ----

    @Test fun aPersonIsTheSamePersonHoweverTheNumberOrAddressIsWritten() {
        assertEquals("7135550142",SendLedger.key("(713) 555-0142"));assertEquals("7135550142",SendLedger.key("+1 713-555-0142"))
        assertEquals("maria@shop.com",SendLedger.key("  Maria@Shop.com "))
    }

    @Test fun textsStopAtTheDailyLimitAndPerPerson() {
        var log=JSONArray()
        assertNull(SendLedger.refusal(log,SendLedger.SMS,"(713) 555-0142","Hi",now,30,3))
        for(i in 1..3) log=SendLedger.record(log,SendLedger.SMS,"(713) 555-0142","message $i",now+i)
        assertNotNull("three to one person is the most",SendLedger.refusal(log,SendLedger.SMS,"713-555-0142","message 4",now+10,30,3))
        assertNull("someone else is fine",SendLedger.refusal(log,SendLedger.SMS,"7135550199","Hi",now+10,30,3))
        var many=JSONArray();for(i in 0 until 10) many=SendLedger.record(many,SendLedger.SMS,"713555%04d".format(i),"hello $i",now)
        assertTrue(SendLedger.refusal(many,SendLedger.SMS,"7135559999","hello",now+1,10,3)!!.contains("daily text limit"))
    }

    @Test fun emailsAndTextsAreCountedApartAndTheLimitsAreDaily() {
        var log=JSONArray()
        for(i in 1..3) log=SendLedger.record(log,SendLedger.EMAIL,"maria@shop.com","note $i",now)
        assertNotNull(SendLedger.refusal(log,SendLedger.EMAIL,"maria@shop.com","note 4",now+1,50,3))
        assertNull("a text to the same person is a different kind",SendLedger.refusal(log,SendLedger.SMS,"maria@shop.com","hi",now+1,30,3))
        assertEquals(3,SendLedger.today(log,SendLedger.EMAIL,now+1));assertEquals(0,SendLedger.today(log,SendLedger.SMS,now+1))
        val later=now+SendLedger.DAY+1
        assertNull("a day later the same person may be written to again",SendLedger.refusal(log,SendLedger.EMAIL,"maria@shop.com","a new thing",later,50,3))
        assertTrue(SendLedger.refusal(log,SendLedger.EMAIL,"x@y.com","hi",now,3,3)!!.contains("daily email limit"))
    }

    @Test fun theSameWordsToTheSamePersonAreNotSentTwiceInThirtyDays() {
        val log=SendLedger.record(JSONArray(),SendLedger.SMS,"7135550142","Your Camry is ready for pickup.",now)
        val soon=now+2*SendLedger.DAY
        assertNotNull(SendLedger.refusal(log,SendLedger.SMS,"(713) 555-0142","  your camry is READY for pickup. ",soon,30,3))
        assertNull("other words are fine",SendLedger.refusal(log,SendLedger.SMS,"7135550142","Your Camry is ready, we close at 6.",soon,30,3))
        assertNull("another person is fine",SendLedger.refusal(log,SendLedger.SMS,"7135550199","Your Camry is ready for pickup.",soon,30,3))
        assertNull("after thirty days it may be said again",SendLedger.refusal(log,SendLedger.SMS,"7135550142","Your Camry is ready for pickup.",now+31*SendLedger.DAY,30,3))
        assertEquals("entries older than thirty days are dropped",1,SendLedger.record(log,SendLedger.SMS,"7135550142","new",now+31*SendLedger.DAY).length())
    }

    @Test fun nobodyOnTheDoNotContactListIsWrittenToByAnyMeans() {
        val stop=setOf(SendLedger.key("(713) 555-0142"),SendLedger.key("Maria@Shop.com"))
        val sms=SendLedger.refusal(JSONArray(),SendLedger.SMS,"+1 713 555 0142","Hi",now,30,3,stop)
        assertTrue(sms!!.contains("do-not-contact"))
        assertNotNull(SendLedger.refusal(JSONArray(),SendLedger.EMAIL,"maria@shop.com","Hi",now,50,3,stop))
        assertNull(SendLedger.refusal(JSONArray(),SendLedger.SMS,"7135550199","Hi",now,30,3,stop))
    }

    @Test fun anEmptyMessageIsNotTreatedAsARepeat() {
        val log=SendLedger.record(JSONArray(),SendLedger.SMS,"7135550142","",now)
        assertNull(SendLedger.refusal(log,SendLedger.SMS,"7135550142","",now+1,30,3))
    }

    // ---- stop words ----

    @Test fun aCustomerWhoWritesStopIsOptingOut() {
        for(w in listOf("STOP","stop","Stop.","  unsubscribe ","CANCEL","end","Stop all","quit","baja","PARAR","no más","no mas","don't text me","opt out"))
            assertTrue(w,Guard.isOptOut(w))
        for(w in listOf("Please stop by at 5","Can you stop the work on the brakes?","stop the car is fine now","Thanks","I need to cancel my 3pm appointment","end of the month"))
            assertFalse(w,Guard.isOptOut(w))
    }
}
