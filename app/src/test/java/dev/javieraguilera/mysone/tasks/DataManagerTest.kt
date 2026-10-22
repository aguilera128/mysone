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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DataManagerTest {
    @get:Rule val tmp = TemporaryFolder()

    private val now = 1_760_000_000_000L
    private val day = 86_400_000L

    @Test fun `al borrar una conversación se van las respuestas que la citan y su pregunta`() {
        val chat = listOf(
            ChatMessage(1, "user", "¿Dónde me alojo en Granada?", createdAt = now),
            ChatMessage(2, "assistant", "En el Alhambra Palace [c:4]", citations = "[4,7]", createdAt = now),
            ChatMessage(3, "user", "¿Y qué tengo pendiente?", createdAt = now),
            ChatMessage(4, "assistant", "Comprar pan [c:5]", citations = "[5]", createdAt = now),
            ChatMessage(5, "assistant", "Sin citas", createdAt = now),
        )
        assertEquals(listOf(2L, 1L), DataManager.chatToForget(chat, setOf(4L)))
        assertEquals(listOf(2L, 1L, 4L, 3L), DataManager.chatToForget(chat, setOf(7L, 5L)))
        assertEquals(emptyList<Long>(), DataManager.chatToForget(chat, setOf(9L)))
    }

    @Test fun `corte de retención`() {
        assertNull(DataManager.retentionCutoff(now, 0))
        assertEquals(now - 90 * day, DataManager.retentionCutoff(now, 90))
        assertEquals(now - day, DataManager.retentionCutoff(now, 1))
    }

    @Test fun `lo importado después del corte conserva su texto aunque el fichero sea antiguo`() {
        val cutoff = now - 90 * day
        val saved = mapOf("5" to now - day, "6" to now - 100 * day, "7" to cutoff, "x" to now, "8" to "basura")
        assertEquals(setOf(5L, 7L), DataManager.importedSince(saved, cutoff).toSet())
        assertEquals(emptyList<Long>(), DataManager.importedSince(emptyMap<String, Any>(), cutoff))
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

    @Test fun `al olvidar solo se van las tareas que salen de lo olvidado`() {
        val since = now - 5 * 60_000
        val left = DataManager.normalized("Vale. ¿Me recuerdas el jueves enviar el presupuesto? Y comprar pan.")
        fun task(state: String, quote: String?, createdAt: Long = now - day) =
            TaskItem(text = "x", state = state, sourceQuote = quote, conversationId = 1, createdAt = createdAt)

        // Aceptada, con su cita en lo que queda (aunque cambien mayúsculas y signos): se queda.
        assertFalse(DataManager.fromForgotten(task(TaskState.OPEN, "me recuerdas el jueves, enviar el presupuesto"), left, since))
        assertFalse(DataManager.fromForgotten(task(TaskState.DISMISSED, "Comprar pan"), left, since))
        // La cita estaba en lo olvidado.
        assertTrue(DataManager.fromForgotten(task(TaskState.OPEN, "llamar al banco mañana"), left, since))
        assertTrue(DataManager.fromForgotten(task(TaskState.DONE, "llamar al banco"), left, since))
        // Sugerida: el reprocesado la rehace.
        assertTrue(DataManager.fromForgotten(task(TaskState.SUGGESTED, "comprar pan"), left, since))
        // Sin cita: por la hora.
        assertFalse(DataManager.fromForgotten(task(TaskState.OPEN, null, createdAt = since - 1), left, since))
        assertTrue(DataManager.fromForgotten(task(TaskState.OPEN, null, createdAt = since), left, since))
        // Sin nada que quede, todo lo citado se va.
        assertTrue(DataManager.fromForgotten(task(TaskState.OPEN, "comprar pan"), "", since))
    }
}
