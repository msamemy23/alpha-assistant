package com.alphainternational.assistant

import org.junit.Assert.*
import org.junit.Test

/** What went wrong in the real "Alpha Advisor" chat on 2026-10-11: a short browser request got the quickest, weakest setting, and the bot remembered "not connected". */
class AdvisorFixesTest {
    @Test fun aShortRequestToUseTheBrowserOrASiteIsRealWorkNotChitChat() {
        listOf("Use the broswer to check the calls","Open broswer so I can sign into telnyx","Check the portal for today's calls","look at the dashboard","Why not. Tell me who called",
            "Open the browser","Find the call log").forEach {assertNotEquals(it,ModelPlan.Level.QUICK,ModelPlan.level(it))}
    }

    @Test fun greetingsAndPlainFollowUpsAreStillQuick() {
        listOf("hi","hi there","thanks","yes do it","ok","good morning").forEach {assertEquals(it,ModelPlan.Level.QUICK,ModelPlan.level(it))}
    }

    @Test fun howThingsStandRightNowIsNeverLearned() {
        val reply="""{"remember":[
            {"id":"","kind":"lesson","title":"Telnyx calls unavailable","text":"Telnyx calls unavailable — connector not connected","from":"doing","private":false},
            {"id":"","kind":"fact","title":"Telnyx","text":"The owner is not signed in to Telnyx right now","from":"doing","private":false},
            {"id":"","kind":"fact","title":"Shop hours","text":"The shop is open 8 to 6 on weekdays","from":"you","private":false},
            {"id":"","kind":"preference","title":"Short texts","text":"Likes texts kept short","from":"you","private":false}]}"""
        val kept=Learning.parse(reply,emptySet()).remember.map {it.title}
        assertEquals(listOf("Shop hours","Short texts"),kept)
    }

    @Test fun aTeamStepOrAColleaguesRequestIsJudgedByTheOwnersWordsNotByItsWrapper() {
        val owner="Find front brake pad prices for the car Masoud Samemy drives"
        val step=owner+"\n\n(Teamwork: this request has 2 parts for different bots. YOUR PART: Check AutoZone front-pad listings for that car. Other parts: Customer Desk — find the car; Leave their parts to them. " +
            "Already found by the team: Customer Desk: Masoud Samemy drives a 2005 Honda Accord. Use it; don't look it up again. Reply with your findings only: exact numbers, names and dates, what you checked, and anything you couldn't find. The lead writes the one message to the owner, so no greeting, no follow-up buttons.)"
        assertTrue(step.length.toString(),step.length>500)
        assertEquals("a long wrapper must not make a lookup look like research",ModelPlan.Level.EVERYDAY,ModelPlan.level(step))
        assertEquals(owner+" Check AutoZone front-pad listings for that car",TeamRules.levelText(step))
        val packet=TeamRules.packet("Alpha",owner,"nothing yet","the prices","abc12345")
        assertEquals("a colleague is judged by what is needed back, not by the owner's whole request","the prices",TeamRules.levelText(packet))
        assertNotEquals(ModelPlan.Level.HARD,ModelPlan.level(packet))
        // a request that really is research is still hard
        assertEquals(ModelPlan.Level.HARD,ModelPlan.level("Research and compare the best brake pad brands for our shop"+"\n\n(Teamwork: you were picked for this message.)"))
        assertEquals("plain requests are untouched",owner,TeamRules.levelText(owner))
    }

    /** The live phone, 2026-10-11: "compare three robot vacuums" was passed whole to two colleagues, each ran it at the deepest level for 6 to 17 minutes. */
    @Test fun aColleagueAskedForOneThingGetsAnEverydayLookupWithAShortBudget() {
        val owner="Compare the three best rated robot vacuums under 400 dollars right now. For each one give the current price at two different stores, the pros and cons, and make a one page PDF."
        assertEquals("the owner's request alone is research",ModelPlan.Level.HARD,ModelPlan.level(owner))
        val ask=TeamRules.packet("Amazon Scout",owner,"three models picked","the price of the Roborock Q7 at eBay and a link","abc12345")
        assertEquals("the price of the Roborock Q7 at eBay and a link",TeamRules.levelText(ask))
        assertEquals(ModelPlan.Level.EVERYDAY,ModelPlan.level(ask))
        assertTrue("it is told to do only that part",ask.contains("Do only that part"))
        // A very short ask is still a real lookup, never small talk
        assertEquals(ModelPlan.Level.EVERYDAY,ModelPlan.level(TeamRules.packet("Alpha","hi","","ok","c1")))
        // Left vague, the owner's own words stand in for it
        assertEquals(ModelPlan.Level.HARD,ModelPlan.level(TeamRules.packet("Amazon Scout",owner,"","","abc12345")))
        // The asker can only wait so long, so the colleague wraps up early with what it has
        val normal=AgentRules.Budget(JobBudget.CAP_WRAP_UP,JobBudget.CAP_STOP)
        assertEquals(AgentRules.Budget(JobBudget.HELPER_WRAP_UP,JobBudget.HELPER_STOP),JobBudget.forHelper(normal))
        assertEquals(AgentRules.Budget(3,5),JobBudget.forHelper(AgentRules.Budget(3,5)))
    }

    @Test fun theCaseWatchdogOutlastsAnyJobSoAWorkingStepIsNeverDeclaredStopped() {
        assertTrue(CaseRules.WATCHDOG_MS>JobBudget.CAP_STOP*60_000L)
        assertTrue(CaseRules.WATCHDOG_MS<=CaseRules.OPEN_MS)
    }

    @Test fun aLinkToAFileOnTheAisOwnComputerIsRemovedAndItsWordsKept() {
        val text="Here is the list.\n\n[Open or download **alpha-simple-price-list.docx**](sandbox:/mnt/data/alpha-simple-price-list.docx)\n\nAnd [the shop site](https://example.com/page) stays."
        val clean=ChatMarkdown.withoutFollowups(text)
        assertFalse(clean,clean.contains("sandbox") || clean.contains("mnt/data"))
        assertTrue(clean,clean.contains("alpha-simple-price-list.docx"))
        assertTrue("real links stay",clean.contains("[the shop site](https://example.com/page)"))
        assertEquals("[Report](/mnt/data/report.pdf) is ready".let {ChatMarkdown.withoutPhantomLinks(it)},"Report is ready")
    }

    /** The live phone, 2026-10-11: an answer that only looked in the bot's folder came with every old file in it, a screenshot of the owner's Telnyx page among them. */
    @Test fun lookingInTheFolderIsNotMakingFiles() {
        val history=org.json.JSONArray().apply {
            put(obj("type" to "function_call","name" to "files_list","call_id" to "l1","arguments" to "{}"))
            put(obj("type" to "function_call_output","call_id" to "l1","output" to """{"file_names":["telnyx-conversation.png","old-estimate.pdf"]}"""))
            put(obj("type" to "function_call","name" to "xlsx_create","call_id" to "x1","arguments" to "{}"))
            put(obj("type" to "function_call_output","call_id" to "x1","output" to """{"files":["sp500.xlsx"],"name":"sp500.xlsx","verified":true}"""))
            put(obj("type" to "function_call","name" to "chart_make","call_id" to "c1","arguments" to "{}"))
            put(obj("type" to "function_call_output","call_id" to "c1","output" to """{"files":["chart.png"],"name":"chart.png","verified":true}"""))
            // an older build named the listing "files": it still must not count
            put(obj("type" to "function_call","name" to "files_list","call_id" to "l2","arguments" to "{}"))
            put(obj("type" to "function_call_output","call_id" to "l2","output" to """{"files":["telnyx-conversation.png"]}"""))
        }
        assertEquals(listOf("sp500.xlsx","chart.png"),TaskProtocol.madeFileNames(TaskProtocol.receipts(history)))
    }

    /** The owner's screenshot, 2026-10-12: file names in grey code boxes that broke in the middle of a word, with a leftover code on the end. */
    @Test fun aFileNameInCodeMarksIsReadableAndRealCodeIsLeftAlone() {
        val spans=ChatMarkdown.spans("The Excel is `sp500-monthly-24-months.xlsx` and the chart `chart-s-p-500-monthly-closing-values-oct-2024--6d34.png`, run `x = 1`.")
        assertEquals(listOf("The Excel is ","sp500 monthly 24 months.xlsx"," and the chart ","chart s p 500 monthly closing values oct 2024.png",", run ","x = 1","."),spans.map {it.text})
        assertTrue(spans[1].bold && !spans[1].code && !spans[3].code)
        assertTrue("real code stays code",spans[5].code)
        assertEquals("estimates oct.pdf",ChatMarkdown.prettyFile("estimates-oct.pdf"))
        assertEquals("Quote 12.PDF".lowercase(),ChatMarkdown.prettyFile("Quote_12.PDF").lowercase())
    }

    @Test fun anAnswerThatSaysAFileIsAttachedOrAboveIsSpottedButOffersAndDenialsAreNot() {
        listOf("The Excel workbook and the chart are ready. It is attached and displayed above.","The chart is below.","Your Excel file is attached.","I've attached the PDF.",
            "The spreadsheet is shown above.").forEach {assertTrue(it,Verify.claimsAttachment(it))}
        listOf("Here you go 👍","Want me to attach the chart?","I couldn't attach the file.","I will attach it when it's ready.","The price above is 38 dollars.","If you want, the PDF can be attached.",
            "No file is attached yet.","It is attached and displayed above.").forEach {assertFalse(it,Verify.claimsAttachment(it))}
        assertEquals("a year at the end of a file name stays",listOf("The Excel is","report 2024.pdf").joinToString(" "),"The Excel is "+ChatMarkdown.prettyFile("report-2024.pdf"))
        assertTrue(Verify.attachRepair().contains("files_show"))
        assertFalse("the repair must not shrink a real answer to one line",Verify.attachRepair().contains("one friendly line"))
    }

    @Test fun aWordExcelOrPowerPointFileGetsAReceiptLine() {
        val history=org.json.JSONArray().apply {
            put(obj("type" to "function_call","name" to "docx_create","call_id" to "d1","arguments" to """{"file_name":"price-list","title":"Prices","blocks_json":"[]"}"""))
            put(obj("type" to "function_call_output","call_id" to "d1","output" to """{"files":["price-list.docx"],"name":"price-list.docx","verified":true,"sent":false}"""))
        }
        val lines=Verify.receiptLines(history)
        assertTrue(lines.toString(),lines.any {it.startsWith("Word file made: price-list.docx")})
    }

    @Test fun theLeadIsAnAdvisorNeverTheTextingBot() {
        val roster=listOf(Bot("Text Helper","Prepare and send texts to customers"),Bot("Alpha Advisor","Your friendly business expert for Acme Auto Repair, researching the business online and helping with operations, customers, estimates, marketing, and decisions."),
            Bot("Customer Desk","Customers, vehicles and invoices in the shop database"),Bot("AutoZone Scout","Finds parts and prices at AutoZone"),Bot("Personal Companion","Personal helper"))
        assertEquals("Alpha Advisor",TeamRules.pickLead(roster))
        assertEquals("a bot named Alpha wins",("Alpha"),TeamRules.pickLead(roster+Bot("Alpha","General")))
        assertEquals("Solo",TeamRules.pickLead(listOf(Bot("Solo","Anything"))))
        assertEquals("",TeamRules.pickLead(emptyList()))
    }

    @Test fun aNudgeWithNothingOpenGetsAnHonestStatus() {
        assertEquals("Nothing is running right now. Ask me for what you need and I'll start on it.",CaseRules.idleText(emptyList()))
        assertEquals("Still on it: Parts is working.",CaseRules.idleText(listOf("Parts")))
        assertEquals("Still on it: Parts, Desk and Scout are working.",CaseRules.idleText(listOf("Parts","Desk","Scout")))
    }
}
