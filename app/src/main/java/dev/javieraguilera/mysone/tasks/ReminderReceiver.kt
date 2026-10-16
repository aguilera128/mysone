package dev.javieraguilera.mysone.tasks

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.javieraguilera.mysone.MainActivity
import dev.javieraguilera.mysone.MysoneApp
import dev.javieraguilera.mysone.R
import dev.javieraguilera.mysone.container
import dev.javieraguilera.mysone.data.TaskItem
import dev.javieraguilera.mysone.data.TaskState
import kotlinx.coroutines.launch

/** Alarma de un recordatorio → notificación con Hecho / Posponer 1 h (§6). */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_TASK_ID, 0)
        if (id <= 0) return
        val app = context.applicationContext
        val c = app.container
        val result = goAsync()
        c.appScope.launch {
            try {
                val tasks = c.db.tasks()
                val task = tasks.get(id)
                if (task == null) {
                    dismiss(app, id)
                    return@launch
                }
                val now = System.currentTimeMillis()
                when (intent.action) {
                    ACTION_FIRE -> {
                        val at = task.remindAt ?: return@launch
                        if (task.state != TaskState.OPEN || (task.notifiedAt ?: 0) >= at) return@launch
                        // Alarma vieja de una hora que se cambió sin reprogramar.
                        if (at - now > EARLY_MS) return@launch c.reminders.schedule(task)
                        notify(app, task, late = now - at > LATE_MS)
                        tasks.update(task.copy(notifiedAt = now))
                    }
                    ACTION_DONE -> {
                        tasks.setState(id, TaskState.DONE)
                        c.reminders.cancel(id)
                    }
                    ACTION_SNOOZE -> {
                        dismiss(app, id)
                        val snoozed = task.copy(remindAt = now + SNOOZE_MS)
                        tasks.update(snoozed)
                        c.reminders.schedule(snoozed)
                    }
                }
            } finally {
                result.finish()
            }
        }
    }

    companion object {
        const val ACTION_FIRE = "dev.javieraguilera.mysone.reminder.FIRE"
        const val ACTION_DONE = "dev.javieraguilera.mysone.reminder.DONE"
        const val ACTION_SNOOZE = "dev.javieraguilera.mysone.reminder.SNOOZE"
        private const val EXTRA_TASK_ID = "taskId"
        private const val TAG = "reminder" // con etiqueta, el id de tarea no choca con otras notificaciones
        private const val SNOOZE_MS = 3_600_000L
        private const val LATE_MS = 15 * 60_000L
        private const val EARLY_MS = 60_000L
        private const val FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        /** requestCode = taskId: un PendingIntent estable por tarea y acción. */
        fun pending(context: Context, action: String, taskId: Long): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                taskId.toInt(),
                Intent(context, ReminderReceiver::class.java).setAction(action).putExtra(EXTRA_TASK_ID, taskId),
                FLAGS,
            )

        /** Solo el texto de la tarea: nunca texto de la conversación (§10). */
        fun notify(context: Context, task: TaskItem, late: Boolean) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            val id = task.id
            val open = PendingIntent.getActivity(context, id.toInt(), MainActivity.intent(context, "tasks:$id"), FLAGS)
            val notification = NotificationCompat.Builder(context, MysoneApp.CHANNEL_REMINDERS)
                .setSmallIcon(R.drawable.ic_stat_mysone)
                .setContentTitle(context.getString(if (late) R.string.reminder_title_late else R.string.reminder_title))
                .setContentText(task.text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(task.text))
                .setWhen(task.remindAt ?: System.currentTimeMillis())
                .setShowWhen(true)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(open)
                .addAction(0, context.getString(R.string.reminder_action_done), pending(context, ACTION_DONE, id))
                .addAction(0, context.getString(R.string.reminder_action_snooze), pending(context, ACTION_SNOOZE, id))
                .build()
            NotificationManagerCompat.from(context).notify(TAG, id.toInt(), notification)
        }

        fun dismiss(context: Context, taskId: Long) {
            NotificationManagerCompat.from(context).cancel(TAG, taskId.toInt())
        }
    }
}
