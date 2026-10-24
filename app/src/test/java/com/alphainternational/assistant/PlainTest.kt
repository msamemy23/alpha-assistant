package com.alphainternational.assistant

import org.junit.Assert.*
import org.junit.Test

/** Plain words for errors, job states and the result strip: nothing technical may reach the owner. */
class PlainTest {
    @Test fun noInternetIsPlain() {
        val text=plainError(java.net.UnknownHostException("api.openai.com"))
        assertTrue(text,text.contains("internet"))
        assertFalse(text.contains("UnknownHost"))
        assertTrue(plainError(java.net.ConnectException("Failed to connect to /10.0.0.1:443")).contains("internet"))
    }

    @Test fun timeoutsSaySoAndSayWhatToDo() {
        assertTrue(plainError(java.net.SocketTimeoutException("timeout")).contains("too long"))
        assertTrue(plainError("","Request timed out after 150 s").contains("Try again"))
    }

    @Test fun signInProblemsPointToMarketplace() {
        assertTrue(plainError("","HTTP 401 Unauthorized").contains("sign in again"))
        assertTrue(plainError("","token expired").contains("sign in again"))
        assertTrue(plainError("","HTTP 403 Forbidden").contains("isn't allowed"))
    }

    @Test fun limitsAreNamedAndCalm() {
        assertTrue(plainError("","HTTP 429 too many requests").contains("limit"))
        assertTrue(plainError("","You've hit your usage limit").contains("limit"))
    }

    @Test fun fullStorageAndMissingPermission() {
        assertTrue(plainError(java.io.IOException("No space left on device")).contains("storage"))
        assertTrue(plainError(SecurityException("Permission Denial: opening provider")).contains("Permissions"))
        assertTrue(plainError("","open failed: EACCES (Permission denied)").contains("Permissions"))
    }

    @Test fun serverTroubleIsNotTheOwnersFault() {
        assertTrue(plainError("","HTTP 503 Service Unavailable").contains("on its side"))
        assertTrue(plainError("","An error occurred while processing your request. Request ID req_1").contains("on its side"))
    }

    @Test fun aMessageWrittenForAPersonPassesThrough() {
        assertEquals("Use a short bot name.",plainError(IllegalArgumentException("Use a short bot name")))
        assertEquals("You can have up to 12 bots.",plainError(IllegalArgumentException("You can have up to 12 bots.")))
    }

    @Test fun codeNeverReachesTheOwner() {
        for(raw in listOf("java.lang.NullPointerException at com.x.Y.z(Y.kt:12)","{\"error\":{\"code\":\"x\"}}","kotlin.KotlinNullPointerException","Unexpected token at 0x7f3a2c10","")) {
            val text=plainError(IllegalStateException(raw))
            assertFalse(text,text.contains("java.") || text.contains("kotlin") || text.contains("{") || text.contains("0x"))
            assertTrue(text.isNotBlank())
        }
        assertEquals("Something went wrong. Please try again in a moment.",plainError(null))
    }

    @Test fun aWrappedCauseIsRead() {
        assertTrue(plainError(RuntimeException("Could not load",java.net.UnknownHostException("x"))).contains("internet"))
    }

    @Test fun stoppingIsNotAnError() {
        assertEquals("Stopped.",plainError(kotlinx.coroutines.CancellationException("job cancelled")))
    }

    @Test fun looksPlainSeparatesWordsFromCode() {
        assertTrue(looksPlain("Give the memory a title and details."))
        assertTrue(looksPlain("That chat isn't there any more, so I couldn't open it."))
        assertFalse(looksPlain("java.io.IOException: boom"))
        assertFalse(looksPlain("at com.alphainternational.assistant.Controller.submit(Controller.kt:965)"))
        assertFalse(looksPlain("x".repeat(300)))
        assertFalse(looksPlain("two\nlines"))
    }

    @Test fun jobStatesUsePlainWordsButKeepTheirStoredNames() {
        assertEquals("Please check",Words.status("Check result"))
        assertEquals("Waiting for you",Words.status("Needs you"))
        assertEquals("Waiting for your OK",Words.status("Needs review"))
        assertEquals("Needs a sign-in",Words.status("Needs connection"))
        assertEquals("Done",Words.status("Completed"))
        assertEquals("Paused",Words.status("Paused"))
        assertEquals("Something else",Words.status("Something else"))
        // The logic compares the stored words, so the set of "needs a person" states must stay exactly these four.
        assertEquals(setOf("Needs review","Needs you","Needs connection","Check result"),Words.attention)
    }

    @Test fun theTeamChatHasAPlainName() {
        assertEquals("Team chat",Words.TEAM_CHAT)
        assertEquals("Teamwork",Team.ROOM)
    }

    @Test fun receiptsReadAJsonListOfLines() {
        assertEquals(listOf("3 drafts saved","0 sent"),Receipts.parse("""["3 drafts saved","0 sent"]"""))
        assertEquals(listOf("File invoice-12.pdf made"),Receipts.parse("""[{"text":"File invoice-12.pdf made"}]"""))
        assertEquals(listOf("A","B"),Receipts.parse("""{"lines":["A","B"]}"""))
    }

    @Test fun receiptsAreTolerant() {
        assertTrue(Receipts.parse("").isEmpty())
        assertTrue(Receipts.parse("[]").isEmpty())
        assertTrue(Receipts.parse("{}").isEmpty())
        assertTrue(Receipts.parse("[not json").isEmpty())
        assertEquals(listOf("one","two"),Receipts.parse("one\n\ntwo"))
        assertEquals(listOf("a"),Receipts.parse("""["a","a","  ","a"]"""))
        assertEquals(Receipts.MAX_LINES,Receipts.parse((1..20).joinToString(",","[","]") {"\"line $it\""}).size)
        assertTrue(Receipts.parse("""["${"x".repeat(500)}"]""")[0].length<=120)
    }

    @Test fun receiptsMarkWhatDidNotHappenWithAnInfoMark() {
        assertTrue(Receipts.isInfo("0 sent"))
        assertTrue(Receipts.isInfo("Couldn't open the invoice"))
        assertTrue(Receipts.isInfo("2 waiting for you"))
        assertFalse(Receipts.isInfo("3 drafts saved"))
        assertFalse(Receipts.isInfo("File invoice-12.pdf made"))
    }
}
