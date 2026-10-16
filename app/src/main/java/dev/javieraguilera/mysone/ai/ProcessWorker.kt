package dev.javieraguilera.mysone.ai

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.javieraguilera.mysone.container
import java.util.concurrent.TimeUnit

/** Procesa todas las conversaciones pendientes cuando hay red (§7.3). Trabajo único: no se duplica. */
class ProcessWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    // Tras unos reintentos lo recoge MaintenanceWorker (cada 30 min), que llama a processPending().
    override suspend fun doWork(): Result {
        val drain = applicationContext.container.ai.drain()
        return if (drain.retryLater && runAttemptCount < MAX_RETRIES) Result.retry() else Result.success()
    }

    companion object {
        private const val NAME = "ai-process"
        private const val MAX_RETRIES = 4 // 1 + 2 + 4 + 8 min

        /** KEEP: si ya hay uno en cola o en marcha, ese recoge también lo nuevo (drain repasa la cola al acabar). */
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<ProcessWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
