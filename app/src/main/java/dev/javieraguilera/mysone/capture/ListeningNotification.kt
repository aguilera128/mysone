package dev.javieraguilera.mysone.capture

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import dev.javieraguilera.mysone.MysoneApp
import dev.javieraguilera.mysone.R
import dev.javieraguilera.mysone.core.Time

/**
 * Notificación del servicio en primer plano (§5.1). Solo estado y contadores: nunca texto transcrito.
 * Las acciones usan `getService`: el servicio ya está en primer plano mientras se ve la notificación. Pausar y
 * detener van sin desbloquear; reanudar, que vuelve a abrir el micro, pide desbloquear ([resumeAction]).
 */
object ListeningNotification {
    const val ID = 1001

    /**
     * [hold]: id de la cadena con el motivo por el que la transcripción espera, o 0.
     * [finishing]: detenida, transcribiendo lo que quedaba antes de cerrar el servicio (sin acciones).
     */
    fun build(context: Context, status: ListeningStatus, fragmentsToday: Int, pending: Int, hold: Int, finishing: Boolean): Notification {
        val res = context.resources
        val title = when {
            finishing -> context.getString(R.string.capture_title_finishing)
            status is ListeningStatus.Listening -> context.getString(R.string.capture_title_listening)
            status is ListeningStatus.Paused -> context.getString(R.string.capture_title_paused, Time.hourMinute(status.untilMs))
            status is ListeningStatus.MicBusy -> context.getString(R.string.capture_title_mic_busy)
            else -> context.getString(R.string.capture_title_starting)
        }
        val text = res.getQuantityString(R.plurals.capture_fragments_today, fragmentsToday, fragmentsToday) + " · " +
            res.getQuantityString(R.plurals.capture_pending, pending, pending)
        val b = NotificationCompat.Builder(context, MysoneApp.CHANNEL_LISTENING)
            .setSmallIcon(R.drawable.ic_stat_mysone)
            .setContentTitle(title)
            .setContentText(text)
            .setSubText(if (hold != 0) context.getString(hold) else null)
            .setContentIntent(openApp(context))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (finishing) return b.build()
        if (status is ListeningStatus.Paused) {
            b.addAction(resumeAction(context, service(context, RC_RESUME, ListeningController.ACTION_RESUME)))
        } else {
            b.addAction(0, context.getString(R.string.capture_action_pause_15), service(context, RC_PAUSE_15, ListeningController.ACTION_PAUSE, 15))
            b.addAction(0, context.getString(R.string.capture_action_pause_60), service(context, RC_PAUSE_60, ListeningController.ACTION_PAUSE, 60))
        }
        b.addAction(0, context.getString(R.string.capture_action_stop), service(context, RC_STOP, ListeningController.ACTION_STOP))
        return b.build()
    }

    /** "Reanudar" que, con la pantalla bloqueada, pide desbloquear: si no, cualquiera podría volver a grabar sin el PIN. */
    internal fun resumeAction(context: Context, intent: PendingIntent): NotificationCompat.Action =
        NotificationCompat.Action.Builder(0, context.getString(R.string.capture_action_resume), intent)
            .setAuthenticationRequired(true)
            .build()

    /** Fuera de la pausa: el "Reanudar" que alguien guardara (un oyente de notificaciones, un reloj) deja de valer. */
    internal fun cancelResume(context: Context) {
        PendingIntent.getService(
            context,
            RC_RESUME,
            ListeningController.intent(context, ListeningController.ACTION_RESUME),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE,
        )?.cancel()
    }

    internal fun openApp(context: Context): PendingIntent? =
        context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

    // Un requestCode por acción: si no, FLAG_UPDATE_CURRENT pisaría los minutos de la otra pausa.
    private fun service(context: Context, requestCode: Int, action: String, minutes: Int = 0) = PendingIntent.getService(
        context,
        requestCode,
        ListeningController.intent(context, action, minutes),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private const val RC_PAUSE_15 = 1
    private const val RC_PAUSE_60 = 2
    private const val RC_RESUME = 3
    private const val RC_STOP = 4
}
