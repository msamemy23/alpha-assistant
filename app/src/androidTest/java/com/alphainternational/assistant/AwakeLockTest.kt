package com.alphainternational.assistant

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** A working job holds the phone awake (so a locked screen does not freeze it) and lets go when the job ends. */
class AwakeLockTest {
    private fun until(what:String,ms:Long=8_000,cond:()->Boolean) {
        runBlocking {try {withTimeout(ms) {while(!cond()) delay(50)}} catch(e:kotlinx.coroutines.TimeoutCancellationException) {fail("Timed out waiting for $what")}}
    }

    @Test fun aRunningJobHoldsThePhoneAwakeAndLetsGoWhenItEnds() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        runBlocking {TaskService.keepAlive(context,"awake-test")}
        until("the phone to be held awake") {KeepAwake.awakeNow}
        val power=context.getSystemService(android.os.PowerManager::class.java)
        assertTrue(TaskService.busy)
        TaskService.release(context,"awake-test")
        until("the hold to be let go") {!KeepAwake.awakeNow}
        assertFalse(TaskService.busy)
        assertNotNull(power)
    }
}
