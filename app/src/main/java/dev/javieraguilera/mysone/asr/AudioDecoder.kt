package dev.javieraguilera.mysone.asr

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** El fichero no tiene pista de audio o no hay decodificador para ella. */
class UnsupportedAudioException : Exception()

/** MediaExtractor + MediaCodec → PCM → mono → 16 kHz, por bloques para no cargar horas de audio en memoria. */
object AudioDecoder {
    private const val TIMEOUT_US = 10_000L

    /**
     * Llama a [onSamples] con cada bloque (float mono 16 kHz) y el progreso aproximado (0..1).
     * Lanza [UnsupportedAudioException] si no se puede decodificar, IOException si no se puede leer.
     */
    suspend fun decode(context: Context, uri: Uri, onSamples: suspend (FloatArray, Float) -> Unit) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw UnsupportedAudioException()
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            codec = try {
                MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            } catch (_: Exception) {
                throw UnsupportedAudioException()
            }
            try {
                codec.configure(format, null, null, 0)
                codec.start()
            } catch (_: Exception) {
                throw UnsupportedAudioException()
            }

            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var isFloat = false
            var resampler = Resampler(rate)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            while (true) {
                currentCoroutineContext().ensureActive()
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (i >= 0) {
                        val n = extractor.readSampleData(codec.getInputBuffer(i)!!, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(i, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val out = codec.outputFormat
                    channels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    val encoding = if (out.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        out.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    } else {
                        AudioFormat.ENCODING_PCM_16BIT
                    }
                    if (encoding != AudioFormat.ENCODING_PCM_16BIT && encoding != AudioFormat.ENCODING_PCM_FLOAT) {
                        throw UnsupportedAudioException()
                    }
                    isFloat = encoding == AudioFormat.ENCODING_PCM_FLOAT
                    val newRate = out.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    if (newRate != rate) {
                        rate = newRate
                        resampler = Resampler(rate)
                    }
                } else if (o >= 0) {
                    val mono = toMono(codec.getOutputBuffer(o)!!, info, channels, isFloat)
                    codec.releaseOutputBuffer(o, false) // antes de transcribir: no retener el búfer del códec
                    if (mono.isNotEmpty()) {
                        val progress = if (durationUs > 0) (info.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f) else 0f
                        onSamples(resampler.process(mono), progress)
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        } finally {
            codec?.let {
                try {
                    it.stop()
                } catch (_: IllegalStateException) {
                }
                it.release()
            }
            extractor.release()
        }
    }

    /** Media de los canales intercalados. */
    private fun toMono(buf: ByteBuffer, info: MediaCodec.BufferInfo, channels: Int, isFloat: Boolean): FloatArray {
        buf.position(info.offset)
        buf.limit(info.offset + info.size)
        buf.order(ByteOrder.nativeOrder())
        return if (isFloat) {
            val f = buf.asFloatBuffer()
            FloatArray(f.remaining() / channels) { i ->
                var sum = 0f
                for (c in 0 until channels) sum += f.get(i * channels + c)
                sum / channels
            }
        } else {
            val s = buf.asShortBuffer()
            FloatArray(s.remaining() / channels) { i ->
                var sum = 0
                for (c in 0 until channels) sum += s.get(i * channels + c)
                sum / (32768f * channels)
            }
        }
    }
}
