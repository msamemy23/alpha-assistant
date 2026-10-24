package com.alphainternational.assistant

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class VerifyTest {
    /** A finished tool call as it sits in a job's history. */
    private class Step(val name:String,val args:JSONObject,val result:JSONObject)
    private fun step(name:String,args:JSONObject=JSONObject(),result:JSONObject=obj("result" to "ok")) = Step(name,args,result)
    private fun history(vararg steps:Step):JSONArray {
        val h=JSONArray().put(obj("role" to "user","content" to "do the thing"))
        steps.forEachIndexed {i,s->
            h.put(obj("type" to "function_call","call_id" to "c$i","name" to s.name,"arguments" to s.args.toString()))
            h.put(obj("type" to "function_call_output","call_id" to "c$i","output" to s.result.toString()))
        }
        return h
    }
    private fun failed(name:String,args:JSONObject=JSONObject()) = step(name,args,obj("error" to "That did not work."))

    // ------------------------------------------------------------------ receipts
    @Test fun threeDraftsSavedAndNoneSent() {
        val ok=obj("id" to "d1","result" to "draft saved","read_back" to obj("checked" to true,"matches" to true,"problem" to ""))
        val args=obj("to" to "a@b.com","subject" to "S","body" to "B")
        assertEquals(listOf("3 email drafts saved and checked · 0 sent"),Verify.receiptLines(history(step("gmail_draft",args,ok),step("gmail_draft",args,ok),step("gmail_draft",args,ok))))
        // Without a read-back the line does not claim a check.
        assertEquals(listOf("2 email drafts saved · 0 sent"),Verify.receiptLines(history(step("gmail_draft",args),step("gmail_draft",args))))
        assertEquals(listOf("1 email draft saved · 0 sent"),Verify.receiptLines(history(step("gmail_draft",args))))
        // One of the checks found a problem: not "checked".
        val bad=obj("id" to "d2","read_back" to obj("checked" to true,"matches" to false,"problem" to "the recipient is different"))
        assertEquals(listOf("2 email drafts saved · 0 sent"),Verify.receiptLines(history(step("gmail_draft",args,ok),step("gmail_draft",args,bad))))
    }

    @Test fun sendsAreCountedWithWhoTheyWentTo() {
        val mail=obj("to" to "maria@shop.com","subject" to "Estimate","body" to "Hi")
        val text=obj("to" to "+17135550123","body" to "Your car is ready")
        assertEquals(listOf("1 email sent to maria@shop.com"),Verify.receiptLines(history(step("gmail_send",mail))))
        assertEquals(listOf("1 text sent to +17135550123"),Verify.receiptLines(history(step("sms_send",text))))
        assertEquals(listOf("2 texts sent to +17135550123 and +17135550124"),Verify.receiptLines(history(step("sms_send",text),step("sms_send",obj("to" to "+17135550124","body" to "x")))))
        assertEquals(listOf("3 texts sent to +1, +2 and 1 more"),Verify.receiptLines(history(*listOf("+1","+2","+3").map {step("sms_send",obj("to" to it,"body" to "x"))}.toTypedArray())))
        // A draft next to a real send: no "0 sent".
        assertEquals(listOf("1 email draft saved","1 email sent to maria@shop.com"),Verify.receiptLines(history(step("gmail_draft",mail),step("gmail_send",mail))))
    }

    @Test fun whatDidNotWorkIsStatedUnlessItWorkedLater() {
        val text=obj("to" to "+17135550123","body" to "hi")
        assertEquals(listOf("1 text did not go through"),Verify.receiptLines(history(failed("sms_send",text))))
        assertEquals(listOf("1 text sent to +17135550123"),Verify.receiptLines(history(failed("sms_send",text),step("sms_send",text))))
        assertEquals(listOf("2 emails did not go through"),Verify.receiptLines(history(failed("gmail_send"),failed("gmail_send"))))
        assertEquals(listOf("A calendar event could not be added"),Verify.receiptLines(history(failed("calendar_create"))))
    }

    @Test fun documentsVideosAndFilesGetPlainLines() {
        assertEquals(listOf("Estimate E-1001 made (PDF) · not sent"),Verify.receiptLines(history(step("invoice_pdf",obj("document_type" to "estimate"),obj("number" to "E-1001","verified" to true)))))
        assertEquals(listOf("Sample invoice INV-7 made (PDF) · not sent"),Verify.receiptLines(history(step("invoice_pdf",obj("document_type" to "invoice","sample" to true),obj("number" to "INV-7")))))
        assertEquals(listOf("PDF made: flyer.pdf · not sent"),Verify.receiptLines(history(step("document_pdf",obj("file_name" to "flyer"),obj("files" to arr(listOf("flyer.pdf")),"verified" to true)))))
        assertEquals(listOf("Video made: reel.mp4"),Verify.receiptLines(history(step("media_job",obj(),obj("name" to "reel.mp4","files" to arr(listOf("reel.mp4")),"verified" to true)))))
        assertEquals(listOf("Sound made: jingle.wav"),Verify.receiptLines(history(step("audio_job",obj(),obj("name" to "jingle.wav","verified" to true)))))
        assertEquals(listOf("File saved: index.html"),Verify.receiptLines(history(step("file_write",obj("name" to "index.html")),step("file_edit",obj("name" to "index.html")))))
        assertEquals(listOf("3 files saved or changed: index.html, style.css, app.js"),
            Verify.receiptLines(history(step("file_write",obj("name" to "index.html")),step("file_write",obj("name" to "style.css")),step("file_append",obj("name" to "app.js")))))
        assertEquals(listOf("Added “Brake job” to the calendar"),Verify.receiptLines(history(step("calendar_create",obj("title" to "Brake job")))))
        assertEquals(listOf("Opened Messages with the text ready · not sent"),Verify.receiptLines(history(step("phone_action",obj("action" to "text")))))
        assertEquals(listOf("Website published: https://shop.vercel.app"),Verify.receiptLines(history(step("site_publish",obj(),obj("url" to "https://shop.vercel.app")))))
        assertEquals(listOf("Routine saved: every day at 8:00 AM"),Verify.receiptLines(history(step("routine_create",obj(),obj("schedule" to "every day at 8:00 AM")))))
    }

    @Test fun lookingThingsUpLeavesNoReceiptAndBookkeepingIsNeverShown() {
        val h=history(step("web_fetch",obj("url" to "https://x.com")),step("web_fetch"),step("file_read"),step("gmail_search"),step("memory_search"),
            step("plan_set"),step("plan_update"),step("tools_enable"),step("task_receipts"),step("capabilities"))
        assertEquals(emptyList<String>(),Verify.receiptLines(h))
        assertEquals("",Verify.receiptJson(emptyList()))
        assertEquals("[\"a\",\"b\"]",Verify.receiptJson(listOf("a","b")))
    }

    @Test fun appsAndDatabaseChangesAreSeparatedFromLooks() {
        assertEquals(listOf("Made a change in notion"),Verify.receiptLines(history(step("mcp_call",obj("server" to "notion","tool" to "create_page","arguments_json" to "{}")))))
        assertEquals(emptyList<String>(),Verify.receiptLines(history(step("mcp_call",obj("server" to "notion","tool" to "list_pages","arguments_json" to "{}")))))
        assertEquals(listOf("Changed data in the database"),Verify.receiptLines(history(step("supabase_management_call",obj("tool" to "execute_sql","arguments_json" to "{\"query\":\"update customers set name='x'\"}")))))
        assertEquals(emptyList<String>(),Verify.receiptLines(history(step("supabase_management_call",obj("tool" to "execute_sql","arguments_json" to "{\"query\":\"select * from customers\"}")))))
        assertEquals(listOf("2 actions taken on websites"),Verify.receiptLines(history(step("browser_click"),step("browser_fill"),step("browser_read"))))
    }

    @Test fun aLongListIsCappedAtEightLines() {
        val many=(1..12).map {step("sheets_create",obj("title" to "Sheet $it"))}
        assertEquals(8,Verify.receiptLines(history(*many.toTypedArray())).size)
    }

    // ------------------------------------------------------------------ claims
    @Test fun saidAsDoneIsAClaim() {
        val said=listOf("I sent the email to John.","I've sent John a text.","I texted Dave your new total.","Done — I emailed the invoice to Maria.","Email sent to Maria.","Sent!","✅ Sent to John.",
            "The text was sent.","I saved the estimate as estimate-12.pdf.","I've saved a draft in your Gmail.","I created the invoice for Maria.","I booked the appointment for Tuesday at 3.",
            "I added it to your calendar.","I deleted the old routine.","I updated the estimate to \$450.","I published the page.","I paid the invoice.","All set: I sent both texts.",
            "I have just sent the quote to the customer by email.","Draft saved.","Saved to your files.","I've scheduled the pickup for Friday.","I successfully uploaded the file.","- I texted both customers.",
            "Great news. I emailed the supplier and I saved the reply as a draft in Gmail.")
        said.forEach {assertTrue("should be a claim: $it",Verify.claims(it).isNotEmpty())}
    }

    @Test fun offersQuestionsDenialsAndQuotedTextAreNotClaims() {
        val notClaims=listOf("I can send it if you want.","Want me to text John?","I haven't sent anything yet.","Nothing was sent.","Ready to send when you are.","I'll text him as soon as you say so.",
            "The draft is below for you to send.","You sent me this message earlier.","Your appointment is booked for Tuesday at 3.","Prices are updated daily on their site.","This saves you about \$20 a month.",
            "If I sent it now, it would go to John.","Did I send it?","I wrote the email below for you to review.","I made a list of options.","I created a quick comparison table for you.",
            "\"I sent the email\" is what you'd say after you tap Send.","Your invoice was paid last week.","I saved you a trip to the store.","Nothing has been sent.","The email is ready for you to send.",
            "The estimate was sent to him on Monday.","Their last invoice was created on Oct 3 and saved in the system.","The text was sent 2 days ago.","The invoice was created 10/3.",
            "I found 3 shops and compared their prices.","Everything is up to date.","I remembered that Dave likes short texts.","Once you tap Send, the text goes out.","Here is the estimate: 2 hours labor and 1 part.",
            "I couldn't send it because the number looks wrong.","I did not text Dave.","Would you like me to save this as a draft?","Sending it would put it in his inbox.","The invoice is saved in the system as number 1234.")
        notClaims.forEach {assertTrue("should not be a claim: $it -> ${Verify.claims(it)}",Verify.claims(it).isEmpty())}
        assertTrue(Verify.claims("Here is the email I wrote for you.\n> Hi John, I sent the invoice and booked your car in.\nLet me know if you want changes.").isEmpty())
        assertTrue(Verify.claims("Here is the text:\n```\nI sent the email and saved the file.\n```\nWant me to send it?").isEmpty())
        assertTrue(Verify.claims("Suggested reply: “Hi Dave, I texted you the total and booked Friday.” Should I send that?").isEmpty())
    }

    @Test fun kindsOfClaimAreTold() {
        fun kind(text:String)=Verify.claims(text).map {it.kind}.toSet()
        assertEquals(setOf(Verify.Kind.EMAIL),kind("I sent the email to John."))
        assertEquals(setOf(Verify.Kind.TEXT),kind("I've sent John a text."))
        assertEquals(setOf(Verify.Kind.SENT),kind("Sent!"))
        assertEquals(setOf(Verify.Kind.DRAFT),kind("I've saved a draft in your Gmail."))
        assertEquals(setOf(Verify.Kind.BOOKED),kind("I added it to your calendar."))
        assertEquals(setOf(Verify.Kind.DELETED),kind("I deleted the old routine."))
        assertEquals(setOf(Verify.Kind.PAID),kind("I paid the invoice."))
    }

    @Test fun aClaimThatAResultSupportsIsNotFlagged() {
        val mail=obj("to" to "john@x.com","subject" to "Hi","body" to "Hello")
        fun unsupported(text:String,vararg steps:Step)=Verify.unsupportedClaims(text,history(*steps)).map {it.kind}
        // Supported.
        assertEquals(emptyList<Verify.Kind>(),unsupported("I sent the email to John.",step("gmail_send",mail)))
        assertEquals(emptyList<Verify.Kind>(),unsupported("I texted Dave your total.",step("sms_send",obj("to" to "+1713","body" to "x"))))
        assertEquals(emptyList<Verify.Kind>(),unsupported("I saved the file.",step("file_write",obj("name" to "a.txt"))))
        assertEquals(emptyList<Verify.Kind>(),unsupported("I've saved a draft in your Gmail.",step("gmail_draft",mail)))
        assertEquals(emptyList<Verify.Kind>(),unsupported("I booked it for Friday.",step("calendar_create",obj("title" to "x"))))
        assertEquals(emptyList<Verify.Kind>(),unsupported("I sent the message to the team.",step("mcp_call",obj("server" to "slack","tool" to "send_message","arguments_json" to "{}"))))
        assertEquals(emptyList<Verify.Kind>(),unsupported("I booked the table.",step("browser_click",obj("id" to "4"))))
        assertEquals(emptyList<Verify.Kind>(),unsupported("I created the invoice.",step("invoice_pdf",obj("document_type" to "invoice"),obj("number" to "1","verified" to true))))
        assertEquals(emptyList<Verify.Kind>(),unsupported("File saved.",step("file_write",obj("name" to "a.txt"))))
        // Not supported: nothing at all, a failed call, a different kind of send, a look, a draft where a send was claimed.
        assertEquals(listOf(Verify.Kind.EMAIL),unsupported("I sent the email to John."))
        assertEquals(listOf(Verify.Kind.TEXT),unsupported("I texted Dave.",failed("sms_send")))
        assertEquals(listOf(Verify.Kind.EMAIL),unsupported("I emailed John.",step("sms_send",obj("to" to "+1713","body" to "x"))))
        assertEquals(listOf(Verify.Kind.SAVED),unsupported("I saved the file.",step("web_fetch",obj("url" to "https://x.com")),step("gmail_search",obj("query" to "x"))))
        assertEquals(listOf(Verify.Kind.EMAIL),unsupported("I emailed the supplier.",step("gmail_draft",mail)))
        assertEquals(listOf(Verify.Kind.SENT),unsupported("Sent!",step("gmail_draft",mail)))
        assertEquals(listOf(Verify.Kind.DRAFT),unsupported("I've saved a draft in your Gmail.",step("file_write",obj("name" to "draft.txt"))))
        assertEquals(listOf(Verify.Kind.TEXT),unsupported("I sent the message to the team.",step("mcp_call",obj("server" to "slack","tool" to "list_channels","arguments_json" to "{}"))))
        assertEquals(listOf(Verify.Kind.PAID),unsupported("I paid the invoice.",step("invoice_pdf",obj("document_type" to "invoice"),obj("number" to "1"))))
        // Control tools prove nothing.
        assertEquals(listOf(Verify.Kind.SAVED),unsupported("I saved the file.",step("plan_set"),step("tools_enable")))
        // An answer with no claim is never flagged.
        assertEquals(emptyList<Verify.Kind>(),unsupported("Your estimate comes to \$412 for parts and labor."))
    }

    @Test fun theRepairNoteSaysWhatWasClaimedAndGivesTwoWaysOut() {
        val problems=Verify.unsupportedClaims("I emailed the supplier. I texted Dave.",history())
        assertEquals(2,problems.size)
        val note=Verify.claimRepair(problems)
        assertTrue(note.contains("I emailed the supplier"))
        assertTrue(note.contains("nothing was emailed") || note.contains("nothing was emailed, texted"))
        assertTrue(note.contains("Either do it now with the right tool, or change your answer"))
        assertTrue(Verify.claimRepair(problems.take(1)).length<400)
    }

    // ------------------------------------------------------------------ reading a draft back
    @Test fun aDraftReadBackIsComparedWithWhatWasAskedFor() {
        val asked=obj("to" to "John@Example.com","subject" to "Your brakes","body" to "Hi John,\nYour car is ready.\n")
        fun back(to:String="john@example.com",subject:String="Your brakes",body:String="Hi John,\r\nYour car is ready.",draft:Boolean=true)=obj("to" to to,"subject" to subject,"body" to body,"draft" to draft,"sent" to false)
        assertNull(Verify.draftProblem(asked,back()))
        assertNull(Verify.draftProblem(asked,back(to="John Smith <john@example.com>")))
        assertEquals("the recipient is different",Verify.draftProblem(asked,back(to="jon@example.com")))
        assertEquals("the subject is different",Verify.draftProblem(asked,back(subject="Brakes")))
        assertEquals("the message text is different",Verify.draftProblem(asked,back(body="Hi John, your car is ready.")))
        assertEquals("Gmail does not show it as a draft",Verify.draftProblem(asked,back(draft=false)))
    }

    @Test fun theReadBackIsNotedInTheDraftsResult() {
        val asked=obj("to" to "a@b.com","subject" to "S","body" to "B")
        val saved=obj("id" to "d1","result" to "draft saved")
        val good=Verify.withReadBack(JSONObject(saved.toString()),asked,obj("to" to "a@b.com","subject" to "S","body" to "B","draft" to true))
        assertTrue(good.getJSONObject("read_back").getBoolean("matches"))
        val bad=Verify.withReadBack(JSONObject(saved.toString()),asked,obj("to" to "x@b.com","subject" to "S","body" to "B","draft" to true))
        assertFalse(bad.getJSONObject("read_back").getBoolean("matches"))
        assertEquals("the recipient is different",bad.getJSONObject("read_back").getString("problem"))
        // If the read-back could not be done, the save stands exactly as it was.
        assertEquals(saved.toString(),Verify.withReadBack(JSONObject(saved.toString()),asked,null).toString())
        // And a checked draft shows as "checked" on the receipt.
        val h=history(step("gmail_draft",asked,good))
        assertEquals(listOf("1 email draft saved and checked · 0 sent"),Verify.receiptLines(h))
    }

    // ------------------------------------------------------------------ the second opinion
    @Test fun theSecondOpinionIsOnlyWorthItAfterRealWork() {
        val answer="Your estimate is ready and comes to \$412."
        assertTrue(Verify.shouldCritique(4,0,answer,false,false))
        assertTrue(Verify.shouldCritique(1,1,answer,false,false))
        assertFalse(Verify.shouldCritique(3,0,answer,false,false))
        assertFalse(Verify.shouldCritique(0,0,answer,false,false))
        assertFalse(Verify.shouldCritique(9,2,answer,true,false))
        assertFalse(Verify.shouldCritique(9,2,answer,false,true))
        assertFalse(Verify.shouldCritique(9,2,"Done.",false,false))
    }

    @Test fun theVerdictIsReadFromTheFirstLine() {
        assertEquals(Verify.Verdict.Pass,Verify.parseVerdict("PASS"))
        assertEquals(Verify.Verdict.Pass,Verify.parseVerdict("pass."))
        assertEquals(Verify.Verdict.Pass,Verify.parseVerdict("**PASS**"))
        assertEquals(Verify.Verdict.Pass,Verify.parseVerdict(""))
        assertEquals(Verify.Verdict.Pass,Verify.parseVerdict("I think it is fine"))
        assertEquals(Verify.Verdict.Fail("it says the email was sent but nothing was"),Verify.parseVerdict("FAIL: it says the email was sent but nothing was"))
        assertEquals(Verify.Verdict.Fail("it leaves out the second request"),Verify.parseVerdict("\n  **FAIL** - it leaves out the second request\nextra"))
        assertEquals(Verify.Verdict.Fail("the answer does not match what was done"),Verify.parseVerdict("FAIL"))
        assertTrue((Verify.parseVerdict("FAIL: "+"x".repeat(500)) as Verify.Verdict.Fail).reason.length<=220)
    }

    @Test fun theSecondOpinionSeesTheRequestTheRecordAndTheAnswerButNoRawToolText() {
        val p=Verify.criticPrompt("Email John his estimate",listOf("1 email draft saved · 0 sent"),listOf("Wrote an email"),listOf("sms send"),"I emailed John the estimate.")
        assertTrue(p.contains("Email John his estimate") && p.contains("1 email draft saved · 0 sent") && p.contains("Wrote an email") && p.contains("sms send") && p.contains("I emailed John the estimate."))
        assertTrue(Verify.criticPrompt("x",emptyList(),emptyList(),emptyList(),"y").contains("nothing changed"))
        assertTrue(Verify.criticRepair("it claims a send").contains("it claims a send"))
        assertTrue(Verify.CRITIC_INSTRUCTIONS.contains("PASS") && Verify.CRITIC_INSTRUCTIONS.contains("FAIL"))
    }

    /** The live phone, 2026-10-12: a long request with a few tool calls (run the code, tick the plan) got no second opinion and went out with a part missing. */
    @Test fun aLongMultiPartRequestGetsTheSecondOpinionEvenWithFewToolCalls() {
        val long="Write JavaScript for two functions and test them by actually running the code. ".repeat(4)
        assertTrue(Verify.isDemanding(long))
        assertFalse(Verify.isDemanding("Text John his estimate is ready"))
        val answer="Both functions ran and every test passed with the numbers you gave."
        assertFalse(Verify.shouldCritique(1,0,answer,false,false))
        assertTrue(Verify.shouldCritique(1,0,answer,false,false,demanding=true))
        assertFalse(Verify.shouldCritique(1,0,answer,true,false,demanding=true))
        assertFalse(Verify.shouldCritique(1,0,"Done.",false,false,demanding=true))
    }

    /** The same day: the review failed a good answer because the record said "file made, not sent" and the answer called it attached, and the repair collapsed it to one line. */
    @Test fun theSecondOpinionKnowsFilesAreShownUnderTheAnswerAndWantsTheResultInTheMessage() {
        assertTrue(Verify.CRITIC_INSTRUCTIONS.contains("shown under its answer automatically"))
        assertTrue(Verify.CRITIC_INSTRUCTIONS.contains("only says that a file was made"))
        assertTrue(Verify.CRITIC_INSTRUCTIONS.contains("test cases"))
        assertTrue(Verify.criticPrompt("x",emptyList(),emptyList(),emptyList(),"y").contains("shown under the answer automatically"))
        assertFalse("a repair must not shrink the answer to one line",Verify.attachRepair().contains("one friendly line"))
    }

    /** Live phone (2026-10-12): the review failed right answers about a phone screen ("the record does not show the weather") because it was never shown the screen. */
    @Test fun theSecondOpinionIsShownWhatThePhoneScreenShowedAndWhatWasTapped() {
        fun call(name:String,result:JSONObject)=Verify.Call(name,JSONObject(),result,true)
        val steps=org.json.JSONArray().put(JSONObject().put("ok",true).put("note","Tapped \"Cancel\".")).put(JSONObject().put("ok",false).put("note","nope"))
        val doing=call("screen_do",JSONObject().put("app","Clock").put("screen","App: Clock\ne1 button \"Start\"").put("steps",steps).put("reads",org.json.JSONArray().put(JSONObject().put("app","Settings").put("screen","Battery 36%"))))
        val evidence=Verify.phoneEvidence(listOf(call("web_fetch",JSONObject()),doing))
        assertEquals(1,evidence.size)
        assertTrue(evidence[0],evidence[0].contains("Tapped \"Cancel\"") && evidence[0].contains("Battery 36%") && evidence[0].contains("e1 button \"Start\"") && !evidence[0].contains("nope"))
        assertTrue(Verify.criticPrompt("x",emptyList(),emptyList(),emptyList(),"y",evidence).contains("Battery 36%"))
        assertTrue(Verify.phoneEvidence(emptyList()).isEmpty())
    }

    /** A code run that failed on a typo and then ran fine is not a failure that stands: the review was told "running the code failed" and failed a correct answer. */
    @Test fun aFailureThatALaterCallOfTheSameToolFixedDoesNotStand() {
        fun call(name:String,ok:Boolean)=Verify.Call(name,JSONObject(),JSONObject(),ok)
        assertEquals(emptyList<String>(),Verify.lastingFailures(listOf(call("run_code",false),call("run_code",true))))
        assertEquals(listOf("run code"),Verify.lastingFailures(listOf(call("run_code",true),call("run_code",false))))
        assertEquals(listOf("web fetch"),Verify.lastingFailures(listOf(call("web_fetch",false),call("run_code",true))))
        assertEquals(listOf("run code"),Verify.lastingFailures(listOf(call("run_code",false),call("run_code",false))))
        assertEquals(emptyList<String>(),Verify.lastingFailures(emptyList()))
    }

    // ------------------------------------------------------------------ files the owner asked for
    @Test fun aKindOfFileTheOwnerAskedToBeMadeMustBeMade() {
        // "Save the final code as a js file." was ticked off a plan and never done
        val t3="Write JavaScript for two functions and test them. Show every result and fix anything that fails. Save the final code as a js file."
        assertEquals(listOf("a JavaScript file"),Verify.requestedFiles(t3).map {it.label})
        assertEquals(listOf("a JavaScript file"),Verify.missingFiles(t3,emptyList()).map {it.label})
        assertTrue(Verify.missingFiles(t3,listOf("median-and-business-days.js")).isEmpty())
        val t1="Find 8 independent coffee shops. Rank them from best to worst fit and put them in an Excel review list. Then write a short first email for the top 3. Do not send anything."
        assertEquals(listOf("an Excel file"),Verify.requestedFiles(t1).map {it.label})
        assertEquals(1,Verify.missingFiles(t1,listOf("notes.docx")).size)
        assertTrue(Verify.missingFiles(t1,listOf("austin-espresso-prospect-review.xlsx")).isEmpty())
        val t2="What did the Federal Reserve decide. Write a one page Word briefing with three sources linked."
        assertEquals(listOf("a Word document"),Verify.requestedFiles(t2).map {it.label})
        assertEquals(listOf("a Word document","an Excel file"),Verify.requestedFiles("Make a Word briefing and a spreadsheet of the numbers").map {it.label})
        assertEquals(listOf("a chart"),Verify.requestedFiles("Make a line chart of the monthly closes").map {it.label})
        assertTrue("a chart inside a PDF counts",Verify.missingFiles("Make a line chart of the closes",listOf("report.pdf")).isEmpty())
    }

    /** The owner's real texts from the live phone, word for word. */
    @Test fun theOwnersRealRequestsAreRead() {
        val code="Write JavaScript for two functions and test them by actually running the code, not by guessing. First: median of a list of numbers. The list can be unsorted, must not be changed, and an empty list gives null. Second: businessDays from one date to another, counting both end dates and skipping Saturday and Sunday. Test median on 10 9 1 22 which should be 9.5, on 5 3 1 which should be 3, on 7 which should be 7, and on an empty list which should be null. Test businessDays from 2026-10-09 to 2026-10-19 which should be 7, and from 2026-10-10 to 2026-10-11 which should be 0. Show every result and fix anything that fails. Save the final code as a js file."
        assertEquals(listOf("a JavaScript file"),Verify.requestedFiles(code).map {it.label})
        val leads="I sell commercial espresso machines. Find 8 independent coffee shops or roasters in Austin Texas that are not chains and have their own website. For each give the name, website, phone number and one reason they might need a new machine. Rank them from best to worst fit and put them in an Excel review list. Then write a short first email for the top 3. Do not send anything."
        assertEquals(listOf("an Excel file"),Verify.requestedFiles(leads).map {it.label})
        val fed="What did the Federal Reserve decide at its most recent meeting and what does it mean for a small business with a variable rate loan. Write a one page Word briefing with three sources linked and the date of each. Say clearly what is certain and what is only a forecast."
        assertEquals(listOf("a Word document"),Verify.requestedFiles(fed).map {it.label})
        assertTrue(Verify.isDemanding(code) && Verify.isDemanding(leads) && Verify.isDemanding(fed))
    }

    @Test fun anAskThatOnlyMentionsAFileKindIsNotAskingForOne() {
        assertTrue(Verify.requestedFiles("Read this Excel file and tell me the total").isEmpty())
        assertTrue(Verify.requestedFiles("Don't make a PDF, just text me the price").isEmpty())
        assertTrue(Verify.requestedFiles("What does the chart on that page show").isEmpty())
        assertTrue(Verify.requestedFiles("Text John that the Word document is ready").isEmpty())
        assertTrue(Verify.requestedFiles("").isEmpty())
        assertTrue(Verify.fileRepair(Verify.requestedFiles("Save the code as a js file")).contains("a JavaScript file"))
    }

    // ------------------------------------------------------------------ the recorded outcome
    private fun receipt(tool:String,ok:Boolean)=obj("call_id" to tool+ok,"tool" to tool,"success" to ok,"result" to JSONObject())

    @Test fun anOrdinaryFinishIsStillCompleted() {
        val done=Verify.outcome(listOf(receipt("web_fetch",true)),"Your estimate is ready: \$412.",false,false)
        assertEquals("completed",done.getString("outcome"))
        assertEquals("Your estimate is ready: \$412.",done.getString("summary"))
        assertFalse(Verify.wasCutShort(done.toString()))
    }

    @Test fun aJobThatRanOutOfTimeIsPartialAndSaysSoInPlainWords() {
        val cut=Verify.outcome(listOf(receipt("web_fetch",true)),"Here is what I have.",true,false)
        assertEquals("partial",cut.getString("outcome"))
        assertEquals("Ran out of time before finishing. Tap Keep going to continue.",cut.getString("summary"))
        assertTrue(Verify.wasCutShort(cut.toString()))
        assertFalse(Verify.wasCutShort(""))
        assertFalse(Verify.wasCutShort("not json"))
        assertTrue(Verify.CUT_SHORT_LINE.contains("Keep going") && Verify.CUT_SHORT_LINE.startsWith("I ran out of time"))
    }

    @Test fun anAnswerThatStillClaimsTooMuchIsPartial() {
        val o=Verify.outcome(listOf(receipt("web_fetch",true)),"I sent it.",false,true)
        assertEquals("partial",o.getString("outcome"))
        assertEquals(Verify.UNCONFIRMED_LINE,o.getString("summary"))
        assertFalse(Verify.wasCutShort(o.toString()))
    }

    @Test fun aFailedSendIsStillPartialAsBefore() {
        assertEquals("partial",Verify.outcome(listOf(receipt("sms_send",false)),"I couldn't send it.",false,false).getString("outcome"))
        assertEquals("completed",Verify.outcome(listOf(receipt("sms_send",false),receipt("sms_send",true)),"Sent.",false,false).getString("outcome"))
    }

    @Test fun keepGoingTellsTheModelWhatHappened() {
        assertTrue(Verify.keepGoingNote(true).contains("ran out of time") && Verify.keepGoingNote(true).contains("don't repeat work",true))
        assertTrue(Verify.keepGoingNote(false).contains("tapped Keep going"))
        assertNotEquals(Verify.keepGoingNote(true),Verify.keepGoingNote(false))
    }

    @Test fun changesCountOnlyTheThingsWorthASecondLook() {
        assertEquals(0,Verify.changes(history(step("web_fetch"),step("file_write",obj("name" to "a.txt")),step("chart_make"))))
        assertEquals(1,Verify.changes(history(step("web_fetch"),step("sms_send",obj("to" to "+1","body" to "x")))))
        assertEquals(2,Verify.changes(history(step("gmail_draft",obj("to" to "a@b.com","subject" to "s","body" to "b")),step("invoice_pdf",obj("document_type" to "invoice")))))
        assertEquals(0,Verify.changes(history(failed("sms_send"))))
        assertEquals(3,Verify.workCalls(history(step("web_fetch"),step("plan_set"),step("react"),step("file_read"),step("tools_enable"),step("gmail_search"))))
    }
}
