package com.alphainternational.assistant

import org.junit.Assert.*
import org.junit.Test

class SmsTest {
    @Test fun numbersAndMessagesAreCheckedBeforeTheApprovalCard() {
        assertEquals("8326568912" to "are we going today?",PhoneSms.check("(832) 656-8912"," are we going today? "))
        assertEquals("+18326568912",PhoneSms.number("+1 832-656-8912"))
        assertTrue(runCatching {PhoneSms.check("12","hi")}.isFailure)
        assertTrue(runCatching {PhoneSms.check("8326568912"," ")}.isFailure)
        assertTrue(runCatching {PhoneSms.check("8326568912","x".repeat(1601))}.isFailure)
    }
    @Test fun theApprovalCardShowsTheExactNumberAndText() {
        val card=Reviews.summary("sms_send","""{"to":"832-656-8912","body":"are we going today?"}""")
        assertEquals("Send a text",card.title)
        assertEquals(listOf("To" to "8326568912","Message" to "are we going today?"),card.rows)
    }
    @Test fun textingIsAReviewedTool() {
        val tool=allTools.single {it.name=="sms_send"}
        assertTrue(tool.review)
    }
}
