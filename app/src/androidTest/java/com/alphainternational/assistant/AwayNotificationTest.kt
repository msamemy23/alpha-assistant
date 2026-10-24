package com.alphainternational.assistant

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The real Messages app's notification, on a real Android: Alpha reads who texted and what, and hands a reply back through the Messages Reply box.
 * Needs a text to be waiting in Google Messages first (from the host: adb emu sms send 7135550142 "Hey is my Camry ready?").
 */
class AwayNotificationTest {
    private val instrument get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrument.targetContext
    private fun shell(command:String):String=instrument.uiAutomation.executeShellCommand(command).use {fd->java.io.FileInputStream(fd.fileDescriptor).bufferedReader().readText()}

    @Test fun aRealTextIsReadAndAReplyGoesBackThroughMessages() {
        shell("cmd notification allow_listener com.alphainternational.assistant/com.alphainternational.assistant.AwayListener")
        runBlocking {withTimeout(20_000) {while(AwayListener.instance==null || !AwayListener.enabled(context)) delay(200)}}
        val active=AwayListener.instance!!.activeNotifications.filter {it.packageName in AwayRules.apps && it.notification.flags and android.app.Notification.FLAG_GROUP_SUMMARY==0}
        assumeTrue("send a text to the emulator first: adb emu sms send 7135550142 \"Hey is my Camry ready?\"",active.isNotEmpty())
        // The newest notification that is an incoming text (a notification Messages refreshed with our own reply is rightly ignored).
        val sbn=active.sortedByDescending {it.postTime}.firstOrNull {AwayNotifications.incoming(it)!=null}?:active.maxByOrNull {it.postTime}!!
        val ex=sbn.notification.extras
        val style=androidx.core.app.NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(sbn.notification)
        val dump="title=[${ex.getCharSequence(android.app.Notification.EXTRA_TITLE)}] text=[${ex.getCharSequence(android.app.Notification.EXTRA_TEXT)}] msgs=${style?.messages?.map {"{"+it.text+"|"+it.person?.name+"|"+it.person?.key+"}"}} user=${style?.user?.name}/${style?.user?.key} keys=${ex.keySet()} count=${active.size} pkg=${sbn.packageName}"
        android.util.Log.i("AwayTest",dump)
        val incoming=AwayNotifications.incoming(sbn)
        assertNotNull("the text was not recognised as an incoming one-to-one text: "+dump+" flags="+sbn.notification.flags,incoming)
        assertFalse(incoming!!.group)
        assertTrue(incoming.text.isNotBlank())
        assertEquals(dump,incoming.sender.filter {it.isDigit()},incoming.digits)
        assertEquals(10,incoming.digits.length)
        assertNull(AwayRules.skipReason(incoming))
        assertTrue("no Reply box was found in the notification",AwayNotifications.canReply(incoming.key))
        val reply="Yes it is ready, come by anytime. Alpha test "+System.currentTimeMillis()%100000
        assertTrue(AwayNotifications.send(context,incoming.key,reply))
        // The Messages app puts the reply in the conversation (the shell may read the phone's text database).
        var found=false
        runBlocking {withTimeout(30_000) {while(!found) {found=shell("content query --uri content://sms --projection body,type,address").contains(reply.take(40));if(!found) delay(1_000)}}}
        assertTrue(found)
    }

    /** The listener's text goes through the whole Away path inside the real app. With no AI signed in on this emulator it can't decide, so it must ask the owner (never send). */
    @Test fun aRealTextWithNoWorkingAiEndsAsAQuestionForTheOwner() {
        shell("cmd notification allow_listener com.alphainternational.assistant/com.alphainternational.assistant.AwayListener")
        runBlocking {withTimeout(20_000) {while(AwayListener.instance==null || !AwayListener.enabled(context)) delay(200)}}
        val sbn=AwayListener.instance!!.activeNotifications.filter {it.packageName in AwayRules.apps && it.notification.flags and android.app.Notification.FLAG_GROUP_SUMMARY==0}.sortedByDescending {it.postTime}.firstOrNull {AwayNotifications.incoming(it)!=null}
        assumeTrue("send a text to the emulator first",sbn!=null)
        shell("pm grant com.alphainternational.assistant android.permission.POST_NOTIFICATIONS")
        val c=(context.applicationContext as AlphaApplication).controller
        val before=c.config("away")
        var sent=0
        try {
            instrument.runOnMainSync {
                c.saveConfig("chatgpt",obj("access_token" to "test-only-not-a-real-token"))
                c.saveConfig("away",AwaySettings(on=true,live=true,since=System.currentTimeMillis()).json())
                c.awaySender={_,_->sent++;true}
                c.awayNotification(sbn!!)
            }
            runBlocking {withTimeout(120_000) {while(c.awayPending().isEmpty()) delay(300)}}
            assertEquals(0,sent)
            assertEquals("ASK",c.awayEntries().last().kind)
            val notice=context.getSystemService(android.app.NotificationManager::class.java).activeNotifications.any {it.notification.channelId=="away"}
            assertTrue("the owner was not told",notice)
        } finally {instrument.runOnMainSync {c.saveConfig("away",before);c.saveConfig("chatgpt",obj())}}
    }
}
