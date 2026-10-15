package dev.javieraguilera.mysone.tasks

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.javieraguilera.mysone.capture.ListeningController
import dev.javieraguilera.mysone.capture.ListeningStatus
import dev.javieraguilera.mysone.capture.ResumeNotifier
import dev.javieraguilera.mysone.container
import kotlinx.coroutines.launch

/** BOOT_COMPLETED, MY_PACKAGE_REPLACED, TIME_SET, TIMEZONE_CHANGED → reprogramar y avisar (§6). */
class SystemEventsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in ACTIONS) return
        val app = context.applicationContext
        val result = goAsync()
        app.container.appScope.launch {
            try {
                remindResumeIfStopped(app)
                app.container.reminders.rescheduleAll()
            } finally {
                result.finish()
            }
        }
    }

    private companion object {
        val ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
        )
    }
}

/**
 * "Toca para reanudar" si el usuario quiere escuchar y el servicio no está en marcha. El servicio vive en
 * este proceso: en un proceso recién creado (arranque, actualización, muerte) el estado es Off.
 * Desde segundo plano no se puede abrir el micro; la notificación sí (docs/investigacion/02 §3).
 */
internal suspend fun remindResumeIfStopped(context: Context) {
    val status = ListeningController.status.value
    if (status !is ListeningStatus.Off && status !is ListeningStatus.Error) return
    if (context.container.settings.current().listeningEnabled) ResumeNotifier.show(context)
}
