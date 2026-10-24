package com.alphainternational.assistant

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat

class CallService:Service() {
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(intent?.action=="END_CALL"){(application as AlphaApplication).controller.voice.end();stopSelf();return START_NOT_STICKY}
        val manager=getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("voice_call","Calls with Alpha",NotificationManager.IMPORTANCE_LOW))
        val open=PendingIntent.getActivity(this,10,AppNavigation.intent(this,"call"),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val end=PendingIntent.getService(this,11,Intent(this,CallService::class.java).setAction("END_CALL"),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        startForeground(1001,NotificationCompat.Builder(this,"voice_call").setSmallIcon(R.drawable.ic_alpha)
            .setContentTitle("Call with Alpha").setContentText("Chatterbox Turbo voice runs on this phone.").setContentIntent(open)
            .setOngoing(true).addAction(0,"End call",end).build())
        return START_NOT_STICKY
    }
    override fun onBind(intent:Intent?):IBinder?=null
    override fun onDestroy(){super.onDestroy();val voice=(application as AlphaApplication).controller.voice;if(voice.connected)voice.end()}
}
