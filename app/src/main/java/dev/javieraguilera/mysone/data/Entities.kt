package dev.javieraguilera.mysone.data

import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Fts5
import androidx.room3.FtsOptions
import androidx.room3.Index
import androidx.room3.PrimaryKey
import kotlinx.serialization.Serializable

// Tiempos en epoch ms UTC. Estados como String con las constantes de abajo (sin TypeConverters).
// @Serializable sirve para la exportación JSON (DataManager).

object ConvSource {
    const val MIC = "MIC"
    const val IMPORT = "IMPORT"
}

object ConvState {
    const val OPEN = "OPEN"
    const val READY = "READY"
    const val PROCESSING = "PROCESSING"
    const val DONE = "DONE"
    const val FAILED = "FAILED"
}

object TaskState {
    const val SUGGESTED = "SUGGESTED"
    const val OPEN = "OPEN"
    const val DONE = "DONE"
    const val DISMISSED = "DISMISSED"
}

object FactState {
    const val SUGGESTED = "SUGGESTED"
    const val CONFIRMED = "CONFIRMED"
}

object GapReason {
    const val PAUSED = "PAUSED"
    const val STOPPED = "STOPPED"
    const val MIC_BUSY = "MIC_BUSY"
    const val BATTERY = "BATTERY"
    const val ERROR = "ERROR"
    const val KILLED = "KILLED"
}

object ChatRole {
    const val USER = "user"
    const val ASSISTANT = "assistant"
}

@Serializable
@Entity(tableName = "conversation", indices = [Index("startedAt"), Index("state")])
data class Conversation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val endedAt: Long,
    val source: String,             // ConvSource
    val state: String,              // ConvState
    val title: String? = null,
    val summary: String? = null,
    val keyPoints: String? = null,  // JSON array de strings
    val isNoise: Boolean = false,   // TV, radio, ruido: se atenúa en la UI y no genera tareas
    val processAttempts: Int = 0,
    val processedAt: Long? = null,
    val lastError: String? = null,  // mensaje corto, sin contenido de la conversación
)

@Serializable
@Entity(
    tableName = "segment",
    foreignKeys = [ForeignKey(Conversation::class, ["id"], ["conversationId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("conversationId"), Index("startedAt")],
)
data class Segment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    val startedAt: Long,
    val endedAt: Long,
    val text: String,
)

/** Índice FTS5 de contenido externo: Room crea los triggers que lo sincronizan con `segment`. */
@Fts5(
    contentEntity = Segment::class,
    tokenizer = FtsOptions.TOKENIZER_UNICODE61,
    tokenizerArgs = ["remove_diacritics", "2"],
)
@Entity(tableName = "segment_fts")
data class SegmentFts(val text: String)

@Fts5(
    contentEntity = Conversation::class,
    tokenizer = FtsOptions.TOKENIZER_UNICODE61,
    tokenizerArgs = ["remove_diacritics", "2"],
)
@Entity(tableName = "conversation_fts")
data class ConversationFts(val title: String?, val summary: String?)

@Entity(
    tableName = "pending_audio",
    foreignKeys = [ForeignKey(Conversation::class, ["id"], ["conversationId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("conversationId")],
)
data class PendingAudio(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    val startedAt: Long,
    val endedAt: Long,
    val path: String,   // PCM16 LE mono 16 kHz en filesDir/pending/
    val attempts: Int = 0, // se incrementa ANTES de decodificar
)

@Serializable
@Entity(
    tableName = "task",
    foreignKeys = [ForeignKey(Conversation::class, ["id"], ["conversationId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("conversationId"), Index("state"), Index("remindAt")],
)
data class TaskItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val state: String,                // TaskState
    val dueAt: Long? = null,          // fecha límite (informativa)
    val remindAt: Long? = null,       // con state OPEN → hay alarma
    val conversationId: Long? = null, // null = creada a mano
    val sourceQuote: String? = null,
    val createdAt: Long,
    val notifiedAt: Long? = null,
)

@Serializable
@Entity(
    tableName = "fact",
    foreignKeys = [ForeignKey(Conversation::class, ["id"], ["conversationId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("conversationId")],
)
data class Fact(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val state: String, // FactState
    val conversationId: Long? = null,
    val createdAt: Long,
)

@Serializable
@Entity(tableName = "daily_digest")
data class DailyDigest(
    @PrimaryKey val day: String, // "2026-10-15", zona local
    val summary: String,
    val highlights: String,      // JSON array de strings
    val createdAt: Long,
)

@Serializable
@Entity(tableName = "chat_message")
data class ChatMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val role: String, // ChatRole
    val text: String,
    val citations: String? = null, // JSON array de conversationId: todas las de su contexto, citadas o no
    val createdAt: Long,
)

@Serializable
@Entity(tableName = "listening_gap", indices = [Index("startedAt")])
data class ListeningGap(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val endedAt: Long? = null, // null = hueco abierto
    val reason: String,        // GapReason
)
