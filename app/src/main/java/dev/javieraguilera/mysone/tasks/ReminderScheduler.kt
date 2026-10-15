package dev.javieraguilera.mysone.tasks

import android.app.AlarmManager
import android.content.Context
import dev.javieraguilera.mysone.container
import dev.javieraguilera.mysone.data.TaskItem
import dev.javieraguilera.mysone.data.TaskState
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Alarmas exactas de los recordatorios (§6, docs/investigacion/02 §13). La BD es la fuente de verdad. */
class ReminderScheduler(private val context: Context) {
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val mutex = Mutex()

    /** Pone la alarma si la tarea está OPEN con remindAt futuro; si ya no es un recordatorio, la quita. */
    fun schedule(task: TaskItem) {
        val at = task.remindAt
        if (task.state != TaskState.OPEN || at == null) {
            cancel(task.id)
            return
        }
        val pi = ReminderReceiver.pending(context, ReminderReceiver.ACTION_FIRE, task.id)
        if (at <= System.currentTimeMillis()) {
            alarms.cancel(pi) // ya pasó: si toca, lo avisa rescheduleAll
            return
        }
        if (alarms.canScheduleExactAlarms()) {
            try {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                return
            } catch (_: SecurityException) {
                // permiso revocado entre la comprobación y la llamada
            }
        }
        alarms.setWindow(AlarmManager.RTC_WAKEUP, at, WINDOW_MS, pi)
    }

    /** Quita la alarma y la notificación si está a la vista. */
    fun cancel(taskId: Long) {
        alarms.cancel(ReminderReceiver.pending(context, ReminderReceiver.ACTION_FIRE, taskId))
        ReminderReceiver.dismiss(context, taskId)
    }

    /** Arranque, cambio de hora/zona, actualización y red de seguridad del mantenimiento. */
    suspend fun rescheduleAll() = mutex.withLock {
        val tasks = context.container.db.tasks()
        val now = System.currentTimeMillis()
        for (task in tasks.withReminder()) {
            when (plan(task, now)) {
                Plan.ALARM -> schedule(task)
                Plan.NOTIFY_NOW -> {
                    ReminderReceiver.notify(context, task, late = true)
                    tasks.update(task.copy(notifiedAt = now))
                }
                Plan.NONE -> Unit
            }
        }
    }

    enum class Plan { ALARM, NOTIFY_NOW, NONE }

    companion object {
        /** Ventana mínima de setWindow desde Android 12 (developer.android.com, "Schedule alarms"). */
        private const val WINDOW_MS = 10 * 60_000L
        private const val DAY_MS = 24 * 3_600_000L

        /** Futuras → alarma; vencidas hace menos de 24 h y sin avisar para esa hora → aviso ya; el resto, nada. */
        fun plan(task: TaskItem, now: Long): Plan {
            val at = task.remindAt
            return when {
                task.state != TaskState.OPEN || at == null -> Plan.NONE
                at > now -> Plan.ALARM
                (task.notifiedAt ?: 0) >= at -> Plan.NONE
                now - at <= DAY_MS -> Plan.NOTIFY_NOW
                else -> Plan.NONE
            }
        }
    }
}
