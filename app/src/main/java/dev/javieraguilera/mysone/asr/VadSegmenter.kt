package dev.javieraguilera.mysone.asr

import android.content.Context
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig

const val SAMPLE_RATE = 16_000

/** Tope de cada trozo para el ASR (03 §5: `maxSpeechDuration` de sherpa no corta en seco). */
const val MAX_CHUNK_SAMPLES = 25 * SAMPLE_RATE

/** Ventana de Silero (§5.4). */
private const val WINDOW = 512

/**
 * Margen antes de cada trozo. Silero solo da por voz lo que pasa de minSpeech (0,25 s) seguido, y sherpa
 * guarda 0,3 s antes de ese punto: una palabra corta seguida de pausa ("Hola, buenos días", "Sí, el señor…")
 * se perdía. Con 1,5 s de margen entra en el trozo siguiente si la pausa no pasa de ~1 s ("Hola, Pablo. ¿Qué tal?
 * [0,9 s] Oye, el jueves…"); una palabra suelta entre pausas más largas sigue sin llegar al ASR.
 */
private const val PRE_ROLL_SAMPLES = SAMPLE_RATE * 3 / 2

/** Lo que se guarda para el margen: el margen más lo que sherpa ya incluye (2 ventanas + minSpeech) y holgura. */
private const val HISTORY_SAMPLES = 3 * SAMPLE_RATE

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
                windowSize = WINDOW,
                maxSpeechDuration = 20f, // corte suave (sube el umbral); el duro lo hace splitSpeech
            ),
            sampleRate = SAMPLE_RATE,
            numThreads = 1,
        ),
    )
    private var base = 0L // posición absoluta de la muestra 0 de sherpa (la del último reset)
    private var fed = 0L  // muestras entregadas a sherpa desde el último reset
    private var closed = false
    private val history = AudioHistory(HISTORY_SAMPLES)
    private var beforeOnset: AudioHistory.Snapshot? = null // lo oído justo antes de que empezara la voz en curso
    private var lastEnd = 0L // fin absoluto del último trozo entregado: el margen no repite lo que ya salió

    /** frame: float [-1,1] a 16 kHz (cualquier tamaño). Devuelve trozos de voz terminados, ≤ 25 s cada uno. */
    fun accept(frame: FloatArray): List<SpeechChunk> {
        // De ventana en ventana: con un bloque grande, sherpa calcula el inicio de la voz desde el final del
        // bloque (tail − 2 ventanas − minSpeech) y se come el principio de la frase (los 32 768 de un WAV).
        val out = ArrayList<SpeechChunk>()
        for (from in 0 until frame.size step WINDOW) {
            val window = if (frame.size <= WINDOW) frame else frame.copyOfRange(from, minOf(from + WINDOW, frame.size))
            val wasSpeaking = vad.isSpeechDetected()
            history.add(window)
            vad.acceptWaveform(window)
            fed += window.size
            if (!wasSpeaking && vad.isSpeechDetected()) beforeOnset = history.snapshot()
            out += drain()
        }
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
            val start = base + segment.start
            val chunk = withPreRoll(start, segment.samples, beforeOnset, notBefore = lastEnd)
            lastEnd = start + segment.samples.size
            out += splitSpeech(chunk.startSample, chunk.samples)
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

/** [samples] desde [start] con hasta [preRoll] muestras anteriores sacadas de [before], sin volver antes de [notBefore]. */
internal fun withPreRoll(
    start: Long,
    samples: FloatArray,
    before: AudioHistory.Snapshot?,
    notBefore: Long,
    preRoll: Int = PRE_ROLL_SAMPLES,
): SpeechChunk {
    val pad = before?.slice(maxOf(start - preRoll, notBefore), start) ?: return SpeechChunk(start, samples)
    return if (pad.isEmpty()) SpeechChunk(start, samples) else SpeechChunk(start - pad.size, pad + samples)
}

/** Las últimas [size] muestras entregadas, por posición absoluta. */
internal class AudioHistory(private val size: Int) {
    private val ring = FloatArray(size)
    private var end = 0L // posición absoluta tras la última muestra

    fun add(samples: FloatArray) {
        for (s in samples) ring[(end++ % size).toInt()] = s
    }

    fun snapshot(): Snapshot {
        val from = maxOf(0L, end - size)
        return Snapshot(from, FloatArray((end - from).toInt()) { ring[((from + it) % size).toInt()] })
    }

    class Snapshot(private val start: Long, private val samples: FloatArray) {
        /** Lo que haya de [from, to); vacío si no lo tiene o si llega hasta [to] sin tocarlo. */
        fun slice(from: Long, to: Long): FloatArray {
            val f = maxOf(from, start)
            val t = minOf(to, start + samples.size)
            // Solo si llega justo hasta [to]: un hueco entre el margen y el trozo desplazaría el audio.
            if (f >= t || t != to) return FloatArray(0)
            return samples.copyOfRange((f - start).toInt(), (t - start).toInt())
        }
    }
}
