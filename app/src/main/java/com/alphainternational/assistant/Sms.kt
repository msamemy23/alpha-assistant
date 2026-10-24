package com.alphainternational.assistant

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.UUID

/**
 * Texts sent from the owner's own number, only after they approve the exact recipient and message.
 * Android saves them in the owner's Messages app like any other text.
 */
object PhoneSms {
    fun allowed(context:Context)=ContextCompat.checkSelfPermission(context,android.Manifest.permission.SEND_SMS)==PackageManager.PERMISSION_GRANTED
    /** The phone number to text, digits with an optional leading +. */
    fun number(to:String):String=PhoneActions.target("text",to).uri.removePrefix("smsto:")
    fun check(to:String,body:String):Pair<String,String> {
        val text=body.trim()
        require(text.isNotBlank()) {"Write the text message first."}
        require(text.length<=1600) {"Keep a text under 1,600 characters."}
        return number(to) to text
    }

    suspend fun send(context:Context,to:String,body:String):JSONObject {
        val (number,text)=check(to,body)
        if(!allowed(context)) throw TakeoverNeeded("I need your OK to send texts from your phone. Tap Allow texts below, then I'll send it.")
        val manager=if(Build.VERSION.SDK_INT>=31) context.getSystemService(SmsManager::class.java) else @Suppress("DEPRECATION") SmsManager.getDefault()
        val parts=manager.divideMessage(text)
        val action="com.alphainternational.assistant.SMS_SENT."+UUID.randomUUID()
        val results=mutableListOf<Int>()
        val done=CompletableDeferred<List<Int>>()
        val receiver=object:BroadcastReceiver() {
            override fun onReceive(c:Context,intent:Intent) {
                synchronized(results) {results+=resultCode;if(results.size>=parts.size) done.complete(results.toList())}
            }
        }
        ContextCompat.registerReceiver(context,receiver,IntentFilter(action),ContextCompat.RECEIVER_NOT_EXPORTED)
        try {
            val sent=ArrayList(parts.indices.map {i->
                PendingIntent.getBroadcast(context,i,Intent(action).setPackage(context.packageName),PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE)
            })
            withContext(Dispatchers.Main) {manager.sendMultipartTextMessage(number,null,parts,sent,null)}
            val codes=withTimeoutOrNull(60_000) {done.await()}
                ?:throw UncertainEffect("The phone didn't confirm the text to $number within a minute. Check your Messages app before sending it again.")
            val failed=codes.firstOrNull {it!=Activity.RESULT_OK}
            require(failed==null) {"The phone couldn't send the text to $number ("+reason(failed!!)+"). It was not sent; check your signal and try again."}
            return obj("result" to "Text sent from the owner's phone number. It appears in their Messages app.","sent" to true,"to" to number,"parts" to parts.size)
        } finally {runCatching {context.unregisterReceiver(receiver)}}
    }
    private fun reason(code:Int)=when(code) {
        SmsManager.RESULT_ERROR_NO_SERVICE->"no cell service"
        SmsManager.RESULT_ERROR_RADIO_OFF->"airplane mode or radio off"
        SmsManager.RESULT_ERROR_NULL_PDU,SmsManager.RESULT_ERROR_GENERIC_FAILURE->"the carrier refused it"
        else->"error $code"
    }
}
