package com.alphainternational.assistant

import org.junit.Assert.*
import org.junit.Test

/** Bots keep working, and keep trying, when the owner is in another app. */
class BackgroundWorkTest {
    @Test fun aBadSignalIsRetriedButALimitIsNot() {
        listOf("Unable to resolve host \"chatgpt.com\": No address associated with hostname","HTTP 503: service unavailable","timeout","Software caused connection abort",
            "Read timed out","Connection reset by peer","Unexpected end of stream on https://chatgpt.com/backend-api","SSLException: Connection closed by peer",
            "Failed to connect to chatgpt.com/104.18.1.1:443","Connection trouble. Trying again in 30 seconds.").forEach {assertTrue(it,TaskProtocol.transient(it))}
        // A usage limit won't clear in a minute, a time-budget stop isn't a connection problem, and a real error needs a person.
        listOf("HTTP 429: usage limit reached","You have hit the usage_limit for your plan","This is taking me a while, so I paused to check in",
            "The model returned no answer. Resume the saved task.","Choose a supported text, PDF or image file.","").forEach {assertFalse(it,TaskProtocol.transient(it))}
    }

    @Test fun retriesWaitLongerEachTimeAndStopAfterThree() {
        assertEquals(listOf(30_000L,60_000L,120_000L),(0 until AgentRules.MAX_RETRIES).map {AgentRules.retryDelayMs(it)})
        assertEquals(3,AgentRules.MAX_RETRIES)
        // Never a negative or runaway wait, whatever the count.
        assertEquals(30_000L,AgentRules.retryDelayMs(-4))
        assertTrue(AgentRules.retryDelayMs(99)<=30_000L*64)
    }

    @Test fun theOwnerHearsAfterTheRetriesRunOut() {
        // Once the tries are used up the old plain-words message is what the owner gets.
        assertEquals("I couldn't reach ChatGPT just now (connection trouble).",TaskProtocol.plainSnag("Connection trouble. Trying again in 120 seconds."))
    }

    @Test fun theBrowserToolsStayAvailableWhileAnotherBotIsNotUsingIt() {
        val all=allTools
        val free=AgentRules.visibleTools(all,{true},true).map {it.name}
        assertTrue("browser_open" in free && "browser_read" in free && "phone_action" in free && "sms_send" in free)
    }
}
