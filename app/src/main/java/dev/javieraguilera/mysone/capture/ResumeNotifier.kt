package dev.javieraguilera.mysone.capture

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import dev.javieraguilera.mysone.MysoneApp
import dev.javieraguilera.mysone.R

/**
 * "La escucha se ha detenido. Toca para reanudar" (§6). Tocarla arranca el servicio con
 * `getForegroundService`: la interacción con la notificación concede el micro "mientras se usa",
 * que es lo único que funciona tras un arranque del móvil o si el sistema mató el servicio (02 §3, §4).
 */
object ResumeNotifier {
    private const val ID = 1002
    private const val RC_START = 10

    fun show(context: Context) {
        val start = PendingIntent.getForegroundService(
            context,
            RC_START,
            ListeningController.intent(context, ListeningController.ACTION_START),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, MysoneApp.CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_mysone)
            .setContentTitle(context.getString(R.string.capture_resume_title))
            .setContentText(context.getString(R.string.capture_resume_text))
            .setContentIntent(start)
            .setAutoCancel(true)
            .apply { ListeningNotification.openApp(context)?.let { addAction(0, context.getString(R.string.capture_action_open_app), it) } }
            .build()
        // Sin permiso de notificaciones el sistema la descarta sin más.
        context.getSystemService(NotificationManager::class.java).notify(ID, n)
    }

    fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(ID)
    }
}
