package com.alphainternational.assistant

import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId

/** The first screen: neutral ideas, the thin status line, which look is on, and the daily notification limit. */
class FrontDoorTest {
    @Test fun suggestionsStartGeneralAndNeverMentionTheShopForFriends() {
        val friends=FrontDoor.suggestions("",true)
        assertEquals(FrontDoor.general,friends)
        assertTrue(friends.none {it.contains("estimate",true) || it.contains("customer",true)})
        // Even if a shop name leaked into a friends copy, the shop ideas stay out.
        assertEquals(FrontDoor.general,FrontDoor.suggestions("Rosa's Bakery",true))
    }

    @Test fun theOwnersShopIdeasComeAfterTheGeneralOnes() {
        val owner=FrontDoor.suggestions("Acme Auto Repair",false)
        assertEquals(FrontDoor.general,owner.take(FrontDoor.general.size))
        assertTrue(owner.drop(FrontDoor.general.size).containsAll(listOf("Make me an estimate","Text a customer their car is ready")))
        assertEquals(FrontDoor.general,FrontDoor.suggestions("",false))
    }

    @Test fun aTappedIdeaFillsTheStartOfASentence() {
        assertEquals("Summarize my day",FrontDoor.fill("Summarize my day"))
        assertTrue(FrontDoor.fill("Draft a message").endsWith(" "))
        assertTrue(FrontDoor.fill("Find something online").startsWith("Find something online"))
        assertEquals("Make me an estimate",FrontDoor.fill("Make me an estimate"))
    }

    @Test fun theTeamChatRowAppearsOnlyWithThreeOrMoreBots() {
        assertFalse(FrontDoor.showTeamRow(1))
        assertFalse(FrontDoor.showTeamRow(2))
        assertTrue(FrontDoor.showTeamRow(3))
        assertTrue(FrontDoor.showTeamRow(12))
    }

    @Test fun anIdleHomeSaysNothing() {
        assertTrue(HomeStatus.segments(emptyList(),emptyList(),false,0).isEmpty())
        assertTrue(HomeStatus.segments(emptyList(),emptyList(),false,5).isEmpty())
    }

    @Test fun oneJobSaysWhoAndWhat() {
        val s=HomeStatus.segments(listOf("Alpha" to "Reading ebay.com"),emptyList(),false,0)
        assertEquals(1,s.size)
        assertEquals(HomeStatus.Kind.RUNNING,s[0].kind)
        assertEquals("Alpha is working · Reading ebay.com",s[0].text)
        assertEquals("Alpha",s[0].bot)
        assertEquals("Alpha is working",HomeStatus.segments(listOf("Alpha" to ""),emptyList(),false,0)[0].text)
    }

    @Test fun manyJobsAreCountedAndWaitingJobsAreNamedByTheirBot() {
        val s=HomeStatus.segments(listOf("Alpha" to "x","Personal" to "y","Sales" to "z"),listOf("Sales","Alpha"),false,0)
        assertEquals("3 jobs running",s[0].text)
        assertEquals("2 waiting for you",s[1].text)
        assertEquals("Sales",s[1].bot)
    }

    @Test fun awayModeShowsOnlyWhenOn() {
        assertEquals("Away mode on · 2 waiting",HomeStatus.segments(emptyList(),emptyList(),true,2)[0].text)
        assertEquals("Away mode on",HomeStatus.segments(emptyList(),emptyList(),true,0)[0].text)
        assertEquals(HomeStatus.Kind.AWAY,HomeStatus.segments(emptyList(),emptyList(),true,0)[0].kind)
    }

    @Test fun theLooksAreDarkLightOrFollowThePhone() {
        assertFalse(lookIsLight("dark",false))
        assertTrue(lookIsLight("light",true))
        assertTrue(lookIsLight("system",false))
        assertFalse(lookIsLight("system",true))
        // Anything unknown (an old or damaged save) stays on the default dark look.
        assertFalse(lookIsLight("",false))
        assertFalse(lookIsLight("neon",false))
    }

    @Test fun urgentNotificationsAlwaysGetThrough() {
        val job="3f2b1c9e-8a4d-4e0b-9c1a-2d3e4f5a6b7c"
        assertTrue(NoticeBudget.urgent(job,"chat"))
        assertTrue(NoticeBudget.urgent("anything","review"))
        assertTrue(NoticeBudget.urgent("reply:$job","chat"))
        assertTrue(NoticeBudget.urgent("stop:$job","chat"))
        assertTrue(NoticeBudget.urgent("team:open:abc","team"))
        assertTrue(NoticeBudget.urgent("handoff:1","chat"))
        assertFalse(NoticeBudget.urgent("routine:$job","chat"))
        assertFalse(NoticeBudget.urgent("away-off","chat"))
        assertFalse(NoticeBudget.urgent("some-new-nudge","chat"))
    }

    @Test fun otherNotificationsStopAtEightADayAndNeverShowWhenQuiet() {
        assertTrue(NoticeBudget.allow(quiet=false,urgent=false,sentToday=0))
        assertTrue(NoticeBudget.allow(quiet=false,urgent=false,sentToday=NoticeBudget.DAILY_CAP-1))
        assertFalse(NoticeBudget.allow(quiet=false,urgent=false,sentToday=NoticeBudget.DAILY_CAP))
        assertFalse(NoticeBudget.allow(quiet=true,urgent=false,sentToday=0))
        assertTrue(NoticeBudget.allow(quiet=true,urgent=true,sentToday=500))
        assertTrue(NoticeBudget.allow(quiet=false,urgent=true,sentToday=500))
        assertEquals(8,NoticeBudget.DAILY_CAP)
    }

    @Test fun theDailyCountResetsEachDay() {
        val utc=ZoneId.of("UTC")
        assertEquals("1970-01-01",NoticeBudget.dayKey(0L,utc))
        assertEquals("1970-01-02",NoticeBudget.dayKey(86_400_000L,utc))
        val saved=org.json.JSONObject().put("notices",org.json.JSONObject().put("day","2026-10-10").put("n",5))
        assertEquals(5,NoticeBudget.sentOn(saved,"2026-10-10"))
        assertEquals(0,NoticeBudget.sentOn(saved,"2026-10-11"))
        assertEquals(0,NoticeBudget.sentOn(org.json.JSONObject(),"2026-10-10"))
    }
}
