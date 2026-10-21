package dev.javieraguilera.mysone.capture

import android.content.Context
import dev.javieraguilera.mysone.data.PendingAudio
import dev.javieraguilera.mysone.data.PendingAudioDao
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Cola en disco del audio de voz aún sin transcribir (§5.2): un fichero PCM16 LE mono 16 kHz por trozo en
 * `filesDir/pending/` y su fila en `pending_audio`. El audio se borra en cuanto se transcribe (o se descarta).
 */
class PendingQueue(context: Context, private val dao: PendingAudioDao) {
    private val dir = File(context.filesDir, "pending")

    suspend fun add(conversationId: Long, startMs: Long, endMs: Long, samples: FloatArray) {
        val file = withContext(Dispatchers.IO) {
            dir.mkdirs()
            File(dir, "${UUID.randomUUID()}.pcm").apply { writeBytes(toPcm16(samples)) }
        }
        try {
            dao.insert(PendingAudio(conversationId = conversationId, startedAt = startMs, endedAt = endMs, path = file.absolutePath))
        } catch (e: Exception) {
            file.delete()
            throw e
        }
    }

    /** El más antiguo. */
    suspend fun next(): PendingAudio? = dao.next()

    suspend fun bumpAttempts(p: PendingAudio) = dao.bumpAttempts(p.id)

    /** null si el fichero ya no está (borrado por "olvidar" o por limpieza). */
    suspend fun read(p: PendingAudio): FloatArray? = withContext(Dispatchers.IO) {
        File(p.path).takeIf { it.exists() }?.readBytes()?.let(::fromPcm16)
    }

    /** Sigue en la cola: "olvidar" puede haberlo quitado mientras se transcribía. */
    suspend fun isQueued(p: PendingAudio): Boolean = dao.forConversation(p.conversationId).any { it.id == p.id }

    suspend fun remove(p: PendingAudio) {
        dao.delete(p.id)
        deleteFile(p)
    }

    /** Solo el fichero, una vez borrada su fila. */
    suspend fun deleteFile(p: PendingAudio) {
        withContext(Dispatchers.IO) { File(p.path).delete() }
    }

    suspend fun totalDurationMs(): Long = dao.totalDurationMs()

    companion object {
        fun toPcm16(samples: FloatArray): ByteArray {
            val buf = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (s in samples) buf.putShort((s.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort())
            return buf.array()
        }

        fun fromPcm16(bytes: ByteArray): FloatArray {
            val shorts = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            return FloatArray(shorts.remaining()) { shorts.get(it) / 32768f }
        }
    }
}
