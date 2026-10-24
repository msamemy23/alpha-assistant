package com.alphainternational.assistant

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Teamwork cases with a scripted AI (the planner, each bot and the lead's write-up are all scripted) and a pretend shop database:
 * the scenarios S1-S8 of AUDIT_TEAMWORK.md as far as a script allows. The scripted AI sees exactly what the real one would: the instructions, the
 * conversation and the tools, so these check the app's plumbing (who is asked, who waits, what is posted), not the model's cleverness.
 */
class TeamCaseFlowTest {
    private fun reply(text:String)=obj("output" to JSONArray().put(obj("type" to "message","role" to "assistant","content" to JSONArray().put(obj("type" to "output_text","text" to text)))))
    private fun call(name:String,id:String,args:JSONObject)=obj("output" to JSONArray().put(obj("type" to "function_call","name" to name,"call_id" to id,"arguments" to args.toString())))
    private fun JSONArray.hasOutput(id:String)=objects().any {it.optString("type")=="function_call_output" && it.optString("call_id")==id}
    private fun JSONArray.output(id:String)=objects().first {it.optString("type")=="function_call_output" && it.optString("call_id")==id}.getString("output")
    private fun JSONArray.users()=objects().filter {it.optString("role")=="user"}.map {it.optString("content")}

    /** The planner, every bot's job and the lead's write-up, told apart by the instructions they were sent. */
    private class TeamAi(val planner:suspend (JSONArray)->String,val job:suspend (String,JSONArray,String,List<Tool>)->JSONObject,val merge:suspend (JSONArray)->String):AiGateway {
        val log=CopyOnWriteArrayList<String>()
        override fun cancel() {}
        override suspend fun respond(model:String,history:JSONArray,instructions:String,tools:List<Tool>,onText:suspend (String)->Unit):JSONObject {
            val text=when {
                instructions.startsWith("You are the chief planner") -> {log+="plan";planner(history)}
                instructions.contains("the lead of the owner's team") -> {log+="merge";merge(history)}
                else -> {
                    val bot=Regex("You are the bot named (.+?)\\. Its job").find(instructions)?.groupValues?.get(1)?:return obj("output" to JSONArray().put(obj("type" to "message","role" to "assistant","content" to JSONArray().put(obj("type" to "output_text","text" to "ok")))))
                    log+="job:"+bot
                    return job(bot,history,instructions,tools)
                }
            }
            return obj("output" to JSONArray().put(obj("type" to "message","role" to "assistant","content" to JSONArray().put(obj("type" to "output_text","text" to text)))))
        }
    }

    private val customers=listOf(obj("t" to "customers","r" to obj("id" to "c1","first_name" to "Creyton","last_name" to "Smith","phone" to "555-0142")))
    private val vehicles=listOf(obj("t" to "vehicles","r" to obj("id" to "v1","customer_id" to "c1","year" to 2018,"make" to "Honda","model" to "Accord","plate" to "7ABC123")))
    private val documents=listOf(obj("t" to "documents","r" to obj("id" to "d1","doc_number" to "INV-69","customer_name" to "Creyton Smith","customer_id" to "c1","total" to 1250)))
    private fun col(t:String,n:String,type:String)=obj("table_name" to t,"column_name" to n,"data_type" to type)
    private val schema=DataMapBuilder.build(listOf(col("customers","id","uuid"),col("customers","first_name","text"),col("customers","last_name","text"),col("customers","phone","text"),
        col("vehicles","id","uuid"),col("vehicles","customer_id","uuid"),col("vehicles","year","integer"),col("vehicles","make","text"),col("vehicles","model","text"),col("vehicles","plate","text"),
        col("documents","id","uuid"),col("documents","doc_number","text"),col("documents","customer_name","text"),col("documents","customer_id","uuid"),col("documents","total","numeric")),
        emptyList(),emptyList(),System.currentTimeMillis())

    private fun <T> withTeam(gateway:AiGateway,signedIn:List<String> = emptyList(),body:(Controller)->T):T {
        val instrument=InstrumentationRegistry.getInstrumentation();val context=instrument.targetContext
        val store=SecureStore(context);val previous=store.load();var made:Controller?=null
        try {
            if(signedIn.isNotEmpty()) store.save(JSONObject(previous.toString()).put("signed_in",arr(signedIn.map {obj("site" to it,"at" to 1)})))
            instrument.runOnMainSync {
                val c=Controller(context,gateway);made=c
                c.saveConfig("chatgpt",obj("access_token" to "test-only-not-a-real-token"))
                // Every test starts with an empty team chat: chats and cases from the tests before it are not part of this one.
                c.clearChat("all")
                listOf("Customer Desk" to "Customers, vehicles and invoices in the shop database","Parts" to "Finds parts and prices online").forEach {(name,role)->
                    if(c.bots.none {it.name==name}) c.createBot(name,role,switch=false)
                }
                // A pretend shop database: the layout is saved already and every look-up is answered from the fixtures above.
                c.saveConfig("supabase_management",obj("connected" to true,"project" to "test"))
                c.saveConfig("data_map",obj("schema" to schema.json()))
                c.databaseOverride={q->when {
                    q.contains("'customers' as t") -> customers
                    q.contains("'vehicles' as t") -> vehicles
                    q.contains("'documents' as t") -> documents
                    else -> emptyList()
                }}
            }
            return body(made!!)
        } finally {instrument.runOnMainSync {made?.scope?.cancel()};store.save(previous)}
    }
    private fun until(timeoutMs:Long=60_000,what:String="the condition",cond:()->Boolean) {
        runBlocking {try {withTimeout(timeoutMs) {while(!cond()) delay(25)}} catch(e:kotlinx.coroutines.TimeoutCancellationException) {fail("Timed out waiting for $what")}}
    }
    private fun send(c:Controller,text:String) {InstrumentationRegistry.getInstrumentation().runOnMainSync {assertTrue(c.submitTeam(text))}}
    private fun steps(vararg s:String)="""{"steps":[""" + s.joinToString(",") + """],"answer":"merge","case":"new"}"""
    private fun step(id:String,bot:String,goal:String,vararg after:String)="""{"id":"$id","bot":"$bot","do":"$goal","after":[${after.joinToString(",") {"\"$it\""}}]}"""
    private fun Controller.visible()=teamMessages.filter {it.role=="assistant" && it.kind==""}
    private fun Controller.newCase(since:Long)=cases.first {it.created>=since}

    // S1: "Find brake pads for Creyton's car". The car is in another table than the invoice. Nobody asks for year/make/model; the parts step waits for the
    // customer step, gets its result, and the owner gets ONE answer whose numbers are the ones that were found.
    @Test fun s1_theCarIsLookedUpThenThePartsAreFoundAndOneAnswerComesBack() {
        var partsSawTheCar=false;var partsDebug=""
        val ai=TeamAi(planner={steps(step("s1","Customer Desk","Find Creyton's car"),step("s2","Parts","Find brake pads for that car","s1"))},
            job={bot,history,instructions,_->when(bot) {
                "Customer Desk" -> if(history.hasOutput("find-1")) reply("ANSWER: Creyton Smith drives a 2018 Honda Accord.") else call("data_find","find-1",obj("query" to "Creyton"))
                "Parts" -> {
                    partsSawTheCar=history.toString().contains("Already found by the team: Customer Desk: ") && history.toString().contains("Creyton Smith drives a 2018 Honda Accord") && (instructions+history.toString()).contains("CASE FILE")
                    partsDebug="found="+history.toString().contains("Already found by the team")+" case="+(instructions+history.toString()).contains("CASE FILE")+" users="+history.users().joinToString(" || ") {it.take(300)}
                    reply("Front brake pads for the 2018 Honda Accord start at \$39.99 at AutoZone.")
                }
                else -> reply("ok")
            }},
            merge={"# Front pads for Creyton's 2018 Honda Accord start at \$39.99\nCreyton Smith drives a 2018 Honda Accord, and AutoZone lists front pads from \$39.99."})
        withTeam(ai) {c->
            val since=System.currentTimeMillis()
            send(c,"Find brake pads for Creyton's car")
            until(what="the case to finish") {c.cases.any {it.created>=since && it.state=="done"}}
            val case=c.newCase(since)
            // The customer step looked in the records (code-run look-up); the parts step started only after it, with its result.
            assertTrue(c.tasks.first {it.id==case.steps[0].taskId}.trace.contains("data find"))
            assertTrue(partsDebug,partsSawTheCar)
            assertTrue(ai.log.indexOf("job:Parts")>ai.log.lastIndexOf("job:Customer Desk"))
            assertEquals(1,ai.log.count {it=="plan"});assertEquals(1,ai.log.count {it=="merge"})
            // The owner sees ONE message, from the lead; each step is a small collapsed line. No bot asked the owner anything.
            val said=c.visible()
            assertEquals(1,said.size)
            assertEquals(c.leadBot(),said[0].bot)
            assertTrue(said[0].text,said[0].text.contains("\$39.99") && said[0].text.contains("2018 Honda Accord"))
            assertEquals(2,c.teamMessages.count {it.kind=="step"})
            assertFalse(c.teamMessages.any {it.text.contains("year, make",true) || it.text.contains("ANSWER:")&&it.kind==""})
            assertEquals(listOf(CaseRules.DONE,CaseRules.DONE),case.steps.map {it.status})
            assertTrue(case.facts.any {it.text.contains("Honda")})
        }
    }

    // S2: "What was the last invoice on supabase?" Exactly one bot posts, no personal bot, no "On it".
    @Test fun s2_oneBotAnswersAndNobodySaysOnIt() {
        val ai=TeamAi(planner={steps(step("s1","Customer Desk","Find the last invoice"))},
            job={bot,history,_,_->if(bot=="Customer Desk") {
                if(history.hasOutput("find-1")) reply("The last invoice is INV-69 for \$1,250.00.") else call("data_find","find-1",obj("query" to "Creyton"))
            } else reply("not mine")},
            merge={fail("a single step needs no write-up");""})
        withTeam(ai) {c->
            val since=System.currentTimeMillis()
            send(c,"What was the last invoice on supabase?")
            until(what="the answer") {c.cases.any {it.created>=since && it.state=="done"}}
            val said=c.visible()
            assertEquals(1,said.size);assertEquals("Customer Desk",said[0].bot)
            assertTrue(said[0].text.contains("INV-69"))
            assertFalse(c.teamMessages.any {it.text.startsWith("On it")})
            assertEquals(0,ai.log.count {it=="job:Personal"});assertEquals(0,ai.log.count {it=="merge"})
        }
    }

    // S3: the same text, then "???" while it is being worked on: one case, one answer, no second job.
    @Test fun s3_aNudgeIsAnsweredFromTheCaseAndStartsNothing() {
        val gate=CompletableDeferred<Unit>()
        val ai=TeamAi(planner={steps(step("s1","Customer Desk","Find the last invoice"))},
            job={bot,_,_,_->gate.await();reply("The last invoice is INV-69 for \$1,250.00.")},merge={""})
        withTeam(ai) {c->
            val since=System.currentTimeMillis()
            send(c,"What was the last invoice on supabase?")
            until(what="the job to start") {c.tasks.any {it.team && it.created>=since && it.status=="Running"}}
            val tasksBefore=c.tasks.count {it.created>=since}
            send(c,"???")
            until(what="the status text") {c.teamMessages.any {it.role=="assistant" && it.text.startsWith("Still on it")}}
            assertEquals(tasksBefore,c.tasks.count {it.created>=since})
            assertEquals(1,ai.log.count {it=="plan"})
            assertEquals(c.leadBot(),c.teamMessages.last {it.role=="assistant"}.bot)
            gate.complete(Unit)
            until(what="the answer") {c.cases.any {it.created>=since && it.state=="done"}}
            assertEquals(1,c.visible().count {it.text.contains("INV-69")})
            assertEquals(1,ai.log.count {it=="job:Customer Desk"})
        }
    }

    // S4: an estimate. The bot makes a PDF nobody asked for: it is sent back once, and the answer that goes out is plain text.
    @Test fun s4_aPdfNobodyAskedForIsCaughtOnce() {
        var rounds=0
        val ai=TeamAi(planner={steps(step("s1","Alpha","Estimate for Ben's Tacoma brakes"))},
            job={_,history,_,_->
                rounds++
                when {
                    history.users().any {it.startsWith("[Check before you answer]")} -> reply("The estimate for Ben's Tacoma brakes comes to \$450.00.")
                    history.hasOutput("pdf-1") -> reply("Here is the estimate PDF for Ben.")
                    else -> call("invoice_pdf","pdf-1",obj("customer_name" to "Ben","vehicle" to "Tacoma","items_json" to """[{"kind":"labor","description":"Brakes","quantity":1,"unit_price":450}]""",
                        "tax_rate_percent" to 0,"sample" to false,"notes" to "","document_type" to "estimate"))
                }
            },merge={""})
        withTeam(ai) {c->
            val since=System.currentTimeMillis()
            send(c,"Estimate for Ben's Tacoma brakes")
            until(what="the answer") {c.cases.any {it.created>=since && it.state=="done"}}
            assertEquals(3,rounds)
            val said=c.visible()
            assertEquals(1,said.size);assertFalse(said[0].text.contains("PDF",true));assertTrue(said[0].text.contains("\$450.00"))
        }
    }

    // S9 (live phone, 2026-10-12): "Save the final code as a js file." The bot answered without saving it and the gate did not ask. A team case asks once, and the file ends up under the answer.
    @Test fun s9_aNamedKindOfFileThatWasNeverMadeIsAskedForInATeamCaseToo() {
        val owner="Write JavaScript for two functions and test them by actually running the code, not by guessing. First: median of a list of numbers. Second: businessDays from one date to another, counting both end dates and skipping Saturday and Sunday. Test median on 10 9 1 22 which should be 9.5. Show every result and fix anything that fails. Save the final code as a js file."
        val ai=TeamAi(planner={steps(step("s1","Alpha","Write and test the two functions and save the code"))},
            job={_,history,_,_->
                when {
                    history.hasOutput("save-1") -> reply("The median of 10 9 1 22 is 9.5.")
                    history.toString().contains("none was made in this job") -> call("file_write","save-1",obj("name" to "final-code.js","text" to "function median(a){return 9.5}"))
                    else -> reply("The median of 10 9 1 22 is 9.5.")
                }
            },merge={""})
        withTeam(ai) {c->
            val since=System.currentTimeMillis()
            send(c,owner)
            until(what="the answer") {c.cases.any {it.created>=since && it.state=="done"}}
            val job=c.tasks.first {it.caseId==c.newCase(since).id}
            assertEquals("Completed",job.status)
            assertEquals(listOf("final-code.js"),c.artifactsFor(job.id))
            assertEquals(1,c.visible().size)
        }
    }

    // S5: the owner already signed in to the site. The bot is not made to sign in again, in the tool or in its answer.
    @Test fun s5_aSignInTheOwnerAlreadyDidIsNotAskedForAgain() {
        var rounds=0
        val ai=TeamAi(planner={steps(step("s1","Alpha","Check eBay for front brake pads for the Accord"))},
            job={_,history,_,_->
                rounds++
                when {
                    history.users().any {it.startsWith("[Check before you answer]")} -> reply("Front pads on eBay start at \$35.99.")
                    history.hasOutput("sign-1") -> reply("Can you sign in to eBay for me? Then I can check the listings.")
                    else -> call("browser_signin","sign-1",obj("site" to "https://www.ebay.com/","reason" to "check the listings"))
                }
            },merge={""})
        withTeam(ai,signedIn=listOf("https://www.ebay.com")) {c->
            val since=System.currentTimeMillis()
            send(c,"Check eBay for front brake pads for the Accord")
            until(what="the answer") {c.cases.any {it.created>=since && it.state=="done"}}
            val job=c.tasks.first {it.caseId==c.newCase(since).id}
            assertEquals("Completed",job.status)
            assertTrue(JSONArray(job.history).output("sign-1").contains("already_signed_in"))
            assertEquals(3,rounds)
            val said=c.visible()
            assertEquals(1,said.size);assertFalse(said[0].text.contains("sign in",true));assertTrue(said[0].text.contains("\$35.99"))
        }
    }

    // The owner-question guard: a bot that asks for the car's year, make and model without looking is sent to data_find, once.
    @Test fun theBotLooksTheCarUpInsteadOfAskingTheOwner() {
        val ai=TeamAi(planner={steps(step("s1","Alpha","Find brake pads for Creyton's car"))},
            job={_,history,_,_->when {
                history.hasOutput("find-1") -> reply("Front pads for Creyton's 2018 Honda Accord start at \$39.99.")
                history.users().any {it.startsWith("[Check before you answer]")} -> call("data_find","find-1",obj("query" to "Creyton"))
                else -> reply("What year, make and model is Creyton's car?")
            }},merge={""})
        withTeam(ai) {c->
            val since=System.currentTimeMillis()
            send(c,"Find brake pads for Creyton's car")
            until(what="the answer") {c.cases.any {it.created>=since && it.state=="done"}}
            val said=c.visible()
            assertEquals(1,said.size);assertTrue(said[0].text.contains("2018 Honda Accord"))
            assertFalse(c.teamMessages.any {it.text.contains("year, make and model",true)})
        }
    }

    // S6: a two-part request. Both parts run at the same time; the lead's first draft has a number nobody found, so it is fixed before it is posted.
    @Test fun s6_independentPartsRunTogetherAndTheMergedNumbersAreChecked() {
        val started=AtomicInteger();var drafts=0
        suspend fun together() {started.incrementAndGet();withTimeout(20_000) {while(started.get()<2) delay(20)}}
        val ai=TeamAi(planner={steps(step("s1","Customer Desk","Find the last invoice for Creyton"),step("s2","Parts","Find the price of an oil filter"))},
            job={bot,_,_,_->together();if(bot=="Customer Desk") reply("The last invoice is INV-69 for \$1,250.00, with \$450.00 still due.") else reply("An oil filter is \$8.49 at AutoZone.")},
            merge={history->
                drafts++
                if(drafts==1) "# The last invoice is INV-69 for \$1,250.00\n\$450.00 is still due, and an oil filter is \$8.49. That is \$458.49 in all, and you saved \$999."
                else "# The last invoice is INV-69 for \$1,250.00\n\$450.00 is still due, and an oil filter is \$8.49 at AutoZone."
            })
        withTeam(ai) {c->
            val since=System.currentTimeMillis()
            send(c,"What was Creyton's last invoice, and what does an oil filter cost?")
            until(what="the case to finish") {c.cases.any {it.created>=since && it.state=="done"}}
            assertEquals(2,drafts)
            val said=c.visible()
            assertEquals(1,said.size)
            assertTrue(said[0].text.contains("\$1,250.00") && said[0].text.contains("\$8.49"))
            assertFalse(said[0].text.contains("999") || said[0].text.contains("458.49"))
        }
    }

    // S7: things go wrong. The planner gives nonsense, then one step breaks and the write-up fails: the owner still gets an answer, and what is missing.
    @Test fun s7_aBadPlanFallsBackToTheLeadBot() {
        val ai=TeamAi(planner={"I think Customer Desk should take that one, no JSON for you"},job={bot,_,_,_->reply("Handled by "+bot)},merge={""})
        withTeam(ai) {c->
            val since=System.currentTimeMillis()
            send(c,"What was the last invoice on supabase?")
            until(what="the answer") {c.cases.any {it.created>=since && it.state=="done"}}
            val said=c.visible()
            assertEquals(1,said.size);assertEquals(c.leadBot(),said[0].bot)
            assertTrue(c.newCase(since).steps.single().bot==c.leadBot())
        }
    }
    @Test fun s7_aBrokenStepAndAFailedWriteUpStillGiveAnAnswerAndSayWhatIsMissing() {
        val ai=TeamAi(planner={steps(step("s1","Customer Desk","Find the last invoice for Creyton"),step("s2","Parts","Find the price of an oil filter"))},
            job={bot,_,_,_->if(bot=="Parts") throw IllegalStateException("The parts site is down") else reply("The last invoice is INV-69 for \$1,250.00.")},
            merge={throw IllegalStateException("write-up failed")})
        withTeam(ai) {c->
            val since=System.currentTimeMillis()
            send(c,"What was Creyton's last invoice, and what does an oil filter cost?")
            until(what="the case to finish") {c.cases.any {it.created>=since && it.state=="done"}}
            val merged=c.visible().last {it.caseId==c.newCase(since).id && it.bot==c.leadBot()}
            assertTrue(merged.text,merged.text.contains("INV-69") && merged.text.contains("Still missing: Parts didn't finish"))
            assertEquals(CaseRules.STOPPED,c.newCase(since).steps[1].status)
        }
    }

    // S8: "hi everyone": every bot says a line for itself; no planner, no merging.
    @Test fun s8_helloEveryoneGetsOneLineFromEachBot() {
        val ai=TeamAi(planner={fail("everyone needs no planner");""},job={bot,_,_,_->reply("Hi! "+bot+" here.")},merge={fail("nothing to merge");""})
        withTeam(ai) {c->
            val since=System.currentTimeMillis()
            send(c,"hi everyone")
            until(what="every bot to answer") {c.visible().count {it.at>=since}>=c.bots.size}
            assertEquals(c.bots.map {it.name}.toSet(),c.visible().filter {it.at>=since}.map {it.bot}.toSet())
            assertEquals(0,c.teamMessages.count {it.kind=="step"})
            assertEquals("direct",c.newCase(since).mode)
        }
    }

    // 2026-10-17: the plan's sharing limit was still on in the morning and "Hello everyone" got no answer at all. The owner must hear, in the TEAM chat they are looking at, that it is
    // the plan's limit (not a broken sign-in) and when Alpha looks again, once for the whole group.
    @Test fun thePlanLimitStillOnIsSaidOnceInTheTeamChatAndNothingIsLeftSilent() {
        val limit=IllegalStateException("The ChatGPT user has reached their Subscription Sharing usage limit. Ask the user to try again after their usage limit resets or use an API key instead.")
        val ai=TeamAi(planner={throw limit},job={_,_,_,_->throw limit},merge={""})
        withTeam(ai) {c->
            c.retryDelayScale=0.01
            val since=System.currentTimeMillis()
            send(c,"hello everyone")
            until(30_000,"the owner to be told in the team chat") {c.teamMessages.any {it.role=="assistant" && it.at>=since && it.text.contains("not a sign-in problem")}}
            val told=c.teamMessages.filter {it.role=="assistant" && it.at>=since && it.text.contains("not a sign-in problem")}
            assertEquals("one line for the whole group: "+c.teamMessages.filter {it.at>=since}.joinToString(" || ") {it.role+"/"+it.bot+": "+it.text.take(60)},1,told.size)
        }
    }

    // 2026-10-17: the owner texted twice while the limit hold was on and the texts just sat there. A text during the hold is tried at once (not left for the next scheduled check), so the owner gets
    // the answer, or the honest reason with the ChatGPT setting that raises the limit, right then.
    @Test fun aTextDuringTheLimitIsTriedAtOnceAndAnsweredNotLeftSilent() {
        val limit=IllegalStateException("The ChatGPT user has reached their Subscription Sharing usage limit. Ask the user to try again after their usage limit resets or use an API key instead.")
        val ai=TeamAi(planner={throw limit},job={_,_,_,_->throw limit},merge={""})
        withTeam(ai) {c->
            val since=System.currentTimeMillis()
            fun notes()=c.teamMessages.count {it.role=="assistant" && it.at>=since && it.text.contains("not a sign-in problem")}
            send(c,"hello everyone")
            until(30_000,"the first note") {notes()>=1}
            assertTrue("the hold is on for minutes",c.limitedUntil>System.currentTimeMillis()+60_000)
            send(c,"are you there?")
            until(30_000,"a second note, because the second text was tried at once") {notes()>=2}
            val last=c.teamMessages.last {it.role=="assistant" && it.text.contains("not a sign-in problem")}.text
            assertTrue(last,last.contains("App limits") && last.contains("Manage limits"))
        }
    }

    // 2026-10-17: about 75 hellos. A greeting gets one line from each bot, said by the bot itself: the bot is told so and may not ask a colleague.
    @Test fun aGreetingIsOneLineFromEachBotAndTheyAreToldNotToAskColleagues() {
        val prompts=CopyOnWriteArrayList<String>();val asked=AtomicInteger(0)
        val ai=TeamAi(planner={fail("a greeting needs no planner");""},job={bot,history,_,tools->
            prompts+=history.users().joinToString("\n")
            if(tools.any {it.name=="ask_team"} && asked.getAndIncrement()==0) call("ask_team","a1",obj("asks_json" to arr(listOf(obj("bot" to "Parts","goal" to "say hello","known" to "","deliverable" to "hello"))).toString(),"wait_seconds" to 10))
            else reply("Hi! "+bot+" here.")
        },merge={""})
        withTeam(ai) {c->
            val since=System.currentTimeMillis()
            send(c,"Hey everyone. Say hi to each other too")
            until(60_000,"every bot to answer") {c.visible().count {it.at>=since}>=c.bots.size}
            assertTrue("every bot was told it is only a greeting",prompts.all {it.contains(Chitchat.MARK)})
            assertTrue("a bot tried to ask a colleague",asked.get()>=1)
            assertEquals("no bot texted a colleague",0,c.tasks.count {it.prompt.startsWith("From ")})
        }
    }

    // 2026-10-17: greetings that waited a long time for their turn are not answered when the limit lifts, and their case closes quietly.
    @Test fun oldGreetingsAreNotAnsweredWhenTheLimitLifts() {
        val limit=IllegalStateException("The ChatGPT user has reached their Subscription Sharing usage limit. Ask the user to try again after their usage limit resets or use an API key instead.")
        val jobCalls=AtomicInteger(0);var thrown=true
        val ai=TeamAi(planner={""},job={_,_,_,_->if(thrown) throw limit else {jobCalls.incrementAndGet();reply("Hi!")}},merge={""})
        withTeam(ai) {c->
            val since=System.currentTimeMillis()
            send(c,"hello everyone")
            // Every bot's job is held (the later ones start only as the first ones fail, so "nothing is running" for a moment is not enough).
            until(30_000,"the limit hold and every bot's job to be held") {c.limitedUntil>0L && c.tasks.filter {it.created>=since}.let {t->t.size>=c.bots.size && t.all {it.status=="Paused"}}}
            runBlocking {delay(800)}   // let the bots that failed finish going on hold
            thrown=false
            InstrumentationRegistry.getInstrumentation().runOnMainSync {c.greetingStaleMs=0;c.releaseLimit(force=true)}
            runBlocking {delay(1500)}
            assertEquals("nobody answered the old greeting: "+c.tasks.filter {it.created>=since}.joinToString(" | ") {it.workspace+" "+it.status+" "+it.detail.take(40)+" case="+it.caseId.take(4)+" prompt="+it.prompt.take(30)}+" cases="+c.cases.joinToString {it.state+":"+it.ownerText.take(20)},0,jobCalls.get())
            assertTrue("nothing is left running or waiting",c.tasks.filter {it.created>=since}.none {it.status in setOf("Running","Queued","Paused")})
            assertTrue("the case is closed",c.cases.filter {it.created>=since}.all {it.state=="done"})
        }
    }

    // One tap stops every bot that is working or waiting.
    @Test fun stopEverythingStopsEveryBot() {
        val gate=CompletableDeferred<Unit>()
        val ai=TeamAi(planner={""},job={_,_,_,_->gate.await();reply("Done.")},merge={""})
        withTeam(ai) {c->
            val since=System.currentTimeMillis()
            send(c,"hello everyone")
            until(30_000,"the bots to start") {c.tasks.count {it.created>=since && it.status=="Running"}>=1}
            var n=0;InstrumentationRegistry.getInstrumentation().runOnMainSync {n=c.stopEverything()}
            assertTrue(n>=1)
            assertTrue(c.tasks.filter {it.created>=since}.none {it.status in setOf("Running","Queued")})
            gate.complete(Unit)
        }
    }

    // Asking colleagues: two at once, one of them needs more and is asked again, and the owner sees only the asker's one answer (no raw ANSWER: text).
    @Test fun askTeamAsksTwoColleaguesAtOnceAndAsksAgainAfterNeedInput() {
        val started=AtomicInteger();val partsAsked=AtomicInteger()
        suspend fun together() {started.incrementAndGet();withTimeout(20_000) {while(started.get()<2) delay(20)}}
        fun asks(vararg a:Triple<String,String,String>)=obj("asks_json" to arr(a.map {obj("bot" to it.first,"goal" to it.second,"known" to it.third,"deliverable" to "the answer")}).toString(),"wait_seconds" to 90)
        val ai=TeamAi(planner={steps(step("s1","Alpha","Find Ben's last invoice and pads for his car"))},
            job={bot,history,_,_->
                val last=history.users().lastOrNull().orEmpty()
                when {
                    last.startsWith("From ") -> when(bot) {
                        "Customer Desk" -> {together();reply("ANSWER: INV-69, total \$1,250.00.")}
                        else -> {if(partsAsked.incrementAndGet()==1) {together();reply("NEED-INPUT: which car does he drive?")} else reply("ANSWER: Front pads for a 2018 Honda Accord are \$39.99.")}
                    }
                    history.hasOutput("ask-2") -> reply("INV-69 is \$1,250.00, and front pads for his 2018 Honda Accord are \$39.99.")
                    history.hasOutput("ask-1") -> call("ask_team","ask-2",asks(Triple("Parts","pads for Ben's car","It is a 2018 Honda Accord")))
                    else -> call("ask_team","ask-1",asks(Triple("Customer Desk","Ben's last invoice",""),Triple("Parts","brake pads for Ben's car","")))
                }
            },merge={""})
        withTeam(ai) {c->
            val since=System.currentTimeMillis()
            send(c,"Find Ben's last invoice and pads for his car")
            until(what="the answer") {c.cases.any {it.created>=since && it.state=="done"}}
            val asker=c.tasks.first {it.caseId==c.newCase(since).id}
            assertEquals(1,c.visible().size)
            assertTrue(c.visible()[0].text.contains("\$1,250.00") && c.visible()[0].text.contains("\$39.99"))
            assertFalse(c.teamMessages.any {it.kind=="" && it.text.contains("ANSWER:")})
            assertTrue(c.teamMessages.count {it.kind=="ask"}>=3)
            assertEquals(setOf("answer"),c.collabsFor(asker.id).map {it.status}.toSet())
            assertEquals(setOf("Customer Desk","Parts"),c.collabsFor(asker.id).map {it.colleague}.toSet())
            assertEquals(2,partsAsked.get())
        }
    }

    // The live phone: a colleague built the owner's page in its own folder and the asker could only describe it. Now the colleague's files are copied to the asker
    // and show under the asker's answer; and a colleague that only looked in its folder does not drag every old file along.
    @Test fun aColleaguesFileReachesTheAskersAnswer() {
        val ai=TeamAi(planner={steps(step("s1","Alpha","Make a chart of the week"))},
            job={bot,history,_,_->
                val last=history.users().lastOrNull().orEmpty()
                when {
                    last.startsWith("From ") && bot=="Parts" -> when {
                        history.hasOutput("chart-1") -> reply("ANSWER: The chart is made.")
                        history.hasOutput("list-1") -> call("chart_make","chart-1",obj("type" to "bar","title" to "Cars per day","data" to "Mon: 6\nTue: 9\nWed: 4"))
                        else -> call("files_list","list-1",obj())
                    }
                    history.hasOutput("ask-1") -> reply("# The chart is ready\nHere it is.")
                    else -> call("ask_colleague","ask-1",obj("bot" to "Parts","goal" to "Make a chart of the week","known" to "","deliverable" to "the chart","wait_seconds" to 60))
                }
            },merge={""})
        withTeam(ai) {c->
            val since=System.currentTimeMillis()
            send(c,"Make a chart of the week")
            until(what="the answer") {c.cases.any {it.created>=since && it.state=="done"}}
            val asker=c.tasks.first {it.caseId==c.newCase(since).id}
            val files=c.artifactsFor(asker.id)
            assertEquals(files.toString(),1,files.size)
            assertTrue(files.toString(),files[0].startsWith("chart-") && files[0].endsWith(".png"))
            assertTrue("the file is in the asker's own folder",java.io.File(c.workspaceDir,asker.workspace.lowercase()+"/"+files[0]).isFile)
            assertTrue(c.artifactsFor(c.tasks.first {it.from==asker.id}.id).none {it.endsWith(".md")})
        }
    }

    // Stop on a job stops the colleagues it asked: they used to run on (a deep job each) with nobody left to read the answer.
    @Test fun stoppingAJobAlsoStopsTheColleaguesItAsked() {
        val ai=TeamAi(planner={steps(step("s1","Alpha","Find Ben's last invoice"))},
            job={bot,history,_,_->
                val last=history.users().lastOrNull().orEmpty()
                when {
                    last.startsWith("From ") -> {delay(60_000);reply("ANSWER: INV-69.")}
                    else -> call("ask_colleague","ask-1",obj("bot" to "Customer Desk","goal" to "Ben's last invoice","known" to "","deliverable" to "the invoice","wait_seconds" to 120))
                }
            },merge={""})
        withTeam(ai) {c->
            val since=System.currentTimeMillis()
            send(c,"Find Ben's last invoice")
            until(what="the colleague to start") {c.tasks.any {it.workspace=="Customer Desk" && it.status=="Running"}}
            val asker=c.tasks.first {it.workspace=="Alpha" && it.created>=since}
            InstrumentationRegistry.getInstrumentation().runOnMainSync {c.stop(asker.id)}
            until(what="the colleague to be stopped") {c.tasks.first {it.workspace=="Customer Desk" && it.from==asker.id}.status=="Cancelled"}
            assertEquals("Paused",c.tasks.first {it.id==asker.id}.status)
            assertTrue(c.tasks.first {it.workspace=="Customer Desk" && it.from==asker.id}.detail.contains("was stopped"))
        }
    }

    // A bot that doesn't wait for a colleague still posts ONE answer: its final text waits until the colleague's answer is in, and uses it.
    @Test fun anAnswerThatIsStillComingIsWaitedForSoTheOwnerGetsOneMessage() {
        val ai=TeamAi(planner={steps(step("s1","Alpha","Find Ben's last invoice"))},
            job={bot,history,_,_->
                val last=history.users().lastOrNull().orEmpty()
                when {
                    last.startsWith("From ") -> {delay(1500);reply("ANSWER: INV-69, total \$1,250.00.")}
                    history.users().any {it.startsWith("[Update from your coworker]")} -> reply("The last invoice is INV-69, total \$1,250.00.")
                    history.hasOutput("ask-1") -> reply("I asked Customer Desk and I'm waiting for the invoice.")
                    else -> call("ask_colleague","ask-1",obj("bot" to "Customer Desk","goal" to "Ben's last invoice","known" to "","deliverable" to "the invoice","wait_seconds" to 0))
                }
            },merge={""})
        withTeam(ai) {c->
            val since=System.currentTimeMillis()
            send(c,"Find Ben's last invoice")
            until(what="the answer") {c.cases.any {it.created>=since && it.state=="done"}}
            val said=c.visible()
            assertEquals(1,said.size)
            assertTrue(said[0].text,said[0].text.contains("\$1,250.00"))
            assertFalse(c.teamMessages.any {it.text.contains("I'm waiting")})
        }
    }
}
