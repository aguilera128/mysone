package dev.javieraguilera.mysone.tasks

import android.content.Context
import dev.javieraguilera.mysone.data.TaskItem

/** Alarmas exactas de los recordatorios (§6). Lo implementa B4. */
class ReminderScheduler(private val context: Context) {
    /** Si state OPEN y remindAt futuro. */
    fun schedule(task: TaskItem) {}

    fun cancel(taskId: Long) {}

    /** Arranque, cambio de hora/zona, actualización. */
    suspend fun rescheduleAll() {}
}
