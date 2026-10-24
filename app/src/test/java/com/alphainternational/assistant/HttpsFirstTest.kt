package com.alphainternational.assistant

import android.webkit.WebViewClient
import org.junit.Assert.*
import org.junit.Test

class HttpsFirstTest {
    @Test fun httpAddressesBecomeTheSameAddressOverHttps() {
        assertEquals("https://example.com",HttpsFirst.upgrade("http://example.com"))
        assertEquals("https://Example.com/a/b%20c?q=1&r=2#top",HttpsFirst.upgrade(" HTTP://Example.com:80/a/b%20c?q=1&r=2#top "))
        assertEquals("https://example.com/x",HttpsFirst.upgrade("https://example.com/x"))
        assertTrue(HttpsFirst.isHttp("http://example.com/") && !HttpsFirst.isHttp("https://example.com/") && !HttpsFirst.isHttp("not a url"))
    }
    @Test fun nothingElseGetsThrough() {
        listOf("http://example.com:8080/","http://user:pw@example.com/","ftp://example.com/","javascript:alert(1)","example.com","https://example.com:8443/").forEach {
            assertThrows(it,IllegalArgumentException::class.java) {HttpsFirst.upgrade(it)}
        }
    }
    @Test fun refusalsSayWhatHappenedInPlainWords() {
        val bot=HttpsFirst.refusal("http://www.smallshop.example/hours",HttpsFirst.reason(WebViewClient.ERROR_FAILED_SSL_HANDSHAKE))
        assertTrue(bot,bot.startsWith("smallshop.example only works without encryption (http://), so Alpha's browser didn't open it. It tried the secure https:// version first, but that has a security problem."))
        assertTrue(bot.contains("never opens them, fills them in or signs in on them") && bot.contains("web search"))
        assertEquals(HttpsFirst.NO_ANSWER,HttpsFirst.reason(WebViewClient.ERROR_CONNECT))
        assertEquals(HttpsFirst.TOO_SLOW,HttpsFirst.reason(WebViewClient.ERROR_TIMEOUT))
        assertTrue(HttpsFirst.notice("http://neverssl.com/",HttpsFirst.NO_ANSWER).startsWith("neverssl.com only works without encryption, so Alpha's browser didn't open it."))
    }
}
