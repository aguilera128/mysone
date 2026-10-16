package dev.javieraguilera.mysone.ai

import dev.javieraguilera.mysone.AppContainer
import dev.javieraguilera.mysone.core.AppJson
import dev.javieraguilera.mysone.core.Time
import dev.javieraguilera.mysone.data.ChatRole
import dev.javieraguilera.mysone.data.ConvState
import dev.javieraguilera.mysone.data.Conversation
import dev.javieraguilera.mysone.data.DailyDigest
import dev.javieraguilera.mysone.data.TaskItem
import dev.javieraguilera.mysone.data.TaskState
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString

/** Resumen de cada día anterior a hoy (§7.6). */
class DigestBuilder(private val container: AppContainer) {

    /** Hasta [MAX_PER_PASS] días sin resumen, los más recientes. Devuelve cuántos ha creado. */
    suspend fun buildMissing(cfg: AiConfig, zone: ZoneId = Time.zone()): Int {
        val db = container.db
        val today = Time.today(zone)
        val todayStart = Time.dayBounds(today, zone).first
        // ponytail: solo mira los últimos 30 días; ampliar si hace falta rellenar historiales antiguos.
        val since = Time.dayBounds(LocalDate.parse(today).minusDays(LOOKBACK_DAYS).toString(), zone).first
        val convs = db.conversations().inRange(since, todayStart, Int.MAX_VALUE)
        val days = daysToDigest(convs, zone).filter { db.digests().get(it) == null }.take(MAX_PER_PASS)
        var built = 0
        for (day in days) {
            val (from, to) = Time.dayBounds(day, zone)
            val dayConvs = convs.filter { it.startedAt in from until to && it.state == ConvState.DONE && !it.isNoise }
                .sortedBy { it.startedAt }
            val tasks = db.tasks().observeInDay(from, to).first()
            val out: DigestOut = try {
                cfg.client.completeJson(
                    LlmRequest(
                        Prompts.DIGEST, listOf(LlmMessage(ChatRole.USER, digestInput(day, dayConvs, tasks, zone))),
                        cfg.ai.modelBackground, 2048, Schemas.DIGEST, "low",
                    ),
                )
            } catch (e: LlmException) {
                if (e.kind == LlmErrorKind.BAD_OUTPUT || e.kind == LlmErrorKind.REFUSAL) continue // se reintenta en otra pasada
                break // clave, red o cuota: lo demás fallaría igual
            }
            val summary = out.summary.clean(1500) ?: continue
            val highlights = out.highlights.mapNotNull { it.clean(200) }.take(6)
            db.digests().upsert(DailyDigest(day, summary, AppJson.encodeToString(highlights), System.currentTimeMillis()))
            built++
        }
        return built
    }

    private companion object {
        const val MAX_PER_PASS = 7
        const val LOOKBACK_DAYS = 30L
    }
}

/** Días ("yyyy-MM-dd", más recientes primero) con algo útil hecho y ninguna conversación aún por procesar. */
internal fun daysToDigest(convs: List<Conversation>, zone: ZoneId): List<String> =
    convs.groupBy { Time.dayKey(it.startedAt, zone) }
        .filterValues { list ->
            list.any { it.state == ConvState.DONE && !it.isNoise } && list.none { it.unfinished() }
        }
        .keys.sortedDescending()

private fun Conversation.unfinished() =
    state == ConvState.OPEN || state == ConvState.READY || state == ConvState.PROCESSING ||
        (state == ConvState.FAILED && processAttempts < AiProcessing.MAX_ATTEMPTS)

internal fun digestInput(day: String, convs: List<Conversation>, tasks: List<TaskItem>, zone: ZoneId): String = buildString {
    val start = Time.dayBounds(day, zone).first
    appendLine("<dia>${Time.dayLong(start, zone)} ($day)</dia>")
    appendLine("<conversaciones>")
    convs.forEach { appendLine("[${Time.hourMinute(it.startedAt, zone)}] ${it.title.orEmpty()} — ${it.summary.orEmpty()}") }
    appendLine("</conversaciones>")
    appendLine("<tareas>")
    tasks.filter { it.state != TaskState.DISMISSED }.forEach { appendLine("- ${it.text} (${taskStateLabel(it.state)})") }
    append("</tareas>")
}

internal fun taskStateLabel(state: String) = when (state) {
    TaskState.OPEN -> "pendiente"
    TaskState.DONE -> "hecha"
    else -> "sugerida"
}
