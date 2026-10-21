package dev.javieraguilera.mysone.ai

import dev.javieraguilera.mysone.data.ConvSource
import dev.javieraguilera.mysone.data.ConvState
import dev.javieraguilera.mysone.data.Conversation
import dev.javieraguilera.mysone.data.DailyDigest
import dev.javieraguilera.mysone.data.Segment
import dev.javieraguilera.mysone.data.TaskState
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.test.runTest
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
        assertEquals("Primera.", m.summary) // el resumen de la parte con ruido tampoco
        assertFalse(m.isNoise)
        assertEquals(listOf("T1"), m.tasks.map { it.text }) // lo de una parte con ruido no cuenta
        assertTrue(merge(listOf(b, b)).isNoise)
    }

    @Test fun `al fusionar muchas partes caben todas, por frases enteras`() {
        val parts = (1..5).map { n ->
            ProcessOut(
                title = "T$n",
                summary = "En la parte $n hablasteis del presupuesto de la obra con todo detalle. ".repeat(6).trim(),
                keyPoints = listOf("p$n.1", "p$n.2", "p$n.3"),
            )
        }
        val m = merge(parts)
        assertTrue(m.summary.length <= MAX_SUMMARY)
        (1..5).forEach { assertTrue("parte $it", "En la parte $it " in m.summary) }
        assertTrue(m.summary.endsWith("detalle."))
        assertEquals(m.summary, validate(m, start, madrid, emptyList()).summary) // validate ya no lo corta
        // Puntos clave por turnos: los primeros de todas las partes antes que los segundos.
        assertEquals(listOf("p1.1", "p2.1", "p3.1", "p4.1", "p5.1", "p1.2", "p2.2", "p3.2"), m.keyPoints)
    }

    @Test fun `recorte por frases`() {
        assertEquals("Corto.", sentencesUpTo("Corto.", 50))
        assertEquals("Una frase. Otra frase.", sentencesUpTo("Una frase. Otra frase. Y una tercera más larga.", 30))
        // Una sola frase que no cabe: en una palabra y con puntos suspensivos.
        assertEquals("Una frase muy…", sentencesUpTo("Una frase muy larga que no cabe", 16))
    }

    @Test fun `no vuelve a sugerir lo ya confirmado`() {
        val out = ProcessOut(facts = listOf(FactOut("Lucía cumple años el 12 de abril"), FactOut("Ana es la contable")))
        val p = validate(out, start, madrid, emptyList(), knownFacts = listOf("lucía cumple años el 12 de abril."))
        assertEquals(listOf("Ana es la contable"), p.facts)
    }

    @Test fun `al pasar la medianoche la transcripción dice de qué día es lo que sigue`() {
        val c = Conversation(id = 7, startedAt = ms(2026, 10, 15, 23, 50), endedAt = ms(2026, 10, 16, 0, 20), source = ConvSource.MIC, state = ConvState.PROCESSING)
        val segs = listOf(
            Segment(1, 7, ms(2026, 10, 15, 23, 55), 0, "Mañana a primera hora lo miro."),
            Segment(2, 7, ms(2026, 10, 16, 0, 10), 0, "Recuérdame mañana a las diez llamar al taller."),
            Segment(3, 7, ms(2026, 10, 16, 0, 12), 0, "Vale."),
        )
        val text = processInput(c, segs, emptyList(), 1, 1, madrid)
        assertTrue(text.contains("[23:55:00] Mañana a primera hora lo miro.\n(día 2026-10-16)\n[00:10:00] Recuérdame"))
        assertEquals(1, text.split("(día ").size - 1)
        // Una parte que ya empieza al día siguiente lo dice desde la primera línea.
        assertTrue(processInput(c, segs.drop(1), emptyList(), 2, 2, madrid).contains("<transcripcion>\n(día 2026-10-16)\n[00:10:00]"))
    }

    @Test fun `una respuesta cortada por max_tokens no se repite entera`() = runTest {
        var calls = 0
        val truncating = object : LlmClient {
            override suspend fun complete(req: LlmRequest): LlmResponse {
                calls++
                throw LlmException(LlmErrorKind.BAD_OUTPUT, "max_tokens", truncated = true)
            }
        }
        assertKind(LlmErrorKind.BAD_OUTPUT) { truncating.completeJson<DigestOut>(LlmRequest("s", emptyList(), "m", 10)) }
        assertEquals(1, calls)

        calls = 0
        val badJson = object : LlmClient {
            override suspend fun complete(req: LlmRequest): LlmResponse {
                calls++
                return LlmResponse("no es JSON", 0, 0, 0)
            }
        }
        assertKind(LlmErrorKind.BAD_OUTPUT) { badJson.completeJson<DigestOut>(LlmRequest("s", emptyList(), "m", 10)) }
        assertEquals(2, calls) // el formato sí se reintenta una vez
    }

    @Test fun `el resumen de un día se rehace si alguna conversación se procesó después`() {
        fun conv(id: Long, processedAt: Long?, state: String = ConvState.DONE) = Conversation(
            id = id, startedAt = start, endedAt = start + 60_000, source = ConvSource.MIC, state = state, processedAt = processedAt,
        )
        val digest = DailyDigest("2026-10-15", "Resumen", "[]", createdAt = 1_000)
        assertTrue(digestOutdated(null, listOf(conv(1, 500))))
        assertFalse(digestOutdated(digest, listOf(conv(1, 500), conv(2, 900))))
        assertTrue(digestOutdated(digest, listOf(conv(1, 500), conv(2, 1_500)))) // importada o reprocesada después
        assertFalse(digestOutdated(digest, listOf(conv(1, 500), conv(2, null, ConvState.READY))))
    }

    @Test fun `los fallos de un día se cuentan mientras el día no cambie`() {
        val day = listOf(Conversation(id = 1, startedAt = start, endedAt = start, source = ConvSource.MIC, state = ConvState.DONE, processedAt = 700))
        assertEquals(0, failures(null, day))
        val once = failed(null, day)
        assertEquals(1, failures(once, day))
        assertEquals(3, failures(failed(failed(once, day), day), day))
        // Otra conversación procesada ese día: empieza de cero.
        val changed = day + day[0].copy(id = 2, processedAt = 900)
        assertEquals(0, failures(once, changed))
        assertEquals(0, failures("basura", day))
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
        assertTrue(Prompts.PROCESS.startsWith("mysone:procesar v2\n"))
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

    @Test fun `un 400 o 413 es de esa conversación y cuenta como intento, 404, clave, cuota y servidor paran la cola`() {
        fun err(code: Int) = httpError(HttpReply(code, """{"error":{"type":"invalid_request_error"}}""", null))
        listOf(400, 413, 422).forEach {
            assertTrue("$it", err(it).isRequestError())
            assertTrue("$it", err(it).countsAsAttempt())
        }
        listOf(401, 403, 404, 408, 429, 500, 503).forEach {
            assertFalse("$it", err(it).isRequestError())
            assertFalse("$it", err(it).countsAsAttempt())
        }
        assertTrue(LlmException(LlmErrorKind.BAD_OUTPUT, "x").countsAsAttempt())
        assertFalse(LlmException(LlmErrorKind.SERVER, "URL no válida", retryable = false).isRequestError())
    }
}
