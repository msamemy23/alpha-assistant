package com.alphainternational.assistant

import org.junit.Assert.*
import org.junit.Test

/**
 * The owner: it "cant ever get hung on anything... it has to finish the task with accuracy, fast and figure itself out". A job that keeps reaching new screens is left alone,
 * however long it takes; one that is going nowhere is walked up a ladder (change method, step back, another route, then stop and report) instead of trying the same thing for ever.
 */
class PhoneProgressTest {
    @Test fun aJobThatKeepsReachingNewScreensIsNeverInterrupted() {
        val p=PhoneProgress()
        repeat(40) {assertNull(p.record("step $it","screen $it",true))}
        assertEquals(0,p.rung)
    }

    @Test fun theSameStepsThreeTimesOnTheSameScreenAreCalledOutOnTheThird() {
        val p=PhoneProgress()
        assertNull(p.record("tap send","a",true))
        assertNull(p.record("tap send","a",true))
        val v=p.record("tap send","a",true)
        assertNotNull(v)
        assertEquals(1,v!!.rung)
        assertTrue(v.pattern,v.pattern.contains("same steps"))
        assertTrue(v.instruction.contains("change the method"))
        assertFalse(v.last)
    }

    @Test fun twoScreensSwappingPlacesAreCalledOut() {
        val p=PhoneProgress()
        assertNull(p.record("open x","a",true))
        assertNull(p.record("tap y","b",true))
        assertNull(p.record("back","a",true))
        val v=p.record("tap y again","b",true)
        assertNotNull(v)
        assertTrue(v!!.pattern,v.pattern.contains("flipping"))
    }

    @Test fun theSameFailureThreeTimesIsCalledOutEvenWhenTheScreenMoves() {
        val p=PhoneProgress()
        // the first sight of a screen counts as progress for the screen part; the repeated error is what gives it away
        p.record("type a","s1",false,"typing did not take")
        p.record("type b","s2",false,"typing did not take")
        p.record("type c","s1",false,"typing did not take")
        val v=p.record("type d","s2",false,"typing did not take")
        assertNotNull(v)
    }

    @Test fun aScreenThatNeverChangesIsCalledOutOnTheThirdTry() {
        val p=PhoneProgress()
        assertNull(p.record("one","a",true))
        assertNull(p.record("two","a",true))
        val v=p.record("three","a",true)
        assertNotNull(v)
        assertTrue(v!!.pattern,v.pattern.contains("did not change"))
    }

    @Test fun theLadderClimbsOneRungAtATimeAndTheLastOneTellsTheJobToStop() {
        val p=PhoneProgress()
        p.record("go","a",true)
        val rungs=ArrayList<Int>()
        var last=false
        repeat(60) {i->
            if(!p.finished) p.record("again $i","a",true)?.let {rungs+=it.rung;last=it.last}
        }
        assertEquals(listOf(1,2,3,4),rungs)
        assertTrue(last)
        assertTrue(p.finished)
        assertNotNull(p.refusal())
        assertTrue(p.refusal()!!.contains("Answer now"))
    }

    @Test fun theRungInstructionsMoveFromChangingMethodToAnotherRouteToReporting() {
        assertTrue(PhoneProgress.instruction(1,"x").contains("change the method"))
        assertTrue(PhoneProgress.instruction(2,"x").contains("screen_look"))
        assertTrue(PhoneProgress.instruction(3,"x").contains("another app or the website"))
        assertTrue(PhoneProgress.instruction(4,"x").contains("Stop trying"))
    }

    @Test fun aNewScreenTakesTheJobBackToTheBottomOfTheLadder() {
        val p=PhoneProgress()
        p.record("go","a",true)
        var v:PhoneProgress.Verdict?=null
        var i=0
        while(v==null && i<10) {v=p.record("again $i","a",true);i++}
        assertEquals(1,p.rung)
        assertNull(p.record("a different thing","fresh",true))
        assertEquals(0,p.rung)
    }

    /** A one-setting job spent six of its ten rounds on writing and ticking off a plan; a short request goes without those tools, a long list of jobs keeps them. */
    @Test fun aShortRequestIsAQuickPhoneJobAndALongListIsNot() {
        assertTrue(PhoneJobRules.isQuickJob("Open the Settings app, find a setting called Flux Capacitor Mode and turn it on. Tell me when it is on."))
        assertTrue(PhoneJobRules.isQuickJob("Open the Camera app and tell me which mode it is on right now and which other modes are listed. Do not take a photo."))
        assertFalse(PhoneJobRules.isQuickJob("x".repeat(300)))
        assertFalse(PhoneJobRules.isQuickJob("First open Chrome, then search for oil, then open Settings and finally check the battery"))
        assertFalse(PhoneJobRules.isQuickJob("Do these:\n1. open Clock\n2. set a timer\n3. open Notes"))
        assertEquals(setOf("plan_set","plan_update"),PhoneJobRules.PLAN_TOOLS)
    }

    @Test fun anAppKnownToRefuseTypedTextSaysSoWhenItIsOpened() {
        assertTrue(PhoneJobRules.tip("com.samsung.android.app.notes").contains("share"))
        assertTrue(PhoneJobRules.tip("com.android.settings").contains("Search settings"))
        assertTrue(PhoneJobRules.tip("com.google.android.gm").contains("phone_action"))
        assertEquals("",PhoneJobRules.tip("com.example.unknown"))
    }

    @Test fun aSlowButMovingJobWithRepeatedReadsInBetweenIsNotFlagged() {
        val p=PhoneProgress()
        // open, read, tap, read the same screen once more, move on: never three quiet calls
        listOf("a","a","b","b","c","c","d").forEachIndexed {i,s-> assertNull("call $i",p.record("step $i",s,true))}
    }
}
