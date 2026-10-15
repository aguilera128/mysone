package dev.javieraguilera.mysone.tasks

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.edit
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.javieraguilera.mysone.container
import java.io.File
import java.util.concurrent.TimeUnit

/** Mantenimiento periódico cada 30 min (§6). */
class MaintenanceWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext
        val c = app.container
        val now = System.currentTimeMillis()

        remindResumeIfStopped(app)
        val known = c.db.pendingAudio().all().map { File(it.path).name }.toSet()
        orphans(pendingDir(app).listFiles()?.toList().orEmpty(), known, now).forEach { it.delete() }
        // Red de seguridad: un force-stop borra las alarmas y aquí vuelven; avisa también de las vencidas sin avisar.
        c.reminders.rescheduleAll()

        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (now - prefs.getLong(KEY_RETENTION_AT, 0) >= DAY_MS) {
            c.data.applyRetention()
            prefs.edit { putLong(KEY_RETENTION_AT, now) }
        }

        if (online(app)) {
            c.ai.processPending()
            c.ai.buildMissingDigests()
        }
        return Result.success()
    }

    companion object {
        private const val PREFS = "maintenance"
        private const val KEY_RETENTION_AT = "retentionAt"
        private const val DAY_MS = 24 * 3_600_000L

        /** Margen para no borrar un trozo que la captura acaba de escribir y aún no tiene fila en pending_audio. */
        private const val ORPHAN_AGE_MS = 10 * 60_000L

        /** Lo llama MysoneApp al arrancar. */
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "maintenance",
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<MaintenanceWorker>(30, TimeUnit.MINUTES).build(),
            )
        }

        /** Ficheros de pending/ sin fila en la cola y con más de 10 min. */
        internal fun orphans(files: List<File>, known: Set<String>, now: Long): List<File> =
            files.filter { it.isFile && it.name !in known && now - it.lastModified() > ORPHAN_AGE_MS }

        private fun online(context: Context): Boolean {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            return cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        }
    }
}
