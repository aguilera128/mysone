package dev.javieraguilera.mysone.ai

import dev.javieraguilera.mysone.core.AppJson
import dev.javieraguilera.mysone.data.ChatMessage
import dev.javieraguilera.mysone.data.ChatRole
import dev.javieraguilera.mysone.data.ConvSource
import dev.javieraguilera.mysone.data.ConvState
import dev.javieraguilera.mysone.data.Conversation
import dev.javieraguilera.mysone.data.Fact
import dev.javieraguilera.mysone.data.FactState
import dev.javieraguilera.mysone.data.Segment
import dev.javieraguilera.mysone.data.TaskItem
import dev.javieraguilera.mysone.data.TaskState
import dev.javieraguilera.mysone.tasks.DataManager
import dev.javieraguilera.mysone.ui.ask.parseCitations
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AskEngineTest {
    private val madrid = ZoneId.of("Europe/Madrid")

    /** Jueves 15-oct-2026 18:42 en Madrid. */
    private val now = ZonedDateTime.of(2026, 10, 15, 18, 42, 0, 0, madrid).toInstant().toEpochMilli()

    @Test fun `citas en orden y sin repetir`() {
        assertEquals(listOf(42L, 7L), Citations.parse("Quedaste con Marta [c:42]. Y con Ana [c:7][c:42]."))
        assertEquals(emptyList<Long>(), Citations.parse("Sin citas [c:] [c:abc] [c 5] c:6 [C:8]"))
        assertEquals(listOf(99999999999L), Citations.parse("[c:99999999999]"))
        // Un número que no cabe en un Long no es una cita y no hace fallar la pregunta (antes, toLong() lanzaba).
        assertEquals("Nada [c:99999999999999999999].", Citations.canonical("Nada [c:99999999999999999999].", setOf(1L)))
        // Un [c: sin cerrar no se lleva el texto hasta el siguiente marcador.
        assertEquals("Uno [c: dos [c:5]", Citations.canonical("Uno [c: dos [c:5]", setOf(5L)))
    }

    @Test fun `una cita combinada se guarda y se borra como la ve la pantalla`() {
        for (raw in listOf("Quedaste con Ana el viernes [c:12, 13].", "Quedaste con Ana el viernes [c:12, c:13].", "Quedaste con Ana el viernes [c: 12].")) {
            assertEquals(raw, parseCitations(raw).ids, Citations.parse(raw))
            // Lo que guarda AskEngine.ask sin resúmenes diarios en el contexto, con la 13 ya borrada.
            val existing = Citations.parse(raw).toSet() - 13L
            val text = Citations.canonical(raw, existing)
            assertEquals(raw, "Quedaste con Ana el viernes [c:12].", text)
            val chat = listOf(
                ChatMessage(1, ChatRole.USER, "¿Con quién quedé?", createdAt = now),
                ChatMessage(2, ChatRole.ASSISTANT, text, citations = AppJson.encodeToString(existing.sorted()), createdAt = now),
            )
            assertEquals(raw, listOf(2L, 1L), DataManager.chatToForget(chat, setOf(12L)))
        }
    }

    @Test fun `consulta FTS con OR de los términos del planificador`() {
        assertEquals("\"Ana\"* OR \"presupuesto\"* OR \"coste\"* OR \"obra\"*", retrievalQuery(listOf("Ana", "presupuesto", " ", "coste de obra"), "da igual"))
        // Fuera las palabras vacías ("de"* casaría con casi todo); los números cortos se quedan.
        assertEquals("\"fin\"* OR \"semana\"* OR \"12\"*", retrievalQuery(listOf("fin de semana", "el 12", "para"), ""))
    }

    @Test fun `sin términos usa las palabras largas de la pregunta`() {
        assertEquals("\"dijo\"* OR \"Marta\"* OR \"presupuesto\"*", retrievalQuery(emptyList(), "¿Qué dijo Marta del presupuesto?"))
        assertNull(retrievalQuery(emptyList(), "¿Y eso?"))
    }

    @Test fun `rango de fechas del planificador`() {
        assertEquals("2026-10-14" to "2026-10-14", resolveRange("2026-10-14", "2026-10-14", now, madrid))
        assertEquals("2026-09-15" to "2026-10-15", resolveRange(null, null, now, madrid)) // últimos 30 días
        assertEquals("2026-10-01" to "2026-10-15", resolveRange("2026-10-01", null, now, madrid))
        assertEquals("2026-10-10" to "2026-10-12", resolveRange("2026-10-12", "2026-10-10", now, madrid)) // al revés
        assertEquals("2026-09-15" to "2026-10-15", resolveRange("2026-10-16", "2026-10-16", now, madrid)) // futuro → lo hablado antes
        assertEquals("2026-09-15" to "2026-10-15", resolveRange("ayer", "nunca", now, madrid))
    }

    @Test fun `historial alterno que empieza por el usuario`() {
        val msgs = listOf(
            LlmMessage("assistant", "a0"),
            LlmMessage("user", "u1"),
            LlmMessage("user", "u2"), // pregunta anterior que falló y se quedó sin respuesta
            LlmMessage("assistant", "a1"),
            LlmMessage("user", "u3"),
        )
        assertEquals(
            listOf(LlmMessage("user", "u1\n\nu2"), LlmMessage("assistant", "a1"), LlmMessage("user", "u3")),
            alternating(msgs),
        )
        assertTrue(alternating(emptyList()).isEmpty())
    }

    @Test fun `una respuesta vacía guardada no llega al historial`() {
        val msgs = listOf(LlmMessage("user", "u1"), LlmMessage("assistant", " "), LlmMessage("user", "u2"))
        assertEquals(listOf(LlmMessage("user", "u1\n\nu2")), alternating(msgs))
    }

    @Test fun `cuentan como citadas las de los resúmenes diarios y los fragmentos, y los datos llevan su cita`() {
        fun conv(id: Long) = Conversation(id = id, startedAt = now, endedAt = now, source = ConvSource.MIC, state = ConvState.DONE)
        val ctx = AskContext(
            conversations = listOf(conv(1), conv(2)),
            segments = listOf(Segment(10, 3, now, now, "x")),
            digests = emptyList(),
            tasks = listOf(
                TaskItem(text = "t", state = TaskState.OPEN, conversationId = 4, createdAt = now),
                TaskItem(text = "a mano", state = TaskState.OPEN, createdAt = now),
            ),
            facts = listOf(
                Fact(text = "Lucía cumple el 3", state = FactState.CONFIRMED, conversationId = 5, createdAt = now),
                Fact(text = "dato a mano", state = FactState.CONFIRMED, createdAt = now),
            ),
            digestSources = listOf(6, 1),
        )
        assertEquals(listOf(1L, 3L, 6L), ctx.implicitCitations)
        val input = answerInput("¿Cuándo cumple Lucía?", ctx, now, madrid)
        assertTrue("- Lucía cumple el 3 [c:5]\n" in input)
        assertTrue("- dato a mano\n" in input)
    }

    @Test fun `una respuesta que copia un fragmento sin citarlo se va con su conversación`() {
        val ctx = AskContext(emptyList(), listOf(Segment(10, 7, now, now, "frase-prueba-7731")), emptyList(), emptyList(), emptyList())
        // Sin marcador, o con el de otra conversación (la 8).
        for (raw in listOf("Dijiste «frase-prueba-7731».", "Dijiste «frase-prueba-7731» [c:8].")) {
            val existing = Citations.parse(raw).toSet()
            // Lo que guarda AskEngine.ask.
            val citations = AppJson.encodeToString((existing + ctx.implicitCitations).sorted())
            val chat = listOf(
                ChatMessage(1, ChatRole.USER, "¿Qué dije?", createdAt = now),
                ChatMessage(2, ChatRole.ASSISTANT, raw, citations = citations, createdAt = now),
            )
            assertEquals(raw, listOf(2L, 1L), DataManager.chatToForget(chat, setOf(7L)))
        }
    }
}
