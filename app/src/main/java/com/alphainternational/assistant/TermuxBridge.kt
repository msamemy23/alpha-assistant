package com.alphainternational.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.app.PendingIntent
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File

/**
 * The bridge to Termux, a free Linux command line app on the phone. Alpha asks it to run a command through Termux's own RUN_COMMAND service
 * and gets the output back. Alpha is the only one allowed to do this (Android permission), and every command passes TermuxRules first.
 */
object TermuxBridge {
    const val PACKAGE="com.termux"
    const val PERMISSION="com.termux.permission.RUN_COMMAND"
    private const val SERVICE="com.termux.app.RunCommandService"
    private var counter=0

    class Result(val exit:Int?,val stdout:String,val stderr:String,val error:String,val finished:Boolean)

    fun installed(context:Context)=runCatching {context.packageManager.getPackageInfo(PACKAGE,0);true}.getOrDefault(false)
    fun permitted(context:Context)=context.checkSelfPermission(PERMISSION)==PackageManager.PERMISSION_GRANTED

    /** Plain-words state of the phone's command line, with the next step when it isn't ready. Cheap: no command is run. */
    fun state(context:Context):JSONObject {
        val installed=installed(context);val permitted=permitted(context)
        return obj("installed" to installed,"permission_granted" to permitted,"ready_to_try" to (installed && permitted),
            "next_step" to when {
                !installed->"Termux isn't installed on this phone yet. The owner or the developer installs it (the GitHub or F-Droid build, not the Play Store one)."
                !permitted->"Alpha isn't allowed to start commands in Termux yet. Allow it in Settings > Apps > Alpha Assistant > Permissions (Run commands in Termux), or ask the developer to grant it."
                else->"Run computer_status with test=true to check that Termux answers."
            })
    }

    /** Runs [script] in Termux and waits up to [waitSeconds] for the result. */
    suspend fun run(context:Context,script:String,waitSeconds:Int,label:String="Alpha"):Result {
        if(!installed(context)) return Result(null,"","","Termux isn't installed.",false)
        if(!permitted(context)) return Result(null,"","","Alpha isn't allowed to run commands in Termux yet.",false)
        val action="com.alphainternational.assistant.TERMUX_RESULT."+System.nanoTime()
        val done=CompletableDeferred<Result>()
        val receiver=object:BroadcastReceiver() {
            override fun onReceive(c:Context,intent:Intent) {
                val bundle=intent.getBundleExtra("result")
                if(bundle==null) {done.complete(Result(null,"","","Termux didn't send a result.",true));return}
                done.complete(Result(if(bundle.containsKey("exitCode")) bundle.getInt("exitCode") else null,bundle.getString("stdout").orEmpty(),bundle.getString("stderr").orEmpty(),
                    (bundle.getString("errmsg").orEmpty()).ifBlank {if(bundle.getInt("err",-1)>0) "Termux reported error "+bundle.getInt("err") else ""},true))
            }
        }
        ContextCompat.registerReceiver(context,receiver,IntentFilter(action),ContextCompat.RECEIVER_NOT_EXPORTED)
        try {
            val flags=PendingIntent.FLAG_UPDATE_CURRENT or (if(Build.VERSION.SDK_INT>=31) PendingIntent.FLAG_MUTABLE else 0)
            val pending=PendingIntent.getBroadcast(context,(++counter) and 0xffff,Intent(action).setPackage(context.packageName),flags)
            val intent=Intent("com.termux.RUN_COMMAND").setClassName(PACKAGE,SERVICE)
                .putExtra("com.termux.RUN_COMMAND_PATH",TermuxRules.BASH)
                .putExtra("com.termux.RUN_COMMAND_ARGUMENTS",arrayOf("-c",script))
                .putExtra("com.termux.RUN_COMMAND_WORKDIR",TermuxRules.HOME)
                .putExtra("com.termux.RUN_COMMAND_BACKGROUND",true)
                .putExtra("com.termux.RUN_COMMAND_COMMAND_LABEL",label)
                .putExtra("com.termux.RUN_COMMAND_PENDING_INTENT",pending)
            try {withContext(Dispatchers.Main) {ContextCompat.startForegroundService(context,intent)}}
            catch(e:SecurityException) {return Result(null,"","","Android didn't let Alpha start Termux: "+(e.message?.take(160)?:"permission missing"),false)}
            catch(e:Exception) {return Result(null,"","","Couldn't start Termux: "+(e.message?.take(160)?:e.javaClass.simpleName),false)}
            return withTimeoutOrNull(waitSeconds*1000L+8_000) {done.await()}
                ?:Result(null,"","","Termux didn't answer in time. It may have been stopped by Android; open Termux once and try again.",false)
        } finally {runCatching {context.unregisterReceiver(receiver)}}
    }

    /** Output as the model should see it: trimmed, with a clear note when Termux cut it short. */
    fun trim(text:String,limit:Int=12_000):String=if(text.length<=limit) text else text.take(limit/2)+"\n…["+(text.length-limit)+" characters left out]…\n"+text.takeLast(limit/2)
}
