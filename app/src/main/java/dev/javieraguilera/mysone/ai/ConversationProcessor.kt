package dev.javieraguilera.mysone.ai

import androidx.room3.withWriteTransaction
import dev.javieraguilera.mysone.AppContainer
import dev.javieraguilera.mysone.core.AppJson
import dev.javieraguilera.mysone.core.Time
import dev.javieraguilera.mysone.data.ChatRole
import dev.javieraguilera.mysone.data.ConvState
import dev.javieraguilera.mysone.data.Conversation
import dev.javieraguilera.mysone.data.Fact
import dev.javieraguilera.mysone.data.FactState
import dev.javieraguilera.mysone.data.Segment
import dev.javieraguilera.mysone.data.TaskItem
import dev.javieraguilera.mysone.data.TaskState
import java.time.ZoneId
import kotlinx.serialization.encodeToString

/** Título, resumen, puntos clave, tareas y facts de una conversación (§7.3). El estado lo lleva [AiProcessing]. */
class ConversationProcessor(private val container: AppContainer) {

    /**
     * Llama a la IA y guarda el resultado con la conversación en DONE. Lanza [LlmException]. false si no se ha
     * guardado nada porque mientras tanto la han olvidado, borrado o cambiado (ver [save]).
     */
    suspend fun process(c: Conversation, cfg: AiConfig, zone: ZoneId = Time.zone()): Boolean {
        val db = container.db
        val now = System.currentTimeMillis()
        val segments = db.segments().textForConversation(c.id)
        if (segments.isEmpty()) {
            return save(c.id, segments) {
                db.conversations().update(it.copy(state = ConvState.DONE, isNoise = true, processedAt = now, lastError = null))
            }
        }
        // Lo sugerido se rehace; lo que el usuario ya ha tocado y los recordatorios rápidos se quedan.
        val kept = db.tasks().forConversation(c.id).filter { it.state != TaskState.SUGGESTED }.map { it.text }
        val registered = kept.toMutableList()
        val parts = splitTranscript(segments)
        val outs = parts.mapIndexed { i, part ->
            val input = processInput(c, part, registered, i + 1, parts.size, zone)
            val out: ProcessOut = cfg.client.completeJson(
                LlmRequest(Prompts.PROCESS, listOf(LlmMessage(ChatRole.USER, input)), cfg.ai.modelBackground, 4096, Schemas.PROCESS, "low"),
            )
            registered += out.tasks.map { it.text }
            out
        }
        // Los recuerdos ya confirmados (de esta conversación o de otra) no se vuelven a sugerir.
        val confirmed = db.facts().listByState(FactState.CONFIRMED, Int.MAX_VALUE).map { it.text }
        val result = validate(merge(outs), c.startedAt, zone, kept, confirmed)

        return save(c.id, segments) { current ->
            db.tasks().forConversation(c.id).filter { it.state == TaskState.SUGGESTED }.forEach { db.tasks().delete(it.id) }
            db.facts().forConversation(c.id).filter { it.state == FactState.SUGGESTED }.forEach { db.facts().delete(it.id) }
            // Ruido (tele, radio): los recordatorios rápidos que dejó y que aún no han sonado vuelven a sugerencia, sin alarma.
            if (result.isNoise) {
                quickToDemote(db.tasks().forConversation(c.id)).forEach {
                    db.tasks().update(it.copy(state = TaskState.SUGGESTED))
                    container.reminders.cancel(it.id)
                }
            } else {
                // El primer «no es ruido» las da por buenas: un reprocesado posterior («Reprocesar», «Olvidar») ya no las quita.
                db.tasks().confirmQuick(c.id)
            }
            result.tasks.forEach { saveTask(it, c.id, now) }
            result.facts.forEach { db.facts().insert(Fact(text = it, state = FactState.SUGGESTED, conversationId = c.id, createdAt = now)) }
            db.conversations().update(
                current.copy(
                    state = ConvState.DONE,
                    title = result.title,
                    summary = result.summary,
                    keyPoints = AppJson.encodeToString(result.keyPoints),
                    isNoise = result.isNoise,
                    processedAt = now,
                    lastError = null,
                ),
            )
        }
    }

    /**
     * Escribe el resultado en una transacción, y solo si la conversación sigue en PROCESSING con los segmentos
     * que se enviaron. "Olvidar", borrar y "Borrar todo" escriben en otra: o terminan antes y aquí se descarta, o
     * después y se llevan también esto. Si solo han cambiado los segmentos, vuelve a READY para reprocesarla.
     * Apagar la IA no lo descarta, a propósito (§7.3): el texto ya ha salido y la respuesta está pagada. Tirarla la
     * dejaría en READY y, al volver a activarla, se enviaría otra vez.
     */
    private suspend fun save(id: Long, sent: List<Segment>, write: suspend (Conversation) -> Unit): Boolean {
        val db = container.db
        return db.withWriteTransaction {
            val current = db.conversations().get(id)?.takeIf { it.state == ConvState.PROCESSING }
                ?: return@withWriteTransaction false
            if (db.segments().textForConversation(id).map { it.id } != sent.map { it.id }) {
                db.conversations().setState(id, ConvState.READY)
                return@withWriteTransaction false
            }
            write(current)
            true
        }
    }

    /** Inserta la tarea y, si es un recordatorio abierto, programa la alarma (con la hora recién pasada, avisa ya). */
    /** [quick]: viene de QuickReminders (ver [TaskItem.quick]). */
    internal suspend fun saveTask(t: NewTask, conversationId: Long, now: Long, quick: Boolean = false) {
        val item = TaskItem(
            text = t.text, state = t.state, dueAt = t.dueAt, remindAt = t.remindAt,
            conversationId = conversationId, sourceQuote = t.quote, createdAt = now, quick = quick,
        )
        val id = container.db.tasks().insert(item)
        if (item.state == TaskState.OPEN && item.remindAt != null) container.reminders.schedule(item.copy(id = id))
    }
}

/** Recordatorios rápidos que el dueño no ha tocado y que aún no han avisado: los que el veredicto de ruido quita. */
internal fun quickToDemote(tasks: List<TaskItem>): List<TaskItem> =
    tasks.filter { it.quick && it.state == TaskState.OPEN && it.notifiedAt == null }

/** Por encima de esto (~1 h de conversación) se procesa por partes y se fusiona. */
internal const val MAX_PART_CHARS = 60_000
private const val DAY_MS = 86_400_000L

/** Tarea validada, lista para guardar. */
internal data class NewTask(val text: String, val state: String, val dueAt: Long?, val remindAt: Long?, val quote: String?)

internal data class Processed(
    val title: String?,
    val summary: String?,
    val keyPoints: List<String>,
    val isNoise: Boolean,
    val tasks: List<NewTask>,
    val facts: List<String>,
)

/** Mensaje de usuario del procesado: lo único que cambia entre llamadas. */
internal fun processInput(c: Conversation, part: List<Segment>, registered: List<String>, index: Int, total: Int, zone: ZoneId): String =
    buildString {
        appendLine("<conversacion>")
        appendLine("inicio: ${Time.dateTimeLong(c.startedAt, zone)} (${Time.toLocalDateTime(c.startedAt, zone)})")
        appendLine("zona: ${zone.id}")
        appendLine("duración: ${((c.endedAt - c.startedAt) / 60_000).coerceAtLeast(1)} min")
        if (total > 1) appendLine("parte: $index de $total")
        appendLine("</conversacion>")
        appendRegistered(registered)
        appendTranscript(part, zone, knownDay = c.startedAt)
    }

internal fun StringBuilder.appendRegistered(texts: List<String>) {
    appendLine("<tareas_registradas>")
    if (texts.isEmpty()) appendLine("(ninguna)") else texts.forEach { appendLine("- $it") }
    appendLine("</tareas_registradas>")
}

/**
 * "[HH:mm:ss] texto" por segmento, con quién lo dice delante si se sabe ([speakerPrefix]). Al pasar a otro día que el
 * de [knownDay] (el que ya da el mensaje) o el de la línea anterior, una línea "(día yyyy-MM-dd)": sin ella, lo dicho
 * después de medianoche parecería del día anterior.
 */
internal fun StringBuilder.appendTranscript(segments: List<Segment>, zone: ZoneId, knownDay: Long? = segments.firstOrNull()?.startedAt) {
    appendLine("<transcripcion>")
    var day = knownDay?.let { Time.dayKey(it, zone) }
    segments.forEach {
        val d = Time.dayKey(it.startedAt, zone)
        if (d != day) appendLine("(día $d)")
        day = d
        appendLine("[${Time.hourMinuteSecond(it.startedAt, zone)}] ${speakerPrefix(it.speaker)}${it.text.trim()}")
    }
    append("</transcripcion>")
}

/** "Tú: " (el dueño) o "Hablante 2: " delante de lo que dice cada uno (§5.6); nada si no se sabe. */
internal fun speakerPrefix(speaker: Int?): String = when (speaker) {
    null -> ""
    0 -> "Tú: "
    else -> "Hablante $speaker: "
}

private val SPEAKER_MARK = Regex("""^\s*(Tú|Hablante \d+):\s*""")

/** Trocea por segmentos para que cada parte quede por debajo de [maxChars]. */
internal fun splitTranscript(segments: List<Segment>, maxChars: Int = MAX_PART_CHARS): List<List<Segment>> {
    val parts = mutableListOf<MutableList<Segment>>()
    var size = 0
    for (s in segments) {
        val len = s.text.length + 12 + speakerPrefix(s.speaker).length // "[HH:mm:ss] " + salto
        if (parts.isEmpty() || size + len > maxChars) {
            parts += mutableListOf<Segment>()
            size = 0
        }
        parts.last() += s
        size += len
    }
    return parts
}

internal const val MAX_SUMMARY = 1200
internal const val MAX_KEY_POINTS = 8

/**
 * Fusiona las salidas de las partes de una conversación larga. El resumen junta los de las partes útiles (sin los de
 * ruido) recortados por frases a su parte de [MAX_SUMMARY], para que quepan todas; los puntos clave se toman por
 * turnos, uno de cada parte.
 */
internal fun merge(outs: List<ProcessOut>): ProcessOut {
    if (outs.size == 1) return outs[0]
    val useful = outs.filterNot { it.isNoise }
    val summaries = useful.ifEmpty { outs }.map { it.summary.trim() }.filter { it.isNotEmpty() }
    val budget = (MAX_SUMMARY - (summaries.size - 1)) / summaries.size.coerceAtLeast(1)
    return ProcessOut(
        title = (useful + outs).firstOrNull { it.title.isNotBlank() }?.title.orEmpty(),
        summary = summaries.joinToString(" ") { sentencesUpTo(it, budget) },
        keyPoints = roundRobin(useful.map { it.keyPoints }).take(MAX_KEY_POINTS),
        isNoise = useful.isEmpty(),
        tasks = useful.flatMap { it.tasks },
        facts = useful.flatMap { it.facts },
    )
}

private val SENTENCE_END = Regex("(?<=[.!?…])\\s+")

/** Las frases enteras de [text] que caben en [max]; si ni la primera cabe, cortada en una palabra y con "…". */
internal fun sentencesUpTo(text: String, max: Int): String {
    if (text.length <= max) return text
    val out = StringBuilder()
    for (sentence in text.split(SENTENCE_END)) {
        if (out.length + sentence.length + (if (out.isEmpty()) 0 else 1) > max) break
        if (out.isNotEmpty()) out.append(' ')
        out.append(sentence)
    }
    if (out.isNotEmpty()) return out.toString()
    val cut = text.take(max - 1)
    return cut.substring(0, cut.lastIndexOf(' ').takeIf { it > 0 } ?: cut.length).trimEnd() + "…"
}

/** El primero de cada lista, después el segundo de cada una… */
private fun <T> roundRobin(lists: List<List<T>>): List<T> =
    (0 until (lists.maxOfOrNull { it.size } ?: 0)).flatMap { i -> lists.mapNotNull { it.getOrNull(i) } }

/**
 * Recorta textos, descarta fechas inválidas y duplicados, y aplica las reglas de ruido y de facts. Ni tareas de
 * [registered] ni facts de [knownFacts] (los ya confirmados), aunque cambien mayúsculas o signos.
 */
internal fun validate(
    out: ProcessOut,
    startedAt: Long,
    zone: ZoneId,
    registered: List<String>,
    knownFacts: List<String> = emptyList(),
): Processed {
    val noise = out.isNoise
    val seenFacts = knownFacts.mapTo(mutableSetOf(), ::normalized)
    return Processed(
        title = out.title.clean(60),
        summary = out.summary.clean(MAX_SUMMARY),
        keyPoints = out.keyPoints.mapNotNull { it.clean(200) }.take(MAX_KEY_POINTS),
        isNoise = noise,
        tasks = if (noise) emptyList() else validTasks(out.tasks, startedAt, zone, registered),
        facts = if (noise) emptyList() else out.facts.mapNotNull { it.text.clean(300) }.filter { seenFacts.add(normalized(it)) }.take(3),
    )
}

/** explicitReminder → OPEN (con alarma si hay remindAt); el resto, SUGGESTED. Sin repetir [registered]. */
internal fun validTasks(tasks: List<TaskOut>, ref: Long, zone: ZoneId, registered: List<String>): List<NewTask> {
    val seen = registered.mapTo(mutableSetOf(), ::normalized)
    return tasks.mapNotNull { t ->
        val text = t.text.clean(200) ?: return@mapNotNull null
        if (!seen.add(normalized(text))) return@mapNotNull null
        NewTask(
            text = text,
            state = if (t.explicitReminder) TaskState.OPEN else TaskState.SUGGESTED,
            dueAt = aiDate(t.dueAt, ref, zone),
            remindAt = aiDate(t.remindAt, ref, zone),
            // Sin la marca de quién habla: al olvidar, la tarea se queda si su frase sigue en el texto (DataManager).
            quote = t.quote.replace(SPEAKER_MARK, "").clean(300),
        )
    }
}

private val DATE_TIME_PREFIX = Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}""")
private val DATE_ONLY = Regex("""^\d{4}-\d{2}-\d{2}$""")

/**
 * Fecha local de la IA ("2026-10-16T09:45") → epoch ms en [zone]. Tolera segundos o zona al final (se ignoran)
 * y fecha sin hora (09:00). null si no es válida o es absurda: antes de la víspera de [ref] o a más de 3 años.
 */
internal fun aiDate(text: String?, ref: Long, zone: ZoneId): Long? {
    val t = text?.trim()?.replace(' ', 'T') ?: return null
    val local = DATE_TIME_PREFIX.find(t)?.value ?: if (DATE_ONLY.matches(t)) "${t}T09:00" else return null
    val ms = Time.parseLocalDateTime(local, zone) ?: return null
    return ms.takeIf { it >= ref - DAY_MS && it <= ref + 3 * 365 * DAY_MS }
}

private val SPACES = Regex("\\s+")

internal fun String.clean(max: Int): String? = trim().replace(SPACES, " ").take(max).trim().ifEmpty { null }

private fun normalized(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
