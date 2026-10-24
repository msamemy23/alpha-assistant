package com.alphainternational.assistant

import org.junit.Assert.*
import org.junit.Test

/** What the ongoing notification and the Home line say about running work, and how a growing answer is tidied. */
class ProgressNoteTest {
    private fun job(id:String,bot:String="Alpha",status:String="Running",steps:List<String> = emptyList(),detail:String="",created:Long=1,from:String="") =
        TaskRecord(id,"t","p",status,detail,created,workspace=bot,steps=steps,from=from)

    private val plan="""[{"id":"a","description":"Find the parts","kind":"live"},{"id":"b","description":"Compare prices","kind":"live"},{"id":"c","description":"Write it up","kind":"answer"}]"""

    @Test fun aPlanGivesStepXOfY() {
        val first=ProgressNote.fromPlan(plan,"")!!
        assertEquals(1,first.current);assertEquals(3,first.total);assertEquals("Find the parts",first.line)
        val second=ProgressNote.fromPlan(plan,"""{"checks":[{"id":"a","status":"passed"}]}""")!!
        assertEquals(2,second.current);assertEquals("Compare prices",second.line)
        val done=ProgressNote.fromPlan(plan,"""{"checks":[{"id":"a","status":"passed"},{"id":"b","status":"passed"},{"id":"c","status":"passed"}]}""")!!
        assertEquals(3,done.current)
    }

    @Test fun noPlanMeansNoCount() {
        assertNull(ProgressNote.fromPlan("[]",""))
        assertNull(ProgressNote.fromPlan("""[{"id":"a","description":"Just one"}]""",""))
        assertNull(ProgressNote.fromPlan("not json",""))
    }

    @Test fun theLineIsTheLatestStepOrTheCount() {
        assertEquals("Read ebay.com",ProgressNote.line(null,"Read ebay.com","web fetch"))
        assertEquals("Step 2 of 3: Compare prices",ProgressNote.line(StepProgress(2,3,"Compare prices"),"Read ebay.com",""))
        assertEquals("Step 2 of 3: Read ebay.com",ProgressNote.line(StepProgress(2,3),"Read ebay.com",""))
        assertEquals("Reading the web",ProgressNote.line(null,"","web fetch"))
        assertEquals("Getting started",ProgressNote.line(null,"",""))
        // A "plan" of one step is not worth counting.
        assertEquals("Read ebay.com",ProgressNote.line(StepProgress(1,1,"x"),"Read ebay.com",""))
        assertTrue(ProgressNote.line(null,"y".repeat(300),"").length<=90)
    }

    @Test fun oneRunningJobGetsItsOwnTitleAndStopButton() {
        val note=ProgressNote.build(listOf(job("j1",steps=listOf("Searched for brake pads","Read autozone.com"))),setOf("j1")) {null}!!
        assertEquals("Alpha is working",note.title)
        assertEquals("Read autozone.com",note.text)
        assertEquals("j1",note.stopId)
        assertEquals("",note.waitingId)
    }

    @Test fun severalJobsAreCountedAndTheNewestOneLeads() {
        val tasks=listOf(job("old","Alpha",created=1,steps=listOf("Old step")),job("new","Personal",created=9,steps=listOf("New step")))
        val note=ProgressNote.build(tasks,setOf("old","new")) {null}!!
        assertEquals("2 jobs are running",note.title)
        assertEquals("Personal: New step",note.text)
        assertEquals("new",note.stopId)
    }

    @Test fun aJobThatWaitsForTheOwnerIsMentionedAndOpenable() {
        val tasks=listOf(job("run",steps=listOf("Working")),job("wait","Sales","Needs review"))
        val note=ProgressNote.build(tasks,setOf("run")) {null}!!
        assertTrue(note.text,note.text.endsWith("1 waiting for you"))
        assertEquals("wait",note.waitingId)
        assertTrue(note.waitingIsReview)
        assertEquals("Sales",note.waitingBot)
    }

    @Test fun nothingRunningMeansNoNote() {
        assertNull(ProgressNote.build(emptyList(),emptySet()) {null})
        assertNull(ProgressNote.build(listOf(job("j",status="Paused")),setOf("j")) {null})
        // Away mode's helper jobs never show up in the owner's progress.
        assertNull(ProgressNote.build(listOf(job("a",from="away-1")),setOf("a")) {null})
    }

    @Test fun theCountComesFromTheProgressSource() {
        val note=ProgressNote.build(listOf(job("j1",steps=listOf("Read ebay.com"))),setOf("j1")) {StepProgress(3,6,"Compare prices")}!!
        assertEquals("Step 3 of 6: Compare prices",note.text)
    }

    @Test fun aGrowingAnswerHidesItsFollowUpButtonsBlock() {
        assertEquals("The answer.",LiveText.clean("The answer.\n\n```followups\n- One\n- Two\n```"))
        assertEquals("The answer.",LiveText.clean("The answer.\n\n```followups\n- One\n- Tw"))
        assertEquals("The answer.",LiveText.clean("The answer.\n```follow"))
        assertEquals("The answer.",LiveText.clean("The answer.\n```"))
        assertEquals("Code:\n```kotlin",LiveText.clean("Code:\n```kotlin"))
        // A finished code block keeps its closing fence.
        assertEquals("Here:\n```\nx=1\n```",LiveText.clean("Here:\n```\nx=1\n```"))
        assertEquals("Plain words",LiveText.clean("Plain words  "))
    }

    @Test fun theGrowingAnswerStepsAsideWhenTheFinishedOneLands() {
        val final="# The used pair is the best value\n\nBoth fit the 2019 Pilot."
        assertTrue(LiveText.superseded("# The used pair is the best value\n\nBoth fit",final))
        assertTrue(LiveText.superseded("The used pair is the best value",final))
        assertFalse(LiveText.superseded("Something else entirely",final))
        assertFalse(LiveText.superseded("",final))
        assertFalse(LiveText.superseded("text",""))
    }

    @Test fun theGrowingBubbleShowsOnlyRealWordsNotYetPosted() {
        assertFalse(LiveText.show("",null))
        assertFalse(LiveText.show("   ",null))
        assertFalse(LiveText.show("```followups\n- a",null))
        assertTrue(LiveText.show("Here is what I found",null))
        assertTrue(LiveText.show("Here is what I found","On it 👍 I'll text you when it's done."))
        assertFalse(LiveText.show("Here is what I found","Here is what I found, in full."))
    }
}
