package com.alphainternational.assistant

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * The Growth Engine chat of 2026-10-13: the owner asked for 50 emails "using my phone", got 5, was asked to sign in to Gmail twice, was told to share a file by hand, and the job sat paused for
 * four hours. These are the rules that fix it: carry on by itself, never dead-end on a broken Google sign-in, do the job on the phone when told to, and count what was sent.
 */
class GrowthEngineFixesTest {
    // ---------------------------------------------------------------- carrying on by itself

    @Test fun theUsageLimitAndTheCheckInStopAreRecognisedSoTheyCanCarryOnWithoutATap() {
        assertTrue(TaskProtocol.isUsageLimit("ChatGPT returned HTTP 429: You've hit your usage limit"))
        assertTrue(TaskProtocol.isUsageLimit("usage_limit_reached"))
        assertFalse(TaskProtocol.isUsageLimit("Connection reset by peer"))
        assertTrue(TaskProtocol.isCheckIn("This is taking me a while, so I paused to check in"))
        assertFalse(TaskProtocol.isCheckIn("Gmail said no"))
    }

    @Test fun aUsageLimitWaitsLongerEachTimeUpToHalfAnHourAndGivesUpAfterSixTries() {
        assertEquals(listOf(5L,10L,15L,20L,25L,30L,30L),(0..6).map {AgentRules.limitDelayMs(it)/60_000})
        assertEquals(6,AgentRules.MAX_AUTO_RETRIES)
        assertTrue("a check-in carries on in seconds",AgentRules.CHECK_IN_DELAY_MS<10_000)
    }

    /** Seen live on 2026-10-14: "Our servers are currently overloaded. Please try again later." paused a job twice and it waited for a tap. */
    @Test fun anOverloadedServiceIsWaitedOutAndNotLeftForTheOwnerToTap() {
        val said="Our servers are currently overloaded. Please try again later."
        assertTrue(TaskProtocol.isOverloaded(said))
        assertTrue("the model is asked another way first (another model, lower effort)",TaskProtocol.serviceGlitch(said))
        assertFalse(TaskProtocol.isOverloaded("HTTP 429 usage limit reached"))
        assertEquals("ChatGPT had a problem on its side and couldn't finish this one.",TaskProtocol.plainSnag(said))
        assertEquals(listOf(30L,60L,120L,240L,480L,600L,600L),(0..6).map {AgentRules.overloadDelayMs(it)/1000})
    }

    @Test fun aJobCutShortWhileGettingResultsCarriesOnBySelfButNotForever() {
        assertTrue(AgentRules.autoContinue(results=5,alreadyContinued=0,automaticJob=false))
        assertTrue(AgentRules.autoContinue(results=2,alreadyContinued=5,automaticJob=false))
        assertFalse("no progress this time: more of the same would only repeat",AgentRules.autoContinue(results=1,alreadyContinued=0,automaticJob=false))
        assertFalse("six times is enough",AgentRules.autoContinue(results=9,alreadyContinued=AgentRules.MAX_AUTO_CONTINUES,automaticJob=false))
        assertFalse("automatic jobs never do",AgentRules.autoContinue(results=9,alreadyContinued=0,automaticJob=true))
    }

    // ---------------------------------------------------------------- a Google sign-in that stops working

    @Test fun aGoogle401GetsOneFreshTokenAndWorksIfThatFixesIt() {
        var tokens=0;var calls=0
        val out=runBlocking {GoogleCalls.withFreshToken({tokens++}) {calls++;if(calls==1) throw ApiFailure(401,"HTTP 401: invalid credentials") else "sent"}}
        assertEquals("sent",out);assertEquals(1,tokens);assertEquals(2,calls)
    }

    @Test fun aGoogle401ThatAFreshTokenDoesNotFixIsABrokenSignInNotAnEndlessRetry() {
        var tokens=0;var calls=0
        try {runBlocking {GoogleCalls.withFreshToken({tokens++}) {calls++;throw ApiFailure(401,"HTTP 401")}};fail("should be broken")}
        catch(e:GoogleSignInBroken) {assertTrue(e.message,e.message.orEmpty().contains("401"))}
        assertEquals(1,tokens);assertEquals(2,calls)
    }

    @Test fun otherGoogleErrorsAreLeftAlone() {
        var tokens=0
        try {runBlocking {GoogleCalls.withFreshToken({tokens++}) {throw ApiFailure(403,"HTTP 403: not allowed")}};fail("403 should pass through")}
        catch(e:ApiFailure) {assertEquals(403,e.code)}
        assertEquals(0,tokens)
        try {runBlocking {GoogleCalls.withFreshToken({tokens++}) {throw ApiFailure(500,"HTTP 500")}};fail()} catch(e:ApiFailure) {assertEquals(500,e.code)}
        assertEquals(0,tokens)
    }

    // ---------------------------------------------------------------- "use my phone" means use the phone

    @Test fun anOwnerWhoSaysToUseTheirPhoneGetsTheJobDoneInThePhonesApps() {
        listOf("Send a 50 more emails find 50 more companies. Use my phone. Take over my phone and use my gmail.","use my phone to send the email","Take over my phone and finish it",
            "please do it on my phone","On my phone, send the quote to Mike").forEach {assertTrue(it,PhoneJobRules.ownerWantsPhone(it))}
        listOf("Send an email to Mike","What is the capital of France","text the number from my phone number list","my phone is slow today").forEach {assertFalse(it,PhoneJobRules.ownerWantsPhone(it))}
        assertTrue(PhoneJobRules.USE_PHONE_NOTE.contains("do not ask them to sign in"))
    }

    @Test fun theChoreRuleIsInTheReviewersInstructions() {
        assertTrue(Verify.CRITIC_INSTRUCTIONS.contains("hands the owner a chore"))
    }

    // ---------------------------------------------------------------- the outreach tools are on when the owner asks for outreach

    @Test fun askingForFiftyEmailsSwitchesOnTheCampaignRecordAndThePhone() {
        val groups=ToolGroups.select("Send a 50 more emails find 50 more companies. Use my phone. Take over my phone and use my gmail.")
        assertTrue(groups.toString(),"outreach" in groups && "phone" in groups)
        assertTrue("outreach" in ToolGroups.select("Run a cold email campaign to Houston fleets"))
        assertFalse("outreach" in ToolGroups.select("What is the capital of Australia"))
        assertEquals(setOf("campaign_sent","campaign_outcome","campaign_report"),ToolGroups.find("outreach")!!.tools.toSet())
    }

    // ---------------------------------------------------------------- measuring a campaign

    private val t0=1_800_000_000_000L
    private val hour=60*60*1000L
    private val dayMs=24*hour

    private fun fill(vararg rows:Triple<String,String,String>):List<Campaign.Entry> =
        rows.mapIndexed {i,(who,version,industry)->Campaign.Entry(who,who.lowercase().replace(' ','.')+"@example.com","email",version,industry,t0+i*1000L)}

    @Test fun everySendIsCountedAndTheSameContactIsNeverMessagedTwiceInThirtyDays() {
        var list=emptyList<Campaign.Entry>()
        list=Campaign.sent(list,"Atlas Delivery","atlas@example.com","email","A","delivery",t0).entries
        list=Campaign.sent(list,"Go-Yes Logistics","","email","B","logistics",t0+1000).entries
        assertEquals(2,list.size)
        try {Campaign.sent(list,"Atlas Delivery","","email","B","delivery",t0+5*dayMs);fail("duplicate")} catch(e:IllegalArgumentException) {assertTrue(e.message,e.message.orEmpty().contains("Already contacted"))}
        try {Campaign.sent(list,"someone","ATLAS@example.com","email","A","x",t0+5*dayMs);fail("same contact, other name")} catch(e:IllegalArgumentException) {}
        // another channel is a different message; after thirty days the same contact may be messaged again
        assertEquals(3,Campaign.sent(list,"Atlas Delivery","","facebook","A","delivery",t0+5*dayMs).entries.size)
        assertEquals(3,Campaign.sent(list,"Atlas Delivery","atlas@example.com","email","A","delivery",t0+31*dayMs).entries.size)
    }

    @Test fun theDailyLimitStopsTheSendingToProtectTheOwnersGmail() {
        var list=emptyList<Campaign.Entry>()
        for(i in 1..Campaign.DAILY_CAP) list=Campaign.sent(list,"Company $i","c$i@example.com","email","A","x",t0+i*1000L).entries
        try {Campaign.sent(list,"One more","more@example.com","email","A","x",t0+hour);fail("over the limit")}
        catch(e:IllegalArgumentException) {assertTrue(e.message,e.message.orEmpty().contains("daily limit"))}
        // texts have their own count, and a day later email may go on
        assertEquals(Campaign.DAILY_CAP+1,Campaign.sent(list,"Texty","555-0100","sms","A","x",t0+hour).entries.size)
        assertEquals(Campaign.DAILY_CAP+1,Campaign.sent(list,"One more","more@example.com","email","A","x",t0+dayMs+2*hour).entries.size)
    }

    @Test fun theReportGivesRealNumbersAndNamesABetterVersionOnlyWhenThereIsEnoughToTell() {
        val none=Campaign.report(emptyList(),t0)
        assertEquals(0,none.getInt("sent_total"));assertEquals("nothing sent yet",none.getString("better_version"))
        var list=fill(Triple("A1","A","delivery"),Triple("A2","A","delivery"),Triple("A3","A","plumbing"),Triple("B1","B","delivery"),Triple("B2","B","plumbing"))
        assertTrue(Campaign.report(list,t0+10*1000L).getString("better_version").startsWith("not enough"))
        list=list+fill(Triple("A4","A","hvac"),Triple("A5","A","hvac"),Triple("B3","B","hvac"),Triple("B4","B","hvac"),Triple("B5","B","delivery"))
        // two of version B answer, none of A
        list=Campaign.outcome(list,"B1","","replied","asked for prices",t0+hour).entries
        list=Campaign.outcome(list,"B3","email","appointment","booked Tuesday",t0+2*hour).entries
        list=Campaign.outcome(list,"A2","","bounced","",t0+hour).entries
        val r=Campaign.report(list,t0+4*dayMs)
        assertEquals(10,r.getInt("sent_total"));assertEquals(2,r.getInt("positive_replies"));assertEquals(1,r.getInt("appointments"));assertEquals(1,r.getInt("bounced"))
        assertTrue(r.getString("better_version"),r.getString("better_version").startsWith("version B (40%"))
        val byVersion=r.getJSONArray("by_version")
        assertEquals(2,byVersion.length())
        assertTrue(r.getJSONArray("due_for_follow_up").length()>=1)
        assertEquals("by_industry has delivery, plumbing and hvac",3,r.getJSONArray("by_industry").length())
        assertTrue(r.getString("instruction").contains("one or two lines"))
    }

    @Test fun anOutcomeNeedsSomeoneWhoWasActuallyContactedAndARealOutcome() {
        val list=fill(Triple("Atlas","A","x"))
        try {Campaign.outcome(list,"Nobody","","replied","",t0);fail()} catch(e:IllegalArgumentException) {assertTrue(e.message,e.message.orEmpty().contains("Nobody"))}
        try {Campaign.outcome(list,"Atlas","","loved it","",t0);fail()} catch(e:IllegalArgumentException) {assertTrue(e.message,e.message.orEmpty().contains("Outcome must be"))}
        assertEquals("not_interested",Campaign.outcome(list,"Atlas","","not interested","",t0).entries[0].outcome)
    }

    @Test fun theRecordIsKeptInTheBotsFolderAsFilesTheOwnerCanOpen() {
        val folder=File.createTempFile("camp","").let {it.delete();it.mkdirs();it}
        try {
            val a=JSONObject().put("who","Atlas Delivery").put("contact","atlas@example.com").put("channel","email").put("version","A").put("industry","delivery")
            assertEquals("recorded",CampaignTools.run("campaign_sent",a,folder,t0).getString("result"))
            CampaignTools.run("campaign_outcome",JSONObject().put("who","Atlas Delivery").put("channel","").put("outcome","replied").put("note","said call Monday, \"quote\""),folder,t0+hour)
            assertEquals(1,CampaignTools.run("campaign_report",JSONObject(),folder,t0+2*hour).getInt("sent_total"))
            val csv=File(folder,"campaign_ledger.csv").readText()
            assertTrue(csv,csv.startsWith("company,contact,channel,version,industry,sent,outcome,outcome date,note"))
            assertTrue(csv,csv.contains("\"Atlas Delivery\"") && csv.contains("\"replied\"") && csv.contains("\"\"quote\"\""))
            // a second send of the same person is refused through the tool too
            try {CampaignTools.run("campaign_sent",a,folder,t0+dayMs);fail()} catch(e:IllegalArgumentException) {}
        } finally {folder.deleteRecursively()}
    }

    /** A bot cleared the record file by hand in the live test and the sheet still showed the old test row. */
    @Test fun theSheetFollowsTheRecordEvenWhenABotOverwroteTheRecordByHand() {
        val folder=File.createTempFile("camp","").let {it.delete();it.mkdirs();it}
        try {
            CampaignTools.run("campaign_sent",JSONObject().put("who","Atlas").put("contact","a@example.com").put("channel","email").put("version","A").put("industry","x"),folder,t0)
            assertTrue(File(folder,"campaign_ledger.csv").readText().contains("Atlas"))
            File(folder,"campaign_ledger.json").writeText("[]")
            assertEquals(0,CampaignTools.run("campaign_report",JSONObject(),folder,t0+hour).getInt("sent_total"))
            assertFalse(File(folder,"campaign_ledger.csv").readText().contains("Atlas"))
        } finally {folder.deleteRecursively()}
    }

    @Test fun aBrokenRecordFileStartsAgainInsteadOfBreakingTheBot() {
        assertEquals(0,Campaign.parse("not json").size)
    }
}
