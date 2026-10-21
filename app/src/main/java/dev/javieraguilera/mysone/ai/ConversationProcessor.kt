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
        val result = validate(merge(outs), c.startedAt, zone, kept)

        return save(c.id, segments) { current ->
            db.tasks().forConversation(c.id).filter { it.state == TaskState.SUGGESTED }.forEach { db.tasks().delete(it.id) }
            db.facts().forConversation(c.id).filter { it.state == FactState.SUGGESTED }.forEach { db.facts().delete(it.id) }
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
    internal suspend fun saveTask(t: NewTask, conversationId: Long, now: Long) {
        val item = TaskItem(
            text = t.text, state = t.state, dueAt = t.dueAt, remindAt = t.remindAt,
            conversationId = conversationId, sourceQuote = t.quote, createdAt = now,
        )
        val id = container.db.tasks().insert(item)
        if (item.state == TaskState.OPEN && item.remindAt != null) container.reminders.schedule(item.copy(id = id))
    }
}

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
        appendTranscript(part, zone)
    }

internal fun StringBuilder.appendRegistered(texts: List<String>) {
    appendLine("<tareas_registradas>")
    if (texts.isEmpty()) appendLine("(ninguna)") else texts.forEach { appendLine("- $it") }
    appendLine("</tareas_registradas>")
}

internal fun StringBuilder.appendTranscript(segments: List<Segment>, zone: ZoneId) {
    appendLine("<transcripcion>")
    segments.forEach { appendLine("[${Time.hourMinuteSecond(it.startedAt, zone)}] ${it.text.trim()}") }
    append("</transcripcion>")
}

/** Trocea por segmentos para que cada parte quede por debajo de [maxChars]. */
internal fun splitTranscript(segments: List<Segment>, maxChars: Int = MAX_PART_CHARS): List<List<Segment>> {
    val parts = mutableListOf<MutableList<Segment>>()
    var size = 0
    for (s in segments) {
        val len = s.text.length + 12 // "[HH:mm:ss] " + salto
        if (parts.isEmpty() || size + len > maxChars) {
            parts += mutableListOf<Segment>()
            size = 0
        }
        parts.last() += s
        size += len
    }
    return parts
}

/** Fusiona las salidas de las partes de una conversación larga. */
internal fun merge(outs: List<ProcessOut>): ProcessOut {
    if (outs.size == 1) return outs[0]
    val useful = outs.filterNot { it.isNoise }
    return ProcessOut(
        title = (useful + outs).firstOrNull { it.title.isNotBlank() }?.title.orEmpty(),
        summary = outs.joinToString(" ") { it.summary.trim() }.trim(),
        keyPoints = useful.flatMap { it.keyPoints },
        isNoise = useful.isEmpty(),
        tasks = useful.flatMap { it.tasks },
        facts = useful.flatMap { it.facts },
    )
}

/** Recorta textos, descarta fechas inválidas y duplicados, y aplica las reglas de ruido y de facts. */
internal fun validate(out: ProcessOut, startedAt: Long, zone: ZoneId, registered: List<String>): Processed {
    val noise = out.isNoise
    return Processed(
        title = out.title.clean(60),
        summary = out.summary.clean(1200),
        keyPoints = out.keyPoints.mapNotNull { it.clean(200) }.take(8),
        isNoise = noise,
        tasks = if (noise) emptyList() else validTasks(out.tasks, startedAt, zone, registered),
        facts = if (noise) emptyList() else out.facts.mapNotNull { it.text.clean(300) }.distinctBy { it.lowercase() }.take(3),
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
            quote = t.quote.clean(300),
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
