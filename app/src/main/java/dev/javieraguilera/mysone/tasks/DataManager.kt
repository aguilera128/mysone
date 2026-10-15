package dev.javieraguilera.mysone.tasks

import android.content.Context
import android.net.Uri
import androidx.room3.withWriteTransaction
import dev.javieraguilera.mysone.AppContainer
import dev.javieraguilera.mysone.core.AppJson
import dev.javieraguilera.mysone.core.Time
import dev.javieraguilera.mysone.data.AiSettings
import dev.javieraguilera.mysone.data.AppSettings
import dev.javieraguilera.mysone.data.ChatMessage
import dev.javieraguilera.mysone.data.ConvState
import dev.javieraguilera.mysone.data.Conversation
import dev.javieraguilera.mysone.data.DailyDigest
import dev.javieraguilera.mysone.data.Fact
import dev.javieraguilera.mysone.data.ListeningGap
import dev.javieraguilera.mysone.data.Segment
import dev.javieraguilera.mysone.data.TaskItem
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.encodeToStream

/** Exportación: todo lo guardado menos la clave de la IA (que no está en AppSettings) y la cola de audio. */
@Serializable
data class ExportFile(
    val app: String = "Mysone",
    val formatVersion: Int = 1,
    val exportedAt: Long,
    val settings: AppSettings,
    val conversations: List<Conversation>,
    val segments: List<Segment>,
    val tasks: List<TaskItem>,
    val facts: List<Fact>,
    val digests: List<DailyDigest>,
    val chat: List<ChatMessage>,
    val gaps: List<ListeningGap>,
)

/** Cola de audio de la captura: filesDir/pending/ (§5.2). */
internal fun pendingDir(context: Context) = File(context.filesDir, "pending")

/** Olvidar, borrar, exportar y retención (§6, §10; docs/investigacion/06 §6.1). */
class DataManager(private val container: AppContainer) {
    private val db get() = container.db

    /**
     * Segmentos y cola de los últimos [minutes], y lo derivado (06 §6.1.3): las conversaciones tocadas
     * pierden tareas, facts y resumen y vuelven a READY para reprocesarse con lo que queda; las vaciadas se borran.
     */
    suspend fun forgetLast(minutes: Int) {
        val now = System.currentTimeMillis()
        val since = now - minutes * MINUTE_MS
        val pending = db.pendingAudio().since(since)
        // Un trozo dura como mucho 25 s: los que terminan después de since empezaron después de since - 1 min.
        val segments = db.segments().inRange(since - MINUTE_MS, Long.MAX_VALUE).filter { it.endedAt > since }
        val touched = (segments.map { it.conversationId } + pending.map { it.conversationId }).toSet()

        pending.forEach { pendingFile(it.path).delete() }
        db.pendingAudio().deleteSince(since)
        db.segments().deleteSince(since)
        for (id in touched) {
            val c = db.conversations().get(id) ?: continue
            dropDerived(id)
            val lastEnd = db.segments().textForConversation(id).maxOfOrNull { it.endedAt } ?: c.endedAt
            db.conversations().update(
                c.copy(
                    endedAt = lastEnd,
                    state = if (c.state == ConvState.OPEN) ConvState.OPEN else ConvState.READY,
                    title = null, summary = null, keyPoints = null, isNoise = false,
                    processAttempts = 0, processedAt = null, lastError = null,
                ),
            )
        }
        db.conversations().deleteEmpty()
        deleteDigests(since, now)
    }

    /** En cascada: segmentos, cola, tareas y facts. También sus ficheros de audio, sus alarmas y el resumen del día. */
    suspend fun deleteConversation(id: Long) {
        val c = db.conversations().get(id) ?: return
        db.tasks().forConversation(id).forEach { container.reminders.cancel(it.id) }
        db.pendingAudio().forConversation(id).forEach { pendingFile(it.path).delete() }
        db.conversations().delete(id)
        deleteDigests(c.startedAt, c.endedAt)
    }

    /** Todo menos la clave. El aviso de 06 §7.4 lo muestra la UI antes de llamar aquí. */
    @OptIn(ExperimentalSerializationApi::class)
    suspend fun exportJson(target: Uri) {
        val export = ExportFile(
            exportedAt = System.currentTimeMillis(),
            settings = container.settings.current(),
            conversations = db.conversations().all(),
            segments = db.segments().inRange(0, Long.MAX_VALUE),
            tasks = db.tasks().all(),
            facts = db.facts().all(),
            digests = db.digests().all(),
            chat = db.chat().all(),
            gaps = db.gaps().all(),
        )
        withContext(Dispatchers.IO) {
            // "wt": algunos proveedores no truncan con "w" y quedarían restos de un fichero anterior.
            val out = container.app.contentResolver.openOutputStream(target, "wt") ?: throw IOException("No se puede abrir el destino")
            out.use { AppJson.encodeToStream(ExportFile.serializer(), export, it) }
        }
    }

    /** BD, cola, chat, ajustes de IA y clave; conserva los modelos ASR y el resto de ajustes. */
    suspend fun deleteAll() {
        db.tasks().withReminder().forEach { container.reminders.cancel(it.id) }
        db.clearAllTables()
        withContext(Dispatchers.IO) { pendingDir(container.app).listFiles()?.forEach { it.delete() } }
        container.settings.update { it.copy(ai = AiSettings()) }
        container.secrets.clearApiKey()
    }

    /** Solo la transcripción literal: resúmenes, tareas y facts se quedan (§3, retentionDays). */
    suspend fun applyRetention() {
        val cutoff = retentionCutoff(System.currentTimeMillis(), container.settings.current().retentionDays) ?: return
        db.segments().deleteOlderThan(cutoff)
    }

    private suspend fun dropDerived(conversationId: Long) {
        db.tasks().forConversation(conversationId).forEach {
            container.reminders.cancel(it.id)
            db.tasks().delete(it.id)
        }
        db.facts().observeForConversation(conversationId).first().forEach { db.facts().delete(it.id) }
    }

    /** Los resúmenes diarios recogen lo borrado: se quitan y DigestBuilder los rehace sin ello. */
    private suspend fun deleteDigests(fromMs: Long, toMs: Long) {
        db.withWriteTransaction {
            usePrepared("DELETE FROM daily_digest WHERE day >= ? AND day <= ?") {
                it.bindText(1, Time.dayKey(fromMs))
                it.bindText(2, Time.dayKey(toMs))
                it.step()
            }
        }
    }

    /** Todos los trozos viven en pending/; se resuelve por nombre, guarde la captura la ruta absoluta o relativa. */
    private fun pendingFile(path: String) = File(pendingDir(container.app), File(path).name)

    companion object {
        private const val MINUTE_MS = 60_000L
        private const val DAY_MS = 24 * 3_600_000L

        /** Instante antes del cual se borra la transcripción; null = no borrar (0 días). */
        fun retentionCutoff(now: Long, days: Int): Long? = if (days <= 0) null else now - days * DAY_MS
    }
}
