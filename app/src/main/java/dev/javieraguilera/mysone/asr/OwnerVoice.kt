package dev.javieraguilera.mysone.asr

import android.content.Context
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Voz registrada del dueño (§5.6): su huella media, para que en lo importado sus frases salgan como «Tú». Es un dato
 * biométrico: solo vive en filesDir (sin copia de seguridad), no se exporta ni sale del móvil, y «Borrar todo» la borra.
 */
object OwnerVoice {
    /** Voz que hace falta para registrarla. */
    const val ENROLL_SAMPLES = 15 * SAMPLE_RATE

    private fun file(context: Context) = File(context.filesDir, "voice/$SPEAKER_MODEL_ID.owner")

    fun exists(context: Context): Boolean = file(context).isFile

    /** La huella guardada, o null si no hay o no tiene [dim] valores (sería de otro modelo). */
    internal suspend fun load(context: Context, dim: Int): FloatArray? = withContext(Dispatchers.IO) {
        val bytes = file(context).takeIf { it.isFile }?.readBytes() ?: return@withContext null
        if (bytes.size != dim * 4) return@withContext null
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        FloatArray(dim).also { buf.get(it) }
    }

    /** Calcula la huella de [speech] (trozos de voz a 16 kHz) y la guarda. false si el modelo de voces no está. */
    suspend fun enroll(context: Context, models: ModelManager, speech: List<FloatArray>): Boolean = withContext(Dispatchers.Default) {
        val extractor = models.openSpeakerExtractor() ?: return@withContext false
        val print = try {
            meanVoice(speech.flatMap { s -> voiceWindows(s.size).map { extractor.voiceprint(s.copyOfRange(it.from, it.to)) } })
        } finally {
            extractor.release()
        }
        withContext(Dispatchers.IO) {
            val target = file(context)
            target.parentFile?.mkdirs()
            val tmp = File(target.path + ".tmp")
            tmp.outputStream().use { out ->
                out.write(ByteBuffer.allocate(print.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply { asFloatBuffer().put(print) }.array())
                out.fd.sync()
            }
            tmp.renameTo(target)
        }
    }

    suspend fun delete(context: Context) {
        withContext(Dispatchers.IO) { file(context).parentFile?.deleteRecursively() }
    }
}
