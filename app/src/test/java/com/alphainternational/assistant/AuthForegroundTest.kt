package com.alphainternational.assistant

import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class AuthForegroundTest {
    @Test fun tokenExchangeWaitsUntilTheUserReturnsFromTheBrowser()=runTest {
        val foreground=AtomicBoolean(false)
        val exchanges=AtomicInteger(0)
        val pending=async {
            awaitSignInForeground({foreground.get()},2_000)
            exchanges.incrementAndGet()
        }
        runCurrent()
        assertEquals(0,exchanges.get())
        assertFalse(pending.isCompleted)
        foreground.set(true)
        advanceTimeBy(51);runCurrent()
        assertEquals(1,pending.await())
    }
    @Test fun leavingTheApprovalPageDoesNotStartAnExchangeAfterTimeout()=runTest {
        var exchanged=false
        val failure=runCatching {
            awaitSignInForeground({false},80)
            exchanged=true
        }.exceptionOrNull()
        assertFalse(exchanged)
        assertTrue(failure is IllegalStateException)
        assertTrue(failure!!.message!!.contains("Return to Alpha Assistant"))
    }
    @Test fun cancellingSignInWhileTheBrowserIsOpenDoesNotExchangeACode()=runTest {
        var exchanged=false
        val pending=async {
            awaitSignInForeground({false},2_000)
            exchanged=true
        }
        runCurrent()
        pending.cancelAndJoin()
        assertTrue(pending.isCancelled)
        assertFalse(exchanged)
    }
}
