package com.alphainternational.assistant

import org.junit.Assert.*
import org.junit.Test

/** 2026-10-17: old greetings came back all at once after the plan limit lifted and every bot answered every one (about 75 hellos). */
class ChitchatTest {
    @Test fun greetingsAreGreetings() {
        listOf("Hi","hi","Hello everyone","Hey everyone. Say hi to each other too","hello!","Good morning team","say hi","Are you there?","Hi everyone, how is it going?","test","thanks","Can you hear me?")
            .forEach {assertTrue(it,Chitchat.isGreeting(it))}
    }
    @Test fun anythingWithAJobInItIsARealRequest() {
        listOf("Hi, find brake pads for Creyton's car","Hello everyone, send the invoice","Hey can you check my email","hi 3 invoices","Say hi to Dave and write his estimate","Good morning, what is on my calendar today and who texted me",
            "","Estimate for a 2012 Civic","Are you able to run the campaign","Can you work on the estimate").forEach {assertFalse(it,Chitchat.isGreeting(it))}
    }
    @Test fun aLongTextIsNeverAGreeting() {
        assertFalse(Chitchat.isGreeting("hi ".repeat(40)))
    }
    @Test fun theNoteForbidsAskingColleagues() {
        assertTrue(Chitchat.NOTE.contains("don't ask a colleague") && Chitchat.NOTE.contains(Chitchat.MARK))
    }
}
