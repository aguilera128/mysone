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
import dev.javieraguilera.mysone.capture.ListeningController
import dev.javieraguilera.mysone.container
import dev.javieraguilera.mysone.data.ConvSource
import dev.javieraguilera.mysone.data.ConvState
import dev.javieraguilera.mysone.data.Conversation
import dev.javieraguilera.mysone.data.Segment
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/** Decodifica, segmenta con su propio VAD, transcribe y guarda una conversación IMPORT en estado READY (§5.5). */
class ImportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    /** [part]: su número en el [Diarizer] (−1 sin él). */
    private class Piece(val startSample: Long, val endSample: Long, val text: String, val part: Int)

    private var startedAt: Long? = null // de la conversación creada, para decir en qué día ha quedado
    private var conversationId: Long? = null // para quitarla de la lista si después se borra
    private var partial = false // el final del fichero no se pudo leer: se guardó lo de antes
    private var sourceModified: Long? = null // fecha del fichero al empezar: si cambia, no se borra

    override suspend fun doWork(): Result {
        val uri = inputData.getString(KEY_URI)?.toUri() ?: return Result.failure()
        val callToken = inputData.getString(KEY_CALL_TOKEN)
        val call = callToken != null
        val c = applicationContext.container
        // Llamada encolada antes de «Dejar de importar» o de volver a elegir la carpeta: ni se lee ni se borra.
        if (callToken != null && !CallImporter.isCurrent(applicationContext, callToken)) return Result.success(workDataOf(KEY_SKIPPED to true))
        val wipes = AudioImporter.wipes
        applicationContext.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, applicationContext.getString(R.string.asr_import_channel_name), NotificationManager.IMPORTANCE_LOW),
        )
        showProgress(0f)
        val error = try {
            if (c.transcriber.acquire()) {
                try {
                    import(uri, c, wipes, if (call) ConvSource.CALL else ConvSource.IMPORT)
                } finally {
                    c.transcriber.release()
                }
            } else {
                // Llamada: que el próximo escaneo la vuelva a encolar cuando el modelo cargue.
                if (callToken != null) CallImporter.unsee(applicationContext, uri)
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
        // La grabación de una llamada ya transcrita entera sobra: Mysone no guarda audio (opción de Ajustes, mirada
        // ahora: apagarla salva lo que estaba en cola). Si falló o el final estaba dañado se queda.
        if (callToken != null && error == null && !partial && c.settings.current().callDeleteAfter && CallImporter.isCurrent(applicationContext, callToken) &&
            sourceModified != null && AudioImporter.lastModified(applicationContext, uri) == sourceModified
        ) {
            try {
                DocumentsContract.deleteDocument(applicationContext.contentResolver, uri)
            } catch (e: Exception) { // FileNotFoundException, SecurityException, UnsupportedOperationException…
                Log.w(TAG, "No se ha podido borrar la grabación: ${e.javaClass.simpleName}")
            }
        }
        // Los errores esperables salen como éxito con mensaje: un Result.failure haría fallar también
        // las importaciones encoladas detrás (van encadenadas en un único trabajo).
        return if (error == null) {
            Result.success(workDataOf(KEY_STARTED_AT to startedAt, KEY_CONVERSATION_ID to conversationId, KEY_PARTIAL to partial))
        } else {
            Result.success(workDataOf(KEY_ERROR to applicationContext.getString(error)))
        }
    }

    /** Devuelve el id del texto de error, o null si se ha creado la conversación. [wipes]: el de AudioImporter al empezar. */
    private suspend fun import(uri: Uri, c: AppContainer, wipes: Int, source: String): Int? {
        // La fecha, antes de decodificar: si entretanto se suelta el permiso de la carpeta, después ya no se lee.
        val modified = AudioImporter.lastModified(applicationContext, uri)
        sourceModified = modified
        // Lo que dura según la cabecera: el extractor a veces da por terminado un fichero dañado sin lanzar nada.
        val expectedMs = if (source == ConvSource.CALL) AudioImporter.durationMs(applicationContext, uri) else null
        // Una llamada encolada dos veces (o ya importada por otro camino) termina en esa fecha: no se repite.
        if (source == ConvSource.CALL && modified != null && CallImporter.imported(applicationContext, modified)) return R.string.asr_import_already
        val pieces = ArrayList<Piece>()
        var total = 0L
        var shownAt = 0L
        // Quién habla (§5.6), si está el modelo de voces: cada trozo se parte donde cambia la voz antes de transcribirlo.
        var diarizer = Diarizer.open(c.models)
        fun parts(chunk: SpeechChunk): List<Pair<Int, SpeechChunk>> {
            val d = diarizer ?: return listOf(-1 to chunk)
            return try {
                d.split(chunk)
            } catch (e: Exception) { // sin hablantes en todo el fichero, pero el texto se guarda igual
                Log.w(TAG, "Sin hablantes: ${e.javaClass.simpleName}")
                d.close()
                diarizer = null
                listOf(-1 to chunk)
            }
        }
        val speakers = try {
            VadSegmenter(applicationContext).use { vad ->
                suspend fun transcribe(chunks: List<SpeechChunk>) {
                    for ((part, piece) in chunks.flatMap(::parts)) {
                        val text = c.transcriber.transcribe(piece.samples)
                        if (text.isNotEmpty()) pieces += Piece(piece.startSample, piece.startSample + piece.samples.size, text, part)
                    }
                }
                try {
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
                } catch (e: Exception) {
                    // Fichero dañado o cortado a mitad (CodecException, IOException del proveedor): lo ya transcrito se
                    // guarda, que repetir fallaría en el mismo sitio. Sin nada transcrito, el error de siempre.
                    val damaged = (e is IllegalStateException && e !is CancellationException) || e is IOException
                    if (!damaged || total == 0L) throw e
                    Log.w(TAG, "Importación cortada: ${e.javaClass.simpleName}")
                    partial = true
                }
                transcribe(vad.flush())
            }
            diarizer?.let { d ->
                try {
                    d.speakers(OwnerVoice.load(applicationContext, d.dim))
                } catch (e: Exception) { // la voz guardada ilegible: el texto se guarda igual, sin hablantes
                    Log.w(TAG, "Sin hablantes: ${e.javaClass.simpleName}")
                    null
                }
            }
        } finally {
            diarizer?.close()
        }
        if (pieces.isEmpty()) return R.string.asr_import_error_no_speech

        val durationMs = total * 1000 / SAMPLE_RATE
        if (expectedMs != null && durationMs < expectedMs - 2_000) partial = true // se queda la grabación
        // Así la conversación termina en la fecha de modificación: AudioImporter.alreadyImported la reconoce por ella y su duración.
        val startedAt = (modified ?: System.currentTimeMillis()) - durationMs
        val ms = { sample: Long -> startedAt + sample * 1000 / SAMPLE_RATE }
        val who = compactSpeakers(pieces.map { speakers?.getOrNull(it.part) })
        // En una transacción: que nadie vea la conversación READY a medio llenar. Y si entretanto se ha hecho
        // "Borrar todo", nada: o se ve aquí, o el borrado viene después y se lleva también esto.
        val id = c.db.withWriteTransaction<Long?> {
            if (AudioImporter.wipes != wipes) return@withWriteTransaction null
            // «Olvidar los últimos N min» mientras se transcribía (una llamada que acaba de colgarse, sobre todo).
            if (ListeningController.isForgotten(startedAt, startedAt + durationMs)) return@withWriteTransaction null
            val convId = c.db.conversations().insert(
                Conversation(
                    startedAt = startedAt,
                    endedAt = startedAt + durationMs,
                    source = source,
                    state = ConvState.READY,
                ),
            )
            for ((i, p) in pieces.withIndex()) {
                c.db.segments().insert(
                    Segment(conversationId = convId, startedAt = ms(p.startSample), endedAt = ms(p.endSample), text = p.text, speaker = who[i]),
                )
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
        const val KEY_CALL_TOKEN = "callToken"
        const val KEY_SKIPPED = "skipped"
        const val KEY_PROGRESS = "progress"
        const val KEY_ERROR = "error"
        const val KEY_STARTED_AT = "startedAt"
        const val KEY_CONVERSATION_ID = "conversationId"
        const val KEY_PARTIAL = "partial"
        private const val PROGRESS_EVERY_MS = 500L
        private const val TAG = "MysoneAsr"
        private const val CHANNEL = "import"
        private const val NOTIFICATION_ID = 7_301
    }
}
