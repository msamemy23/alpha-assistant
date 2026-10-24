package com.alphainternational.assistant

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

class RoutineWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params) {
    override suspend fun doWork():Result {
        val controller=(applicationContext as AlphaApplication).controller
        val routine=controller.routines.firstOrNull { it.id==inputData.getString("id") }?:return Result.success()
        try {
            controller.runRoutine(routine)
            withContext(Dispatchers.Main) {controller.reportRoutineAttention(routine)}
        } finally {
            // Timed routines run once per occurrence; book the next one even if this run failed or Android stopped the worker.
            // NonCancellable: a stopped worker's coroutine is cancelled, and the booking must still happen. The margin avoids rebooking the same slot.
            withContext(NonCancellable+Dispatchers.Main) {
                controller.routines.firstOrNull {it.id==routine.id}?.let {runCatching {controller.enqueueNext(it,java.time.ZonedDateTime.now().plusMinutes(2))}}
                controller.flushState()
            }
        }
        return Result.success()
    }
}
