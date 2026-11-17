package dev.javieraguilera.mysone.ai

import android.content.Context
import androidx.core.content.edit
import androidx.room3.withWriteTransaction
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

    /**
     * Hasta [MAX_PER_PASS] días sin resumen (o con uno anterior a alguna de sus conversaciones), los más recientes.
     * Devuelve cuántos ha creado. Los ajustes y las conversaciones del día se leen antes de cada día: si la IA se
     * apaga o cambia de proveedor a mitad, no sale nada más, y lo borrado entretanto no se envía.
     */
    suspend fun buildMissing(zone: ZoneId = Time.zone()): Int {
        val db = container.db
        val attempts = container.app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val today = Time.today(zone)
        val todayStart = Time.dayBounds(today, zone).first
        // ponytail: solo mira los últimos 30 días; ampliar si hace falta rellenar historiales antiguos.
        val sinceDay = LocalDate.parse(today).minusDays(LOOKBACK_DAYS).toString()
        val since = Time.dayBounds(sinceDay, zone).first
        // Y los de antes cuyo resumen se quitó al borrar u olvidar algo: si no, se quedarían sin él para siempre.
        val redo = redoDays(container.app)
        val older = redo.filter { it < sinceDay }.flatMap { d ->
            val (from, to) = Time.dayBounds(d, zone)
            db.conversations().inRange(from, to, Int.MAX_VALUE)
        }
        val convs = db.conversations().inRange(since, todayStart, Int.MAX_VALUE) + older
        settledRedo(redo, convs, zone).takeIf { it.isNotEmpty() }?.let { done -> editRedo(container.app) { it - done } }
        val days = daysToDigest(convs, zone).filter { day ->
            // Las mismas que se envían abajo: así la cuenta de fallos ve el mismo día.
            val dayConvs = convs.filter { Time.dayKey(it.startedAt, zone) == day }.digestable()
            digestOutdated(db.digests().get(day), dayConvs) && failures(attempts.getString(day, null), dayConvs) < MAX_ATTEMPTS
        }.take(MAX_PER_PASS)
        var built = 0
        for (day in days) {
            val cfg = aiConfig(container) ?: break
            val (from, to) = Time.dayBounds(day, zone)
            val dayConvs = db.conversations().inRange(from, to, Int.MAX_VALUE).digestable().sortedBy { it.startedAt }
            if (dayConvs.isEmpty()) continue // olvidado o borrado desde que se miró
            val tasks = db.tasks().observeInDay(from, to).first()
            val summary: String
            val highlights: List<String>
            try {
                val out: DigestOut = cfg.client.completeJson(
                    LlmRequest(
                        Prompts.DIGEST, listOf(LlmMessage(ChatRole.USER, digestInput(day, dayConvs, tasks, zone))),
                        cfg.ai.modelBackground, 2048, Schemas.DIGEST, "low",
                    ),
                )
                summary = out.summary.clean(1500) ?: throw LlmException(LlmErrorKind.BAD_OUTPUT, "Resumen vacío")
                highlights = out.highlights.mapNotNull { it.clean(200) }.take(6)
            } catch (e: LlmException) {
                if (e.kind != LlmErrorKind.BAD_OUTPUT && e.kind != LlmErrorKind.REFUSAL) break // clave, red o cuota: lo demás fallaría igual
                // De este día: se reintenta en otra pasada, como mucho MAX_ATTEMPTS veces mientras no cambie.
                attempts.edit { putString(day, failed(attempts.getString(day, null), dayConvs)) }
                continue
            }
            // Olvidar, borrar o "Borrar todo" durante la llamada cambian las conversaciones del día (o sus
            // resúmenes): entonces este resumen lleva lo borrado y no se guarda. Las otras escriben en su propia
            // transacción, así que o se ven aquí o se llevan también el resumen.
            val saved = db.withWriteTransaction {
                val now = db.conversations().inRange(from, to, Int.MAX_VALUE).digestable()
                if (now.fingerprint() != dayConvs.fingerprint()) return@withWriteTransaction false
                db.digests().upsert(DailyDigest(day, summary, AppJson.encodeToString(highlights), System.currentTimeMillis()))
                editRedo(container.app) { it - day } // dentro: un borrado que venga después lo vuelve a apuntar
                true
            }
            if (saved) {
                attempts.edit { remove(day) }
                built++
            }
        }
        return built
    }

    private fun List<Conversation>.digestable() = filter { it.state == ConvState.DONE && !it.isNoise }

    private fun List<Conversation>.fingerprint() = map { it.id to it.processedAt }.toSet()

    companion object {
        private const val MAX_PER_PASS = 7
        private const val LOOKBACK_DAYS = 30L
        internal const val MAX_ATTEMPTS = 3
        private const val PREFS = "digest_attempts"
        private const val KEY_REDO = "redo" // días a rehacer; las demás claves son días ("yyyy-MM-dd") con sus fallos

        /** "Borrar todo": fuera también los días que fallaron y los que había que rehacer. */
        fun forgetAttempts(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { clear() }

        /** Borrar u olvidar han quitado los resúmenes de [fromDay] a [toDay]: se rehacen aunque sean antiguos. */
        fun redo(context: Context, fromDay: String, toDay: String) = editRedo(context) { it + daysBetween(fromDay, toDay) }

        private fun redoDays(context: Context): Set<String> =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY_REDO, null).orEmpty()

        private fun editRedo(context: Context, change: (Set<String>) -> Set<String>) = synchronized(this) {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            p.edit(commit = true) { putStringSet(KEY_REDO, change(p.getStringSet(KEY_REDO, null).orEmpty())) }
        }
    }
}

/** Días "yyyy-MM-dd" de [fromDay] a [toDay], ambos incluidos. */
internal fun daysBetween(fromDay: String, toDay: String): List<String> {
    val to = LocalDate.parse(toDay)
    return generateSequence(LocalDate.parse(fromDay)) { it.plusDays(1) }.takeWhile { it <= to }.map { it.toString() }.toList()
}

/** De los días a rehacer [redo], los que ya no hace falta apuntar: en [convs] no tienen nada que resumir ni por procesar. */
internal fun settledRedo(redo: Set<String>, convs: List<Conversation>, zone: ZoneId): Set<String> {
    val alive = convs.filter { (it.state == ConvState.DONE && !it.isNoise) || it.unfinished() }
    return redo - alive.map { Time.dayKey(it.startedAt, zone) }.toSet()
}

/** Días ("yyyy-MM-dd", más recientes primero) con algo útil hecho y ninguna conversación aún por procesar. */
internal fun daysToDigest(convs: List<Conversation>, zone: ZoneId): List<String> =
    convs.groupBy { Time.dayKey(it.startedAt, zone) }
        .filterValues { list ->
            list.any { it.state == ConvState.DONE && !it.isNoise } && list.none { it.unfinished() }
        }
        .keys.sortedDescending()

/**
 * Sin resumen, o hecho antes de que se procesara alguna de las conversaciones del día: una importada después con
 * la fecha del fichero, una reprocesada o una fallida que se volvió a intentar.
 */
internal fun digestOutdated(digest: DailyDigest?, dayConvs: List<Conversation>): Boolean =
    digest == null || dayConvs.any { it.state == ConvState.DONE && (it.processedAt ?: 0) > digest.createdAt }

// Fallos de un día en las preferencias: "<último procesado de sus conversaciones>|<veces>". Si el día cambia
// (otra conversación, un reprocesado), la cuenta vuelve a empezar.

private fun stamp(dayConvs: List<Conversation>) = dayConvs.maxOfOrNull { it.processedAt ?: 0 } ?: 0

/** Veces que ha fallado el resumen del día tal como está ahora. */
internal fun failures(saved: String?, dayConvs: List<Conversation>): Int {
    val (at, count) = saved?.split('|')?.takeIf { it.size == 2 } ?: return 0
    return if (at.toLongOrNull() == stamp(dayConvs)) count.toIntOrNull() ?: 0 else 0
}

/** El valor que hay que guardar tras otro fallo. */
internal fun failed(saved: String?, dayConvs: List<Conversation>): String = "${stamp(dayConvs)}|${failures(saved, dayConvs) + 1}"

private fun Conversation.unfinished() =
    state == ConvState.OPEN || state == ConvState.READY || state == ConvState.PROCESSING ||
        (state == ConvState.FAILED && processAttempts < AiProcessing.MAX_ATTEMPTS)

internal fun digestInput(day: String, convs: List<Conversation>, tasks: List<TaskItem>, zone: ZoneId, lang: String = Prompts.outputLanguage()): String = buildString {
    val start = Time.dayBounds(day, zone).first
    appendLine("<dia>${Time.dayLong(start, zone)} ($day)</dia>")
    appendLine("<idioma>$lang</idioma>")
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
