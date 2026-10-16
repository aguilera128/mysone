package dev.javieraguilera.mysone.capture

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.os.Process
import android.util.Log
import dev.javieraguilera.mysone.asr.SpeechChunk
import dev.javieraguilera.mysone.asr.VadSegmenter
import kotlin.concurrent.thread
import kotlin.math.log10

/** Trozo de voz con tiempos absolutos (epoch ms). */
class VoiceChunk(val startMs: Long, val endMs: Long, val samples: FloatArray)

/**
 * AudioRecord 16 kHz mono PCM16 + VAD en un hilo propio de prioridad de audio (§5.2.1). [onChunk] y
 * [onLevel] se llaman desde ese hilo y no deben bloquear (el servicio los pasa a un Channel).
 * [onSilenced] llega por el hilo principal: llamada, otra app grabando… (02 §8).
 */
class AudioCapture(
    private val context: Context,
    private val source: Int,
    private val onChunk: (VoiceChunk) -> Unit,
    private val onLevel: (Float) -> Unit,
    private val onSilenced: (Boolean) -> Unit,
    private val onFailure: (AudioCapture) -> Unit, // el micro se ha perdido a mitad de captura
) {
    @Volatile var speaking = false
        private set

    @Volatile private var running = false
    @Volatile private var current: AudioRecord? = null
    private var thread: Thread? = null

    private val watcher = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
            val mine = runCatching { current?.activeRecordingConfiguration }.getOrNull() ?: return
            if (running) onSilenced(mine.isClientSilenced)
        }
    }

    /** Abre el micro y arranca el hilo. false si no se puede grabar. */
    fun start(): Boolean {
        val vad = try {
            VadSegmenter(context)
        } catch (e: Exception) {
            Log.w(TAG, "VAD no disponible: ${e.javaClass.simpleName}")
            return false
        }
        val record = open()
        if (record == null) {
            vad.close()
            return false
        }
        running = true
        thread = thread(name = "mysone-capture") { loop(record, vad) }
        return true
    }

    /** Para y espera a que el hilo vuelque lo último de la VAD (una lectura son 32 ms). */
    fun stop() {
        running = false
        thread?.join(1_000)
        thread = null
    }

    @SuppressLint("MissingPermission") // el servicio comprueba RECORD_AUDIO antes de crear la captura
    private fun open(): AudioRecord? {
        val minBuf = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = try {
            AudioRecord.Builder()
                .setAudioSource(source)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SR)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(minBuf, SR * 2)) // ≥ 1 s de margen
                .build()
        } catch (e: Exception) { // UnsupportedOperationException, SecurityException
            Log.w(TAG, "AudioRecord no se crea: ${e.javaClass.simpleName}")
            return null
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return null
        }
        current = record
        // Antes de startRecording, para enterarnos también si arrancamos ya silenciados.
        record.registerAudioRecordingCallback(context.mainExecutor, watcher)
        try {
            record.startRecording()
        } catch (_: IllegalStateException) {
        }
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            Log.w(TAG, "El micro no empieza a grabar")
            close(record)
            return null
        }
        // Si ya empezamos silenciados (llamada en curso) puede que no llegue ningún cambio después.
        record.activeRecordingConfiguration?.let { cfg ->
            context.mainExecutor.execute { if (running) onSilenced(cfg.isClientSilenced) }
        }
        return record
    }

    private fun loop(first: AudioRecord, vad: VadSegmenter) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        var record = first
        var t0 = System.currentTimeMillis() // instante de la muestra 0 del segmentador
        var fed = 0L // muestras entregadas al segmentador
        var syncedAt = 0L
        val buf = ShortArray(FRAME)
        var sumSq = 0.0
        var counted = 0
        var failed = false

        fun emit(chunks: List<SpeechChunk>) = chunks.forEach {
            val start = t0 + it.startSample * 1000 / SR
            onChunk(VoiceChunk(start, start + it.samples.size * 1000L / SR, it.samples))
        }

        // Sin voz a medias, recoloca la muestra 0 para que la última leída sea "ahora". El reloj de muestras
        // se atrasa si se pierden lecturas o se reabre el micro (03 §8).
        fun resync() {
            t0 = System.currentTimeMillis() - fed * 1000 / SR
            syncedAt = fed
        }

        try {
            while (running) {
                val n = record.read(buf, 0, FRAME, AudioRecord.READ_BLOCKING)
                if (n == AudioRecord.ERROR_DEAD_OBJECT) { // se reinició audioserver: el FGS conserva el permiso (02 §10)
                    emit(vad.flush())
                    close(record)
                    val again = open()
                    if (again == null) {
                        failed = true
                        break
                    }
                    record = again
                    resync()
                    continue
                }
                if (n < 0) {
                    Log.w(TAG, "read() devolvió $n")
                    failed = true
                    break
                }
                // Array nuevo en cada lectura: el segmentador puede quedarse con la referencia.
                val frame = FloatArray(n) { buf[it] / 32768f }
                for (s in frame) sumSq += s * s
                counted += n
                fed += n
                emit(vad.accept(frame))
                speaking = vad.isSpeaking
                if (!speaking && fed - syncedAt >= SR * 3600L) resync()
                if (counted >= SR / 4) { // nivel 4 veces por segundo
                    onLevel(level(sumSq / counted))
                    sumSq = 0.0
                    counted = 0
                }
            }
            emit(vad.flush())
        } catch (e: Exception) {
            Log.w(TAG, "Captura interrumpida: ${e.javaClass.simpleName}")
            failed = true
        } finally {
            speaking = false
            close(record)
            vad.close()
        }
        if (failed && running) onFailure(this)
    }

    /** Idempotente. */
    private fun close(record: AudioRecord) {
        runCatching { record.unregisterAudioRecordingCallback(watcher) }
        try {
            record.stop()
        } catch (_: IllegalStateException) {
        }
        record.release()
        if (current === record) current = null
    }

    companion object {
        private const val TAG = "AudioCapture"
        const val SR = 16_000
        private const val FRAME = 512

        /** RMS² a 0..1 en escala de -60 a 0 dBFS (en lineal, la voz a un metro sería casi siempre ≈ 0). */
        fun level(meanSquare: Double): Float =
            if (meanSquare <= 0) 0f else ((10 * log10(meanSquare) + 60) / 60).toFloat().coerceIn(0f, 1f)
    }
}
