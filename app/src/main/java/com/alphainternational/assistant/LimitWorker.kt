package com.alphainternational.assistant

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Booked for the time the ChatGPT plan's sharing limit clears (see Controller.holdForLimit). If Android has killed the app by then, this starts it again and the bots the limit
 * held carry on by themselves; if the app is alive its own timer has already done it and this finds nothing to do.
 */
class LimitWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params) {
    override suspend fun doWork():Result {
        val controller=(applicationContext as AlphaApplication).controller
        withContext(Dispatchers.Main) {controller.restoreLimit()}
        return Result.success()
    }
    companion object {const val NAME="alpha-limit-release"}
}
