package com.alphainternational.assistant

import org.junit.Assert.*
import org.junit.Test

class PlanTest {
    private val steps=listOf("Find the three best shops","Compare their prices","Check their reviews","Write up the answer")

    @Test fun aPlanStartsOnItsFirstStep() {
        val plan=Plan.set(steps)
        val items=Plan.items(plan.toString())
        assertEquals(4,items.size)
        assertEquals(listOf("doing","open","open","open"),items.map {it.status})
        assertEquals(listOf("1","2","3","4"),items.map {it.id})
        assertEquals("Progress: 0 of 4 done, now: Find the three best shops.",Plan.line(plan.toString()))
        assertEquals("Step 1 of 4: Find the three best shops",Plan.progress(plan.toString())!!.live)
    }

    @Test fun progressMovesAsStepsAreMarked() {
        var plan=Plan.set(steps).toString()
        plan=Plan.update(plan,"1","done").toString()
        // Nothing is "doing" yet, so the next open step is the one it is on.
        assertEquals("Progress: 1 of 4 done, now: Compare their prices.",Plan.line(plan))
        plan=Plan.update(plan,"2","doing").toString()
        plan=Plan.update(plan,"3","skipped").toString()
        assertEquals("Progress: 2 of 4 done, now: Compare their prices.",Plan.line(plan))
        val p=Plan.progress(plan)!!
        assertEquals(2,p.done);assertEquals(4,p.total);assertEquals(2,p.step);assertEquals("Step 2 of 4: Compare their prices",p.live)
        assertFalse(p.finished)
        plan=Plan.update(plan,"2","done").toString()
        assertEquals("Progress: 3 of 4 done, now: Write up the answer.",Plan.line(plan))
        plan=Plan.update(plan,"4","done").toString()
        assertEquals("Progress: all 4 steps are done.",Plan.line(plan))
        assertTrue(Plan.progress(plan)!!.finished)
        assertEquals("",Plan.progress(plan)!!.live)
        assertTrue(Plan.open(plan).isEmpty())
    }

    @Test fun stepsCanBeNamedInSeveralWays() {
        val plan=Plan.set(steps).toString()
        assertEquals("done",Plan.items(Plan.update(plan,"step 2","done").toString())[1].status)
        assertEquals("done",Plan.items(Plan.update(plan,"#3","DONE").toString())[2].status)
        assertEquals("skipped",Plan.items(Plan.update(plan," 4 ","skipped").toString())[3].status)
    }

    @Test fun badPlansAndUpdatesAreRefusedInPlainWords() {
        assertEquals("A plan needs at least two steps. For a one-step job, just do it.",assertThrows(IllegalArgumentException::class.java) {Plan.set(listOf("Do it"))}.message)
        assertThrows(IllegalArgumentException::class.java) {Plan.set(listOf("a","  ","\n"))}
        assertThrows(IllegalArgumentException::class.java) {Plan.set((1..13).map {"Step $it"})}
        assertThrows(IllegalArgumentException::class.java) {Plan.set(listOf("ok","x".repeat(141)))}
        val plan=Plan.set(steps).toString()
        assertTrue(assertThrows(IllegalArgumentException::class.java) {Plan.update(plan,"9","done")}.message!!.contains("Steps: 1, 2, 3, 4"))
        assertThrows(IllegalArgumentException::class.java) {Plan.update(plan,"1","finished")}
        assertTrue(assertThrows(IllegalArgumentException::class.java) {Plan.update("[]","1","done")}.message!!.contains("plan_set"))
        // Long text is cleaned up, not rejected.
        assertEquals("Find shops near the shop",Plan.items(Plan.set(listOf("  Find   shops\nnear the shop ","Next")).toString())[0].text)
    }

    @Test fun theOldOneLinePlanIsNotTheModelsPlan() {
        val implicit=AgentRules.implicitPlan("Make me an estimate").toString()
        assertNull(Plan.progress(implicit))
        assertEquals("",Plan.line(implicit))
        assertTrue(Plan.open(implicit).isEmpty())
        assertTrue(Plan.items("not json").isEmpty())
        assertTrue(Plan.items("[]").isEmpty())
    }

    @Test fun theNudgeNamesTheOpenStepsOnce() {
        var plan=Plan.set(steps).toString()
        plan=Plan.update(plan,"1","done").toString()
        val nudge=Plan.nudge(Plan.open(plan))
        assertTrue(nudge.contains("2. Compare their prices") && nudge.contains("4. Write up the answer") && !nudge.contains("Find the three"))
        assertTrue(nudge.contains("plan_update"))
    }

    @Test fun theTaskCarriesItsPlanThroughSavingAndLoading() {
        val plan=Plan.set(steps).toString()
        val task=TaskRecord("t","T","T","Running","",0,plan=plan)
        val back=TaskRecord.parse(task.json())
        assertEquals(plan,back.plan)
        assertEquals(4,Plan.progress(back.plan)!!.total)
    }
}
