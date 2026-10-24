package com.alphainternational.assistant

import org.junit.Assert.*
import org.junit.Test

/** The owner-question guard, with the phrasings the bots really used (AUDIT_TEAMWORK.md: asking the owner for a car's year/make/model, a sign-in already done, unrequested PDFs). */
class AskGuardTest {
    private fun check(reply:String,tools:Set<String> = emptySet(),signedIn:List<String> = emptyList(),request:String="Find brake pads for Creyton's car",
        lookups:Boolean=true,already:Boolean=false,away:Boolean=false,packet:Boolean=false)=AskGuard.check(reply,tools,signedIn,request,lookups,already,away,packet)

    @Test fun askingTheOwnerForAFindableDetailSendsTheBotBackToLookItUp() {
        listOf(
            "What year, make and model is Creyton's car?",
            "I need the vehicle's year/make/model to find the right brake pads.",
            "Can you send me the year, make and model of his car?",
            "Which car does he drive?",
            "To find the right pads, what's the make and model of Creyton's truck?",
            "Please tell me the customer's phone number so I can text him.",
            "What's the invoice number for that one?",
            "Which invoice do you mean?",
            "I found parts for several cars. Could you confirm the vehicle's year and model?"
        ).forEach {reply->
            val nudge=check(reply)
            assertNotNull(reply,nudge);assertEquals(reply,"lookup",nudge!!.reason)
            assertTrue(nudge.text,nudge.text.contains("data_find") && nudge.text.contains("ask_team"))
        }
    }

    @Test fun statementsAndAnswersAreNotNudged() {
        listOf(
            "Creyton drives a 2018 Honda Accord (from his customer record). Front brake pads start at \$39.99.",
            "I looked up the year, make and model: 2018 Honda Accord.",
            "The last invoice is INV-69 for \$1,250.00, with \$450.00 still due.",
            "Your invoice total is \$450.",
            "Done. I texted Creyton.",
            ""
        ).forEach {assertNull(it,check(it))}
    }

    @Test fun aBotThatAlreadyLookedAsksWhatItMust() {
        // It called data_find, or asked a colleague: nothing was findable, so it may ask the owner.
        assertNull(check("What year, make and model is Creyton's car?",tools=setOf("data_find")))
        assertNull(check("What year, make and model is Creyton's car?",tools=setOf("web_fetch","ask_colleague")))
        assertNull(check("What year, make and model is Creyton's car?",tools=setOf("ask_team")))
        // Other tools don't count as having looked.
        assertNotNull(check("What year, make and model is Creyton's car?",tools=setOf("web_fetch","supabase_management_call")))
    }

    @Test fun neverTwiceNeverAwayNeverWithNothingToPointAt() {
        val ask="What year, make and model is Creyton's car?"
        assertNotNull(check(ask))
        assertNull(check(ask,already=true))
        assertNull(check(ask,away=true))
        // No database and no colleague: there is nothing to send the bot to.
        assertNull(check(ask,lookups=false))
        // A colleague answering a question may say NEED-INPUT; that isn't the owner being asked.
        assertNull(check(ask,packet=true))
    }

    @Test fun askingForASignInTheOwnerAlreadyDidIsCaughtButOtherSitesAreFine() {
        val signedIn=listOf("ebay.com","autozone.com")
        val nudge=check("I can't see the listing. Can you sign in to eBay for me?",signedIn=signedIn)
        assertNotNull(nudge);assertEquals("signin",nudge!!.reason);assertTrue(nudge.text.contains("ebay.com"))
        assertEquals("autozone.com",AskGuard.signInAsk("Please log in to autozone.com so I can check stock.",signedIn))
        assertNull(AskGuard.signInAsk("Please sign in to amazon.com.",signedIn))
        assertNull(AskGuard.signInAsk("Can you sign in to eBay for me?",emptyList()))
        // Telling the owner something is not asking.
        assertNull(AskGuard.signInAsk("I'm already signed in to ebay.com.",signedIn))
    }

    @Test fun pdfsNobodyAskedForAreCaught() {
        assertTrue(AskGuard.madeUnaskedPdf(setOf("web_fetch","invoice_pdf"),"Estimate for Ben's Tacoma brakes"))
        assertTrue(AskGuard.madeUnaskedPdf(setOf("document_pdf"),"What was the last invoice on supabase?"))
        assertFalse(AskGuard.madeUnaskedPdf(setOf("invoice_pdf"),"Make me a PDF estimate for Ben"))
        assertFalse(AskGuard.madeUnaskedPdf(setOf("document_pdf"),"Write a flyer for brake week"))
        assertFalse(AskGuard.madeUnaskedPdf(setOf("web_fetch"),"Estimate for Ben's Tacoma brakes"))
        val nudge=check("Here is the estimate for Ben.",tools=setOf("invoice_pdf"),request="Estimate for Ben's Tacoma brakes")
        assertEquals("pdf",nudge!!.reason);assertTrue(nudge.text.contains("didn't ask for a PDF"))
    }

    @Test fun theMostUsefulNudgeComesFirst() {
        // Asking for something findable beats the other two when all happen.
        val nudge=check("Can you sign in to eBay and tell me the car's year, make and model?",tools=setOf("invoice_pdf"),signedIn=listOf("ebay.com"),request="Estimate for Ben")
        assertEquals("lookup",nudge!!.reason)
    }
}
