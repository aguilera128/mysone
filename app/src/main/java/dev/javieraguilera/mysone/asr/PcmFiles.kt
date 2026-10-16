package dev.javieraguilera.mysone.asr

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/** PCM16 little-endian mono 16 kHz, el formato de la cola `filesDir/pending/`. Float [-1,1] ↔ short / 32768. */
object PcmFiles {
    fun write(file: File, samples: FloatArray) = file.writeBytes(encode(samples))

    fun read(file: File): FloatArray = decode(file.readBytes())

    fun encode(samples: FloatArray): ByteArray {
        val buf = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) buf.putShort((s * 32768f).roundToInt().coerceIn(-32768, 32767).toShort())
        return buf.array()
    }

    fun decode(bytes: ByteArray): FloatArray {
        val shorts = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return FloatArray(shorts.remaining()) { shorts.get(it) / 32768f }
    }

    /** WAV PCM16 mono 16 kHz (el de la autoprueba). Recorre los chunks: la cabecera no siempre mide 44 bytes. */
    fun readWav(bytes: ByteArray): FloatArray {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(bytes.size >= 12 && String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WAVE") { "no es WAV" }
        var pos = 12
        var formatOk = false
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4)
            val size = buf.getInt(pos + 4)
            val body = pos + 8
            when (id) {
                "fmt " -> formatOk = buf.getShort(body).toInt() == 1 && buf.getShort(body + 2).toInt() == 1 &&
                    buf.getInt(body + 4) == 16_000 && buf.getShort(body + 14).toInt() == 16
                "data" -> {
                    require(formatOk) { "WAV no es PCM16 mono 16 kHz" }
                    return decode(bytes.copyOfRange(body, minOf(body + size, bytes.size)))
                }
            }
            pos = body + size + (size and 1)
        }
        error("WAV sin datos")
    }
}
