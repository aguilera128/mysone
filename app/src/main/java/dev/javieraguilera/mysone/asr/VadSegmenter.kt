package dev.javieraguilera.mysone.asr

import android.content.Context

/** VAD Silero sobre sherpa-onnx (§5.4). Lo usan la captura en vivo (B1) y la importación (B2). Lo implementa B2. */
class VadSegmenter(private val context: Context) : AutoCloseable {
    /** frame: float [-1,1] a 16 kHz (cualquier tamaño). Devuelve trozos de voz terminados, ≤ 25 s cada uno. */
    fun accept(frame: FloatArray): List<SpeechChunk> = emptyList()

    fun flush(): List<SpeechChunk> = emptyList()

    val isSpeaking: Boolean get() = false

    override fun close() {}
}

/** startSample: posición absoluta (Long) desde la creación del segmentador; el wrapper gestiona el
 *  desbordamiento int32 de sherpa (reset en silencio) y trocea a 25 s. */
class SpeechChunk(val startSample: Long, val samples: FloatArray)
