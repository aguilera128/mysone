package dev.javieraguilera.mysone.tasks

import dev.javieraguilera.mysone.core.AppJson
import dev.javieraguilera.mysone.data.AppSettings
import dev.javieraguilera.mysone.data.ChatMessage
import dev.javieraguilera.mysone.data.ConvSource
import dev.javieraguilera.mysone.data.ConvState
import dev.javieraguilera.mysone.data.Conversation
import dev.javieraguilera.mysone.data.DailyDigest
import dev.javieraguilera.mysone.data.Fact
import dev.javieraguilera.mysone.data.FactState
import dev.javieraguilera.mysone.data.GapReason
import dev.javieraguilera.mysone.data.ListeningGap
import dev.javieraguilera.mysone.data.Segment
import dev.javieraguilera.mysone.data.TaskItem
import dev.javieraguilera.mysone.data.TaskState
import java.io.File
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DataManagerTest {
    @get:Rule val tmp = TemporaryFolder()

    private val now = 1_760_000_000_000L
    private val day = 86_400_000L

    @Test fun `corte de retención`() {
        assertNull(DataManager.retentionCutoff(now, 0))
        assertEquals(now - 90 * day, DataManager.retentionCutoff(now, 90))
        assertEquals(now - day, DataManager.retentionCutoff(now, 1))
    }

    @Test fun `la exportación va y vuelve sin perder nada y sin clave`() {
        val export = ExportFile(
            exportedAt = now,
            settings = AppSettings(listeningEnabled = true, retentionDays = 30),
            conversations = listOf(Conversation(1, now - 60_000, now, ConvSource.MIC, ConvState.DONE, title = "Presupuesto", keyPoints = "[\"a\"]")),
            segments = listOf(Segment(1, 1, now - 60_000, now - 50_000, "Hay que mandar el presupuesto «hoy».")),
            tasks = listOf(TaskItem(1, "Mandar presupuesto", TaskState.OPEN, remindAt = now + day, conversationId = 1, createdAt = now)),
            facts = listOf(Fact(1, "Ana es la contable", FactState.CONFIRMED, 1, now)),
            digests = listOf(DailyDigest("2026-10-14", "Día tranquilo", "[]", now)),
            chat = listOf(ChatMessage(1, "user", "¿Qué tengo pendiente?", createdAt = now)),
            gaps = listOf(ListeningGap(1, now - day, null, GapReason.KILLED)),
        )
        val json = AppJson.encodeToString(ExportFile.serializer(), export)

        assertEquals(export, AppJson.decodeFromString(ExportFile.serializer(), json))
        assertEquals(
            setOf("app", "formatVersion", "exportedAt", "settings", "conversations", "segments", "tasks", "facts", "digests", "chat", "gaps"),
            AppJson.parseToJsonElement(json).jsonObject.keys,
        )
        assertFalse(json.contains("apiKey", ignoreCase = true))
    }

    @Test fun `huérfanos de pending solo si no están en la cola y tienen más de 10 min`() {
        val queued = tmp.newFile("a.pcm").apply { setLastModified(now - day) }
        val orphan = tmp.newFile("b.pcm").apply { setLastModified(now - day) }
        val fresh = tmp.newFile("c.pcm").apply { setLastModified(now - 60_000) }
        val dir = tmp.newFolder("sub").apply { setLastModified(now - day) }

        val result = MaintenanceWorker.orphans(listOf(queued, orphan, fresh, dir), setOf("a.pcm"), now)

        assertEquals(listOf<File>(orphan), result)
    }
}
