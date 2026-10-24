package com.alphainternational.assistant

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** The chief planner's plan, the case file, steps that wait for each other, the one merged answer and the number check. No phone, no model. */
class CaseTest {
    private val bots=listOf("Alpha","Customer Desk","AutoZone Scout","Personal Companion")
    private fun parse(reply:String,taken:Set<String> = emptySet())=CaseRules.parsePlan(reply,bots,"Find brake pads for Creyton's car",taken)

    private val twoSteps="""{"steps":[{"id":"s1","bot":"Customer Desk","do":"Find Creyton's car","after":[]},{"id":"s2","bot":"AutoZone Scout","do":"Find brake pads for that car","after":["s1"]}],"answer":"merge"}"""

    // ---- reading the planner's reply ----

    @Test fun aGoodPlanIsRead() {
        val plan=parse(twoSteps)
        assertEquals(listOf("s1","s2"),plan.steps.map {it.id})
        assertEquals(listOf("Customer Desk","AutoZone Scout"),plan.steps.map {it.bot})
        assertEquals(emptyList<String>(),plan.steps[0].after);assertEquals(listOf("s1"),plan.steps[1].after)
        assertEquals("Find brake pads for that car",plan.steps[1].goal)
        assertEquals("merge",plan.answer);assertFalse(plan.amend);assertTrue(plan.usable)
    }

    @Test fun aFenceAndChattyTextAroundTheJsonAreFine() {
        val plan=parse("Sure! Here is the plan:\n```json\n$twoSteps\n```\nLet me know if you need anything else {not json}.")
        assertEquals(listOf("s1","s2"),plan.steps.map {it.id})
        // JSON with braces inside text values still reads.
        val tricky=parse("""{"steps":[{"id":"s1","bot":"Alpha","do":"Reply with {thanks} and \"ok\"","after":[]}],"answer":"lead"}""")
        assertEquals(1,tricky.steps.size);assertEquals("lead",tricky.answer)
    }

    @Test fun garbageGivesAnEmptyPlanSoTheCallerFallsBackToTheLeadBot() {
        listOf("","I think Customer Desk","{not json","{\"bots\":[]}","{\"steps\":[]}","{\"steps\":[{\"bot\":\"Nobody\",\"do\":\"x\"}]}","[1,2,3]","null").forEach {
            assertFalse(it,parse(it).usable)
        }
        val fallback=CaseRules.fallback("Alpha","  Find brake pads\nfor Creyton's car ")
        assertEquals(listOf(CaseRules.PlanStep("s1","Alpha","Find brake pads for Creyton's car")),fallback.steps);assertEquals("lead",fallback.answer)
    }

    @Test fun unknownBotNamesAreFixedOrDropped() {
        val plan=parse("""{"steps":[
            {"id":"a","bot":"customer desk","do":"find the car"},
            {"id":"b","bot":"Autozone Scoot","do":"find pads","after":["a"]},
            {"id":"c","bot":"Nobody","do":"x","after":["a"]},
            {"id":"d","bot":"Personal","do":"y","after":["c"]}]}""")
        assertEquals(listOf("Customer Desk","AutoZone Scout","Personal Companion"),plan.steps.map {it.bot})
        // The dropped step is gone, and nobody waits for it.
        assertEquals(emptyList<String>(),plan.steps[2].after)
        assertEquals(listOf(plan.steps[0].id),plan.steps[1].after)
        assertEquals("Customer Desk",CaseRules.resolveBot("Customer",bots));assertEquals("Alpha",CaseRules.resolveBot(" ALPHA ",bots))
        assertNull(CaseRules.resolveBot("Bookkeeper",bots));assertNull(CaseRules.resolveBot("",bots))
    }

    @Test fun waitsThatMakeACircleOrPointNowhereAreCut() {
        val circle=parse("""{"steps":[{"id":"s1","bot":"Alpha","do":"a","after":["s2"]},{"id":"s2","bot":"Customer Desk","do":"b","after":["s1"]}]}""")
        assertEquals(2,circle.steps.size)
        assertTrue("every step can start eventually",startsEventually(circle.steps))
        val selfWait=parse("""{"steps":[{"id":"s1","bot":"Alpha","do":"a","after":["s1","s9"]}]}""")
        assertEquals(emptyList<String>(),selfWait.steps[0].after)
        val big=parse("""{"steps":[{"id":"a","bot":"Alpha","do":"1","after":["c"]},{"id":"b","bot":"Customer Desk","do":"2","after":["a"]},{"id":"c","bot":"AutoZone Scout","do":"3","after":["b"]}]}""")
        assertTrue(startsEventually(big.steps))
    }
    private fun startsEventually(steps:List<CaseRules.PlanStep>):Boolean {
        val done=HashSet<String>();var left=steps
        while(left.isNotEmpty()) {
            val ready=left.filter {s->s.after.all {it in done}}
            if(ready.isEmpty()) return false
            done+=ready.map {it.id};left=left.filter {it !in ready}
        }
        return true
    }

    @Test fun atMostFiveStepsAndNoBotDoesTwoIndependentThings() {
        val many=parse("""{"steps":[""" + (1..8).joinToString(",") {"""{"id":"s$it","bot":"${bots[it%4]}","do":"part $it"}"""} + "]}")
        assertTrue(many.steps.size<=CaseRules.MAX_STEPS)
        // Two independent steps for one bot become one with both goals; a chain through another bot stays two.
        val twice=parse("""{"steps":[{"id":"s1","bot":"Customer Desk","do":"find the car"},{"id":"s2","bot":"Customer Desk","do":"find the last invoice"}]}""")
        assertEquals(1,twice.steps.size);assertTrue(twice.steps[0].goal.contains("find the car") && twice.steps[0].goal.contains("find the last invoice"))
        val chain=parse("""{"steps":[{"id":"s1","bot":"Customer Desk","do":"car"},{"id":"s2","bot":"AutoZone Scout","do":"pads","after":["s1"]},{"id":"s3","bot":"Customer Desk","do":"invoice","after":["s2"]}]}""")
        assertEquals(3,chain.steps.size)
    }

    @Test fun waitsMayNameABotAndTheOldFormatStillReads() {
        val byBot=parse("""{"steps":[{"id":"x","bot":"Customer Desk","do":"car"},{"id":"y","bot":"AutoZone Scout","do":"pads","after":"Customer Desk"}]}""")
        assertEquals(listOf("x"),byBot.steps[1].after)
        val old=parse("""{"bots":[{"name":"Customer Desk","part":"find the car"},"alpha"]}""")
        assertEquals(listOf("Customer Desk","Alpha"),old.steps.map {it.bot});assertEquals("find the car",old.steps[0].goal)
        // No "do": the owner's own words.
        assertEquals("Find brake pads for Creyton's car",parse("""{"steps":[{"bot":"Alpha"}]}""").steps[0].goal)
    }

    @Test fun amendingAnOpenCaseKeepsOldIdsFreeAndMayWaitForOldSteps() {
        val plan=parse("""{"steps":[{"id":"s1","bot":"Alpha","do":"also the oil","after":["s2"]}],"case":"amend"}""",taken=setOf("s1","s2"))
        assertTrue(plan.amend)
        assertEquals("s3",plan.steps[0].id);assertEquals(listOf("s2"),plan.steps[0].after)
        assertEquals("merge",parse("""{"steps":[{"bot":"Alpha"}]}""").answer)
    }

    // ---- running a case ----

    private fun plan2()=parse(twoSteps)
    private fun case2():Case=CaseRules.newCase("c1",plan2(),"Find brake pads for Creyton's car","Alpha","named:g1",1_000)
    private fun task(id:String,status:String,detail:String="",bot:String="Customer Desk")=TaskRecord(id,id,"p",status,detail,0,workspace=bot,team=true)

    @Test fun stepsWithNothingToWaitForRunNowAndTheRestWait() {
        val c=case2()
        assertEquals(listOf(CaseRules.RUNNING,CaseRules.WAITING),c.steps.map {it.status})
        assertEquals("working",c.state);assertEquals("Find brake pads for Creyton's car",c.goal);assertTrue(CaseRules.synthesizes(c))
        // A single step, or bots the owner named, are never merged.
        assertFalse(CaseRules.synthesizes(CaseRules.newCase("c2",CaseRules.fallback("Alpha","hi"),"hi","Alpha","g",0)))
        assertFalse(CaseRules.synthesizes(CaseRules.newCase("c3",CaseRules.direct(listOf("Alpha","Customer Desk"),"hi everyone"),"hi everyone","Alpha","g",0)))
    }

    @Test fun aWaitingStepStartsWhenWhatItWaitsForIsInOrHasStopped() {
        var c=case2().let {it.copy(steps=it.steps.mapIndexed {i,s->s.copy(taskId="t"+(i+1))})}
        assertTrue(CaseRules.ready(c).isEmpty())
        assertEquals(setOf("t2"),CaseRules.heldTasks(listOf(c)))
        c=c.copy(steps=c.steps.map {if(it.id=="s1") it.copy(status=CaseRules.DONE,result="2018 Honda Accord") else it})
        assertEquals(listOf("s2"),CaseRules.ready(c).map {it.id})
        // A step that stopped is reported, not waited for forever.
        val stopped=c.copy(steps=c.steps.map {if(it.id=="s1") it.copy(status=CaseRules.STOPPED,note="Paused") else it})
        assertEquals(listOf("s2"),CaseRules.ready(stopped).map {it.id})
        // Once released nothing is held; a finished case holds nothing either.
        val released=c.copy(steps=c.steps.map {if(it.id=="s2") it.copy(status=CaseRules.RUNNING) else it})
        assertTrue(CaseRules.heldTasks(listOf(released)).isEmpty())
        assertTrue(CaseRules.heldTasks(listOf(case2().copy(state="done"))).isEmpty())
    }

    @Test fun theSchedulerSkipsHeldJobs() {
        fun t(id:String,created:Long)=TaskRecord(id,id,id,"Queued","",created,workspace="Bot"+id,team=true)
        val tasks=listOf(t("t2",1),t("other",2))
        assertEquals("t2",AgentRules.nextToStart(tasks,emptySet())?.id)
        assertEquals("other",AgentRules.nextToStart(tasks,emptySet(),held=setOf("t2"))?.id)
        assertNull(AgentRules.nextToStart(listOf(tasks[0]),emptySet(),held=setOf("t2")))
        assertFalse(AgentRules.canStart(tasks[0],tasks,emptySet(),held=setOf("t2")))
        assertTrue(AgentRules.canStart(tasks[0],tasks,emptySet()))
    }

    @Test fun stepsFollowTheirJobs() {
        val c=case2().let {it.copy(steps=it.steps.mapIndexed {i,s->s.copy(taskId="t"+(i+1))})}
        fun sync(vararg tasks:TaskRecord,ask:Boolean=false)=CaseRules.sync(c,tasks.toList(),{id->if(id=="t1") "ANSWER: 2018 Honda Accord" else ""}) {ask}.steps
        // Finished: done, with its last text as the result.
        val done=sync(task("t1","Completed"),task("t2","Queued"))
        assertEquals(CaseRules.DONE,done[0].status);assertEquals("ANSWER: 2018 Honda Accord",done[0].result);assertEquals(CaseRules.WAITING,done[1].status)
        assertEquals(CaseRules.RUNNING,sync(task("t1","Running"),task("t2","Queued"))[0].status)
        // A job that only lost its connection and is trying again is still running.
        assertEquals(CaseRules.RUNNING,sync(task("t1","Paused","Connection trouble. Trying again in 30 seconds."),task("t2","Queued"))[0].status)
        // Stopped for the owner, cancelled, or gone: stopped, with why.
        assertEquals(CaseRules.STOPPED,sync(task("t1","Paused","Paused. Resume to continue from saved tool results."),task("t2","Queued"))[0].status)
        assertEquals(CaseRules.STOPPED,sync(task("t1","Needs connection","Connect Supabase"),task("t2","Queued"))[0].status)
        assertEquals(CaseRules.STOPPED,sync(task("t1","Cancelled"),task("t2","Queued"))[0].status)
        assertEquals(CaseRules.STOPPED,sync(task("t2","Queued"))[0].status)
        // Finished but still waiting on a colleague it asked: not done yet.
        assertEquals(CaseRules.RUNNING,sync(task("t1","Completed"),task("t2","Queued"),ask=true)[0].status)
        // Everything in (done or stopped) means the case can be written up.
        assertTrue(CaseRules.finished(c.copy(steps=c.steps.map {it.copy(status=CaseRules.DONE)})))
        assertFalse(CaseRules.finished(c))
    }

    @Test fun aStepIsToldItsPartWhoElseIsWorkingAndWhatIsAlreadyFound() {
        val c=case2().let {it.copy(steps=it.steps.map {s->if(s.id=="s1") s.copy(status=CaseRules.DONE,result="2018 Honda Accord, 2.4L") else s})}
        val first=CaseRules.stepPrompt(c,c.steps[0])
        assertTrue(first,first.startsWith("Find brake pads for Creyton's car") && first.contains("YOUR PART: Find Creyton's car") && first.contains("AutoZone Scout — Find brake pads") && first.contains("Leave their parts to them"))
        assertFalse(first.contains("Already found"))
        val second=CaseRules.stepPrompt(c,c.steps[1])
        assertTrue(second,second.contains("Already found by the team: Customer Desk: 2018 Honda Accord, 2.4L") && second.contains("don't look it up again"))
        assertTrue(second.contains("The lead writes the one message"))
        // One step: exactly the owner's words, like always. Named bots keep the old "also called on" note.
        val one=CaseRules.newCase("c",CaseRules.fallback("Alpha","What was the last invoice?"),"What was the last invoice?","Alpha","g",0)
        assertEquals("What was the last invoice?",CaseRules.stepPrompt(one,one.steps[0]))
        val direct=CaseRules.newCase("d",CaseRules.direct(listOf("Alpha","Customer Desk"),"hi everyone"),"hi everyone","Alpha","g",0)
        assertTrue(CaseRules.stepPrompt(direct,direct.steps[0]).contains("Also called on for other parts: Customer Desk"))
    }

    @Test fun anAmendmentAddsStepsToTheOpenCase() {
        val c=case2().let {it.copy(steps=it.steps.map {s->if(s.id=="s1") s.copy(status=CaseRules.DONE) else s})}
        val more=CaseRules.parsePlan("""{"steps":[{"id":"s9","bot":"Alpha","do":"also the oil change price","after":["s1"]},{"id":"s8","bot":"Personal Companion","do":"remind me","after":["s9"]}],"case":"amend"}""",bots,"also the oil",c.steps.map {it.id}.toSet())
        val merged=CaseRules.amend(c,more,"also the oil")
        assertEquals(4,merged.steps.size);assertTrue(merged.goal.contains("Also: also the oil"))
        // Waiting on an old step that's done: runs now. Waiting on a new step: waits.
        assertEquals(CaseRules.RUNNING,merged.steps.first {it.goal.contains("oil change")}.status)
        assertEquals(CaseRules.WAITING,merged.steps.first {it.goal.contains("remind me")}.status)
        assertEquals("working",merged.state)
    }

    @Test fun onlyARecentUnfinishedCaseCountsAsOpen() {
        val c=case2()
        assertEquals("c1",CaseRules.openCase(listOf(c),2_000)?.id)
        assertNull(CaseRules.openCase(listOf(c),1_000+CaseRules.OPEN_MS+1))
        assertNull(CaseRules.openCase(listOf(c.copy(state="done")),2_000))
        assertNull(CaseRules.openCase(listOf(c.copy(steps=c.steps.map {it.copy(status=CaseRules.DONE)})),2_000))
        assertNull(CaseRules.openCase(emptyList(),0))
        // A nudge while the lead is writing the answer still counts the case as open (it is answered, not planned again).
        val merging=c.copy(state="merging")
        assertNull(CaseRules.openCase(listOf(merging),2_000))
        assertEquals("c1",CaseRules.openCase(listOf(merging),2_000,merging=true)?.id)
    }

    // ---- a nudge while a request is open ----

    @Test fun nudgesAreAnsweredFromTheCaseAndStartNothingNew() {
        listOf("???","?","any update","Any updates?","update?","hello?","hello???","hi?","status","what's the status?","are you still working on it","still there?","done yet?","any luck?","Hello ?? 🙂","how's it going")
            .forEach {assertTrue(it,CaseRules.isStatusPing(it))}
        // Greetings and acknowledgements without a question, and real requests, are not nudges.
        listOf("hello","hi","ok","thanks","done","find brake pads for creyton","update the invoice for Ben","what is the status of invoice 69 for Ben Toyota","","   ","!!")
            .forEach {assertFalse(it,CaseRules.isStatusPing(it))}
    }

    @Test fun theStatusIsPlainWords() {
        val running=CaseRules.newCase("c",CaseRules.fallback("Customer Desk","last invoice?"),"last invoice?","Alpha","g",0)
        assertEquals("Still on it. Customer Desk is working on it.",CaseRules.statusText(running))
        val c=case2().let {it.copy(steps=it.steps.map {s->if(s.id=="s1") s.copy(status=CaseRules.DONE) else s})}
        val text=CaseRules.statusText(c)
        assertTrue(text,text.startsWith("Still on it.") && text.contains("Customer Desk is done") && text.contains("AutoZone Scout will start as soon as Customer Desk finishes"))
        assertTrue(CaseRules.statusText(c.copy(state="merging")).startsWith("Almost there"))
        val stuck=c.copy(steps=c.steps.map {if(it.id=="s2") it.copy(status=CaseRules.STOPPED,note="Needs you to sign in.") else it})
        assertTrue(CaseRules.statusText(stuck),CaseRules.statusText(stuck).contains("AutoZone Scout got stuck (Needs you to sign in)"))
    }

    // ---- the case file ----

    private fun dataFind():JSONObject=JSONObject("""{"found":true,"matched":[{"table":"customers","rows":[{"id":"c1","first_name":"Creyton","last_name":"Smith","phone":"555-0142"}]}],
        "related":[{"table":"vehicles","via":"customer_id → customers","rows":[{"id":"v1","customer_id":"c1","year":2018,"make":"Honda","model":"Accord"}]}]}""")

    @Test fun factsAreWrittenFromToolResultsWithTheirSource() {
        val facts=CaseRules.factsFrom("Customer Desk","data_find",dataFind())
        assertEquals(2,facts.size)
        // (a row's columns come in the database's order, so the pieces are checked, not the order)
        assertTrue(facts[0].text,facts[0].text.startsWith("customers: ") && listOf("first name Creyton","last name Smith","phone 555-0142").all {facts[0].text.contains(it)})
        assertTrue(facts[1].text,facts[1].text.startsWith("vehicles: ") && listOf("year 2018","make Honda","model Accord").all {facts[1].text.contains(it)} && !facts[1].text.contains("c1"))
        assertEquals("Customer Desk · looked up records",facts[0].source)
        val asked=CaseRules.factsFrom("Alpha","ask_colleague",obj("status" to "answer","bot" to "Customer Desk","answer" to "Customer Desk says: INV-69 is \$1,250"))
        assertEquals(CaseFact("Customer Desk says: INV-69 is \$1,250","Alpha · asked Customer Desk"),asked[0])
        val team=CaseRules.factsFrom("Alpha","ask_team",obj("results" to arr(listOf(obj("status" to "answer","bot" to "Customer Desk","answer" to "ok A"),obj("status" to "waiting","bot" to "Parts")))))
        assertEquals(1,team.size)
        assertEquals("INV-1: total 450 for Ben",CaseRules.factsFrom("Alpha","invoice_pdf",obj("number" to "INV-1","total" to 450,"customer" to "Ben"))[0].text)
        val sql=obj("content" to arr(listOf(obj("type" to "text","text" to "<untrusted-data-1>\n[{\"doc_number\":\"INV-69\",\"total\":1250}]\n</untrusted-data-1>"))))
        val record=CaseRules.factsFrom("Alpha","supabase_management_call",sql)[0]
        assertTrue(record.text,record.text.startsWith("record: ") && record.text.contains("doc number INV-69") && record.text.contains("total 1250"))
        // Errors and tools that found nothing add nothing.
        assertTrue(CaseRules.factsFrom("Alpha","data_find",obj("error" to "x")).isEmpty())
        assertTrue(CaseRules.factsFrom("Alpha","web_fetch",obj("title" to "x")).isEmpty())
    }

    @Test fun theCaseFileKeepsFactsOnceAndShowsEveryParticipantTheSameThing() {
        var c=case2()
        c=CaseRules.addFacts(c,CaseRules.factsFrom("Customer Desk","data_find",dataFind()))
        c=CaseRules.addFacts(c,CaseRules.factsFrom("Customer Desk","data_find",dataFind()))
        assertEquals(2,c.facts.size)
        assertEquals(24,CaseRules.addFacts(c,(1..40).map {CaseFact("fact $it","x")}).facts.size)
        val text=CaseRules.fileText(c.copy(open=listOf("tyre size")),"s2")
        assertTrue(text,text.startsWith("CASE FILE") && text.contains("Find brake pads for Creyton's car") && text.contains("s2 AutoZone Scout (you)"))
        assertTrue(text.contains("[Customer Desk · looked up records]") && text.contains("model Accord") && text.contains("Still open: tyre size"))
        assertTrue(text.length<=1800)
        // It is part of a team bot's instructions, and a single-step case carries it too.
        val mine=TaskRecord("t","x","p","Running","",0,workspace="AutoZone Scout",team=true,caseId="c1")
        val section=TeamPrompt.section(mine,listOf(Bot("Customer Desk","Records")),c.copy(steps=c.steps.map {it.copy(taskId=if(it.id=="s2") "t" else "")}),"- Customer Desk: Records",true)
        assertTrue(section,section.contains("CASE FILE") && section.contains("finding for the lead"))
    }

    @Test fun theOneMessageIsWrittenFromTheFindingsAndNeverShowsBotChatter() {
        val c=case2().let {it.copy(steps=it.steps.map {s->if(s.id=="s1") s.copy(status=CaseRules.DONE,result="ANSWER: 2018 Honda Accord, 2.4L") else s.copy(status=CaseRules.STOPPED,note="Needs you to sign in.")})}
        val input=CaseRules.synthText(c)
        assertTrue(input,input.contains("[Customer Desk] asked to: Find Creyton's car") && input.contains("2018 Honda Accord, 2.4L"))
        assertFalse("markers are removed",input.contains("ANSWER:"))
        assertTrue(input.contains("(couldn't finish: Needs you to sign in.)"))
        val rules=CaseRules.synthInstructions("Alpha")
        assertTrue(rules.contains("only facts and numbers that are in the findings") && rules.contains("Never mention steps, tools, case files"))
        // If the write-up can't be made, the findings are posted plainly, with what is missing. Never silent.
        val plain=CaseRules.plainMerge(c)
        assertTrue(plain,plain.contains("2018 Honda Accord, 2.4L") && plain.contains("Still missing: AutoZone Scout didn't finish (Needs you to sign in)"))
        assertFalse(plain.contains("ANSWER:"))
        assertTrue(CaseRules.plainMerge(case2().copy(steps=emptyList())).isNotBlank())
    }

    @Test fun aBotsPieceShowsAsOneQuietLine() {
        assertEquals("Customer Desk found 2018 Honda Accord, 2.4L",CaseRules.stepLine("Customer Desk","ANSWER: 2018 Honda Accord, 2.4L. From his record."))
        assertEquals("Customer Desk found the car",CaseRules.stepLine("Customer Desk","# The car\nMore."))
        assertEquals("Customer Desk couldn't find it",CaseRules.stepLine("Customer Desk","CANNOT: no such customer"))
        assertEquals("Customer Desk needs to know which Ben",CaseRules.stepLine("Customer Desk","NEED-INPUT: which Ben"))
        assertTrue(CaseRules.stepLine("Customer Desk","x".repeat(300)).length<=100)
        assertEquals("✓",CaseRules.mark(CaseRules.DONE));assertEquals("⏳",CaseRules.mark(CaseRules.RUNNING));assertEquals("⚠",CaseRules.mark(CaseRules.STOPPED))
    }

    @Test fun casesSurviveSavingAndOldDataStillLoads() {
        val c=case2().copy(facts=listOf(CaseFact("a","b")),files=listOf("x.pdf"),open=listOf("tyre size"))
        assertEquals(c,Case.parse(c.json()))
        val bare=Case.parse(obj("id" to "old","owner_text" to "hi","steps" to arr(listOf(obj("id" to "s1","bot" to "Alpha")))))
        assertEquals("working",bare.state);assertEquals("merge",bare.mode);assertEquals("waiting",bare.steps[0].status)
    }

    // ---- the number check ----

    @Test fun figuresAreComparedWhateverTheirFormat() {
        assertEquals(listOf("1250","1250","12.5"),Figures.find("\$1,250.00 and 1250 and 12.5%").map {it.key})
        val pool=Figures.all(listOf("Invoice INV-69: total \$1,250.00, paid \$800, due \$450. Call 555-0142. The 2018 Honda."))
        assertTrue(Figures.unsupported("The total is \$1,250, \$450 is still due. Call 555-0142.",pool).isEmpty())
        assertEquals(listOf("\$300"),Figures.unsupported("The total is \$1,250 and the deposit \$300.",pool))
        // A year, a plate number or a phone number nobody reported is flagged; small counts and times are not.
        assertEquals(listOf("2019"),Figures.unsupported("It's a 2019 Honda.",pool))
        assertTrue(Figures.unsupported("There are 2 options and the shop opens at 9:30.",pool).isEmpty())
        assertEquals(listOf("8.5%"),Figures.unsupported("Tax is 8.5% here.",pool))
        // The owner's own numbers count too.
        assertTrue(Figures.unsupported("You asked about 5551234.",Figures.all(listOf("call 555-1234   5551234"))).isEmpty())
    }

    @Test fun anUnsupportedFigureIsTakenOutWithItsSentenceOrLine() {
        val text="The total is \$1,250. The deposit was \$300 last week. Call us.\n- Brake pads: \$89\n- Rotors: \$150"
        assertEquals("The total is \$1,250. Call us.\n- Brake pads: \$89",Figures.without(text,listOf("\$300","\$150")))
        assertEquals("The total is \$1,250. The deposit was \$300 last week. Call us.",Figures.without("The total is \$1,250. The deposit was \$300 last week. Call us.",emptyList()))
        assertEquals("",Figures.without("About \$300.",listOf("\$300")))
        assertTrue(CaseRules.figureFix(listOf("\$300","2019")).contains("\$300, 2019"))
    }

    @Test fun thePoolHoldsTheOwnersWordsTheFactsAndEveryFinding() {
        val c=case2().copy(facts=listOf(CaseFact("vehicles: year 2018","x")),steps=case2().steps.map {it.copy(result="pads are \$89.99")})
        val pool=CaseRules.figurePool(c,"2025 2026")
        assertTrue("2018" in pool && "89.99" in pool && "2026" in pool)
        assertFalse("90" in pool)
    }

    @Test fun thePlannerSeesEveryBotsJobAndTheOpenCase() {
        val list=listOf(Bot("Customer Desk","Customers and their vehicles",apps=listOf("Supabase"),skills=listOf("estimates")),Bot("Personal Companion","Personal assistant, separate from the shop"))
        val text=CasePlanner.instructions(list,"Alpha",null)
        assertTrue(text,text.contains("- Customer Desk: Customers and their vehicles; works with Supabase; skills: estimates"))
        assertTrue(text.contains("{\"steps\":[") && text.contains("FEW steps") && text.contains("never mix them") && text.contains("one step to Alpha"))
        assertFalse(text.contains("\"case\":\"amend\"."))
        val open=CasePlanner.instructions(list,"Alpha",case2())
        assertTrue(open.contains("set \"case\":\"amend\""))
        val user=CasePlanner.userText("Owner: hi",  "find pads",case2())
        assertTrue(user,user.startsWith("Recent group chat:\nOwner: hi") && user.contains("Request still being worked on:") && user.endsWith("New message from the owner: find pads"))
        assertEquals("New message from the owner: x",CasePlanner.userText("","x",null))
    }

    @Test fun theLeadIsFoundInOnePlace() {
        // The planner falls back to the lead bot alone; the lead is whatever the app says (the Controller's leadBot()).
        assertEquals("Chief",CaseRules.fallback("Chief","x").steps[0].bot)
    }
}
