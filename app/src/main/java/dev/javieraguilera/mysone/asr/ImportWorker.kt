package dev.javieraguilera.mysone.asr

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
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

    private var startedAt: Long? = null // de la conversación creada, para decir en qué día ha quedado
    private var conversationId: Long? = null // para quitarla de la lista si después se borra

    override suspend fun doWork(): Result {
        val uri = inputData.getString(KEY_URI)?.toUri() ?: return Result.failure()
        val c = applicationContext.container
        val wipes = AudioImporter.wipes
        applicationContext.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, applicationContext.getString(R.string.asr_import_channel_name), NotificationManager.IMPORTANCE_LOW),
        )
        showProgress(0f)
        val error = try {
            if (c.transcriber.acquire()) {
                try {
                    import(uri, c, wipes)
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
        return if (error == null) {
            Result.success(workDataOf(KEY_STARTED_AT to startedAt, KEY_CONVERSATION_ID to conversationId))
        } else {
            Result.success(workDataOf(KEY_ERROR to applicationContext.getString(error)))
        }
    }

    /** Devuelve el id del texto de error, o null si se ha creado la conversación. [wipes]: el de AudioImporter al empezar. */
    private suspend fun import(uri: Uri, c: AppContainer, wipes: Int): Int? {
        val pieces = ArrayList<Piece>()
        var total = 0L
        var shownAt = 0L
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
                // Como mucho dos veces por segundo: el sistema descarta las notificaciones más seguidas.
                val now = System.currentTimeMillis()
                if (now - shownAt >= PROGRESS_EVERY_MS) {
                    shownAt = now
                    setProgress(workDataOf(KEY_PROGRESS to progress))
                    showProgress(progress)
                }
            }
            transcribe(vad.flush())
        }
        if (pieces.isEmpty()) return R.string.asr_import_error_no_speech

        val durationMs = total * 1000 / SAMPLE_RATE
        // Así la conversación termina en la fecha de modificación: AudioImporter.alreadyImported la reconoce por ella.
        val startedAt = (AudioImporter.lastModified(applicationContext, uri) ?: System.currentTimeMillis()) - durationMs
        val ms = { sample: Long -> startedAt + sample * 1000 / SAMPLE_RATE }
        // En una transacción: que nadie vea la conversación READY a medio llenar. Y si entretanto se ha hecho
        // "Borrar todo", nada: o se ve aquí, o el borrado viene después y se lleva también esto.
        val id = c.db.withWriteTransaction<Long?> {
            if (AudioImporter.wipes != wipes) return@withWriteTransaction null
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
        } ?: return R.string.asr_import_cancelled
        this.startedAt = startedAt
        conversationId = id
        c.ai.onConversationReady(id)
        return null
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
        const val KEY_STARTED_AT = "startedAt"
        const val KEY_CONVERSATION_ID = "conversationId"
        private const val PROGRESS_EVERY_MS = 500L
        private const val TAG = "MysoneAsr"
        private const val CHANNEL = "import"
        private const val NOTIFICATION_ID = 7_301
    }
}
