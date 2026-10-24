package com.alphainternational.assistant

import org.junit.Assert.*
import org.json.JSONObject
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * On 2026-10-16 every bot paused with "The ChatGPT user has reached their Subscription Sharing usage limit. Ask the user to try again after ..." and the owner took it for a broken
 * OAuth connection. The reset time in that sentence is read so Alpha waits for exactly that and says so.
 */
class PlanLimitTest {
    private val chicago=ZoneId.of("America/Chicago")
    private val base="ChatGPT returned HTTP 429: The ChatGPT user has reached their Subscription Sharing usage limit. Ask the user to try again after "
    /** Thursday 2026-10-15, 10:44 PM in Chicago. */
    private val now=LocalDateTime.of(2026,10,15,22,44).atZone(chicago).toInstant().toEpochMilli()
    private fun at(y:Int,mo:Int,d:Int,h:Int,mi:Int)=LocalDateTime.of(y,mo,d,h,mi).atZone(chicago).toInstant().toEpochMilli()
    private fun reset(tail:String)=PlanLimit.resetAt(base+tail,now,chicago)

    @Test fun anIsoTimeIsReadWithAndWithoutAZone() {
        assertEquals(LocalDateTime.of(2026,10,16,5,41).toInstant(ZoneOffset.UTC).toEpochMilli(),reset("2026-10-16T05:41:00Z."))
        assertEquals(LocalDateTime.of(2026,10,16,5,41).toInstant(ZoneOffset.UTC).toEpochMilli(),reset("2026-10-16T05:41:00.000+00:00"))
        assertEquals(at(2026,10,17,6,0),reset("2026-10-17 06:00"))
    }

    @Test fun aDateAndTimeInWordsIsRead() {
        assertEquals(at(2026,10,20,9,41),reset("Oct 20, 2026 9:41 AM."))
        assertEquals(at(2026,10,20,9,41),reset("October 20 at 9:41 AM"))
        assertEquals(at(2026,10,20,21,5),reset("Oct 20th 9:05 PM."))
    }

    @Test fun aClockTimeAloneMeansTheNextTimeItIsThatTime() {
        // it is 10:44 PM: 11:59 PM is today, 9:41 AM is tomorrow
        assertEquals(at(2026,10,15,23,59),reset("11:59 PM."))
        assertEquals(at(2026,10,16,9,41),reset("9:41 AM."))
        assertEquals(at(2026,10,16,8,15),reset("tomorrow at 8:15 AM"))
        assertEquals(at(2026,10,16,6,30),reset("06:30"))
    }

    @Test fun aZoneWordIsRespectedIncludingDaylightSaving() {
        // 3:40 PM Pacific on 16 Oct (daylight time) is 5:40 PM in Chicago
        assertEquals(at(2026,10,16,17,40),reset("3:40 PM PT."))
        assertEquals(LocalDateTime.of(2026,10,16,15,40).toInstant(ZoneOffset.UTC).toEpochMilli(),reset("3:40 PM UTC"))
    }

    @Test fun aWaitInWordsOrARetryAfterIsRead() {
        assertEquals(now+135*60_000L,reset("in 2 hours 15 minutes."))
        assertEquals(now+3*86_400_000L,reset("in 3 days"))
        assertEquals(now+90*60_000L,reset("in about 90 minutes"))
        assertEquals(now+3_600_000L,PlanLimit.resetAt("ChatGPT returned HTTP 429: usage limit (retry-after 3600s) (request req_1)",now,chicago))
    }

    @Test fun anAnswerThatSaysNothingUsefulOrIsOutOfRangeIsNull() {
        assertNull(reset(""))
        assertNull(reset("a little while."))
        assertNull("more than two weeks away is not believed",reset("Dec 25, 2026 9:00 AM"))
        assertNull("a time in the past",reset("2026-10-15T03:41:00Z"))
        assertNull(PlanLimit.resetAt("Connection reset by peer",now,chicago))
    }

    /** The words the phone actually logged on 2026-10-16: no time at all, so Alpha has to guess and say so. */
    @Test fun theRealMessageNamesNoTime() {
        assertNull(PlanLimit.resetAt("The ChatGPT user has reached their Subscription Sharing usage limit. Ask the user to try again after their usage limit resets or use an API key instead.",now,chicago))
    }

    @Test fun aResetHintOutsideTheMessageIsReadFromTheEventOrTheHeaders() {
        fun secs(json:String,headers:Map<String,String> = emptyMap())=PlanLimit.secondsFrom(JSONObject(json),headers,now)
        assertEquals(1800L,secs("""{"type":"error","message":"limit","resets_in_seconds":1800}"""))
        assertEquals(600L,secs("""{"type":"error","error":{"message":"limit","retry_after":600}}"""))
        assertEquals(3600L,secs("""{"type":"response.failed","response":{"error":{"message":"limit","resets_at":${(now+3_600_000L)/1000}}}}"""))
        assertEquals(3600L,secs("""{"type":"error","error":{"resets_at":${now+3_600_000L}}}"""))
        assertEquals(120L,secs("""{"type":"error","message":"limit"}""",mapOf("retry-after" to "120")))
        assertEquals(7200L,secs("""{"type":"error"}""",mapOf("x-codex-primary-used-percent" to "100","x-codex-primary-reset-after-seconds" to "7200","x-codex-secondary-used-percent" to "40","x-codex-secondary-reset-after-seconds" to "90000")))
        assertNull("a window that is not used up says nothing",secs("""{"type":"error"}""",mapOf("x-codex-primary-used-percent" to "40","x-codex-primary-reset-after-seconds" to "7200")))
        assertNull("a time already past",secs("""{"type":"error","resets_at":${(now-60_000L)/1000}}"""))
        assertNull(secs("""{"type":"error","message":"The ChatGPT user has reached their Subscription Sharing usage limit."}"""))
    }

    @Test fun theWordsInsideSubscriptionAreNotMistakenForTheTime() {
        // "on" inside "Subscription" and numbers elsewhere in the message must not matter
        assertEquals(at(2026,10,16,9,41),PlanLimit.resetAt("HTTP 429 (request req_9f3) The ChatGPT user has reached their Subscription Sharing usage limit. Ask the user to try again after 9:41 AM.",now,chicago))
    }

    @Test fun theOwnerIsToldWhenInPlainWords() {
        assertEquals("in about 20 minutes",PlanLimit.describe(now+20*60_000L,now,chicago))
        assertEquals("in about 75 minutes",PlanLimit.describe(at(2026,10,15,23,59),now,chicago))
        assertEquals("at 5:30 PM",PlanLimit.describe(at(2026,10,15,17,30),at(2026,10,15,8,0),chicago))
        assertEquals("tomorrow at 9:41 AM",PlanLimit.describe(at(2026,10,16,9,41),now,chicago))
        assertEquals("on Oct 20 at 9:41 AM",PlanLimit.describe(at(2026,10,20,9,41),now,chicago))
        assertEquals("in a moment",PlanLimit.describe(now-1,now,chicago))
    }
}
