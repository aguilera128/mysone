package dev.javieraguilera.mysone.ui.today

import dev.javieraguilera.mysone.data.Conversation
import dev.javieraguilera.mysone.data.ListeningGap
import dev.javieraguilera.mysone.data.TaskItem
import dev.javieraguilera.mysone.data.TaskState

/** Fila de la línea de tiempo de Hoy: una conversación o un hueco de la escucha. */
sealed interface TimelineItem {
    val at: Long
    val key: String

    data class Conv(val conversation: Conversation, val tasks: List<TaskItem>, val transcribing: Boolean) : TimelineItem {
        override val at get() = conversation.startedAt
        override val key get() = "c${conversation.id}"

        /** Recordatorios: tareas abiertas con hora de aviso. */
        val reminders get() = tasks.count { it.state == TaskState.OPEN && it.remindAt != null }
        val otherTasks get() = tasks.count { it.state == TaskState.SUGGESTED || (it.state == TaskState.OPEN && it.remindAt == null) }
    }

    data class Gap(val gap: ListeningGap) : TimelineItem {
        override val at get() = gap.startedAt
        override val key get() = "g${gap.id}"
    }
}

/** Conversaciones y huecos intercalados, lo más reciente arriba. Las tareas descartadas no cuentan. */
fun buildTimeline(
    conversations: List<Conversation>,
    gaps: List<ListeningGap>,
    tasks: List<TaskItem>,
    transcribingIds: Collection<Long>,
): List<TimelineItem> {
    val byConversation = tasks.filter { it.state != TaskState.DISMISSED }.groupBy { it.conversationId }
    val transcribing = transcribingIds.toSet()
    val convs = conversations.map { TimelineItem.Conv(it, byConversation[it.id].orEmpty(), it.id in transcribing) }
    return (convs + gaps.map { TimelineItem.Gap(it) }).sortedByDescending { it.at }
}
