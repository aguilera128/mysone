package dev.javieraguilera.mysone.ai

import dev.javieraguilera.mysone.AppContainer
import dev.javieraguilera.mysone.core.AppJson
import dev.javieraguilera.mysone.core.Time
import dev.javieraguilera.mysone.data.ChatMessage
import dev.javieraguilera.mysone.data.ChatRole
import dev.javieraguilera.mysone.data.Conversation
import dev.javieraguilera.mysone.data.DailyDigest
import dev.javieraguilera.mysone.data.Fact
import dev.javieraguilera.mysone.data.FactState
import dev.javieraguilera.mysone.data.FtsQuery
import dev.javieraguilera.mysone.data.Segment
import dev.javieraguilera.mysone.data.TaskItem
import dev.javieraguilera.mysone.data.TaskState
import io.sentry.Sentry
import java.text.Normalizer
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString

/**
 * Preguntas sobre lo escuchado (§7.5): planificador (términos y fechas) → recuperación local por FTS → respuesta.
 * Citas en la respuesta: marcadores literales [c:123] (id de conversación). La UI los convierte en chips.
 */
class AskEngine(private val container: AppContainer) {

    // Pregunta en curso y último fallo viven aquí (todo el proceso) y no en el ViewModel de Preguntar, que muere al
    // cambiar de pestaña mientras la pregunta sigue en appScope: al volver no se perdería el "pensando" ni el error.
    private val _pending = MutableStateFlow<String?>(null)

    /** Pregunta en curso ("pensando"). */
    val pending: StateFlow<String?> = _pending.asStateFlow()

    /** La última que falló, para el aviso y "Reintentar". */
    class Failure(val question: String, val error: Throwable)

    private val _failure = MutableStateFlow<Failure?>(null)
    val failure: StateFlow<Failure?> = _failure.asStateFlow()

    @Volatile private var checkedInterrupted = false

    /** No bloquea, y una cada vez. La respuesta llega a chat_message; el estado, a [pending] y [failure]. */
    fun submit(question: String) {
        val q = question.trim()
        if (q.isEmpty() || !_pending.compareAndSet(null, q)) return
        _failure.value = null
        container.appScope.launch {
            try {
                ask(q).onFailure { _failure.value = Failure(q, it) }
            } finally {
                _pending.value = null
            }
        }
    }

    fun retry() {
        _failure.value?.let { submit(it.question) }
    }

    fun dismissFailure() {
        _failure.value = null
    }

    /** Una vez por proceso: si murió a mitad de una pregunta, esta sigue guardada sin respuesta y se ofrece repetirla. */
    suspend fun flagInterrupted() {
        if (checkedInterrupted) return
        checkedInterrupted = true
        val last = container.db.chat().recent(1).firstOrNull() ?: return
        if (last.role == ChatRole.USER && _pending.value == null && _failure.value == null) {
            _failure.value = Failure(last.text, IllegalStateException("Pregunta sin respuesta"))
        }
    }

    /** Guarda pregunta y respuesta en chat_message. */
    suspend fun ask(question: String): Result<ChatMessage> {
        val q = question.trim().take(MAX_QUESTION)
        if (q.isEmpty()) return Result.failure(IllegalArgumentException("Pregunta vacía"))
        val cfg = aiConfig(container) ?: return Result.failure(notConfigured())
        val chat = container.db.chat()
        val recent = chat.recent(HISTORY + 1).reversed()
        // Reintento tras un fallo: la pregunta sigue guardada y sin respuesta, no se duplica.
        val retry = recent.lastOrNull()?.let { it.role == ChatRole.USER && it.text == q } == true
        val history = (if (retry) recent.dropLast(1) else recent).takeLast(HISTORY)
        if (!retry) chat.insert(ChatMessage(role = ChatRole.USER, text = q, createdAt = System.currentTimeMillis()))
        return try {
            val zone = Time.zone()
            val now = System.currentTimeMillis()
            val plan = plan(cfg, q, history, now, zone)
            val context = retrieve(plan, q, now, zone)
            val raw = cfg.client.complete(
                LlmRequest(
                    Prompts.ANSWER,
                    alternating(history.map { LlmMessage(it.role, it.text) } + LlmMessage(ChatRole.USER, answerInput(q, context, now, zone))),
                    cfg.ai.modelAnswer, 4096, effort = "medium", cacheSystem = false,
                ),
            ).text.trim()
            val cited = Citations.parse(raw)
            val existing = if (cited.isEmpty()) emptySet() else container.db.conversations().byIds(cited).mapTo(mutableSetOf()) { it.id }
            val valid = cited.filter { it in existing }
            // Un marcador a una conversación que no existe no lleva a ningún sitio: fuera.
            val text = Citations.MARKER.replace(raw) { if (it.groupValues[1].toLong() in existing) it.value else "" }.trim()
            val answer = ChatMessage(
                role = ChatRole.ASSISTANT,
                text = text,
                citations = valid.takeIf { it.isNotEmpty() }?.let { AppJson.encodeToString(it) },
                createdAt = System.currentTimeMillis(),
            )
            Result.success(answer.copy(id = chat.insert(answer)))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e !is LlmException) Sentry.captureException(e)
            Result.failure(e)
        }
    }

    suspend fun clearChat() = container.db.chat().clear()

    /** Si el planificador no da un JSON válido, se busca con las palabras de la pregunta en los últimos 30 días. */
    private suspend fun plan(cfg: AiConfig, question: String, history: List<ChatMessage>, now: Long, zone: ZoneId): PlanOut = try {
        cfg.client.completeJson(
            LlmRequest(
                Prompts.PLAN, listOf(LlmMessage(ChatRole.USER, planInput(question, history, now, zone))),
                cfg.ai.modelBackground, 1024, Schemas.PLAN, "low", cacheSystem = false,
            ),
        )
    } catch (e: LlmException) {
        if (e.kind != LlmErrorKind.BAD_OUTPUT && e.kind != LlmErrorKind.REFUSAL) throw e
        PlanOut()
    }

    private suspend fun retrieve(plan: PlanOut, question: String, now: Long, zone: ZoneId): AskContext {
        val db = container.db
        val (fromDay, toDay) = resolveRange(plan.from, plan.to, now, zone)
        val from = Time.dayBounds(fromDay, zone).first
        val to = Time.dayBounds(toDay, zone).second
        val query = retrievalQuery(plan.keywords, question)

        var convs = db.conversations().inRange(from, to, MAX_CONVERSATIONS + 1)
        if (convs.size > MAX_CONVERSATIONS) {
            convs = query?.let { db.conversations().searchFts(it, from, to, MAX_CONVERSATIONS) }?.takeIf { it.isNotEmpty() }
                ?.sortedByDescending { it.startedAt } ?: convs.take(MAX_CONVERSATIONS)
        }

        // Mejores fragmentos primero (bm25), cada uno con el anterior y el siguiente, hasta llenar el presupuesto.
        val picked = LinkedHashMap<Long, Segment>()
        if (query != null) {
            val hits = db.segments().search(query, from, to, MAX_HITS)
            val byId = db.segments().byIds(hits.map { it.segmentId }).associateBy { it.id }
            var chars = 0
            for (hit in hits) {
                val s = byId[hit.segmentId] ?: continue
                val window = listOfNotNull(db.segments().previous(s.conversationId, s.startedAt), s, db.segments().next(s.conversationId, s.startedAt))
                for (w in window) if (picked.put(w.id, w) == null) chars += w.text.length
                if (chars > MAX_SEGMENT_CHARS) break
            }
        }

        return AskContext(
            conversations = convs.filterNot { it.isNoise },
            segments = picked.values.sortedWith(compareBy({ it.conversationId }, { it.startedAt })),
            digests = db.digests().inRange(fromDay, toDay, MAX_DIGESTS),
            tasks = db.tasks().listByState(TaskState.OPEN, MAX_TASKS),
            facts = db.facts().listByState(FactState.CONFIRMED, MAX_FACTS),
        )
    }

    private companion object {
        const val MAX_QUESTION = 2000
        const val HISTORY = 4
        const val MAX_CONVERSATIONS = 60
        const val MAX_HITS = 40
        const val MAX_SEGMENT_CHARS = 24_000 // ~6 k tokens de transcripción literal
        const val MAX_DIGESTS = 14
        const val MAX_TASKS = 30
        const val MAX_FACTS = 100
    }
}

object Citations {
    /** `[c:123]` → 123 en el grupo 1. */
    val MARKER = Regex("""\[c:(\d+)]""")

    /** IDs citados, sin repetir, en orden de aparición. */
    fun parse(text: String): List<Long> =
        MARKER.findAll(text).mapNotNull { it.groupValues[1].toLongOrNull() }.distinct().toList()
}

internal class AskContext(
    val conversations: List<Conversation>,
    val segments: List<Segment>,
    val digests: List<DailyDigest>,
    val tasks: List<TaskItem>,
    val facts: List<Fact>,
)

private val WORD = Regex("[\\p{L}\\p{N}]+")

// Con OR y prefijo, "de"* o "para"* casarían con casi todo y taparían lo relevante en bm25.
private val STOPWORDS = setOf(
    "del", "los", "las", "una", "unos", "unas", "que", "qué", "con", "por", "para", "como", "cómo",
    "cuando", "cuándo", "donde", "dónde", "pero", "eso", "esto", "esta", "este", "sobre",
)

/** Consulta FTS (OR) con los términos del planificador; sin términos, las palabras de 4+ letras de la pregunta. */
internal fun retrievalQuery(keywords: List<String>, question: String): String? {
    fun words(texts: List<String>, minLength: Int) = texts
        .flatMap { WORD.findAll(Normalizer.normalize(it, Normalizer.Form.NFC)).map { m -> m.value }.toList() }
        .filter { (it.length >= minLength || it.any(Char::isDigit)) && it.lowercase() !in STOPWORDS }
    return FtsQuery.any(words(keywords, 3).ifEmpty { words(listOf(question), 4) })
}

/**
 * Rango de días [desde, hasta] del planificador. Sin fechas, los últimos 30 días. Nunca pasa de hoy: una pregunta
 * sobre el futuro ("¿qué tengo mañana?") se responde con lo hablado antes y las tareas.
 */
internal fun resolveRange(from: String?, to: String?, now: Long, zone: ZoneId): Pair<String, String> {
    val today = LocalDate.parse(Time.dayKey(now, zone))
    var start = from.toLocalDate()
    var end = to.toLocalDate()
    if (start != null && end != null && start > end) start = end.also { end = start }
    val e = minOf(end ?: today, today)
    val s = start?.takeIf { it <= e } ?: e.minusDays(30)
    return s.toString() to e.toString()
}

private fun String?.toLocalDate(): LocalDate? = this?.trim()?.take(10)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

/** Anthropic y algunos compatibles exigen empezar por el usuario y alternar turnos: se juntan los seguidos. */
internal fun alternating(messages: List<LlmMessage>): List<LlmMessage> =
    messages.dropWhile { it.role != ChatRole.USER }.fold(mutableListOf()) { acc, m ->
        if (acc.lastOrNull()?.role == m.role) acc[acc.lastIndex] = m.copy(text = acc.last().text + "\n\n" + m.text) else acc += m
        acc
    }

internal fun planInput(question: String, history: List<ChatMessage>, now: Long, zone: ZoneId): String = buildString {
    appendLine("<ahora>${Time.dateTimeLong(now, zone)} (${Time.toLocalDateTime(now, zone)}), zona ${zone.id}</ahora>")
    appendLine("<historial>")
    history.forEach { appendLine("${if (it.role == ChatRole.USER) "usuario" else "asistente"}: ${it.text.replace('\n', ' ')}") }
    appendLine("</historial>")
    append("<pregunta>$question</pregunta>")
}

internal fun answerInput(question: String, ctx: AskContext, now: Long, zone: ZoneId): String = buildString {
    appendLine("<ahora>${Time.dateTimeLong(now, zone)} (${Time.toLocalDateTime(now, zone)}), zona ${zone.id}</ahora>")
    appendLine("<datos>")
    ctx.facts.forEach { appendLine("- ${it.text}") }
    appendLine("</datos>")
    appendLine("<tareas_pendientes>")
    ctx.tasks.forEach { t ->
        val notice = t.remindAt?.let { " (aviso: ${Time.dateTimeLong(it, zone)})" }.orEmpty()
        val source = t.conversationId?.let { " [c:$it]" }.orEmpty()
        appendLine("- ${t.text}$notice$source")
    }
    appendLine("</tareas_pendientes>")
    appendLine("<resumenes_diarios>")
    ctx.digests.forEach { appendLine("[${it.day}] ${it.summary}") }
    appendLine("</resumenes_diarios>")
    appendLine("<conversaciones>")
    ctx.conversations.forEach { c ->
        val title = c.title ?: "(sin procesar)"
        appendLine("[c:${c.id}] ${Time.dateTimeLong(c.startedAt, zone)}–${Time.hourMinute(c.endedAt, zone)} · $title — ${c.summary.orEmpty()}")
    }
    appendLine("</conversaciones>")
    appendLine("<fragmentos>")
    var current: Long? = null
    ctx.segments.forEach { s ->
        if (s.conversationId != current) {
            current = s.conversationId
            appendLine("[c:${s.conversationId}] ${Time.dayLong(s.startedAt, zone)}")
        }
        appendLine("${Time.hourMinuteSecond(s.startedAt, zone)} ${s.text.trim()}")
    }
    appendLine("</fragmentos>")
    append("<pregunta>$question</pregunta>")
}
