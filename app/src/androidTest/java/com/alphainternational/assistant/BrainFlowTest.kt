package com.alphainternational.assistant

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * The agent loop with a scripted model: the visible plan, a request whose start stays the same, tool groups, recovery from a model slip, the
 * finish gate (a claim nothing supports, the second opinion), a job cut short by time and continued with Keep going. (Compile-checked by the
 * build; the integrator runs them on a device.)
 */
class BrainFlowTest {
    private fun message(text:String)=obj("output" to JSONArray().put(obj("type" to "message","role" to "assistant","content" to JSONArray().put(obj("type" to "output_text","text" to text)))))
    private fun calls(vararg calls:Triple<String,String,JSONObject>):JSONObject {
        val out=JSONArray()
        calls.forEach {(id,name,args)->out.put(obj("type" to "function_call","name" to name,"call_id" to id,"arguments" to args.toString()))}
        return obj("output" to out)
    }

    /** A scripted model that remembers what each round was given. */
    private class Scripted(val script:(round:Int,history:JSONArray,instructions:String,tools:List<Tool>)->JSONObject):AiGateway {
        val instructions=mutableListOf<String>();val tools=mutableListOf<List<String>>();val histories=mutableListOf<JSONArray>()
        var rounds=0
        override fun cancel() {}
        override suspend fun respond(model:String,history:JSONArray,instructions:String,tools:List<Tool>,onText:suspend (String)->Unit):JSONObject {
            delay(1)
            // (The learner and the second opinion talk to the same scripted model; they are not rounds of the job.)
            if(instructions.startsWith("You are a careful reviewer") || instructions.startsWith("You help a personal assistant bot learn")) return script(-1,history,instructions,tools)
            rounds++
            this.instructions+=instructions;this.tools+=listOf(tools.map {it.name});this.histories+=JSONArray(history.toString())
            return script(rounds,history,instructions,tools)
        }
        /** What the last message of a round said (the changing notes the phone adds at the end). */
        fun lastNote(round:Int)=histories[round-1].let {it.getJSONObject(it.length()-1).optString("content")}
    }

    private fun run(gateway:AiGateway,prompt:String,setup:(Controller)->Unit = {},done:Set<String> = setOf("Completed","Partial","Paused","Needs you","Needs review","Check result"),check:(Controller,String)->Unit) {
        val instrument=InstrumentationRegistry.getInstrumentation();val context=instrument.targetContext
        val store=SecureStore(context);val previous=store.load();var made:Controller?=null;var id=""
        try {
            instrument.runOnMainSync {
                made=Controller(context,gateway);made!!.saveConfig("chatgpt",obj("access_token" to "test-only-not-a-real-token"))
                setup(made!!)
                id=made!!.submit(prompt)!!
            }
            val c=made!!
            runBlocking {withTimeout(30_000) {while(c.tasks.first {it.id==id}.status !in done) delay(20)}}
            check(c,id)
        } finally {instrument.runOnMainSync {made?.scope?.cancel()};store.save(previous)}
    }

    /** A plan is written, kept up to date, shown as progress, and the request's start does not change between rounds. */
    @Test fun aMultiStepJobKeepsAVisiblePlanAndAStableStart() {
        val gateway=Scripted {round,_,_,_->
            when(round) {
                1->calls(Triple("p1","plan_set",obj("items" to arr(listOf("Make the note","Check the note","Tell the owner")))))
                2->calls(Triple("w1","file_write",obj("name" to "brain-note.txt","text" to "Hello")))
                3->calls(Triple("u1","plan_update",obj("id" to "1","status" to "done")),Triple("u2","plan_update",obj("id" to "2","status" to "done")),Triple("u3","plan_update",obj("id" to "3","status" to "done")))
                else->message("The note is saved as brain-note.txt.")
            }
        }
        run(gateway,"Create a local note called brain-note.txt and then double check it") {c,id->
            try {
                assertEquals(c.tasks.first {it.id==id}.let {"detail="+it.detail+" completion="+it.completion},"Completed",c.tasks.first {it.id==id}.status)
                val progress=c.progressOf(id)!!
                assertEquals(3,progress.total);assertEquals(3,progress.done);assertTrue(progress.finished)
                // The progress line rides at the very end of the model's input (not in the saved history) and moves as steps are done.
                assertTrue(gateway.lastNote(2),gateway.lastNote(2).contains("Progress: 0 of 3 done, now: Make the note."))
                assertTrue(gateway.lastNote(4),gateway.lastNote(4).contains("Progress: all 3 steps are done."))
                assertFalse(c.tasks.first {it.id==id}.history.contains("Progress: 0 of 3"))
                // The instructions and the tool list are the same text on every round.
                assertEquals(1,gateway.instructions.toSet().size)
                assertEquals(1,gateway.tools.toSet().size)
                assertEquals("developer",JSONArray(gateway.histories[1].toString()).let {it.getJSONObject(it.length()-1).getString("role")})
                // What the job really did is listed under the answer.
                val reply=c.messages.last {it.taskId==id && it.role=="assistant"}
                assertTrue(reply.receipt,reply.receipt.contains("File saved: brain-note.txt"))
            } finally {c.file("brain-note.txt").delete()}
        }
    }

    /** Finishing with plan steps still open: the model is reminded once, then the answer goes out. */
    @Test fun openStepsAreRaisedOnceAtTheEnd() {
        val gateway=Scripted {round,_,_,_->
            when(round) {
                1->calls(Triple("p1","plan_set",obj("items" to arr(listOf("Look it up","Write it up")))))
                2->message("Here is what I found.")
                3->calls(Triple("u1","plan_update",obj("id" to "1","status" to "done")),Triple("u2","plan_update",obj("id" to "2","status" to "done")))
                else->message("Here is what I found, all written up.")
            }
        }
        run(gateway,"Look up how often to rotate tires and write it up") {c,id->
            assertEquals(c.tasks.first {it.id==id}.let {"detail="+it.detail+" completion="+it.completion},"Completed",c.tasks.first {it.id==id}.status)
            assertEquals(4,gateway.rounds)
            assertTrue(JSONArray(c.tasks.first {it.id==id}.history).objects().any {it.optString("role")=="developer" && it.optString("content").contains("still open")})
            // The first answer was held back: only the second reached the chat.
            assertEquals(1,c.messages.count {it.taskId==id && it.role=="assistant" && it.text.contains("Here is what I found")})
        }
    }

    /** A tool that doesn't exist, and details that aren't valid, come back to the model as errors; the job carries on. */
    @Test fun aModelSlipIsAnErrorForTheModelNotTheEndOfTheJob() {
        val gateway=Scripted {round,history,_,_->
            when(round) {
                1->calls(Triple("x1","made_up_tool",obj()),Triple("x2","file_write",JSONObject()))
                2-> {
                    val outputs=history.objects().filter {it.optString("type")=="function_call_output"}.associate {it.optString("call_id") to it.optString("output")}
                    assertTrue(outputs.getValue("x1"),outputs.getValue("x1").contains("There is no tool called"))
                    message("I could not use that tool, so here is the answer directly: nothing to do.")
                }
                else->message("unused")
            }
        }
        run(gateway,"Do something unusual") {c,id->
            // (The write with empty details really failed, so the job honestly ends Partial. What matters is that the slips did not pause it.)
            assertTrue(c.tasks.first {it.id==id}.status,c.tasks.first {it.id==id}.status in setOf("Completed","Partial"))
            assertEquals(2,gateway.rounds)
        }
    }

    @Test fun detailsThatAreNotJsonAreSentBackToBeFixed() {
        val gateway=Scripted {round,history,_,_->
            when(round) {
                1->obj("output" to JSONArray().put(obj("type" to "function_call","name" to "file_write","call_id" to "b1","arguments" to "{\"name\":\"a.txt\",\"text\":\"half")))
                2-> {
                    val out=history.objects().first {it.optString("type")=="function_call_output" && it.optString("call_id")=="b1"}.optString("output")
                    assertTrue(out,out.contains("were not valid") && out.contains("Nothing was done"))
                    message("Understood, I will not write that file.")
                }
                else->message("unused")
            }
        }
        run(gateway,"Write a small file") {c,id->
            assertEquals(c.tasks.first {it.id==id}.let {"detail="+it.detail+" completion="+it.completion},"Completed",c.tasks.first {it.id==id}.status)
            assertFalse(c.file("a.txt").exists())
        }
    }

    /** The plan's usage limit used to leave a job paused with Keep going / Never mind (a 50-email job sat that way for four hours); now it waits and carries on by itself. */
    @Test fun aUsageLimitIsWaitedOutAndTheJobFinishesByItself() {
        var thrown=false
        val gateway=Scripted {_,_,_,_->
            if(!thrown) {thrown=true;throw ApiFailure(429,"ChatGPT returned HTTP 429: you have hit your usage limit")}
            message("Done after the wait.")
        }
        run(gateway,"Write me a short thank-you note",setup={it.retryDelayScale=0.003},done=setOf("Completed")) {c,id->
            assertEquals(c.tasks.first {it.id==id}.let {"detail="+it.detail},"Completed",c.tasks.first {it.id==id}.status)
            assertTrue(c.messages.any {it.taskId==id && it.role=="assistant" && it.text.contains("not a sign-in problem")})
            assertTrue(c.messages.last {it.taskId==id && it.role=="assistant"}.text.contains("Done after the wait"))
        }
    }

    /**
     * On 2026-10-16 a group message woke eleven bots, all hit the plan's sharing limit together and every one paused; the owner took it for a broken OAuth connection. Now the limit's own
     * reset time is waited for once, for everyone, the owner is told in one text that it is a plan limit, and nothing new starts until it clears.
     */
    @Test fun aPlanLimitHoldsEverythingTogetherAndReleasesItAtTheResetTime() {
        var thrown=false
        val gateway=Scripted {_,_,_,_->
            if(!thrown) {thrown=true;throw ApiFailure(429,"ChatGPT returned HTTP 429: The ChatGPT user has reached their Subscription Sharing usage limit. Ask the user to try again after "+java.time.Instant.now().plusSeconds(300).toString())}
            message("Done after the limit.")
        }
        val instrument=InstrumentationRegistry.getInstrumentation();val context=instrument.targetContext
        val store=SecureStore(context);val previous=store.load();var made:Controller?=null;var first="";var second=""
        try {
            instrument.runOnMainSync {
                made=Controller(context,gateway);made!!.saveConfig("chatgpt",obj("access_token" to "test-only-not-a-real-token"))
                made!!.retryDelayScale=0.01
                first=made!!.submit("Write me a short thank-you note")!!
            }
            val c=made!!
            runBlocking {withTimeout(30_000) {while(c.limitedUntil==0L) delay(20)}}
            assertTrue(c.planLimitNote().contains("not a sign-in problem"))
            // a text sent while the limit is on does not start, and the first job is held, not retried
            instrument.runOnMainSync {second=c.submit("Write me a short hello note")!!}
            assertEquals("Queued",c.tasks.first {it.id==second}.status)
            assertEquals("Paused",c.tasks.first {it.id==first}.status)
            runBlocking {withTimeout(30_000) {while(c.tasks.first {it.id==first}.status!="Completed" || c.tasks.first {it.id==second}.status!="Completed") delay(20)}}
            assertEquals(0L,c.limitedUntil)
            assertEquals("one text for the whole group",1,c.messages.count {it.role=="assistant" && it.text.contains("not a sign-in problem")})
        } finally {instrument.runOnMainSync {made?.scope?.cancel()};store.save(previous)}
    }

    /**
     * The real limit message names no time ("...try again after their usage limit resets or use an API key instead"). Then Alpha checks again after 5 minutes, and if that check
     * hits the limit again after 10, so a limit that lasts hours is not poked every five minutes. (The test runs the waits at 1/100 of their length.)
     */
    @Test fun aLimitThatNamesNoTimeIsCheckedAgainLessAndLessOften() {
        var thrown=0
        val gateway=Scripted {_,_,_,_->
            if(thrown<2) {thrown++;throw IllegalStateException("The ChatGPT user has reached their Subscription Sharing usage limit. Ask the user to try again after their usage limit resets or use an API key instead.")}
            message("Done after two checks.")
        }
        val instrument=InstrumentationRegistry.getInstrumentation();val context=instrument.targetContext
        val store=SecureStore(context);val previous=store.load();var made:Controller?=null;var first=""
        try {
            instrument.runOnMainSync {
                made=Controller(context,gateway);made!!.saveConfig("chatgpt",obj("access_token" to "test-only-not-a-real-token"))
                made!!.retryDelayScale=0.01
                first=made!!.submit("Write me a short thank-you note")!!
            }
            val c=made!!
            runBlocking {withTimeout(30_000) {while(c.limitedUntil==0L) delay(10)}}
            val firstGap=c.limitedUntil-System.currentTimeMillis()
            assertTrue("a guess is said to be a guess",c.planLimitNote().contains("doesn't say when"))
            val firstUntil=c.limitedUntil
            runBlocking {withTimeout(30_000) {while(c.limitedUntil==0L || c.limitedUntil==firstUntil) delay(10)}}
            val secondGap=c.limitedUntil-System.currentTimeMillis()
            assertTrue("first wait about 3 s (was $firstGap ms)",firstGap in 1_500..3_200)
            assertTrue("second wait is longer ($firstGap ms then $secondGap ms)",secondGap>firstGap*3/2)
            runBlocking {withTimeout(30_000) {while(c.tasks.first {it.id==first}.status!="Completed") delay(20)}}
            assertEquals(0L,c.limitedUntil)
        } finally {instrument.runOnMainSync {made?.scope?.cancel()};store.save(previous)}
    }

    /** An overloaded AI service paused a live job twice on 2026-10-14; it now tries again by itself. */
    @Test fun anOverloadedServiceIsRetriedByItselfAndTheJobFinishes() {
        var thrown=0
        val gateway=Scripted {_,_,_,_->
            if(thrown<2) {thrown++;throw IllegalStateException("Our servers are currently overloaded. Please try again later.")}
            message("Done after the overload.")
        }
        run(gateway,"Write me a short thank-you note",setup={it.retryDelayScale=0.003},done=setOf("Completed")) {c,id->
            assertEquals(c.tasks.first {it.id==id}.let {"detail="+it.detail},"Completed",c.tasks.first {it.id==id}.status)
            assertTrue(c.messages.last {it.taskId==id && it.role=="assistant"}.text.contains("Done after the overload"))
        }
    }

    /** A Google sign-in that Google rejected was still shown as connected, so the bot could neither use Gmail nor be told to reconnect it. */
    @Test fun aGoogleSignInThatGoogleRejectedIsNotShownAsConnected() {
        val gateway=Scripted {_,_,_,_->message("ok")}
        run(gateway,"hello",setup={it.saveConfig("google",obj("connected" to true,"scopes" to arr(GoogleAccess.groups.getValue("Gmail"))))}) {c,_->
            fun gmailConnected()=c.capabilities().getJSONArray("services").objects().first {it.getString("service")=="gmail"}.getBoolean("connected")
            assertTrue(gmailConnected())
            c.saveConfig("google",c.config("google").put("broken",true))
            assertFalse(gmailConnected())
        }
    }

    /** A reply cut off by the length limit is asked again in smaller pieces; the job does not pause. */
    @Test fun aCutOffReplyIsAskedAgainInSmallerPieces() {
        var thrown=false
        val gateway=Scripted {round,_,_,_->
            if(round==1 && !thrown) {thrown=true;throw ModelCutOff("ChatGPT's reply was cut off by its length limit.")}
            message("Short and complete.")
        }
        run(gateway,"Write me a very long report") {c,id->
            assertEquals(c.tasks.first {it.id==id}.let {"detail="+it.detail+" completion="+it.completion},"Completed",c.tasks.first {it.id==id}.status)
            assertTrue(gateway.lastNote(gateway.rounds),gateway.lastNote(gateway.rounds).contains("smaller pieces"))
        }
    }

    /** Only the tools the request is about are sent; others are switched on by the model, and the start of the list never changes. */
    @Test fun toolGroupsComeOnByRequestOrByTheModelAndOnlyGrowAtTheEnd() {
        val gateway=Scripted {round,_,_,_->
            when(round) {
                1->calls(Triple("e1","tools_enable",obj("group" to "documents")))
                else->message("Switched on.")
            }
        }
        run(gateway,"What is a good tire pressure for a Camry?") {c,id->
            assertEquals(c.tasks.first {it.id==id}.let {"detail="+it.detail+" completion="+it.completion},"Completed",c.tasks.first {it.id==id}.status)
            val first=gateway.tools[0];val second=gateway.tools[1]
            listOf("studio_start","media_job","run_shell","game_template","telnyx_calls","invoice_pdf").forEach {assertFalse(it,it in first)}
            assertTrue("web_fetch" in first && "tools_enable" in first && "plan_set" in first)
            assertTrue("invoice_pdf" in second)
            assertEquals(first,second.take(first.size))
            assertTrue(first.size<80)
            assertFalse(gateway.instructions[0].contains("VIDEOS: media_job") || gateway.instructions[0].contains("WEBSITES, WEB APPS") || gateway.instructions[0].contains("invoice_pdf makes invoices"))
            assertTrue(gateway.instructions[1].contains("invoice_pdf makes invoices"))
        }
    }

    @Test fun aRequestAboutAVideoStartsWithTheVideoTools() {
        val gateway=Scripted {_,_,_,_->message("I can do that.")}
        run(gateway,"Make a 15 second video reel for the brake special") {c,id->
            assertEquals(c.tasks.first {it.id==id}.let {"detail="+it.detail+" completion="+it.completion},"Completed",c.tasks.first {it.id==id}.status)
            assertTrue("media_job" in gateway.tools[0] && "video_contact_sheet" in gateway.tools[0])
            assertTrue(gateway.instructions[0].contains("VIDEOS: media_job") && gateway.instructions[0].contains("MUSIC AND SOUNDS"))
        }
    }

    /** An answer that claims an email was sent when nothing was sent is held back once; the model must do it or say so. */
    @Test fun aClaimNothingSupportsIsHeldBackAndFixedOnce() {
        val gateway=Scripted {round,_,_,_->
            when(round) {
                1->message("I sent the email to John about his brakes.")
                else->message("I have not sent anything yet. Want me to save a draft for John?")
            }
        }
        run(gateway,"Tell John his brakes are done") {c,id->
            assertEquals(c.tasks.first {it.id==id}.let {"detail="+it.detail+" completion="+it.completion},"Completed",c.tasks.first {it.id==id}.status)
            assertEquals(2,gateway.rounds)
            val note=JSONArray(c.tasks.first {it.id==id}.history).objects().first {it.optString("role")=="developer"}.optString("content")
            assertTrue(note,note.contains("Check before you finish") && note.contains("nothing was emailed"))
            val texts=c.messages.filter {it.taskId==id && it.role=="assistant"}.map {it.text}
            assertTrue(texts.none {it.contains("I sent the email to John")})
            assertTrue(texts.any {it.contains("I have not sent anything yet")})
        }
    }

    /**
     * The owner's screenshot (2026-10-12): asked for a file it made earlier, the bot looked in its folder and wrote "It is attached and displayed above" with nothing attached.
     * It is held back once, attaches the file with files_show, and the answer that goes out is one friendly line with the file under it.
     */
    @Test fun anAnswerThatSaysAFileIsAttachedWhenNothingIsGetsTheFileAttached() {
        val gateway=Scripted {round,history,_,_->
            val text=history.toString()
            when {
                text.contains("Attached to your answer") -> message("Here you go 👍")
                text.contains("nothing is attached to this answer") -> calls(Triple("show1","files_show",obj("names" to "old-chart.png")))
                round==1 -> calls(Triple("list1","files_list",obj()))
                else -> message("The chart is confirmed available now. It is attached and displayed above.")
            }
        }
        run(gateway,"Send me the S and P chart again",setup={c->
            java.io.File(c.workspaceDir,c.workspace.lowercase()).apply {mkdirs()}.let {java.io.File(it,"old-chart.png").writeBytes(ByteArray(64) {1})}
        }) {c,id->
            assertEquals("Completed",c.tasks.first {it.id==id}.status)
            assertEquals(listOf("old-chart.png"),c.artifactsFor(id))
            val reply=c.messages.last {it.taskId==id && it.role=="assistant"}.text
            assertEquals("Here you go 👍",reply)
            assertTrue("held back once for the claim",JSONArray(c.tasks.first {it.id==id}.history).objects().count {it.optString("role")=="developer" && it.optString("content").contains("nothing is attached")}==1)
        }
    }

    /** Just listing its folder attaches nothing (old files were being dragged in), and a file that does not exist says so. */
    @Test fun listingFilesAttachesNothingAndAMissingFileIsSaidPlainly() {
        val gateway=Scripted {round,history,_,_->
            val text=history.toString()
            when {
                text.contains("None of those files exist") -> message("I couldn't find that one. Want me to make it again?")
                text.contains("file_names") -> calls(Triple("show1","files_show",obj("names" to "ghost.pdf")))
                else -> calls(Triple("list1","files_list",obj()))
            }
        }
        run(gateway,"Send me the ghost report",setup={c->java.io.File(c.workspaceDir,c.workspace.lowercase()).apply {mkdirs()}.let {java.io.File(it,"real-one.pdf").writeBytes(ByteArray(64) {1})}}) {c,id->
            assertEquals("Completed",c.tasks.first {it.id==id}.status)
            assertTrue(c.artifactsFor(id).isEmpty())
            assertTrue(c.messages.last {it.taskId==id && it.role=="assistant"}.text.contains("couldn't find"))
        }
    }

    /**
     * The owner's screenshot (2026-10-12): asked for the S&P chart again after the chart fix, the bot used files_show to hand back the OLD chart (12 of 24 months,
     * squeezed axis). A chart picture an older chart maker drew is refused with a plain reason, the bot draws it again, and the new picture carries today's stamp.
     */
    @Test fun anOldChartPictureIsNeverHandedBackAndTheChartIsDrawnAgain() {
        val gateway=Scripted {_,history,_,_->
            val text=history.toString()
            when {
                text.contains("Chart made") -> message("Here is the new chart 👍")
                text.contains("older, poorer chart maker") -> calls(Triple("chart1","chart_make",obj("type" to "line","title" to "Test closes","data" to "Jan: 10\nFeb: 12\nMar: 11\nApr: 15")))
                else -> calls(Triple("show1","files_show",obj("names" to "chart-test-closes-0000.png")))
            }
        }
        run(gateway,"Get the monthly closes again",setup={c->
            java.io.File(c.workspaceDir,c.workspace.lowercase()).apply {mkdirs()}.let {java.io.File(it,"chart-test-closes-0000.png").writeBytes(ByteArray(300) {1})}
        }) {c,id->
            assertEquals("Completed",c.tasks.first {it.id==id}.status)
            val shown=c.artifactsFor(id)
            assertEquals(1,shown.size)
            assertTrue("a new chart, not the old file: $shown",shown.first().startsWith("chart-") && shown.first()!="chart-test-closes-0000.png")
            assertEquals(Drawings.CHART_ENGINE,Drawings.chartEngine(java.io.File(java.io.File(c.workspaceDir,c.workspace.lowercase()),shown.first())))
            assertEquals("Here is the new chart 👍",c.messages.last {it.taskId==id && it.role=="assistant"}.text)
        }
    }

    /**
     * The owner's live test (2026-10-12): "Save the final code as a js file." The bot ticked it off its plan, never saved it and answered. A kind of file the owner named that was never
     * made is asked for once; the bot saves it and the answer that goes out has the file under it.
     */
    @Test fun aFileTheOwnerAskedForThatWasNeverMadeIsAskedForOnceAndThenSaved() {
        val gateway=Scripted {_,history,_,_->
            val text=history.toString()
            when {
                text.contains("File saved on this phone") -> message("The median of 10 9 1 22 is 9.5, as you expected.")
                text.contains("none was made in this job") -> calls(Triple("save1","file_write",obj("name" to "final-code.js","text" to "function median(a){const s=[...a].sort((x,y)=>x-y);const m=s.length>>1;return s.length%2?s[m]:(s[m-1]+s[m])/2}")))
                else -> message("The median of 10 9 1 22 is 9.5.")
            }
        }
        run(gateway,"Write a median function and save the final code as a js file") {c,id->
            assertEquals("Completed",c.tasks.first {it.id==id}.status)
            assertEquals(listOf("final-code.js"),c.artifactsFor(id))
            assertEquals("The median of 10 9 1 22 is 9.5, as you expected.",c.messages.last {it.taskId==id && it.role=="assistant"}.text)
            assertEquals("asked once",1,JSONArray(c.tasks.first {it.id==id}.history).objects().count {it.optString("role")=="developer" && it.optString("content").contains("none was made in this job")})
        }
    }

    /** If the model insists, the answer goes out with a plain warning and the job is Partial, never a second repair. */
    @Test fun aModelThatInsistsGetsAHonestWarningInsteadOfALoop() {
        val gateway=Scripted {_,_,_,_->message("I sent the email to John about his brakes.")}
        run(gateway,"Tell John his brakes are done") {c,id->
            assertEquals("Partial",c.tasks.first {it.id==id}.status)
            assertEquals(2,gateway.rounds)
            val reply=c.messages.last {it.taskId==id && it.role=="assistant"}.text
            assertTrue(reply,reply.contains(Verify.UNCONFIRMED_LINE))
        }
    }

    /** After real work, a quick second opinion can send the answer back once. */
    @Test fun theSecondOpinionCanSendAnAnswerBackOnce() {
        var opinions=0
        val gateway=Scripted {round,history,instructions,_->
            if(round==-1 && instructions.startsWith("You are a careful reviewer")) {opinions++;message(if(opinions==1) "FAIL: it leaves out the price" else "PASS")}
            else when(round) {
                1->calls(*(1..4).map {Triple("w$it","file_write",obj("name" to "brain-part$it.txt","text" to "x"))}.toTypedArray())
                2->message("All four notes are saved. Done.")
                else->message("All four notes are saved, and the price is \$25. Done.")
            }
        }
        run(gateway,"Make four little notes and tell me the price",setup={it.critic=true}) {c,id->
            try {
                assertEquals(c.tasks.first {it.id==id}.let {"detail="+it.detail+" completion="+it.completion},"Completed",c.tasks.first {it.id==id}.status)
                assertEquals(1,opinions)
                assertEquals(3,gateway.rounds)
                assertTrue(JSONArray(c.tasks.first {it.id==id}.history).objects().any {it.optString("content").contains("it leaves out the price")})
                assertTrue(c.messages.last {it.taskId==id && it.role=="assistant"}.text.contains("the price is"))
            } finally {(1..4).forEach {c.file("brain-part$it.txt").delete()}}
        }
    }

    /** Running out of time: the answer says so, the job is Partial and offers Keep going, and Keep going finishes it. */
    @Test fun aJobCutShortByTimeSaysSoAndKeepGoingFinishesIt() {
        val gateway=Scripted {round,_,_,tools->
            if(tools.isEmpty() && round>0) message("Here is what I have so far.")
            else if(round==1) message("Here is what I have so far.")
            else message("Now I finished the rest.")
        }
        val instrument=InstrumentationRegistry.getInstrumentation();val context=instrument.targetContext
        val store=SecureStore(context);val previous=store.load();var made:Controller?=null;var id=""
        try {
            instrument.runOnMainSync {
                made=Controller(context,gateway);made!!.saveConfig("chatgpt",obj("access_token" to "test-only-not-a-real-token"))
                made!!.budgetOverride=AgentRules.Budget(0,0)
                id=made!!.submit("Research tire brands in detail")!!
            }
            val c=made!!
            runBlocking {withTimeout(30_000) {while(c.tasks.first {it.id==id}.status !in setOf("Partial","Completed","Paused")) delay(20)}}
            assertEquals("Partial",c.tasks.first {it.id==id}.status)
            assertTrue(c.isCutShort(id))
            assertTrue(c.messages.last {it.taskId==id && it.role=="assistant"}.text.contains("Tap Keep going"))
            // Keep going: the model is told, the old finish is cleared, and this time it has the time to finish.
            instrument.runOnMainSync {c.budgetOverride=null;c.resume(id)}
            runBlocking {withTimeout(30_000) {while(c.messages.none {it.taskId==id && it.role=="assistant" && it.text.contains("Now I finished the rest.")} || c.tasks.first {it.id==id}.status !in setOf("Completed","Partial","Paused")) delay(20)}}
            assertEquals(c.tasks.first {it.id==id}.let {"detail="+it.detail+" completion="+it.completion},"Completed",c.tasks.first {it.id==id}.status)
            assertFalse(c.isCutShort(id))
            assertTrue(JSONArray(c.tasks.first {it.id==id}.history).objects().any {it.optString("role")=="developer" && it.optString("content").contains("tapped Keep going")})
            assertTrue(c.messages.last {it.taskId==id && it.role=="assistant"}.text.contains("Now I finished the rest."))
        } finally {instrument.runOnMainSync {made?.scope?.cancel()};store.save(previous)}
    }
}
