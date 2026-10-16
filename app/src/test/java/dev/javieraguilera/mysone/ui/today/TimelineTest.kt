package dev.javieraguilera.mysone.ui.today

import dev.javieraguilera.mysone.data.ConvSource
import dev.javieraguilera.mysone.data.ConvState
import dev.javieraguilera.mysone.data.Conversation
import dev.javieraguilera.mysone.data.GapReason
import dev.javieraguilera.mysone.data.ListeningGap
import dev.javieraguilera.mysone.data.TaskItem
import dev.javieraguilera.mysone.data.TaskState
import org.junit.Assert.assertEquals
import org.junit.Test

class TimelineTest {
    private fun conv(id: Long, at: Long) = Conversation(id = id, startedAt = at, endedAt = at + 60_000, source = ConvSource.MIC, state = ConvState.DONE)
    private fun task(id: Long, conv: Long, state: String, remindAt: Long? = null) =
        TaskItem(id = id, text = "t$id", state = state, remindAt = remindAt, conversationId = conv, createdAt = 0)

    @Test fun `intercala huecos y conversaciones, lo más reciente arriba`() {
        val items = buildTimeline(
            conversations = listOf(conv(1, 1_000), conv(2, 5_000)),
            gaps = listOf(ListeningGap(id = 7, startedAt = 3_000, endedAt = 4_000, reason = GapReason.PAUSED)),
            tasks = emptyList(),
            transcribingIds = listOf(2L),
        )
        assertEquals(listOf("c2", "g7", "c1"), items.map { it.key })
        assertEquals(true, (items[0] as TimelineItem.Conv).transcribing)
        assertEquals(false, (items[2] as TimelineItem.Conv).transcribing)
    }

    @Test fun `cuenta recordatorios y tareas por conversación sin las descartadas`() {
        val items = buildTimeline(
            conversations = listOf(conv(1, 1_000)),
            gaps = emptyList(),
            tasks = listOf(
                task(1, 1, TaskState.OPEN, remindAt = 9_000),
                task(2, 1, TaskState.OPEN),
                task(3, 1, TaskState.SUGGESTED),
                task(4, 1, TaskState.DISMISSED),
                task(5, 2, TaskState.OPEN, remindAt = 9_000), // de otra conversación
            ),
            transcribingIds = emptyList(),
        )
        val c = items.single() as TimelineItem.Conv
        assertEquals(1, c.reminders)
        assertEquals(2, c.otherTasks)
    }
}
