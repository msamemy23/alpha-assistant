package com.alphainternational.assistant

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** OEM background firewalls can deny DNS while the system browser owns the screen. Ten minutes allows for switching accounts. */
internal suspend fun awaitSignInForeground(isForeground:()->Boolean,timeoutMillis:Long=600_000) {
    val returned=withTimeoutOrNull(timeoutMillis) {
        while(!isForeground()) delay(50)
        true
    }==true
    check(returned) { "Return to Alpha Assistant to finish connecting. The sign-in timed out; start again." }
}
