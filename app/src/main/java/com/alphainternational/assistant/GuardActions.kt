package com.alphainternational.assistant

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * The buttons on an "needs your OK" notification (Approve, Deny) and the pause switch. Approve and Deny do exactly what the same buttons on the card in the chat do,
 * and on a locked phone Android asks for the unlock first, so nobody holding a locked phone can approve a text.
 */
class GuardReceiver:BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent) {
        val c=(context.applicationContext as? AlphaApplication)?.controller?:return
        val task=intent.getStringExtra(EXTRA_TASK).orEmpty()
        when(intent.action) {
            ACTION_APPROVE->c.approveFromNotice(task)
            ACTION_DENY->c.declineFromNotice(task)
            ACTION_PAUSE->c.setActionsPaused(true)
            ACTION_RESUME->c.setActionsPaused(false)
            else->return
        }
        if(task.isNotBlank()) runCatching {NotificationManagerCompat.from(context).cancel(task.hashCode())}
    }

    companion object {
        const val ACTION_APPROVE="com.alphainternational.assistant.GUARD_APPROVE"
        const val ACTION_DENY="com.alphainternational.assistant.GUARD_DENY"
        const val ACTION_PAUSE="com.alphainternational.assistant.GUARD_PAUSE"
        const val ACTION_RESUME="com.alphainternational.assistant.GUARD_RESUME"
        const val EXTRA_TASK="task"

        private fun action(context:Context,action:String,task:String,label:String,code:Int):NotificationCompat.Action {
            val intent=Intent(context,GuardReceiver::class.java).setAction(action).putExtra(EXTRA_TASK,task)
            val pending=PendingIntent.getBroadcast(context,code+task.hashCode(),intent,PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            return NotificationCompat.Action.Builder(0,label,pending).setAuthenticationRequired(true).build()
        }

        /** The Approve and Deny buttons for the job [task] waiting for an OK. */
        fun reviewActions(context:Context,task:String)=listOf(action(context,ACTION_APPROVE,task,"Approve",1),action(context,ACTION_DENY,task,"Deny",2))
    }
}
