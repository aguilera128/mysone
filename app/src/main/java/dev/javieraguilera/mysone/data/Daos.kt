package dev.javieraguilera.mysone.data

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import androidx.room3.Update
import androidx.room3.Upsert
import java.text.Normalizer
import kotlinx.coroutines.flow.Flow

// Rangos de tiempo: [from, to) en epoch ms. Para "sin límite" pasa 0 y Long.MAX_VALUE.

/** Resultado de búsqueda: fragmento con los términos entre «» y elipsis «…». */
data class SegmentHit(val segmentId: Long, val conversationId: Long, val startedAt: Long, val snippet: String)

data class StateCount(val state: String, val count: Int)

/**
 * Saneado de consultas FTS5: solo tokens `[\p{L}\p{N}]+`, cada uno como `"token"*` (prefijo).
 * Así ninguna entrada del usuario o de la IA rompe la sintaxis de MATCH. null = no queda nada que buscar.
 */
object FtsQuery {
    private val TOKEN = Regex("[\\p{L}\\p{N}]+")

    /** Búsqueda del usuario: todos los términos (AND). */
    fun all(input: String): String? = build(tokens(input), " ")

    /** Recuperación para la IA: cualquiera de los términos (OR). */
    fun any(terms: List<String>): String? = build(terms.flatMap(::tokens), " OR ")

    private fun tokens(text: String): List<String> =
        TOKEN.findAll(Normalizer.normalize(text, Normalizer.Form.NFC)).map { it.value }.toList()

    private fun build(tokens: List<String>, separator: String): String? =
        tokens.distinctBy { it.lowercase() }.takeIf { it.isNotEmpty() }?.joinToString(separator) { "\"$it\"*" }
}

@Dao
interface ConversationDao {
    @Insert suspend fun insert(c: Conversation): Long

    @Update suspend fun update(c: Conversation)

    @Query("SELECT * FROM conversation WHERE id = :id")
    suspend fun get(id: Long): Conversation?

    @Query("SELECT * FROM conversation WHERE id = :id")
    fun observe(id: Long): Flow<Conversation?>

    @Query("SELECT * FROM conversation WHERE id IN (:ids)")
    suspend fun byIds(ids: List<Long>): List<Conversation>

    /** Las del día (o rango), más recientes primero. */
    @Query("SELECT * FROM conversation WHERE startedAt >= :from AND startedAt < :to ORDER BY startedAt DESC")
    fun observeDay(from: Long, to: Long): Flow<List<Conversation>>

    @Query("SELECT * FROM conversation WHERE state = 'OPEN' ORDER BY startedAt DESC LIMIT 1")
    suspend fun findOpen(): Conversation?

    @Query("SELECT * FROM conversation WHERE state = :state ORDER BY startedAt")
    suspend fun byState(state: String): List<Conversation>

    @Query(
        "SELECT * FROM conversation WHERE state = 'READY' AND NOT EXISTS " +
            "(SELECT 1 FROM pending_audio WHERE pending_audio.conversationId = conversation.id) ORDER BY startedAt",
    )
    suspend fun readyWithoutPending(): List<Conversation>

    @Query("SELECT * FROM conversation WHERE startedAt >= :from AND startedAt < :to ORDER BY startedAt DESC LIMIT :limit")
    suspend fun inRange(from: Long, to: Long, limit: Int): List<Conversation>

    /** [q] ya saneada con [FtsQuery]. Busca en título y resumen. */
    @Query(
        "SELECT conversation.* FROM conversation JOIN conversation_fts ON conversation.id = conversation_fts.rowid " +
            "WHERE conversation_fts MATCH :q AND conversation.startedAt >= :from AND conversation.startedAt < :to " +
            "ORDER BY bm25(conversation_fts) LIMIT :limit",
    )
    suspend fun searchFts(q: String, from: Long, to: Long, limit: Int): List<Conversation>

    @Query("UPDATE conversation SET state = :state WHERE id = :id")
    suspend fun setState(id: Long, state: String)

    /** Solo si sigue en [from]: "olvidar" o borrar pueden haberla cambiado mientras tanto. */
    @Query("UPDATE conversation SET state = :to WHERE id = :id AND state = :from")
    suspend fun setStateIf(id: Long, from: String, to: String)

    /** Fin de un procesado fallido, si nadie la ha tocado mientras ("olvidar" la deja en READY y sin resumen). */
    @Query(
        "UPDATE conversation SET state = :state, processAttempts = processAttempts + :addAttempts, lastError = :lastError " +
            "WHERE id = :id AND state = 'PROCESSING'",
    )
    suspend fun endFailed(id: Long, state: String, addAttempts: Int, lastError: String?)

    /** Con otros ajustes de IA, las que fallaron recuperan sus intentos. */
    @Query("UPDATE conversation SET processAttempts = 0 WHERE state = 'FAILED'")
    suspend fun resetFailedAttempts()

    /**
     * "Olvidar" la ha tocado: sin lo derivado y con las horas de lo que queda. La abierta sigue abierta;
     * el resto, a reprocesar.
     */
    @Query(
        "UPDATE conversation SET startedAt = :startedAt, endedAt = :endedAt, title = NULL, summary = NULL, " +
            "keyPoints = NULL, isNoise = 0, processAttempts = 0, processedAt = NULL, lastError = NULL, " +
            "state = CASE WHEN state = 'OPEN' THEN 'OPEN' ELSE 'READY' END WHERE id = :id",
    )
    suspend fun resetForgotten(id: Long, startedAt: Long, endedAt: Long)

    @Query("UPDATE conversation SET endedAt = :endedAt WHERE id = :id")
    suspend fun setEndedAt(id: Long, endedAt: Long)

    /** Último instante con voz registrado (para el hueco KILLED). */
    @Query("SELECT MAX(endedAt) FROM conversation")
    suspend fun lastEndedAt(): Long?

    @Query("DELETE FROM conversation WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM conversation ORDER BY startedAt")
    suspend fun all(): List<Conversation>

    /** Las importadas (a mano o de llamadas) que siguen existiendo (lista de importaciones de Ajustes › Transcripción). */
    @Query("SELECT id FROM conversation WHERE source IN ('IMPORT', 'CALL')")
    fun observeImportIds(): Flow<List<Long>>

    /** Las importadas de ficheros con esa fecha de modificación: terminan justo en ella (§5.5). */
    @Query("SELECT * FROM conversation WHERE source IN ('IMPORT', 'CALL') AND endedAt = :endedAt ORDER BY id")
    suspend fun importsEndingAt(endedAt: Long): List<Conversation>

    @Query("SELECT state, COUNT(*) AS count FROM conversation GROUP BY state")
    fun countByState(): Flow<List<StateCount>>
}

@Dao
interface SegmentDao {
    @Insert suspend fun insert(s: Segment): Long

    @Query("SELECT * FROM segment WHERE id IN (:ids) ORDER BY startedAt")
    suspend fun byIds(ids: List<Long>): List<Segment>

    @Query("SELECT * FROM segment WHERE conversationId = :id ORDER BY startedAt")
    fun forConversation(id: Long): Flow<List<Segment>>

    @Query("SELECT * FROM segment WHERE conversationId = :id ORDER BY startedAt")
    suspend fun textForConversation(id: Long): List<Segment>

    @Query("SELECT COUNT(*) FROM segment WHERE conversationId = :id")
    suspend fun countForConversation(id: Long): Int

    /** Segmento anterior dentro de la misma conversación (contexto de un resultado). */
    @Query("SELECT * FROM segment WHERE conversationId = :conversationId AND startedAt < :startedAt ORDER BY startedAt DESC LIMIT 1")
    suspend fun previous(conversationId: Long, startedAt: Long): Segment?

    @Query("SELECT * FROM segment WHERE conversationId = :conversationId AND startedAt > :startedAt ORDER BY startedAt LIMIT 1")
    suspend fun next(conversationId: Long, startedAt: Long): Segment?

    /** [q] ya saneada con [FtsQuery]. Mejores primero (bm25). */
    @Query(
        "SELECT segment.id AS segmentId, segment.conversationId AS conversationId, segment.startedAt AS startedAt, " +
            "snippet(segment_fts, 0, '«', '»', '…', 12) AS snippet " +
            "FROM segment_fts JOIN segment ON segment.id = segment_fts.rowid " +
            "WHERE segment_fts MATCH :q AND segment.startedAt >= :from AND segment.startedAt < :to " +
            "ORDER BY bm25(segment_fts) LIMIT :limit",
    )
    suspend fun search(q: String, from: Long, to: Long, limit: Int): List<SegmentHit>

    @Query("SELECT * FROM segment WHERE startedAt >= :from AND startedAt < :to ORDER BY startedAt")
    suspend fun inRange(from: Long, to: Long): List<Segment>

    /** Retención: borra la transcripción literal que terminó antes de [ts]. */
    @Query("DELETE FROM segment WHERE endedAt < :ts")
    suspend fun deleteOlderThan(ts: Long): Int

    /** "Olvidar los últimos N min": todo lo que termina después de [ts], aunque empezara antes. */
    @Query("DELETE FROM segment WHERE endedAt > :ts")
    suspend fun deleteSince(ts: Long): Int

    @Query("SELECT COUNT(*) FROM segment WHERE startedAt >= :ts")
    fun countSince(ts: Long): Flow<Int>
}

@Dao
interface PendingAudioDao {
    @Insert suspend fun insert(p: PendingAudio): Long

    /** El más antiguo. */
    @Query("SELECT * FROM pending_audio ORDER BY startedAt, id LIMIT 1")
    suspend fun next(): PendingAudio?

    @Query("UPDATE pending_audio SET attempts = attempts + 1 WHERE id = :id")
    suspend fun bumpAttempts(id: Long)

    @Query("DELETE FROM pending_audio WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COUNT(*) FROM pending_audio WHERE conversationId = :id")
    suspend fun countForConversation(id: Long): Int

    @Query("SELECT * FROM pending_audio WHERE conversationId = :id ORDER BY startedAt")
    suspend fun forConversation(id: Long): List<PendingAudio>

    @Query("SELECT COUNT(*) FROM pending_audio")
    fun count(): Flow<Int>

    /** Conversaciones con audio aún por transcribir ("Transcribiendo…" en la UI). */
    @Query("SELECT DISTINCT conversationId FROM pending_audio")
    fun observeConversationIds(): Flow<List<Long>>

    @Query("SELECT COALESCE(SUM(endedAt - startedAt), 0) FROM pending_audio")
    suspend fun totalDurationMs(): Long

    @Query("SELECT * FROM pending_audio ORDER BY startedAt")
    suspend fun all(): List<PendingAudio>

    @Query("SELECT * FROM pending_audio WHERE endedAt > :ts ORDER BY startedAt")
    suspend fun since(ts: Long): List<PendingAudio>

    @Query("DELETE FROM pending_audio WHERE endedAt > :ts")
    suspend fun deleteSince(ts: Long): Int
}

@Dao
interface TaskDao {
    @Insert suspend fun insert(t: TaskItem): Long

    @Update suspend fun update(t: TaskItem)

    @Query("SELECT * FROM task WHERE id = :id")
    suspend fun get(id: Long): TaskItem?

    @Query("DELETE FROM task WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE task SET state = :state WHERE id = :id")
    suspend fun setState(id: Long, state: String)

    @Query("SELECT * FROM task WHERE state = :state ORDER BY createdAt DESC")
    fun observeByState(state: String): Flow<List<TaskItem>>

    @Query("SELECT * FROM task WHERE state = :state ORDER BY createdAt DESC LIMIT :limit")
    suspend fun listByState(state: String, limit: Int): List<TaskItem>

    @Query("SELECT * FROM task WHERE conversationId = :id ORDER BY createdAt")
    suspend fun forConversation(id: Long): List<TaskItem>

    @Query("SELECT * FROM task WHERE conversationId = :id ORDER BY createdAt")
    fun observeForConversation(id: Long): Flow<List<TaskItem>>

    /** Tareas de las conversaciones que empiezan en [from, to) (chips de la línea de tiempo). */
    @Query(
        "SELECT task.* FROM task JOIN conversation ON conversation.id = task.conversationId " +
            "WHERE conversation.startedAt >= :from AND conversation.startedAt < :to ORDER BY task.createdAt",
    )
    fun observeInDay(from: Long, to: Long): Flow<List<TaskItem>>

    /** Las que llevan alarma: abiertas y con hora de aviso. */
    @Query("SELECT * FROM task WHERE state = 'OPEN' AND remindAt IS NOT NULL ORDER BY remindAt")
    suspend fun withReminder(): List<TaskItem>

    @Query("SELECT * FROM task ORDER BY createdAt")
    suspend fun all(): List<TaskItem>

    /** Retención: la cita literal de las tareas de conversaciones que terminaron antes de [ts]. La tarea se queda. */
    @Query(
        "UPDATE task SET sourceQuote = NULL WHERE sourceQuote IS NOT NULL AND conversationId IN " +
            "(SELECT id FROM conversation WHERE endedAt < :ts)",
    )
    suspend fun dropQuotesOlderThan(ts: Long): Int
}

@Dao
interface FactDao {
    @Insert suspend fun insert(f: Fact): Long

    @Update suspend fun update(f: Fact)

    @Query("DELETE FROM fact WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE fact SET state = :state WHERE id = :id")
    suspend fun setState(id: Long, state: String)

    @Query("UPDATE fact SET text = :text WHERE id = :id")
    suspend fun setText(id: Long, text: String)

    @Query("SELECT * FROM fact WHERE state = :state ORDER BY createdAt DESC")
    fun observeByState(state: String): Flow<List<Fact>>

    @Query("SELECT * FROM fact WHERE conversationId = :id ORDER BY createdAt")
    fun observeForConversation(id: Long): Flow<List<Fact>>

    @Query("SELECT * FROM fact WHERE conversationId = :id ORDER BY createdAt")
    suspend fun forConversation(id: Long): List<Fact>

    @Query("SELECT * FROM fact WHERE state = :state ORDER BY createdAt DESC LIMIT :limit")
    suspend fun listByState(state: String, limit: Int): List<Fact>

    @Query("SELECT * FROM fact ORDER BY createdAt")
    suspend fun all(): List<Fact>
}

@Dao
interface DigestDao {
    @Upsert suspend fun upsert(d: DailyDigest)

    @Query("SELECT * FROM daily_digest WHERE day = :day")
    suspend fun get(day: String): DailyDigest?

    @Query("SELECT * FROM daily_digest WHERE day = :day")
    fun observe(day: String): Flow<DailyDigest?>

    /** Días "yyyy-MM-dd" entre [fromDay] y [toDay], ambos incluidos; más recientes primero. */
    @Query("SELECT * FROM daily_digest WHERE day >= :fromDay AND day <= :toDay ORDER BY day DESC LIMIT :limit")
    suspend fun inRange(fromDay: String, toDay: String, limit: Int): List<DailyDigest>

    @Query("SELECT * FROM daily_digest ORDER BY day")
    suspend fun all(): List<DailyDigest>

    /** Días "yyyy-MM-dd" entre [fromDay] y [toDay], ambos incluidos. */
    @Query("DELETE FROM daily_digest WHERE day >= :fromDay AND day <= :toDay")
    suspend fun deleteDays(fromDay: String, toDay: String)
}

@Dao
interface ChatDao {
    @Insert suspend fun insert(m: ChatMessage): Long

    @Query("SELECT * FROM chat_message ORDER BY createdAt, id")
    fun observeAll(): Flow<List<ChatMessage>>

    /** Los últimos [limit], del más reciente al más antiguo. */
    @Query("SELECT * FROM chat_message ORDER BY createdAt DESC, id DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<ChatMessage>

    @Query("DELETE FROM chat_message")
    suspend fun clear()

    @Query("DELETE FROM chat_message WHERE id IN (:ids)")
    suspend fun delete(ids: List<Long>)

    /** Retención: las respuestas copian fragmentos literales; se van con la transcripción. */
    @Query("DELETE FROM chat_message WHERE createdAt < :ts")
    suspend fun deleteOlderThan(ts: Long): Int

    @Query("SELECT * FROM chat_message ORDER BY createdAt, id")
    suspend fun all(): List<ChatMessage>
}

@Dao
interface GapDao {
    @Insert suspend fun insert(g: ListeningGap): Long

    @Query("UPDATE listening_gap SET endedAt = :endedAt WHERE id = :id")
    suspend fun setEnded(id: Long, endedAt: Long)

    @Query("DELETE FROM listening_gap WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM listening_gap WHERE endedAt IS NULL ORDER BY startedAt DESC LIMIT 1")
    suspend fun findOpen(): ListeningGap?

    /** Huecos que se solapan con [from, to), incluidos los abiertos. */
    @Query("SELECT * FROM listening_gap WHERE startedAt < :to AND (endedAt IS NULL OR endedAt > :from) ORDER BY startedAt DESC")
    fun observeRange(from: Long, to: Long): Flow<List<ListeningGap>>

    @Query("SELECT * FROM listening_gap ORDER BY startedAt")
    suspend fun all(): List<ListeningGap>
}
