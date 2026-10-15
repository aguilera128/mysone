package dev.javieraguilera.mysone.tasks

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/** Mantenimiento periódico cada 30 min (§6). Lo implementa B4. */
class MaintenanceWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = Result.success()

    companion object {
        /** Lo llama MysoneApp al arrancar (enqueueUniquePeriodicWork con KEEP). */
        fun schedule(context: Context) {}
    }
}
