package dev.javieraguilera.mysone.ai

import dev.javieraguilera.mysone.data.ConvSource
import dev.javieraguilera.mysone.data.ConvState
import dev.javieraguilera.mysone.data.Conversation
import dev.javieraguilera.mysone.data.Segment
import dev.javieraguilera.mysone.data.TaskState
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessingTest {
    private val madrid = ZoneId.of("Europe/Madrid")

    private fun ms(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int = 0) =
        ZonedDateTime.of(y, mo, d, h, mi, s, 0, madrid).toInstant().toEpochMilli()

    /** Jueves 15-oct-2026 18:42 en Madrid. */
    private val start = ms(2026, 10, 15, 18, 42)

    private val sample = """
        ```json
        {"title":"  Cena   del viernes ","summary":"Quedasteis para cenar.","keyPoints":["Cena el viernes"],"isNoise":false,
         "tasks":[
          {"text":"Llamar al taller","explicitReminder":true,"dueAt":null,"remindAt":"2026-10-16T10:00","quote":"Recuérdame mañana a las diez llamar al taller"},
          {"text":"Mirar vuelos","explicitReminder":false,"dueAt":"2026-10-18","remindAt":"2026-13-40T99:00","quote":"Tenemos que mirar vuelos"},
          {"text":"llamar al taller.","explicitReminder":false,"dueAt":null,"remindAt":null,"quote":""}
         ],
         "facts":[{"text":"Lucía cumple años el 12 de abril"},{"text":"A"},{"text":"B"},{"text":"C"}],
         "extra":"se ignora"}
        ```
    """.trimIndent()

    @Test fun `decodifica aunque venga entre comillas de código y con claves de más`() {
        val out = decodeOutput<ProcessOut>(sample)
        assertEquals(3, out.tasks.size)
        assertEquals(4, out.facts.size)
    }

    @Test fun `una salida que no es JSON es BAD_OUTPUT`() {
        assertKind(LlmErrorKind.BAD_OUTPUT) { decodeOutput<ProcessOut>("Lo siento, no puedo") }
        assertKind(LlmErrorKind.BAD_OUTPUT) { decodeOutput<ProcessOut>("""{"tasks":"no es una lista"}""") }
    }

    @Test fun `validación de textos, estados, fechas, duplicados y facts`() {
        val p = validate(decodeOutput(sample), start, madrid, registered = emptyList())
        assertEquals("Cena del viernes", p.title)
        assertEquals(listOf("Cena el viernes"), p.keyPoints)

        assertEquals(2, p.tasks.size) // "llamar al taller." repite la primera
        val reminder = p.tasks[0]
        assertEquals(TaskState.OPEN, reminder.state)
        assertEquals(ms(2026, 10, 16, 10, 0), reminder.remindAt)
        assertNull(reminder.dueAt)

        val suggested = p.tasks[1]
        assertEquals(TaskState.SUGGESTED, suggested.state)
        assertEquals(ms(2026, 10, 18, 9, 0), suggested.dueAt) // fecha sin hora → 09:00
        assertNull(suggested.remindAt) // fecha imposible → null

        assertEquals(3, p.facts.size) // máximo 3
    }

    @Test fun `no repite tareas ya registradas`() {
        val p = validate(decodeOutput(sample), start, madrid, registered = listOf("Llamar  al TALLER"))
        assertEquals(listOf("Mirar vuelos"), p.tasks.map { it.text })
    }

    @Test fun `ruido sin tareas ni facts`() {
        val out = decodeOutput<ProcessOut>(sample).copy(isNoise = true)
        val p = validate(out, start, madrid, emptyList())
        assertTrue(p.isNoise)
        assertTrue(p.tasks.isEmpty())
        assertTrue(p.facts.isEmpty())
    }

    @Test fun `fechas de la IA en hora local`() {
        assertEquals(ms(2026, 10, 16, 9, 45), aiDate("2026-10-16T09:45", start, madrid))
        assertEquals(ms(2026, 10, 16, 9, 45), aiDate(" 2026-10-16 09:45:30+02:00 ", start, madrid)) // segundos y zona fuera
        assertEquals(ms(2026, 10, 20, 9, 0), aiDate("2026-10-20", start, madrid))
        // El 25-oct es el cambio al horario de invierno: 10:00 ya es UTC+1.
        assertEquals(ZonedDateTime.of(2026, 10, 25, 9, 0, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli(), aiDate("2026-10-25T10:00", start, madrid))
        for (bad in listOf(null, "", "mañana", "2026-02-30T10:00", "2026-10-16T25:00", "16/10/2026 10:00")) {
            assertNull(bad, aiDate(bad, start, madrid))
        }
        assertNull(aiDate("2020-01-01T10:00", start, madrid)) // pasado lejano
        assertNull(aiDate("2035-01-01T10:00", start, madrid)) // demasiado lejos
        assertEquals(ms(2026, 10, 15, 8, 0), aiDate("2026-10-15T08:00", start, madrid)) // la mañana del mismo día vale
    }

    @Test fun `la misma fecha local es otro instante en otra zona`() {
        val tokyo = ZoneId.of("Asia/Tokyo")
        assertEquals(
            ZonedDateTime.of(2026, 10, 16, 10, 0, 0, 0, tokyo).toInstant().toEpochMilli(),
            aiDate("2026-10-16T10:00", start, tokyo),
        )
    }

    @Test fun `mensaje de usuario del procesado`() {
        val c = Conversation(id = 7, startedAt = start, endedAt = start + 6 * 60_000, source = ConvSource.MIC, state = ConvState.PROCESSING)
        val segs = listOf(
            Segment(1, 7, ms(2026, 10, 15, 18, 42, 10), 0, " Oye, ¿venís el viernes? "),
            Segment(2, 7, ms(2026, 10, 15, 18, 43, 2), 0, "Recuérdame mañana a las diez llamar al taller."),
        )
        val expected = """
            <conversacion>
            inicio: jueves 15 de octubre de 2026, 18:42 (2026-10-15T18:42)
            zona: Europe/Madrid
            duración: 6 min
            </conversacion>
            <tareas_registradas>
            - Comprar pan
            </tareas_registradas>
            <transcripcion>
            [18:42:10] Oye, ¿venís el viernes?
            [18:43:02] Recuérdame mañana a las diez llamar al taller.
            </transcripcion>
        """.trimIndent()
        assertEquals(expected, processInput(c, segs, listOf("Comprar pan"), 1, 1, madrid))
        assertTrue(processInput(c, segs, emptyList(), 2, 3, madrid).contains("parte: 2 de 3\n"))
        assertTrue(processInput(c, segs, emptyList(), 1, 1, madrid).contains("<tareas_registradas>\n(ninguna)\n"))
    }

    @Test fun `trocea por segmentos sin pasarse del tope`() {
        val segs = (1..10).map { Segment(it.toLong(), 1, it * 1000L, 0, "x".repeat(88)) } // 100 caracteres cada uno
        val parts = splitTranscript(segs, maxChars = 350)
        assertEquals(listOf(3, 3, 3, 1), parts.map { it.size })
        assertEquals(segs, parts.flatten())
        assertEquals(1, splitTranscript(segs).size)
    }

    @Test fun `fusiona las partes`() {
        val a = ProcessOut(title = "", summary = "Primera.", keyPoints = listOf("a"), tasks = listOf(TaskOut("T1")))
        val b = ProcessOut(title = "Título", summary = "Segunda.", isNoise = true, tasks = listOf(TaskOut("T2")))
        val m = merge(listOf(a, b))
        assertEquals("Título", m.title)
        assertEquals("Primera. Segunda.", m.summary)
        assertFalse(m.isNoise)
        assertEquals(listOf("T1"), m.tasks.map { it.text }) // lo de una parte con ruido no cuenta
        assertTrue(merge(listOf(b, b)).isNoise)
    }

    @Test fun `todos los esquemas son estrictos`() {
        listOf(Schemas.PROCESS, Schemas.REMINDER, Schemas.PLAN, Schemas.DIGEST).forEach(::assertStrict)
        assertEquals(
            JsonArray(listOf(JsonPrimitive("string"), JsonPrimitive("null"))),
            Schemas.PROCESS["properties"]!!.jsonObject["tasks"]!!.jsonObject["items"]!!.jsonObject["properties"]!!.jsonObject["remindAt"]!!.jsonObject["type"],
        )
    }

    /** Cada objeto: additionalProperties false y todas sus propiedades en required. */
    private fun assertStrict(e: JsonElement) {
        if (e !is JsonObject) return
        if (e["type"] == JsonPrimitive("object")) {
            assertEquals(JsonPrimitive(false), e["additionalProperties"])
            val props = e["properties"]!!.jsonObject
            assertEquals(props.keys, e["required"]!!.jsonArray.map { (it as JsonPrimitive).content }.toSet())
            props.values.forEach(::assertStrict)
        }
        e["items"]?.let(::assertStrict)
    }

    @Test fun `los prompts empiezan por su etiqueta y el de procesado es largo`() {
        assertTrue(Prompts.PROCESS.startsWith("mysone:procesar v1\n"))
        assertTrue(Prompts.REMINDER.startsWith("mysone:recordatorio v1\n"))
        assertTrue(Prompts.PLAN.startsWith("mysone:planificar v1\n"))
        assertTrue(Prompts.ANSWER.startsWith("mysone:responder v1\n"))
        assertTrue(Prompts.DIGEST.startsWith("mysone:resumen-diario v1\n"))
        assertTrue(Prompts.TEST.startsWith("mysone:prueba v1\n"))
        // ≥ 512 tokens para que cachee: en español sale a más de 3 caracteres por token.
        assertTrue(Prompts.PROCESS.length > 4 * 512)
        assertTrue(Prompts.DIGEST.length > 4 * 512)
        // json_object (DeepSeek) exige la palabra JSON en el prompt.
        listOf(Prompts.PROCESS, Prompts.REMINDER, Prompts.PLAN, Prompts.DIGEST).forEach { assertTrue("JSON" in it) }
    }

    @Test fun `días con resumen pendiente`() {
        fun conv(day: Int, state: String, noise: Boolean = false, attempts: Int = 0) = Conversation(
            startedAt = ms(2026, 10, day, 12, 0), endedAt = ms(2026, 10, day, 12, 5), source = ConvSource.MIC,
            state = state, isNoise = noise, processAttempts = attempts,
        )
        val convs = listOf(
            conv(10, ConvState.DONE), conv(10, ConvState.FAILED, attempts = 3), // fallida del todo: no espera
            conv(11, ConvState.DONE, noise = true),                            // solo ruido: nada que resumir
            conv(12, ConvState.DONE), conv(12, ConvState.READY),                // aún por procesar
            conv(13, ConvState.DONE), conv(13, ConvState.FAILED, attempts = 1), // se reintentará
            conv(14, ConvState.DONE),
        )
        assertEquals(listOf("2026-10-14", "2026-10-10"), daysToDigest(convs, madrid))
    }
}
