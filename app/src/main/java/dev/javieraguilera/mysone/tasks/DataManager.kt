package dev.javieraguilera.mysone.tasks

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import androidx.room3.executeSQL
import androidx.room3.useWriterConnection
import androidx.room3.withWriteTransaction
import dev.javieraguilera.mysone.AppContainer
import dev.javieraguilera.mysone.ai.DigestBuilder
import dev.javieraguilera.mysone.asr.AudioImporter
import dev.javieraguilera.mysone.asr.CallImporter
import dev.javieraguilera.mysone.asr.OwnerVoice
import dev.javieraguilera.mysone.capture.ListeningController
import dev.javieraguilera.mysone.capture.ListeningService
import dev.javieraguilera.mysone.capture.ResumeNotifier
import dev.javieraguilera.mysone.core.AppJson
import dev.javieraguilera.mysone.core.Time
import dev.javieraguilera.mysone.data.AiSettings
import dev.javieraguilera.mysone.data.AppSettings
import dev.javieraguilera.mysone.data.ChatMessage
import dev.javieraguilera.mysone.data.ChatRole
import dev.javieraguilera.mysone.data.ConvState
import dev.javieraguilera.mysone.data.Conversation
import dev.javieraguilera.mysone.data.DailyDigest
import dev.javieraguilera.mysone.data.Fact
import dev.javieraguilera.mysone.data.FactState
import dev.javieraguilera.mysone.data.ListeningGap
import dev.javieraguilera.mysone.data.Segment
import dev.javieraguilera.mysone.data.TaskItem
import dev.javieraguilera.mysone.data.TaskState
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
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

    private val _epoch = AtomicInteger()

    /**
     * Sube antes de cada olvido o borrado. Lo que se preparó con los datos de antes y se guarda después (la
     * respuesta de Preguntar, que tarda segundos en llegar) lo mira para no volver a escribir lo borrado.
     */
    val epoch: Int get() = _epoch.get()

    /**
     * Segmentos y cola de los últimos [minutes], y lo derivado de ellos (06 §6.1.3): las conversaciones tocadas
     * pierden el resumen y las tareas y facts que salían de lo olvidado (lo que el usuario aceptó de lo que queda
     * se conserva), y vuelven a READY para reprocesarse con lo que queda; las vaciadas se borran.
     *
     * Con el cerrojo de la captura y en una transacción: el servicio no encola ni guarda un trozo a medias de esto,
     * y lo que la VAD o el canal aún traigan del intervalo se descarta al llegar. Una llamada a la IA en curso ve
     * la conversación cambiada al ir a guardar y no escribe nada.
     */
    suspend fun forgetLast(minutes: Int) {
        val now = System.currentTimeMillis()
        val since = now - minutes * MINUTE_MS
        ListeningController.markForgotten(since, now)
        _epoch.incrementAndGet()
        val (touched, files) = ListeningService.book.withLock {
            db.withWriteTransaction {
                val pending = db.pendingAudio().since(since)
                // Un trozo dura como mucho 25 s: los que terminan después de since empezaron después de since - 1 min.
                val segments = db.segments().inRange(since - MINUTE_MS, Long.MAX_VALUE).filter { it.endedAt > since }
                val touched = (segments.map { it.conversationId } + pending.map { it.conversationId }).toSet()
                // El resumen diario va por el día en que empezó cada conversación: una que cruza la medianoche
                // lleva lo olvidado al resumen de ayer aunque [since] ya sea hoy.
                val firstStart = touched.mapNotNull { db.conversations().get(it)?.startedAt }.minOrNull() ?: since
                db.pendingAudio().deleteSince(since)
                db.segments().deleteSince(since)
                for (id in touched) {
                    val left = db.segments().textForConversation(id)
                    val leftPending = db.pendingAudio().forConversation(id)
                    val starts = left.map { it.startedAt } + leftPending.map { it.startedAt }
                    if (starts.isEmpty()) {
                        // Vaciada, también la abierta: la voz que siga abre otra en vez de heredar sus horas.
                        db.tasks().forConversation(id).forEach { container.reminders.cancel(it.id) }
                        db.conversations().delete(id)
                        continue
                    }
                    dropDerived(id, since, left)
                    val end = (left.map { it.endedAt } + leftPending.map { it.endedAt }).max()
                    db.conversations().resetForgotten(id, starts.min(), end)
                }
                deleteDigests(minOf(since, firstStart), now)
                dropChat(touched)
                touched to pending.map { it.path }
            }
        }
        files.forEach { pendingFile(it).delete() }
        flushWal()
        // Las tocadas que siguen vivas se vuelven a resumir sin lo olvidado (las OPEN y las borradas se saltan).
        touched.forEach { container.ai.onConversationReady(it) }
    }

    /** En cascada: segmentos, cola, tareas y facts. También sus ficheros de audio, sus alarmas y el resumen del día. */
    suspend fun deleteConversation(id: Long) {
        _epoch.incrementAndGet()
        // Con el cerrojo de la captura: ningún trozo nuevo de la cola se queda sin borrar su fichero.
        val files = ListeningService.book.withLock {
            db.withWriteTransaction {
                val c = db.conversations().get(id) ?: return@withWriteTransaction emptyList()
                // La abierta: lo que la VAD o el canal aún traigan de antes de borrar no abre otra con esa voz. Un
                // trozo llega como mucho unos 30 s tarde (como en forgetLast).
                if (c.state == ConvState.OPEN) {
                    val now = System.currentTimeMillis()
                    ListeningController.markForgotten(maxOf(c.startedAt, now - MINUTE_MS), now)
                }
                db.tasks().forConversation(id).forEach { container.reminders.cancel(it.id) }
                val files = db.pendingAudio().forConversation(id).map { it.path }
                db.conversations().delete(id)
                deleteDigests(c.startedAt, c.endedAt)
                dropChat(setOf(id))
                files
            }
        }
        files.forEach { pendingFile(it).delete() }
        flushWal()
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

    /**
     * BD, cola, chat, importaciones, ajustes de IA y clave; conserva los modelos ASR y el resto de ajustes.
     * La escucha queda detenida aunque ya la hubiera parado el sistema: sin la intención de escuchar ni el
     * "Toca para reanudar", que si no seguirían en Hoy y MaintenanceWorker volvería a avisar (§6).
     */
    suspend fun deleteAll() {
        // Primero la carpeta de llamadas (espera a un escaneo en marcha), para que nada vuelva a encolarse.
        CallImporter.clearFolder(container.app)
        // Antes que la BD: una importación a medias guardaría su conversación después del borrado.
        AudioImporter.cancelAll(container.app)
        _epoch.incrementAndGet()
        ListeningService.book.withLock {
            db.tasks().withReminder().forEach { container.reminders.cancel(it.id) }
            db.clearAllTables()
            withContext(Dispatchers.IO) { pendingDir(container.app).listFiles()?.forEach { it.delete() } }
        }
        flushWal()
        DigestBuilder.forgetAttempts(container.app)
        imports().edit { clear() }
        ListeningService.forgetSession(container.app)
        AudioImporter.forgetHistory(container.app)
        container.settings.update { it.copy(ai = AiSettings(), listeningEnabled = false) }
        ResumeNotifier.cancel(container.app)
        container.secrets.clearApiKey()
        OwnerVoice.delete(container.app) // la huella de voz del dueño (dato biométrico, §5.6); el modelo de voces se queda
    }

    /**
     * Lo literal: la transcripción, la cita de cada tarea y el chat de Preguntar, cuyas respuestas copian fragmentos.
     * Resúmenes, tareas y facts se quedan (§3, retentionDays).
     */
    suspend fun applyRetention() {
        val cutoff = retentionCutoff(System.currentTimeMillis(), container.settings.current().retentionDays) ?: return
        val imports = imports()
        db.withWriteTransaction {
            // Dentro de la transacción: una importación que se esté guardando ya se ha apuntado o aún no tiene texto.
            val keep = importedSince(imports.all, cutoff)
            db.segments().deleteOlderThan(cutoff, keep)
            db.tasks().dropQuotesOlderThan(cutoff, keep)
            db.chat().deleteOlderThan(cutoff)
        }
        // Las importadas antes del corte ya cuentan como las demás. Por fecha: lo apuntado entretanto se queda.
        imports.edit { imports.all.filterValues { (it as? Long ?: 0L) < cutoff }.keys.forEach(::remove) }
        flushWal()
    }

    /**
     * Lo importado (audio o llamada) cuenta para la retención desde que se importa, no desde la fecha del fichero: una
     * llamada de hace meses, cuya grabación ya se ha borrado, perdería su texto en la siguiente pasada (§5.5).
     * ImportWorker lo llama en la transacción que la guarda.
     */
    // ponytail: en preferencias para no migrar la BD; pasarlo a una columna conversation.importedAt en la próxima migración.
    fun markImported(conversationId: Long) =
        imports().edit(commit = true) { putLong(conversationId.toString(), System.currentTimeMillis()) }

    private fun imports() = container.app.getSharedPreferences(IMPORTS, Context.MODE_PRIVATE)

    /** Lo sacado de lo olvidado ([fromForgotten]) y los facts sugeridos; el reprocesado rehace las sugerencias. */
    private suspend fun dropDerived(conversationId: Long, since: Long, left: List<Segment>) {
        val leftText = normalized(left.joinToString(" ") { it.text })
        db.tasks().forConversation(conversationId).filter { fromForgotten(it, leftText, since) }.forEach {
            container.reminders.cancel(it.id)
            db.tasks().delete(it.id)
        }
        db.facts().forConversation(conversationId).filter { it.state == FactState.SUGGESTED }.forEach { db.facts().delete(it.id) }
    }

    /**
     * Las respuestas de Preguntar copian lo que dijeron las conversaciones que citan (y las de los resúmenes diarios y
     * los fragmentos que recibieron, citadas o no): con ellas se van la respuesta y su pregunta. Si no, lo olvidado
     * seguiría en pantalla y volvería al proveedor con el historial (§7.5).
     */
    private suspend fun dropChat(conversationIds: Set<Long>) {
        if (conversationIds.isEmpty()) return
        val ids = chatToForget(db.chat().all(), conversationIds)
        if (ids.isNotEmpty()) db.chat().delete(ids)
    }

    /**
     * Las páginas borradas ya se ponen a cero (el SQLite incluido se compila con SQLITE_SECURE_DELETE), pero en el
     * WAL quedan sus versiones anteriores hasta que se reutiliza: se vacía (06 §6.1.11). Si hay lecturas en curso,
     * SQLite no lo trunca y queda para el siguiente borrado.
     */
    private suspend fun flushWal() = db.useWriterConnection { it.executeSQL("PRAGMA wal_checkpoint(TRUNCATE)") }

    /** Los resúmenes diarios recogen lo borrado: se quitan y DigestBuilder los rehace sin ello. */
    private suspend fun deleteDigests(fromMs: Long, toMs: Long) = db.digests().deleteDays(Time.dayKey(fromMs), Time.dayKey(toMs))

    /** Todos los trozos viven en pending/; se resuelve por nombre, guarde la captura la ruta absoluta o relativa. */
    private fun pendingFile(path: String) = File(pendingDir(container.app), File(path).name)

    companion object {
        private const val MINUTE_MS = 60_000L
        private const val DAY_MS = 24 * 3_600_000L
        private const val IMPORTS = "retention_imports" // id de conversación importada → cuándo se importó

        /** Ids de las conversaciones de [saved] ([IMPORTS]) importadas desde [cutoff]: conservan su texto. */
        internal fun importedSince(saved: Map<String, *>, cutoff: Long): List<Long> =
            saved.mapNotNull { (id, at) -> id.toLongOrNull()?.takeIf { (at as? Long ?: 0L) >= cutoff } }

        /** Ids de las respuestas con alguna de [conversationIds] en su contexto y de la pregunta que las precede. */
        internal fun chatToForget(messages: List<ChatMessage>, conversationIds: Set<Long>): List<Long> {
            val out = ArrayList<Long>()
            messages.forEachIndexed { i, m ->
                if (m.role != ChatRole.ASSISTANT || m.citations == null) return@forEachIndexed
                val cited = runCatching { AppJson.decodeFromString<List<Long>>(m.citations) }.getOrDefault(emptyList())
                if (cited.none { it in conversationIds }) return@forEachIndexed
                out += m.id
                messages.getOrNull(i - 1)?.takeIf { it.role == ChatRole.USER }?.let { out += it.id }
            }
            return out
        }

        /** Instante antes del cual se borra la transcripción; null = no borrar (0 días). */
        fun retentionCutoff(now: Long, days: Int): Long? = if (days <= 0) null else now - days * DAY_MS

        /**
         * Tarea que sale de lo olvidado: sugerida (el reprocesado la rehace), con su cita fuera de lo que queda
         * ([leftText], ya con [normalized]) o, sin cita, creada después de [since]. Las demás, aceptadas, editadas
         * o descartadas por el usuario con lo que sigue en la conversación, se quedan.
         */
        internal fun fromForgotten(t: TaskItem, leftText: String, since: Long): Boolean = when {
            t.state == TaskState.SUGGESTED -> true
            t.sourceQuote == null -> t.createdAt >= since
            else -> normalized(t.sourceQuote) !in leftText
        }

        private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")

        internal fun normalized(s: String) = s.lowercase().replace(NON_WORD, " ").trim()
    }
}
