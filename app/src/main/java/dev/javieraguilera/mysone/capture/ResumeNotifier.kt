package dev.javieraguilera.mysone.capture

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
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
    private const val RC_STOP = 11
    private const val PREFS = "resume_notice"
    private const val KEY_ALERTED = "alerted"

    /**
     * Suena solo la primera vez por interrupción: el mantenimiento y los cambios de hora la vuelven a publicar
     * (también si se ha descartado) y no deben despertar a nadie cada 30 min. [cancel] cierra la interrupción.
     */
    fun show(context: Context) {
        val start = PendingIntent.getForegroundService(
            context,
            RC_START,
            ListeningController.intent(context, ListeningController.ACTION_START),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // "Detener" sin abrir el micro: quita la intención de escuchar y, con ella, este aviso.
        val stop = PendingIntent.getService(
            context,
            RC_STOP,
            ListeningController.intent(context, ListeningController.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val n = NotificationCompat.Builder(context, MysoneApp.CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_mysone)
            .setContentTitle(context.getString(R.string.capture_resume_title))
            .setContentText(context.getString(R.string.capture_resume_text))
            .setContentIntent(start)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setSilent(prefs.getBoolean(KEY_ALERTED, false))
            .addAction(0, context.getString(R.string.capture_action_dont_resume), stop)
            .apply { ListeningNotification.openApp(context)?.let { addAction(0, context.getString(R.string.capture_action_open_app), it) } }
            .build()
        // Sin permiso de notificaciones el sistema la descarta sin más.
        context.getSystemService(NotificationManager::class.java).notify(ID, n)
        prefs.edit { putBoolean(KEY_ALERTED, true) }
    }

    fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(ID)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { remove(KEY_ALERTED) }
    }
}
