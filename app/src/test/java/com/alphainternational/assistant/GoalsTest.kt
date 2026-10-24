package com.alphainternational.assistant

import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** Goals, check-backs and "done means done": the rules, with no phone and no AI. */
class GoalsTest {
    private val zone=ZoneId.of("America/Chicago")
    /** Saturday, October 10, 2026, 2:00 pm. */
    private val now=ZonedDateTime.of(2026,10,10,14,0,0,0,zone)
    private val t0=now.toInstant().toEpochMilli()
    private val hour=3_600_000L
    private fun ms(z:ZonedDateTime)=z.toInstant().toEpochMilli()
    private fun at(y:Int,mo:Int,d:Int,h:Int,mi:Int=0)=ms(ZonedDateTime.of(y,mo,d,h,mi,0,0,zone))

    @Test fun timesAreReadTheWayBotsWriteThem() {
        assertEquals(ms(now.plusHours(2)),When.parse("in 2 hours",now))
        assertEquals(ms(now.plusMinutes(30)),When.parse("in half an hour",now))
        assertEquals(ms(now.plusHours(1)),When.parse("in an hour",now))
        assertEquals(ms(now.plusDays(3)),When.parse("in 3 days",now))
        assertEquals(at(2026,10,11,8),When.parse("tomorrow 8:00",now))
        assertEquals(at(2026,10,11,8),When.parse("Tomorrow at 8am",now))
        assertEquals(at(2026,10,11,9),When.parse("tomorrow",now))
        assertEquals(at(2026,10,16,9),When.parse("friday 9am",now))
        assertEquals(at(2026,10,12,9),When.parse("next monday",now))
        assertEquals(at(2026,10,21,15),When.parse("Oct 21 3pm",now))
        assertEquals(at(2026,10,12,8),When.parse("2026-10-12 08:00",now))
        assertEquals(at(2026,10,12,8),When.parse("2026-10-12T08:00",now))
        assertEquals(at(2026,10,10,20),When.parse("tonight",now))
        assertEquals(at(2026,10,10,17,30),When.parse("17:30",now))
        // 9 am has already passed today, so it means tomorrow
        assertEquals(at(2026,10,11,9),When.parse("9am",now))
        assertEquals(at(2026,10,12,13,0),When.parse("2026-10-12T13:00:00-05:00",now))
        assertNull(When.parse("sometime soon",now))
        assertNull(When.parse("",now))
        assertNull(When.parse("whenever it is convenient for the customer",now))
    }

    @Test fun timesAreDescribedInPlainWords() {
        assertEquals("today 3:00 PM",When.describe(at(2026,10,10,15),t0,zone))
        assertEquals("tomorrow 8:00 AM",When.describe(at(2026,10,11,8),t0,zone))
        assertEquals("Fri 8:00 AM",When.describe(at(2026,10,16,8),t0,zone))
        assertEquals("Oct 21, 8:00 AM",When.describe(at(2026,10,21,8),t0,zone))
        assertEquals("no time set",When.describe(0,t0,zone))
        assertEquals("tomorrow",When.onDay(at(2026,10,11,14),t0,zone))
        assertEquals("on Wednesday",When.onDay(at(2026,10,14,14),t0,zone))
        assertEquals("5 min ago",When.ago(t0-5*60_000,t0))
        assertEquals("2 h ago",When.ago(t0-2*hour,t0))
        assertEquals("yesterday",When.ago(t0-30*hour,t0))
        assertEquals("never",When.ago(0,t0))
    }

    @Test fun aGoalMovesThroughItsStatesAndDoneStaysDone() {
        val (b1,g)=GoalBook().setGoal("Alpha","Collect payment from Mike",at(2026,10,11,8),"Invoice 1042",t0)
        assertEquals("active",g.status)
        val (b2,waiting)=b1.updateGoal(g.id,"waiting","Texted Mike",at(2026,10,12,8),null,t0+1000)
        assertEquals("waiting",waiting.status);assertEquals("Texted Mike",waiting.lastAction);assertEquals(at(2026,10,12,8),waiting.nextCheck)
        val (b3,done)=b2.updateGoal(g.id,"done",null,null,null,t0+2000)
        assertEquals("done",done.status);assertEquals(0L,done.nextCheck)
        // a bot can neither bring it back nor add to it
        assertThrows(IllegalArgumentException::class.java) {b3.updateGoal(g.id,"active",null,null,null,t0+3000)}
        assertThrows(IllegalArgumentException::class.java) {b3.updateGoal(g.id,null,"nagging again",null,null,t0+3000)}
        // the owner can
        assertEquals("active",b3.updateGoal(g.id,"active",null,null,null,t0+3000,byOwner=true).second.status)
        assertThrows(IllegalArgumentException::class.java) {b2.updateGoal(g.id,"banana",null,null,null,t0+3000)}
        assertThrows(IllegalArgumentException::class.java) {b2.updateGoal("nope","done",null,null,null,t0+3000)}
        // plain words for the same states
        assertEquals("done",GoalRules.status("Completed"));assertEquals("active",GoalRules.status("in progress"));assertEquals("waiting",GoalRules.status("blocked"));assertNull(GoalRules.status("banana"))
    }

    @Test fun theSameGoalIsNotCreatedTwiceAndTheLimitHolds() {
        val (b1,g1)=GoalBook().setGoal("Alpha","Fix the shop sign",0,"",t0)
        val (b2,g2)=b1.setGoal("Alpha","  fix the SHOP sign ",0,"call the sign guy",t0+5)
        assertEquals(g1.id,g2.id);assertEquals(1,b2.goals.size);assertEquals("call the sign guy",g2.notes)
        assertEquals(2,b2.setGoal("Personal","Fix the shop sign",0,"",t0).first.goals.size)
        assertThrows(IllegalArgumentException::class.java) {GoalBook().setGoal("Alpha","ab",0,"",t0)}
        var full=GoalBook()
        (1..GoalRules.MAX_OPEN_GOALS).forEach {full=full.setGoal("Alpha","Goal number $it",0,"",t0).first}
        assertThrows(IllegalArgumentException::class.java) {full.setGoal("Alpha","One goal too many",0,"",t0)}
    }

    @Test fun followUpsHaveTimeAndCountLimits() {
        fun check(due:Long,all:List<Followup> = emptyList(),bot:String="Alpha",what:String="see if Mike answered the quote",depth:Int=0,goals:List<Goal> = emptyList(),goalId:String="")=
            GoalRules.checkFollowup(all,goals,bot,what,due,t0,goalId,depth)
        assertTrue(check(t0+2*hour) is GoalRules.Check.Ok)
        assertTrue(check(t0+4*60_000) is GoalRules.Check.Error)
        assertTrue(check(t0+5*60_000) is GoalRules.Check.Ok)
        assertTrue(check(t0+30*24*hour) is GoalRules.Check.Ok)
        assertTrue(check(t0+31*24*hour) is GoalRules.Check.Error)
        assertTrue(check(t0+2*hour,what="soon") is GoalRules.Check.Error)
        assertTrue(check(t0+2*hour,depth=GoalRules.MAX_DEPTH) is GoalRules.Check.Error)
        assertTrue(check(t0+2*hour,depth=GoalRules.MAX_DEPTH-1) is GoalRules.Check.Ok)
        val five=(1..5).map {Followup("f$it","Alpha","check number $it please",t0+it*hour,created=t0)}
        assertTrue("a sixth is refused",check(t0+9*hour,five) is GoalRules.Check.Error)
        assertTrue("another bot has its own five",check(t0+9*hour,five,bot="Personal") is GoalRules.Check.Ok)
        // finished ones don't count against the five
        assertTrue(check(t0+9*hour,five.map {it.copy(status="done")}) is GoalRules.Check.Ok)
        // saying the same thing again returns the one already waiting
        val same=check(t0+3*hour,listOf(Followup("f9","Alpha","See if Mike answered the QUOTE!",t0+hour,created=t0)))
        assertTrue(same is GoalRules.Check.Same && same.existing.id=="f9")
        // a goal that is done, or does not exist, takes no follow-ups
        val done=Goal("g1","Old thing","Alpha","done")
        assertTrue(check(t0+hour,goals=listOf(done),goalId="g1") is GoalRules.Check.Error)
        assertTrue(check(t0+hour,goalId="missing") is GoalRules.Check.Error)
        val open=Goal("g2","Open thing","Alpha")
        val ok=check(t0+hour,goals=listOf(open),goalId="g2")
        assertTrue(ok is GoalRules.Check.Ok && ok.followup.goalId=="g2")
    }

    @Test fun aFollowUpIsSkippedQuietlyWhenItsGoalIsDoneOrGone() {
        val (b1,g)=GoalBook().setGoal("Alpha","Collect payment from Mike",0,"",t0)
        val f=(GoalRules.checkFollowup(b1.followups,b1.goals,"Alpha","see if Mike paid the invoice",t0+hour,t0,g.id) as GoalRules.Check.Ok).followup
        val b2=b1.addFollowup(f)
        assertTrue(b2.due(f.id,f.due+60_000) is GoalBook.Due.Run)
        val finished=b2.updateGoal(g.id,"done",null,null,null,t0+1000).first
        assertEquals("cancelled",finished.followup(f.id)!!.status)
        assertTrue(finished.due(f.id,f.due) is GoalBook.Due.Skip)
        assertTrue(b2.deleteGoal(g.id).due(f.id,f.due) is GoalBook.Due.Skip)
        // a paused goal does not chase either
        assertTrue(b2.updateGoal(g.id,"paused",null,null,null,t0+1000).first.due(f.id,f.due) is GoalBook.Due.Skip)
        // it runs once: after it is marked fired it is skipped
        val fired=b2.markFollowup(f.id,"fired","started",f.due)
        assertTrue(fired.due(f.id,f.due) is GoalBook.Due.Skip)
        assertTrue(b2.due("nothing",f.due) is GoalBook.Due.Skip)
    }

    @Test fun aFollowUpThatIsFarTooLateBecomesAMissedNoticeInsteadOfRunning() {
        val f=Followup("f1","Alpha","see if the part arrived",t0,created=t0-hour)
        val book=GoalBook(followups=listOf(f))
        val soonAfter=book.due("f1",t0+30*60_000)
        assertTrue(soonAfter is GoalBook.Due.Run && soonAfter.late)
        assertTrue(book.due("f1",t0+7*hour) is GoalBook.Due.Stale)
        assertEquals(listOf("f1"),book.late(t0+7*hour).map {it.id})
        assertTrue(book.late(t0+hour).isEmpty())
        val notice=NoticeScans.missed(f,t0+7*hour)
        assertEquals("I missed a check-back",notice.title);assertTrue(notice.saw.contains("see if the part arrived"))
    }

    @Test fun aGoalWhoseNextCheckPassedWithNoUpdateIsOverdue() {
        val (b1,g)=GoalBook().setGoal("Alpha","Chase the parts order",t0-hour,"",t0-3*hour)
        assertEquals(listOf(g.id),b1.overdue(t0).map {it.id})
        assertTrue("not yet, within the grace time",b1.overdue(t0-hour+10*60_000).isEmpty())
        // a progress note clears a check time that has passed, so it is not overdue any more
        val (b2,updated)=b1.updateGoal(g.id,null,"Called the supplier",null,null,t0)
        assertEquals(0L,updated.nextCheck);assertTrue(b2.overdue(t0+hour).isEmpty())
        // paused and done goals never nag
        assertTrue(b1.updateGoal(g.id,"paused",null,null,null,t0-2*hour,true).first.overdue(t0).isEmpty())
        assertTrue(b1.updateGoal(g.id,"done",null,null,null,t0,true).first.overdue(t0+hour).isEmpty())
        val notice=NoticeScans.goals(b1,t0).single()
        assertTrue(notice.title.startsWith("Still open"));assertEquals("Check on it now",notice.action)
    }

    @Test fun bookSurvivesBeingSavedAndOldDataStillLoads() {
        val (b1,g)=GoalBook().setGoal("Alpha","Collect payment from Mike",t0+hour,"notes",t0)
        val f=(GoalRules.checkFollowup(b1.followups,b1.goals,"Alpha","see if Mike paid the invoice",t0+hour,t0,g.id,1,true,listOf("mike@shop.com")) as GoalRules.Check.Ok).followup
        val book=b1.addFollowup(f).withNag(Nag("t1",g.id,f.id,"Alpha",t0))
        val back=GoalBook.parse(org.json.JSONObject(book.json().toString()))
        assertEquals(book.goals,back.goals);assertEquals(book.followups,back.followups);assertEquals(book.nag,back.nag)
        assertTrue(back.followups.single().tellEither);assertEquals(listOf("mike@shop.com"),back.followups.single().sendTo)
        assertEquals(GoalBook(),GoalBook.parse(null));assertEquals(GoalBook(),GoalBook.parse(org.json.JSONObject("{}")))
        // a goal saved by an older build with no status still loads as active
        assertEquals("active",Goal.parse(org.json.JSONObject("""{"id":"x","title":"Old"}""")).status)
        // finished follow-ups are tidied away after the newest few
        var many=GoalBook()
        (1..40).forEach {many=many.addFollowup(Followup("d$it","Alpha","old thing number $it",t0-it*hour,status="done",firedAt=t0-it*hour))}
        many=many.addFollowup(Followup("p","Alpha","still waiting thing",t0+hour))
        assertEquals(31,many.followups.size);assertEquals(1,many.pending().size)
    }

    // ---- "done" means done ----
    private val goals=listOf(Goal("g1","Collect payment from Mike for invoice 1042","Alpha"),Goal("g2","Order brake pads for the Camry","Parts"))
    private val ups=listOf(Followup("f1","Alpha","see if Mike paid invoice 1042",t0+hour,goalId="g1",created=t0-hour),Followup("f2","Parts","check if the brake pads shipped",t0+hour,goalId="g2",created=t0-hour))
    private val nag=Nag("t1","g1","f0","Alpha",t0-hour)

    @Test fun aBareDoneAnswersTheLastAutomaticMessage() {
        listOf("done","Done.","that's handled","That's handled, thanks","never mind","Nevermind!","stop","all set","I handled it","it's taken care of","already done").forEach {text->
            val r=StopRules.resolve(text,goals,ups,nag,true,t0)
            assertEquals(text,setOf("g1"),r.goalIds);assertEquals(text,setOf("f1"),r.followupIds)
        }
        // only when that message really was the last thing, and recent
        assertTrue(StopRules.resolve("done",goals,ups,nag,false,t0).empty)
        assertTrue(StopRules.resolve("done",goals,ups,nag,true,t0+StopRules.NAG_WINDOW_MS).empty)
        assertTrue(StopRules.resolve("done",goals,ups,null,true,t0).empty)
    }

    @Test fun aNamedThingIsClosedAndNothingElse() {
        val brakes=StopRules.resolve("Stop reminding me about the Camry brake pads",goals,ups,null,false,t0)
        assertEquals(setOf("g2"),brakes.goalIds);assertEquals(setOf("f2"),brakes.followupIds)
        val mike=StopRules.resolve("Mike paid, that's handled",goals,ups,null,false,t0)
        assertEquals(setOf("g1"),mike.goalIds);assertEquals(setOf("f1"),mike.followupIds)
        val book=GoalBook(goals,ups).closeFor(mike,t0)
        assertEquals("done",book.goal("g1")!!.status);assertEquals("active",book.goal("g2")!!.status)
        assertEquals("cancelled",book.followup("f1")!!.status);assertEquals("pending",book.followup("f2")!!.status)
    }

    @Test fun ordinaryTalkNeverClosesAnything() {
        assertTrue(StopRules.resolve("I'm done for the day, thanks",goals,ups,nag,true,t0).empty)
        assertTrue(StopRules.resolve("never mind the weather, what about the Camry brake pads order and the invoice from Mike and when will it all be paid and shipped and delivered to the shop on Main Street this week",goals,ups,nag,true,t0).empty)
        assertTrue(StopRules.resolve("Can you stop by the shop and pick up the Camry brake pads?",goals,ups,nag,true,t0).empty)
        assertTrue(StopRules.resolve("Remind me about the Camry brake pads tomorrow",goals,ups,nag,true,t0).empty)
        assertTrue("an unrelated topic is not guessed",StopRules.resolve("stop reminding me about the dentist",goals,ups,null,false,t0).empty)
        assertTrue(StopRules.resolve("",goals,ups,nag,true,t0).empty)
        assertFalse(StopRules.isBare("done with lunch what's next"))
    }
}
