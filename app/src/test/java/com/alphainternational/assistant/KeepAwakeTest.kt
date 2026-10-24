package com.alphainternational.assistant

import org.junit.Assert.*
import org.junit.Test

/** The owner (2026-10-12): jobs must finish whether the screen is locked or not. The phone is held awake while a job works, renewed while it does, never for long on its own. */
class KeepAwakeTest {
    private val minute=60_000L

    @Test fun theHoldIsTakenWhenNoneIsHeldAndRenewedBeforeItRunsOut() {
        assertTrue("nothing held yet",KeepAwake.needsRenewal(false,0,1_000))
        assertTrue("a hold that was lost is taken again",KeepAwake.needsRenewal(false,10*minute,0))
        assertFalse("plenty left",KeepAwake.needsRenewal(true,10*minute,1*minute))
        assertFalse("just over the renewal point",KeepAwake.needsRenewal(true,10*minute,4*minute))
        assertTrue("under five minutes left",KeepAwake.needsRenewal(true,10*minute,6*minute))
        assertTrue("run out",KeepAwake.needsRenewal(true,10*minute,11*minute))
    }

    @Test fun aHoldIsShortSoAStuckJobCannotKeepThePhoneAwakeForLong() {
        assertTrue(KeepAwake.WINDOW_MS<=15*minute)
        assertTrue(KeepAwake.RENEW_MS<KeepAwake.WINDOW_MS)
    }
}
