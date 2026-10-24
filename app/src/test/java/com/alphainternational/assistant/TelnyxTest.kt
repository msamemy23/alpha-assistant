package com.alphainternational.assistant

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId

/** The Telnyx connector's pure parts: phone number forms, call records whatever their field names, and the sign-in check. */
class TelnyxTest {
    private val zone=ZoneId.of("America/Chicago")

    @Test fun phoneNumbersAreWrittenTheSameWay() {
        assertEquals("+17135550142",Telnyx.e164("(713) 555-0142"))
        assertEquals("+17135550142",Telnyx.e164("713-555-0142"))
        assertEquals("+17135550142",Telnyx.e164("1 713 555 0142"))
        assertEquals("+17135550142",Telnyx.e164("+1 (713) 555-0142"))
        assertEquals("+442071838750",Telnyx.e164("+44 20 7183 8750"))
        assertEquals("(713) 555-0142",Telnyx.pretty("+17135550142"))
        assertEquals("(713) 555-0142",Telnyx.pretty("7135550142"))
        assertEquals("+442071838750",Telnyx.pretty("+442071838750"))
    }

    @Test fun aCallRecordIsUnderstoodWhateverItsFieldsAreCalled() {
        val a=Telnyx.normalizeCall("call-control",JSONObject("""{"id":"abc","direction":"inbound","cli":"+17135550142","cld":"+18325550100","started_at":"2026-10-10T18:02:11Z","answered_at":"2026-10-10T18:02:15Z","call_sec":45}"""),zone)
        assertEquals("inbound",a.getString("direction"));assertEquals("(713) 555-0142",a.getString("from_pretty"));assertEquals("(832) 555-0100",a.getString("to_pretty"))
        assertTrue(a.getBoolean("answered"));assertEquals(45,a.getInt("seconds"));assertEquals("Sat Oct 10, 1:02 PM",a.getString("when"))
        // Another record type, other names, never answered.
        val b=Telnyx.normalizeCall("sip-trunking",JSONObject("""{"uuid":"u1","call_type":"1","caller_number":"7135550142","dest_number":"8325550100","start_timestamp":"2026-10-10T18:02:11.123456Z"}"""),zone)
        assertEquals("inbound",b.getString("direction"));assertEquals("7135550142",b.getString("from"));assertFalse(b.getBoolean("answered"));assertEquals(0,b.getInt("seconds"))
        assertEquals("u1",b.getString("id"))
        // A record with almost nothing in it does not crash.
        assertEquals("",Telnyx.normalizeCall("x",JSONObject(),zone).getString("from"))
    }

    @Test fun timesInTelnyxFormsAreParsed() {
        assertEquals(Telnyx.parseTime("2026-10-10T18:02:11Z"),Telnyx.parseTime("2026-10-10T13:02:11-05:00"))
        assertTrue(Telnyx.parseTime("2026-10-10T18:02:11.123456Z")>Telnyx.parseTime("2026-10-10T18:02:11Z"))
        assertEquals(0L,Telnyx.parseTime("nonsense"))
    }

    @Test fun theTelnyxPortalSignInIsCheckedByTheSessionNotByCookies() {
        val site="https://portal.telnyx.com"
        assertNotNull(SignInChecks.script(site));assertNull(SignInChecks.script("https://example.com"))
        assertEquals(SignInChecks.Verdict.SIGNED_IN,SignInChecks.verdict(site,"""{"host":"portal.telnyx.com","hash":"#/home","session":true}"""))
        assertEquals(SignInChecks.Verdict.NOT_SIGNED_IN,SignInChecks.verdict(site,"""{"host":"portal.telnyx.com","hash":"#/login/sign-in","session":false}"""))
        assertEquals(SignInChecks.Verdict.NOT_SIGNED_IN,SignInChecks.verdict(site,"""{"host":"portal.telnyx.com","hash":"#/login/sign-in","session":true}"""))
        // Anything unclear is not called a failure: the bot just carries on as before.
        assertEquals(SignInChecks.Verdict.UNKNOWN,SignInChecks.verdict(site,""))
        assertEquals(SignInChecks.Verdict.UNKNOWN,SignInChecks.verdict(site,"""{"error":"x"}"""))
        assertEquals(SignInChecks.Verdict.UNKNOWN,SignInChecks.verdict(site,"""{"host":"accounts.google.com","hash":"","session":false}"""))
        assertEquals(SignInChecks.Verdict.UNKNOWN,SignInChecks.verdict("https://example.com","{}"))
        assertTrue(SignInChecks.failureNote(site).contains("API key") && SignInChecks.failureNote(site).contains("email and password") && SignInChecks.failureNote(site).contains("sign-in window"))
    }

    @Test fun theTelnyxToolsAreReadOnly() {
        telnyxTools.forEach {assertFalse(it.name,it.review)}
        assertEquals("telnyx",AgentRules.connectionFor("telnyx_calls"))
        assertTrue(allTools.any {it.name=="telnyx_lookup"})
    }
}
