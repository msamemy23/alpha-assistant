package com.alphainternational.assistant

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.util.concurrent.ConcurrentHashMap

/**
 * Hears new texts the way the phone shows them: through the Messages app's own notification (this also covers the newer chat-style texts).
 * It only does something while Away mode is on, and only for the Messages apps. The owner turns this on once in Android's Notification access.
 */
class AwayListener:NotificationListenerService() {
    override fun onListenerConnected() {instance=this}
    override fun onListenerDisconnected() {if(instance===this) instance=null}
    override fun onNotificationPosted(sbn:StatusBarNotification) {
        if(sbn.packageName !in AwayRules.apps) return
        val controller=(application as? AlphaApplication)?.controller?:return
        runCatching {controller.awayNotification(sbn)}
        runCatching {controller.proactive.textNotification(sbn)}
    }
    override fun onNotificationRemoved(sbn:StatusBarNotification) {AwayNotifications.forget(sbn.key)}

    companion object {
        @Volatile var instance:AwayListener?=null
        /** Whether the owner has turned on Notification access for Alpha. */
        fun enabled(context:Context)=NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
        /** Android's own switch for this (opens straight to Alpha's row on newer phones). */
        fun settingsIntent(context:Context):Intent =
            if(Build.VERSION.SDK_INT>=30) Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,ComponentName(context,AwayListener::class.java).flattenToString())
            else Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
    }
}

/** Reads a Messages notification into an [AwayIncoming], and hands a reply back to the Messages app through that notification's own Reply box. */
object AwayNotifications {
    private class Reply(val intent:PendingIntent,val inputs:Array<RemoteInput>)
    private val replies=ConcurrentHashMap<String,Reply>()

    fun forget(key:String) {replies.remove(key)}

    /** The new text in this notification, or null when it is not an incoming one-to-one text (a summary, the owner's own message, nothing new). */
    fun incoming(sbn:StatusBarNotification):AwayIncoming? {
        val n=sbn.notification
        if(n.flags and Notification.FLAG_GROUP_SUMMARY!=0) return null
        val extras=n.extras
        val style=NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
        val title=extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().trim()
        val messages=style?.messages.orEmpty()
        val last=messages.lastOrNull()
        fun mine(m:NotificationCompat.MessagingStyle.Message):Boolean {
            val p=m.person
            return p==null || (p.key!=null && p.key==style?.user?.key) || p.name?.toString().orEmpty().trim().equals("You",true)
        }
        if(last!=null && mine(last)) return null
        val text=(last?.text?.toString()?:extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()).orEmpty().trim()
        if(text.isBlank()) return null
        val sender=(last?.person?.name?.toString()?:title).trim().ifBlank {title}
        val senders=messages.mapNotNull {it.person?.name?.toString()}.distinct()
        val group=style?.isGroupConversation==true || extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION) ||
            (!style?.conversationTitle?.toString().isNullOrBlank() && style?.conversationTitle?.toString()!=sender && senders.size>1)
        val history=messages.dropLast(1).map {(if(mine(it)) "owner" else sender)+": "+it.text.toString().take(300)}
        register(sbn)
        return AwayIncoming(sbn.key,sbn.packageName,sender,text,group,n.`when`.takeIf {it>0}?:System.currentTimeMillis(),history)
    }

    private fun register(sbn:StatusBarNotification) {
        val action=sbn.notification.actions?.firstOrNull {a->
            a.remoteInputs?.isNotEmpty()==true && (a.semanticAction==Notification.Action.SEMANTIC_ACTION_REPLY || a.title?.toString()?.contains("reply",true)==true)
        }?:return
        val intent=action.actionIntent?:return
        replies[sbn.key]=Reply(intent,action.remoteInputs)
    }

    /** True when there is a Reply box for this conversation right now. */
    fun canReply(key:String):Boolean {
        if(replies.containsKey(key)) return true
        AwayListener.instance?.let {l->runCatching {l.activeNotifications}.getOrNull()?.firstOrNull {it.key==key}?.let {register(it)}}
        return replies.containsKey(key)
    }

    /** Puts [text] in the conversation's Reply box and presses send, as the owner (same number, same thread, text or chat). False if the notification is gone. */
    fun send(context:Context,key:String,text:String):Boolean {
        if(!canReply(key)) return false
        val reply=replies[key]?:return false
        val intent=Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        val results=Bundle()
        reply.inputs.forEach {results.putCharSequence(it.resultKey,text)}
        RemoteInput.addResultsToIntent(reply.inputs,intent,results)
        if(Build.VERSION.SDK_INT>=28) RemoteInput.setResultsSource(intent,RemoteInput.SOURCE_FREE_FORM_INPUT)
        return try {reply.intent.send(context,0,intent);true} catch(e:PendingIntent.CanceledException) {replies.remove(key);false}
    }
}

/** The Send and Skip buttons on an "Alpha needs you" notification. */
class AwayReceiver:BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent) {
        val id=intent.getStringExtra("id").orEmpty()
        val controller=(context.applicationContext as? AlphaApplication)?.controller?:return
        when(intent.action) {
            ACTION_SEND->controller.awaySend(id)
            ACTION_SKIP->controller.awaySkip(id)
            ACTION_OFF->controller.awaySetOn(false)
        }
        NotificationManagerCompat.from(context).cancel(("away:"+id).hashCode())
    }
    companion object {
        const val ACTION_SEND="com.alphainternational.assistant.AWAY_SEND"
        const val ACTION_SKIP="com.alphainternational.assistant.AWAY_SKIP"
        const val ACTION_OFF="com.alphainternational.assistant.AWAY_OFF"
    }
}
