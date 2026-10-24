package com.alphainternational.assistant

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The insurance behind "bots keep working": while any job runs, one of these is booked a couple of minutes ahead (see Controller.scheduleResumeGuard). If Android
 * kills the app mid-job, WorkManager starts the app again for this check, and any job that was cut off carries on by itself, without the owner opening Alpha.
 * When the jobs finish normally the check is cancelled and never runs. While it waits for the jobs it started, it keeps the app alive (a background worker may run
 * for about ten minutes); if they are still going after that it asks to run again.
 */
class ResumeWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params) {
    override suspend fun doWork():Result {
        val controller=(applicationContext as AlphaApplication).controller
        withContext(Dispatchers.Main) {controller.resumeInterruptedInBackground()}
        val finished=withTimeoutOrNull(9*60_000L) {
            delay(5_000)
            while(withContext(Dispatchers.Main) {controller.hasRunningJobs()}) delay(3_000)
            true
        }
        // Still working after the time a worker is allowed: run again shortly (this also covers the app being killed in the meantime).
        return if(finished==null) Result.retry() else Result.success()
    }
    companion object {const val NAME="alpha-resume-guard"}
}
