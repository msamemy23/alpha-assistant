package com.alphainternational.assistant

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class LifecycleTest {
    private val instrument get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrument.targetContext
    private fun await(label:String="condition",condition:()->Boolean) {
        try {runBlocking {withTimeout(15_000){while(!condition())delay(40)}}}
        catch(e:kotlinx.coroutines.TimeoutCancellationException){throw AssertionError("Timed out: "+label+"\n"+screenText(),e)}
    }
    private fun screenText():String {
        val result=StringBuilder()
        fun walk(node:AccessibilityNodeInfo) {
            node.text?.let {result.append(it).append('\n')}
            node.contentDescription?.let {result.append(it).append('\n')}
            repeat(node.childCount){node.getChild(it)?.let(::walk)}
        }
        instrument.uiAutomation.rootInActiveWindow?.let(::walk)
        return result.toString()
    }
    private fun visible(text:String)=screenText().contains(text)
    private fun fixture(browser:BrowserEngine) {
        instrument.runOnMainSync {
            browser.view.loadDataWithBaseURL("https://session.example/test",
                "<html><body><h1>Session fixture</h1><script>window.alphaSession='kept-in-memory';</script></body></html>",
                "text/html","UTF-8",null)
        }
        await("browser fixture loaded") {browser.currentUrl=="https://session.example/test"}
    }
    private fun assertPageSurvives(browser:BrowserEngine) {
        var marker=""
        instrument.runOnMainSync {browser.view.evaluateJavascript("window.alphaSession"){marker=it}}
        await {marker.isNotBlank()}
        assertEquals("\"kept-in-memory\"",marker)
    }
    @Test fun BrowserAndCallPanelSurviveActivityRecreation() {
        val scenario=ActivityScenario.launch<MainActivity>(AppNavigation.intent(context,"call"))
        try {
            lateinit var original:MainActivity;lateinit var browser:BrowserEngine
            // The call is with whichever bot was open last.
            var space="Alpha"
            scenario.onActivity {original=it;browser=it.browser;space=it.c.workspace}
            fixture(browser);await("call panel before recreation") {visible("Call with $space")}
            scenario.recreate()
            scenario.onActivity {
                assertNotSame(original,it)
                assertSame(browser,it.browser)
                assertTrue(it.c.uiActive)
                assertSame(browser,it.c.web())
            }
            await("call panel after recreation") {visible("Call with $space")}
            assertPageSurvives(browser)
        }finally {scenario.close()}
    }
    @Test fun ImportantNotificationsReuseTheActivityAndOpenTheCorrectWorkspace() {
        val scenario=ActivityScenario.launch<MainActivity>(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val id="notification-test-"+UUID.randomUUID()
        val manager=context.getSystemService(NotificationManager::class.java)
        var originalSpace="Alpha"
        try {
            lateinit var original:MainActivity;lateinit var browser:BrowserEngine
            scenario.onActivity {original=it;browser=it.browser;originalSpace=it.c.workspace
                // Any bot other than Alpha; the owner may have deleted the starter ones.
                it.c.switchWorkspace(it.c.bots.firstOrNull {b->b.name!="Alpha"}?.name?:it.c.createBot("Personal","Personal helper",false))}
            fixture(browser)
            assertTrue("Grant notification permission to the test app first.",
                Updates.notify(context,id,"Attention fixture","A test task needs review.","Alpha"))
            manager.activeNotifications.single {it.id==id.hashCode()}.notification.contentIntent.send()
            await("notification opened Tasks") {visible("Create a routine")}
            scenario.onActivity {assertSame(original,it);assertSame(browser,it.browser);assertEquals("Alpha",it.c.workspace);assertTrue(it.c.uiActive)}
            assertPageSurvives(browser)
            val pending=PendingIntent.getActivity(context,10,AppNavigation.intent(context,"call"),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            pending.send()
            await("call notification opened the call panel") {visible("Call with Alpha")}
            scenario.onActivity {assertSame(original,it);assertSame(browser,it.browser)}
            assertPageSurvives(browser)
        }finally {
            manager.cancel(id.hashCode())
            scenario.onActivity {it.c.switchWorkspace(originalSpace)}
            scenario.close()
        }
    }
}
