package dev.javieraguilera.mysone.asr

import android.content.Context
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig

const val SAMPLE_RATE = 16_000

/** Tope de cada trozo para el ASR (03 §5: `maxSpeechDuration` de sherpa no corta en seco). */
const val MAX_CHUNK_SAMPLES = 25 * SAMPLE_RATE

/** Reset del VAD en silencio cada hora: `SpeechSegment.start` es int32 en C++ y desborda a ~37 h (03 §3.1). */
private const val RESET_EVERY_SAMPLES = 3_600L * SAMPLE_RATE

/**
 * VAD Silero sobre sherpa-onnx (§5.4). Lo usan la captura en vivo (B1) y la importación (B2).
 * No es seguro entre hilos: una instancia por hilo productor.
 */
class VadSegmenter(context: Context) : AutoCloseable {
    private val vad = Vad(
        assetManager = context.assets, // el VAD va en assets; los modelos ASR, desde fichero
        config = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = "silero_vad.onnx",
                threshold = 0.5f,
                minSilenceDuration = 0.5f,
                minSpeechDuration = 0.25f,
                windowSize = 512,
                maxSpeechDuration = 20f, // corte suave (sube el umbral); el duro lo hace splitSpeech
            ),
            sampleRate = SAMPLE_RATE,
            numThreads = 1,
        ),
    )
    private var base = 0L // posición absoluta de la muestra 0 de sherpa (la del último reset)
    private var fed = 0L  // muestras entregadas a sherpa desde el último reset
    private var closed = false

    /** frame: float [-1,1] a 16 kHz (cualquier tamaño). Devuelve trozos de voz terminados, ≤ 25 s cada uno. */
    fun accept(frame: FloatArray): List<SpeechChunk> {
        vad.acceptWaveform(frame)
        fed += frame.size
        val out = drain()
        if (fed >= RESET_EVERY_SAMPLES && !vad.isSpeechDetected()) restart()
        return out
    }

    /** Entrega la voz a medias (al parar o al acabar un fichero). Después se puede seguir con [accept]. */
    fun flush(): List<SpeechChunk> {
        vad.flush()
        val out = drain()
        restart()
        return out
    }

    val isSpeaking: Boolean get() = vad.isSpeechDetected()

    override fun close() {
        if (closed) return
        closed = true
        vad.release()
    }

    private fun restart() {
        vad.reset()
        base += fed
        fed = 0
    }

    private fun drain(): List<SpeechChunk> {
        if (vad.empty()) return emptyList()
        val out = ArrayList<SpeechChunk>()
        while (!vad.empty()) {
            val segment = vad.front() // copia desde JNI
            vad.pop()
            out += splitSpeech(base + segment.start, segment.samples)
        }
        return out
    }
}

/** startSample: posición absoluta (Long) desde la creación del segmentador; el wrapper gestiona el
 *  desbordamiento int32 de sherpa (reset en silencio) y trocea a 25 s. */
class SpeechChunk(val startSample: Long, val samples: FloatArray)

/** Parte la voz en trozos iguales de ≤ [max] muestras (26 s → 13 + 13, no 25 + 1). */
internal fun splitSpeech(start: Long, samples: FloatArray, max: Int = MAX_CHUNK_SAMPLES): List<SpeechChunk> {
    if (samples.size <= max) return listOf(SpeechChunk(start, samples))
    val parts = (samples.size + max - 1) / max
    val size = (samples.size + parts - 1) / parts
    val out = ArrayList<SpeechChunk>(parts)
    var from = 0
    while (from < samples.size) {
        val to = minOf(from + size, samples.size)
        out += SpeechChunk(start + from, samples.copyOfRange(from, to))
        from = to
    }
    return out
}
