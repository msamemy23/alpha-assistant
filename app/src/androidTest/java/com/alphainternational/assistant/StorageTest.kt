package com.alphainternational.assistant

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class StorageTest {
    @Test fun ClearingBrowserSessionsDeletesLocalSiteStorage() {
        val instrument=InstrumentationRegistry.getInstrumentation();val context=instrument.targetContext
        lateinit var browser:BrowserEngine
        instrument.runOnMainSync {browser=BrowserEngine(context,{it},{_,_->})}
        try {
            instrument.runOnMainSync {browser.view.loadDataWithBaseURL("https://clear.example/first","<html><body>Storage fixture</body></html>","text/html","UTF-8",null)}
            kotlinx.coroutines.runBlocking {kotlinx.coroutines.withTimeout(10_000) {while(!browser.currentUrl.endsWith("/first"))kotlinx.coroutines.delay(20)}}
            var value=""
            instrument.runOnMainSync {browser.view.evaluateJavascript("localStorage.setItem('alpha-test','marker');localStorage.getItem('alpha-test')") {value=it}}
            kotlinx.coroutines.runBlocking {kotlinx.coroutines.withTimeout(5000) {while(value.isBlank())kotlinx.coroutines.delay(20)}}
            assertEquals("\"marker\"",value)
            instrument.runOnMainSync {browser.clearSession()}
            kotlinx.coroutines.runBlocking {kotlinx.coroutines.withTimeout(5000){while(browser.currentUrl!="about:blank")kotlinx.coroutines.delay(20)}}
            instrument.runOnMainSync {browser.view.loadDataWithBaseURL("https://clear.example/second","<html><body>New document</body></html>","text/html","UTF-8",null)}
            kotlinx.coroutines.runBlocking {kotlinx.coroutines.withTimeout(10_000){while(!browser.currentUrl.endsWith("/second"))kotlinx.coroutines.delay(20)}}
            value=""
            instrument.runOnMainSync {browser.view.evaluateJavascript("localStorage.getItem('alpha-test')") {value=it}}
            kotlinx.coroutines.runBlocking {kotlinx.coroutines.withTimeout(5000) {while(value.isBlank())kotlinx.coroutines.delay(20)}}
            assertEquals("null",value)
        }finally{instrument.runOnMainSync{browser.destroy()}}
    }
    @Test fun StateIsEncryptedAndSurvivesOpeningANewStore() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val store=SecureStore(context)
        val previous=store.load()
        val probe="private-probe-"+UUID.randomUUID()
        try {
            store.save(obj("test" to probe))
            assertEquals(probe,SecureStore(context).load().getString("test"))
            // Every saved file is sealed: neither the core file nor any other holds the words or the field names in the clear.
            val disk=File(context.noBackupFilesDir,"alpha-data").walkTopDown().filter {it.isFile}.joinToString("") {it.readBytes().toString(Charsets.ISO_8859_1)}
            assertFalse(disk.contains(probe))
            assertFalse(disk.contains("\"test\""))
        } finally {store.save(previous)}
    }
    @Test fun BrowserObservationsOmitInputValuesAndRejectPasswordFill() {
        val instrument=InstrumentationRegistry.getInstrumentation()
        val context=instrument.targetContext
        lateinit var browser:BrowserEngine
        instrument.runOnMainSync {
            browser=BrowserEngine(context,{it},{_,_->})
        }
        try {
            instrument.runOnMainSync {
                browser.view.loadDataWithBaseURL("https://fixture.example",
                    "<html><head><title>Browser fixture</title></head><body><h1>Public page text</h1><input type='email' aria-label='Email' value='private-email@example.com'><input type='password' aria-label='Password' value='never-send-me'><textarea aria-label='Message'>private draft</textarea><button>Sign in</button></body></html>",
                    "text/html","UTF-8",null)
            }
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeout(10_000) { while(browser.currentUrl.isBlank()) kotlinx.coroutines.delay(50) }
                val text=browser.read()
                assertTrue(text.contains("Public page text"))
                assertFalse(text.contains("\"value\":"))
                assertFalse(text.contains("never-send-me"))
                assertFalse(text.contains("private-email@example.com"))
                assertFalse(text.contains("private draft"))
                val passwordId=org.json.JSONObject(text).getJSONArray("elements").objects().first {it.optBoolean("secret")}.getString("id")
                assertTrue(browser.fill(passwordId,"replacement").contains("login vault"))
                val button=org.json.JSONObject(text).getJSONArray("elements").objects().first {it.optString("kind")=="button"}.getString("id")
                val snapshot=browser.describe(button)
                instrument.runOnMainSync {browser.view.evaluateJavascript("document.querySelector('button').innerText='Changed action';",null)}
                kotlinx.coroutines.delay(100)
                assertTrue(browser.click(button,snapshot).contains("Blocked"))
                assertThrows(IllegalArgumentException::class.java) {
                    kotlinx.coroutines.runBlocking {browser.fill("injected-code","test")}
                }
                assertThrows(IllegalArgumentException::class.java) {
                    kotlinx.coroutines.runBlocking {browser.fillLogin("https://other.example","user","secret")}
                }
            }
        } finally {instrument.runOnMainSync {browser.destroy()}}
    }
}
