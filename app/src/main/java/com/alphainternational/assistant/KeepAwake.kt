package com.alphainternational.assistant

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.util.Log

/**
 * Keeps the phone's processor and Wi-Fi running while a job works, so a locked screen never freezes it. The owner (2026-10-12): "whether the screen is locked or whatever,
 * as long as the app is running in the background, finish the task, don't stop no matter what." The foreground notice keeps Alpha's process alive, but without a wake
 * lock Android may still put the processor to sleep between network replies, and a job then waits for the screen to come back.
 * The locks are only ever held for a short window and are renewed by the service while a job really runs, so a job that hangs can never keep the phone awake for long.
 */
class KeepAwake(private val context:Context) {
    private var cpu:PowerManager.WakeLock?=null
    private var wifi:WifiManager.WifiLock?=null
    private var until=0L

    val held:Boolean get()=cpu?.isHeld==true

    /** Holds the locks for the next [WINDOW_MS] unless they already are for long enough. Cheap to call often. */
    fun hold(now:Long=System.currentTimeMillis()) {
        if(!needsRenewal(held,until,now)) return
        runCatching {
            val lock=cpu?:context.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"alpha:job").apply {setReferenceCounted(false)}.also {cpu=it}
            lock.acquire(WINDOW_MS)
        }.onFailure {Log.w("AlphaWake","could not hold the processor awake: "+it.javaClass.simpleName)}
        runCatching {
            @Suppress("DEPRECATION")
            val lock=wifi?:context.applicationContext.getSystemService(WifiManager::class.java).createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF,"alpha:job").apply {setReferenceCounted(false)}.also {wifi=it}
            if(!lock.isHeld) lock.acquire()
        }
        if(until==0L || now>=until) Log.i("AlphaWake","holding the phone awake while a job works")
        until=now+WINDOW_MS
        awakeNow=held
    }

    fun release() {
        runCatching {if(cpu?.isHeld==true) cpu?.release()}
        runCatching {if(wifi?.isHeld==true) wifi?.release()}
        if(until!=0L) Log.i("AlphaWake","released: no job is working")
        until=0L;awakeNow=false
    }

    companion object {
        /** How long one hold lasts; the service renews it, so this is the longest the phone stays awake after the service dies without a clean-up. */
        const val WINDOW_MS=10*60_000L
        /** The hold is renewed when less than this is left of it. */
        const val RENEW_MS=5*60_000L
        /** Whether the locks are held right now (read by the tests and the home screen). */
        @Volatile var awakeNow=false
        fun needsRenewal(held:Boolean,until:Long,now:Long):Boolean=!held || until-now<RENEW_MS
    }
}
