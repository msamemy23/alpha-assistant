package com.alphainternational.assistant

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** Noticing: how many alerts reach the phone, when, never twice; and the cheap checks for clashes and deadlines. */
class NoticesTest {
    private val zone=ZoneId.of("America/Chicago")
    private fun z(d:Int,h:Int,m:Int=0)=ZonedDateTime.of(2026,10,d,h,m,0,0,zone)
    private fun ms(z:ZonedDateTime)=z.toInstant().toEpochMilli()
    /** Saturday, October 10, 2026, noon. */
    private val noon=ms(z(10,12))
    private val settings=ProSettings()
    private fun notice(key:String,priority:Int=1,expires:Long=0,now:Long=noon)=Notice.found(key,"Title $key","Saw $key","Gmail","Do it","do the thing","because",priority,now,expires)

    @Test fun quietHoursWrapAroundMidnight() {
        assertTrue(Quiet.isQuiet(21*60,21*60,7*60));assertTrue(Quiet.isQuiet(23*60+59,21*60,7*60));assertTrue(Quiet.isQuiet(0,21*60,7*60));assertTrue(Quiet.isQuiet(6*60+59,21*60,7*60))
        assertFalse(Quiet.isQuiet(7*60,21*60,7*60));assertFalse(Quiet.isQuiet(12*60,21*60,7*60));assertFalse(Quiet.isQuiet(20*60+59,21*60,7*60))
        // a plain daytime window, and none at all
        assertTrue(Quiet.isQuiet(13*60,12*60,14*60));assertFalse(Quiet.isQuiet(15*60,12*60,14*60))
        assertFalse(Quiet.isQuiet(3*60,0,0))
        assertTrue(Quiet.isQuiet(ms(z(10,22)),settings,zone));assertFalse(Quiet.isQuiet(ms(z(10,8)),settings,zone))
        assertEquals("9:00 PM",Quiet.label(21*60));assertEquals("7:30 AM",Quiet.label(7*60+30))
    }

    @Test fun aNightTimeWaitsForTheMorning() {
        assertEquals(ms(z(11,7)),Quiet.nextOpen(ms(z(10,23,30)),settings,zone))
        assertEquals(ms(z(10,7)),Quiet.nextOpen(ms(z(10,2)),settings,zone))
        assertEquals(ms(z(10,12)),Quiet.nextOpen(ms(z(10,12)),settings,zone))
        assertEquals("exactly when quiet hours begin is still quiet",ms(z(11,7)),Quiet.nextOpen(ms(z(10,21)),settings,zone))
    }

    @Test fun settingsLoadFromOldDataAndStayInRange() {
        assertEquals(ProSettings(),ProSettings.parse(null));assertEquals(ProSettings(),ProSettings.parse(JSONObject("{}")))
        val odd=ProSettings.parse(JSONObject("""{"budget":99,"quiet_from":5000,"quiet_to":-4,"brief_time":"banana","brief_on":true}"""))
        assertEquals(10,odd.budget);assertEquals(1439,odd.quietFrom);assertEquals(0,odd.quietTo);assertEquals("07:30",odd.briefTime);assertTrue(odd.briefOn)
        val mine=ProSettings(paused=true,noticing=false,budget=5,quietFrom=22*60,quietTo=6*60,briefOn=true,briefTime="06:45",briefBot="Chief of Staff",quickPass=true)
        assertEquals(mine,ProSettings.parse(JSONObject(mine.json().toString())))
        assertFalse("the brief is off until the owner turns it on",ProSettings().briefOn)
        assertEquals(3,ProSettings().budget)
    }

    @Test fun onlyTheBudgetReachesThePhoneAndTheMostImportantGoFirst() {
        val found=listOf(notice("low",1),notice("mid",3),notice("high",5),notice("mid2",3),notice("top",5))
        val first=NoticeRules.offer(NoticeBook(),found,settings,noon,zone)
        assertEquals(3,first.push.size)
        assertEquals(setOf("high","top"),first.push.map {it.key}.take(2).toSet())
        assertEquals(3,NoticeRules.pushedToday(first.book,noon,zone))
        assertEquals("the rest wait",2,first.book.items.count {it.state=="waiting"})
        assertEquals(0,NoticeRules.budgetLeft(first.book,settings,noon,zone))
        // later the same day: nothing more, however many are waiting
        val again=NoticeRules.offer(first.book,listOf(notice("late",5)),settings,noon+3_600_000L,zone)
        assertTrue(again.push.isEmpty())
        // the next morning the waiting ones may go, three at a time
        val next=NoticeRules.offer(again.book,emptyList(),settings,ms(z(11,8)),zone)
        assertEquals(3,next.push.size)
        assertEquals("late",next.push.first().key)
        // a budget of one means one
        assertEquals(1,NoticeRules.offer(NoticeBook(),found,settings.copy(budget=1),noon,zone).push.size)
    }

    @Test fun nothingIsPushedInQuietHoursButItWaitsForTheMorning() {
        val night=ms(z(10,22))
        val atNight=NoticeRules.offer(NoticeBook(),listOf(notice("a",5,now=night)),settings,night,zone)
        assertTrue(atNight.push.isEmpty());assertEquals(1,atNight.book.items.size);assertEquals("waiting",atNight.book.items.single().state)
        val morning=NoticeRules.offer(atNight.book,emptyList(),settings,ms(z(11,7,5)),zone)
        assertEquals(listOf("a"),morning.push.map {it.key})
        assertEquals("pushed",morning.book.items.single().state)
    }

    @Test fun theSameThingIsNeverOfferedTwice() {
        val first=NoticeRules.offer(NoticeBook(),listOf(notice("clash:e1+e2")),settings,noon,zone)
        assertEquals(1,first.push.size)
        val again=NoticeRules.offer(first.book,listOf(notice("clash:e1+e2")),settings,noon+3_600_000L,zone)
        assertTrue(again.push.isEmpty());assertEquals(1,again.book.items.size)
        // not even after the owner dismissed it
        val dismissed=again.book.with(first.push.single().id,"dismissed")
        val third=NoticeRules.offer(dismissed,listOf(notice("clash:e1+e2")),settings,ms(z(12,12)),zone)
        assertTrue(third.push.isEmpty());assertEquals(0,third.book.open().size)
        // two scans finding the same thing in one go are one notice
        assertEquals(1,NoticeRules.offer(NoticeBook(),listOf(notice("same"),notice("same")),settings,noon,zone).book.items.size)
    }

    @Test fun pausedOrSwitchedOffMeansSilence() {
        assertTrue(NoticeRules.offer(NoticeBook(),listOf(notice("a")),settings.copy(paused=true),noon,zone).let {it.push.isEmpty() && it.book.items.isEmpty()})
        assertTrue(NoticeRules.offer(NoticeBook(),listOf(notice("a")),settings.copy(noticing=false),noon,zone).let {it.push.isEmpty() && it.book.items.isEmpty()})
    }

    @Test fun somethingThatAlreadyHappenedIsDroppedAndNeverPushed() {
        val old=NoticeRules.offer(NoticeBook(),listOf(notice("gone",5,expires=noon-1)),settings,noon,zone)
        assertTrue(old.push.isEmpty());assertTrue(old.book.items.isEmpty())
        // waiting at night, then it passes before morning
        val night=ms(z(10,22))
        val waiting=NoticeRules.offer(NoticeBook(),listOf(notice("soon",5,expires=ms(z(11,1)),now=night)),settings,night,zone)
        val morning=NoticeRules.offer(waiting.book,emptyList(),settings,ms(z(11,8)),zone)
        assertTrue(morning.push.isEmpty());assertTrue(morning.book.items.isEmpty())
    }

    @Test fun oldNoticesAreTidiedAwayButTheirKeysAreRememberedLonger() {
        val first=NoticeRules.offer(NoticeBook(),listOf(notice("x")),settings,noon,zone)
        val later=noon+20*86_400_000L
        val tidy=NoticeRules.offer(first.book,emptyList(),settings,later,zone)
        assertTrue(tidy.book.items.isEmpty());assertTrue(tidy.book.seen.containsKey("x"))
        assertTrue("still not offered again",NoticeRules.offer(tidy.book,listOf(notice("x")),settings,later,zone).push.isEmpty())
        val forgotten=NoticeRules.offer(tidy.book,listOf(notice("x")),settings,noon+70*86_400_000L,zone)
        assertEquals(1,forgotten.push.size)
        val saved=NoticeBook.parse(JSONObject(first.book.json().toString()))
        assertEquals(first.book.items,saved.items);assertEquals(first.book.seen,saved.seen)
    }

    // ---- two things at once ----
    private fun event(id:String,title:String,start:ZonedDateTime,end:ZonedDateTime,extra:String="")=JSONObject("""{"id":"$id","summary":"$title","start":{"dateTime":"${start.toOffsetDateTime()}"},"end":{"dateTime":"${end.toOffsetDateTime()}"}$extra}""")

    @Test fun onlyRealTimedEventsCount() {
        assertNotNull(Calendars.parse(event("e1","Oil change",z(11,14),z(11,15)),"Work"))
        assertNull(Calendars.parse(JSONObject("""{"id":"a","summary":"Holiday","start":{"date":"2026-10-12"},"end":{"date":"2026-10-13"}}"""),"Work"))
        assertNull(Calendars.parse(event("e2","Cancelled",z(11,14),z(11,15),""","status":"cancelled" """),"Work"))
        assertNull(Calendars.parse(event("e3","Free time",z(11,14),z(11,15),""","transparency":"transparent" """),"Work"))
        assertNull(Calendars.parse(event("e4","Declined",z(11,14),z(11,15),""","attendees":[{"self":true,"responseStatus":"declined"}] """),"Work"))
        assertNotNull(Calendars.parse(event("e5","Accepted",z(11,14),z(11,15),""","attendees":[{"self":true,"responseStatus":"accepted"},{"responseStatus":"declined"}] """),"Work"))
        assertNull(Calendars.parse(event("e6","Home",z(11,0),z(11,23),""","eventType":"workingLocation" """),"Work"))
        assertNull(Calendars.parse(event("e7","Backwards",z(11,15),z(11,14)),"Work"))
        assertEquals("Oil change",Calendars.parse(event("e1","Oil change",z(11,14),z(11,15)),"Work")!!.title)
    }

    @Test fun overlappingEventsClashAndTouchingOnesDoNot() {
        fun e(id:String,s:ZonedDateTime,e:ZonedDateTime,title:String=id)=CalEvent(id,title,ms(s),ms(e))
        val a=e("a",z(11,14),z(11,15));val b=e("b",z(11,14,30),z(11,15,30))
        val clash=Calendars.clashes(listOf(b,a),noon).single()
        assertEquals("a",clash.a.id);assertEquals("b",clash.b.id);assertEquals(30,clash.overlapMinutes)
        assertTrue("back to back is fine",Calendars.clashes(listOf(a,e("c",z(11,15),z(11,16))),noon).isEmpty())
        assertTrue("under five minutes is sloppy timing, not a clash",Calendars.clashes(listOf(a,e("d",z(11,14,57),z(11,16))),noon).isEmpty())
        assertTrue("the same event twice is one",Calendars.clashes(listOf(a,a.copy()),noon).isEmpty())
        assertTrue("the same meeting on two calendars is not a clash",Calendars.clashes(listOf(a,e("a2",z(11,14),z(11,15),"a")),noon).isEmpty())
        assertTrue("events that are over are ignored",Calendars.clashes(listOf(a,b),ms(z(11,16))).isEmpty())
        assertEquals("one event inside another",1,Calendars.clashes(listOf(e("big",z(11,9),z(11,17)),e("small",z(11,12),z(11,13))),noon).size)
        assertEquals(3,Calendars.clashes(listOf(e("x",z(11,9),z(11,12)),e("y",z(11,10),z(11,13)),e("z",z(11,11),z(11,14))),noon).size)
    }

    @Test fun aClashBecomesOneNoticeWithOneAction() {
        val a=CalEvent("a","Oil change – Mike",ms(z(11,14)),ms(z(11,15)));val b=CalEvent("b","Dentist",ms(z(11,14,30)),ms(z(11,15,30)))
        val n=Calendars.notice(Calendars.clashes(listOf(a,b),noon).single(),noon,zone,"Chief of Staff")
        assertEquals("Two things overlap tomorrow",n.title)
        assertTrue(n.saw.contains("Oil change – Mike") && n.saw.contains("Dentist") && n.saw.contains("30 minutes"))
        assertEquals("Google Calendar",n.source);assertEquals("Suggest a fix",n.action);assertEquals("Chief of Staff",n.bot)
        assertEquals("more than a day away",4,n.priority);assertEquals("within a day is urgent",5,Calendars.notice(Calendars.clashes(listOf(a,b),noon).single(),ms(z(10,20)),zone).priority);assertEquals(ms(z(11,15)),n.expires)
        assertFalse("the event names stay out of the job's instructions",n.actionPrompt.contains("Dentist"))
        assertEquals("the same pair has the same key",n.key,Calendars.notice(Calendars.clashes(listOf(b,a),noon).single(),noon+1000,zone).key)
    }

    // ---- a deadline in the mail ----
    private val saturday=z(10,14)
    private fun find(subject:String,text:String)=Deadlines.find(subject,text,saturday)

    @Test fun deadlinesInTheNextTwoDaysAreFound() {
        assertEquals(ms(z(11,23,59)),find("Invoice","Your invoice is due tomorrow.")!!.at)
        assertEquals(ms(z(11,23,59)),find("Bill","Payment due Oct 11, 2026. Thank you.")!!.at)
        assertEquals(ms(z(11,23,59)),find("Reminder","Please respond by Sunday so we can book the date.")!!.at)
        assertEquals(ms(z(10,17)),find("Heads up","The deadline is today at 5pm.")!!.at)
        assertEquals(ms(z(10,17)),find("Form","The form must be submitted by end of day today")!!.at)
        assertEquals(ms(z(10,17)),find("Report","Send it by EOD.")!!.at)
        assertNotNull(find("Invoice due tomorrow","See attached."))
        assertNotNull(find("Quote","We need an answer in 24 hours. The offer expires in 2 days."))
        assertEquals("the earliest of several",ms(z(10,23,59)),find("Notice","The deadline for A is tomorrow. The deadline for B is today.")!!.at)
        assertEquals("Your invoice is due tomorrow.",find("Invoice","Hello Sam. Your invoice is due tomorrow. Thanks.")!!.sentence)
    }

    @Test fun otherMailIsLeftAlone() {
        assertNull("no deadline word",find("Hello","See you tomorrow at the shop."))
        assertNull("a deadline word but no date",find("Hello","The deadline is approaching fast."))
        assertNull("too far away",find("Hello","This is due Oct 30."))
        assertNull("already over",find("Hello","This was due yesterday."))
        assertNull("a date but no deadline word",find("Lunch","Let's meet Sunday for lunch."))
        assertNull("nothing at all",find("",""))
        assertTrue(Deadlines.promotional("Sale ends tonight! 50% off, limited time offer. Unsubscribe here."))
        assertFalse(Deadlines.promotional("Your electric bill is due tomorrow. Pay online."))
        assertFalse("one promo word alone is not enough",Deadlines.promotional("Your order qualifies for free shipping."))
    }

    @Test fun aDeadlineBecomesANoticeThatQuotesWhereItCameFrom() {
        val hit=find("Invoice","Hello Sam. Your invoice is due tomorrow. Thanks.")!!
        val n=Deadlines.notice("m1","Invoice 1042","Acme Billing <billing@acme.com>",hit,saturday.toInstant().toEpochMilli(),zone,"Alpha")
        assertEquals("A deadline tomorrow",n.title)
        assertTrue(n.saw.contains("Acme Billing") && n.saw.contains("Invoice 1042") && n.saw.contains("due tomorrow"))
        assertEquals("Gmail",n.source);assertEquals("Draft the reply",n.action);assertEquals("deadline:m1",n.key)
        assertTrue(n.actionPrompt.contains("m1") && n.actionPrompt.contains("gmail_draft") && n.actionPrompt.contains("Do not send"))
        assertFalse("what the email said is data, kept out of the instructions",n.actionPrompt.contains("Hello Sam"))
        assertEquals(4,n.priority)
    }
}
