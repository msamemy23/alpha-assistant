package com.alphainternational.assistant

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object Updates {
    /**
     * A one-tap hand-off for something Android only lets a visible app do (open the dialer, Maps, the email app). When the owner is in another
     * app, the bot posts this and carries on; tapping it opens [intent] with the details filled in. False if notifications are off.
     */
    /** Takes a hand-off notification back (the screen it would have opened was opened another way). */
    fun cancelHandoff(context:Context,id:String) {runCatching {context.getSystemService(NotificationManager::class.java).cancel(id.hashCode())}}
    fun handoff(context:Context,id:String,title:String,text:String,intent:android.content.Intent):Boolean {
        val manager=context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("handoff","Ready to open",NotificationManager.IMPORTANCE_HIGH))
        if(!NotificationManagerCompat.from(context).areNotificationsEnabled() ||
            manager.getNotificationChannel("handoff").importance==NotificationManager.IMPORTANCE_NONE)return false
        val pending=PendingIntent.getActivity(context,id.hashCode(),intent,PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return try {
            manager.notify(id.hashCode(),NotificationCompat.Builder(context,"handoff").setSmallIcon(R.drawable.ic_alpha)
                .setContentTitle(title.take(100)).setContentText(text.take(180)).setStyle(NotificationCompat.BigTextStyle().bigText(text.take(600)))
                .setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true).setContentIntent(pending).build())
            true
        }catch(_:SecurityException){false}
    }

    /**
     * "Alpha needs you" for a text that Away mode would not answer by itself (or only drafted): the text, the draft reply, and Send / Skip buttons.
     * Tapping the notification opens the Away screen, where the draft can be edited first.
     */
    fun away(context:Context,p:AwayPending):Boolean {
        val manager=context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("away","Texts that need you",NotificationManager.IMPORTANCE_HIGH))
        if(!NotificationManagerCompat.from(context).areNotificationsEnabled() || manager.getNotificationChannel("away").importance==NotificationManager.IMPORTANCE_NONE) return false
        val flags=PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open=PendingIntent.getActivity(context,("away-open:"+p.id).hashCode(),AppNavigation.intent(context,"away"),flags)
        fun button(action:String)=PendingIntent.getBroadcast(context,(action+p.id).hashCode(),Intent(context,AwayReceiver::class.java).setAction(action).putExtra("id",p.id),flags)
        val text=p.incoming.take(200)+(if(p.draft.isBlank()) "" else "\nDraft reply: "+p.draft.take(300))+(if(p.why.isBlank()) "" else "\n("+p.why.take(120)+")")
        return try {
            val builder=NotificationCompat.Builder(context,"away").setSmallIcon(R.drawable.ic_alpha).setContentTitle(p.who+" texted you").setContentText(p.incoming.take(100))
                .setStyle(NotificationCompat.BigTextStyle().bigText(text)).setAutoCancel(true).setContentIntent(open).setPriority(NotificationCompat.PRIORITY_HIGH)
            if(p.draft.isNotBlank()) builder.addAction(0,"Send",button(AwayReceiver.ACTION_SEND))
            builder.addAction(0,"Skip",button(AwayReceiver.ACTION_SKIP))
            manager.notify(("away:"+p.id).hashCode(),builder.build())
            true
        } catch(_:SecurityException) {false}
    }

    /** The steady notice while Away mode is on, with a one-tap Turn off. */
    fun awayOn(context:Context,live:Boolean,waiting:Int):Boolean {
        val manager=context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("away-on","Away mode is on",NotificationManager.IMPORTANCE_LOW))
        if(!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        val flags=PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open=PendingIntent.getActivity(context,41,AppNavigation.intent(context,"away"),flags)
        val off=PendingIntent.getBroadcast(context,42,Intent(context,AwayReceiver::class.java).setAction(AwayReceiver.ACTION_OFF),flags)
        return try {
            manager.notify(AWAY_ON_ID,NotificationCompat.Builder(context,"away-on").setSmallIcon(R.drawable.ic_alpha).setContentTitle("Away mode is on")
                .setContentText((if(live) "Answering your texts for you" else "Drafting replies, nothing is sent")+(if(waiting>0) " · $waiting waiting for you" else ""))
                .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open).addAction(0,"Turn off",off).build())
            true
        } catch(_:SecurityException) {false}
    }
    fun awayOff(context:Context) {context.getSystemService(NotificationManager::class.java).cancel(AWAY_ON_ID)}
    private const val AWAY_ON_ID=7301

    /** "Call from (713) 555-0142" with a "Who is this?" button that asks the bot to match the number to a customer. */
    fun call(context:Context,title:String,text:String,number:String):Boolean {
        val manager=context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("calls","Calls to your Telnyx numbers",NotificationManager.IMPORTANCE_HIGH))
        if(!NotificationManagerCompat.from(context).areNotificationsEnabled() || manager.getNotificationChannel("calls").importance==NotificationManager.IMPORTANCE_NONE) return false
        val flags=PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open=PendingIntent.getActivity(context,("call-open:"+number).hashCode(),AppNavigation.intent(context,"chat"),flags)
        val who=PendingIntent.getBroadcast(context,("call-who:"+number).hashCode(),Intent(context,TelnyxReceiver::class.java).setAction(TelnyxReceiver.ACTION_WHO).putExtra("number",number),flags)
        return try {
            manager.notify(("call:"+number).hashCode(),NotificationCompat.Builder(context,"calls").setSmallIcon(R.drawable.ic_alpha).setContentTitle(title.take(100)).setContentText(text.take(180))
                .setStyle(NotificationCompat.BigTextStyle().bigText(text.take(400))).setAutoCancel(true).setContentIntent(open).addAction(0,"Who is this?",who).build())
            true
        } catch(_:SecurityException) {false}
    }

    /** [picture] (a screenshot of a page the bot looked at) makes the notification a big-picture one: the headline, the first words and the page. */
    fun notify(context:Context,id:String,title:String,text:String,workspace:String="",destination:String="tasks",picture:android.graphics.Bitmap?=null,actions:List<NotificationCompat.Action> = emptyList()):Boolean {
        val manager=context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("important","Important Alpha updates",NotificationManager.IMPORTANCE_DEFAULT))
        if(!NotificationManagerCompat.from(context).areNotificationsEnabled() ||
            manager.getNotificationChannel("important").importance==NotificationManager.IMPORTANCE_NONE)return false
        // Not urgent and over the daily limit (or the owner chose Quiet): stay silent. What needs the owner always gets through.
        if(!NoticeBudget.allowNow(context,id,destination)) return false
        val pending=PendingIntent.getActivity(context,id.hashCode(),AppNavigation.intent(context,destination,workspace),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return try {
            val builder=NotificationCompat.Builder(context,"important").setSmallIcon(R.drawable.ic_alpha)
                .setContentTitle(title.take(100)).setContentText(text.take(180)).setAutoCancel(true).setContentIntent(pending)
            actions.forEach {builder.addAction(it)}
            if(picture!=null) builder.setLargeIcon(picture).setStyle(NotificationCompat.BigPictureStyle().bigPicture(picture).bigLargeIcon(null as android.graphics.Bitmap?).setSummaryText(text.take(300)))
            else builder.setStyle(NotificationCompat.BigTextStyle().bigText(text.take(1500)))
            manager.notify(id.hashCode(),builder.build())
            true
        }catch(_:SecurityException){false}
    }
}
