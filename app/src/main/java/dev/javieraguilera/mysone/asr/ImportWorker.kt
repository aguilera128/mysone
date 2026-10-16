package dev.javieraguilera.mysone.asr

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import androidx.room3.withWriteTransaction
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.javieraguilera.mysone.AppContainer
import dev.javieraguilera.mysone.R
import dev.javieraguilera.mysone.container
import dev.javieraguilera.mysone.data.ConvSource
import dev.javieraguilera.mysone.data.ConvState
import dev.javieraguilera.mysone.data.Conversation
import dev.javieraguilera.mysone.data.Segment
import kotlin.coroutines.cancellation.CancellationException

/** Decodifica, segmenta con su propio VAD, transcribe y guarda una conversación IMPORT en estado READY (§5.5). */
class ImportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private class Piece(val startSample: Long, val endSample: Long, val text: String)

    override suspend fun doWork(): Result {
        val uri = inputData.getString(KEY_URI)?.toUri() ?: return Result.failure()
        val c = applicationContext.container
        applicationContext.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, applicationContext.getString(R.string.asr_import_channel_name), NotificationManager.IMPORTANCE_LOW),
        )
        showProgress(0f)
        val error = try {
            if (c.transcriber.acquire()) {
                try {
                    import(uri, c)
                } finally {
                    c.transcriber.release()
                }
            } else {
                R.string.asr_import_error_no_model
            }
        } catch (e: CancellationException) {
            throw e // detenido: WorkManager puede reintentarlo, así que el permiso del fichero se conserva
        } catch (e: UnsupportedAudioException) {
            R.string.asr_import_error_format
        } catch (e: Exception) { // IOException, CodecException, SecurityException…
            Log.w(TAG, "Importación fallida: ${e.javaClass.simpleName}")
            R.string.asr_import_error_read
        }
        try {
            applicationContext.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
        }
        // Los errores esperables salen como éxito con mensaje: un Result.failure haría fallar también
        // las importaciones encoladas detrás (van encadenadas en un único trabajo).
        return if (error == null) Result.success() else Result.success(workDataOf(KEY_ERROR to applicationContext.getString(error)))
    }

    /** Devuelve el id del texto de error, o null si se ha creado la conversación. */
    private suspend fun import(uri: Uri, c: AppContainer): Int? {
        val pieces = ArrayList<Piece>()
        var total = 0L
        var shown = 0f
        VadSegmenter(applicationContext).use { vad ->
            suspend fun transcribe(chunks: List<SpeechChunk>) {
                for (chunk in chunks) {
                    val text = c.transcriber.transcribe(chunk.samples)
                    if (text.isNotEmpty()) pieces += Piece(chunk.startSample, chunk.startSample + chunk.samples.size, text)
                }
            }
            AudioDecoder.decode(applicationContext, uri) { samples, progress ->
                total += samples.size
                transcribe(vad.accept(samples))
                if (progress - shown >= 0.01f) {
                    shown = progress
                    setProgress(workDataOf(KEY_PROGRESS to progress))
                    showProgress(progress)
                }
            }
            transcribe(vad.flush())
        }
        if (pieces.isEmpty()) return R.string.asr_import_error_no_speech

        val durationMs = total * 1000 / SAMPLE_RATE
        val startedAt = (lastModified(uri) ?: System.currentTimeMillis()) - durationMs
        val ms = { sample: Long -> startedAt + sample * 1000 / SAMPLE_RATE }
        // En una transacción: que nadie vea la conversación READY a medio llenar.
        val id = c.db.withWriteTransaction {
            val convId = c.db.conversations().insert(
                Conversation(
                    startedAt = startedAt,
                    endedAt = startedAt + durationMs,
                    source = ConvSource.IMPORT,
                    state = ConvState.READY,
                ),
            )
            for (p in pieces) {
                c.db.segments().insert(Segment(conversationId = convId, startedAt = ms(p.startSample), endedAt = ms(p.endSample), text = p.text))
            }
            convId
        }
        c.ai.onConversationReady(id)
        return null
    }

    private fun lastModified(uri: Uri): Long? = try {
        applicationContext.contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null)
            ?.use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0).takeIf { ms -> ms > 0 } else null }
    } catch (_: RuntimeException) {
        null
    }

    /** Notificación del servicio en primer plano (dataSync). Si el sistema no deja, sigue sin ella. */
    private suspend fun showProgress(progress: Float) {
        val ctx = applicationContext
        val notification = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_mysone)
            .setContentTitle(ctx.getString(R.string.asr_import_notification_title))
            .setProgress(100, (progress * 100).toInt(), progress <= 0f)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
        try {
            setForeground(ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC))
        } catch (e: IllegalStateException) { // ForegroundServiceStartNotAllowedException desde segundo plano
            Log.w(TAG, "Sin primer plano: ${e.javaClass.simpleName}")
        }
    }

    companion object {
        const val KEY_URI = "uri"
        const val KEY_PROGRESS = "progress"
        const val KEY_ERROR = "error"
        private const val TAG = "MysoneAsr"
        private const val CHANNEL = "import"
        private const val NOTIFICATION_ID = 7_301
    }
}
