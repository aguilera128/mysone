package dev.javieraguilera.mysone.tasks

import dev.javieraguilera.mysone.data.TaskItem
import dev.javieraguilera.mysone.data.TaskState
import dev.javieraguilera.mysone.tasks.ReminderScheduler.Companion.plan
import dev.javieraguilera.mysone.tasks.ReminderScheduler.Plan
import org.junit.Assert.assertEquals
import org.junit.Test

class ReminderPlanTest {
    private val now = 1_760_000_000_000L
    private val hour = 3_600_000L

    private fun task(remindAt: Long?, state: String = TaskState.OPEN, notifiedAt: Long? = null) =
        TaskItem(id = 1, text = "Llamar al fontanero", state = state, remindAt = remindAt, createdAt = 0, notifiedAt = notifiedAt)

    @Test fun `futura lleva alarma`() {
        assertEquals(Plan.ALARM, plan(task(now + hour), now))
    }

    @Test fun `vencida hace menos de 24 h y sin avisar se avisa ya`() {
        assertEquals(Plan.NOTIFY_NOW, plan(task(now - hour), now))
        assertEquals(Plan.NOTIFY_NOW, plan(task(now), now))
    }

    @Test fun `vencida y ya avisada no se repite`() {
        assertEquals(Plan.NONE, plan(task(now - hour, notifiedAt = now - hour + 1_000), now))
    }

    @Test fun `pospuesta tras un aviso anterior vuelve a avisarse`() {
        assertEquals(Plan.NOTIFY_NOW, plan(task(now - hour, notifiedAt = now - 2 * hour), now))
    }

    @Test fun `vencida hace más de 24 h se pierde`() {
        assertEquals(Plan.NONE, plan(task(now - 25 * hour), now))
    }

    @Test fun `sin hora o no abierta no hace nada`() {
        assertEquals(Plan.NONE, plan(task(null), now))
        assertEquals(Plan.NONE, plan(task(now + hour, state = TaskState.DONE), now))
        assertEquals(Plan.NONE, plan(task(now - hour, state = TaskState.SUGGESTED), now))
    }
}
