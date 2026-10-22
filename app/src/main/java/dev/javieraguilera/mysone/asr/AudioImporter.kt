package dev.javieraguilera.mysone.asr

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.core.content.edit
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import dev.javieraguilera.mysone.R
import dev.javieraguilera.mysone.container
import dev.javieraguilera.mysone.data.Conversation
import java.util.UUID
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext

/** Importar un fichero de audio como conversación (§5.5). Las importaciones van de una en una. */
object AudioImporter {
    private const val TAG = "import"

    // Nombre y hora de cada importación, por id de trabajo. No van en las etiquetas del WorkRequest:
    // WorkManager las escribe en logcat y el nombre de un fichero puede decir de qué va (§10).
    private const val PREFS = "imports"
    private const val STALE_MS = 3_600_000L

    /**
     * [uri] de SAF (`OpenDocument` con tipo audio, o un documento de la carpeta de llamadas). ImportWorker decodifica,
     * pasa el VAD, transcribe y crea la conversación IMPORT (o CALL, con el [callToken] de la carpeta elegida) → READY.
     */
    fun enqueue(context: Context, uri: Uri, callToken: String? = null): Operation {
        // El permiso de OpenDocument no sobrevive a la actividad: lo hacemos persistente hasta que acabe el worker.
        // (Los de la carpeta de llamadas se leen con el permiso del árbol: aquí da SecurityException y no pasa nada.)
        try {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
        }
        val name = displayName(context, uri) ?: context.getString(R.string.asr_import_default_name)
        val request = OneTimeWorkRequestBuilder<ImportWorker>()
            .setInputData(workDataOf(ImportWorker.KEY_URI to uri.toString(), ImportWorker.KEY_CALL_TOKEN to callToken))
            .addTag(TAG)
            .build()
        prefs(context).edit { putString(request.id.toString(), "${System.currentTimeMillis()}|$name") }
        return WorkManager.getInstance(context).enqueueUniqueWork(TAG, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    /**
     * Inicio de la conversación que ya se importó de este fichero, o null. Se reconoce porque termina justo en su
     * fecha de modificación (ImportWorker) y dura lo mismo: la fecha va en segundos y una copia en lote se la da igual
     * a varios ficheros. Sin las dos no se puede saber. Si se borró, se puede importar otra vez.
     */
    suspend fun alreadyImported(context: Context, uri: Uri): Long? {
        val (modified, duration) = withContext(Dispatchers.IO) { lastModified(context, uri) to durationMs(context, uri) }
        if (modified == null || duration == null) return null
        return sameAudio(context.container.db.conversations().importsEndingAt(modified), duration)?.startedAt
    }

    /** De la más antigua a la más reciente, sin las que dieron una conversación que ya se ha borrado u olvidado. */
    fun observe(context: Context): Flow<List<ImportProgress>> =
        combine(
            WorkManager.getInstance(context).getWorkInfosByTagFlow(TAG),
            context.container.db.conversations().observeImportIds(),
        ) { all, alive ->
            val existing = alive.toSet()
            // Fuera también las llamadas de una carpeta que ya no está elegida: no se han tocado.
            val infos = all.filter { info -> !info.outputData.getBoolean(ImportWorker.KEY_SKIPPED, false) && info.outputData.getLong(ImportWorker.KEY_CONVERSATION_ID, 0L).let { it == 0L || it in existing } }
            val prefs = prefs(context)
            val saved = prefs.all
            // WorkManager acaba olvidando los trabajos terminados: sus nombres sobran, igual que los de conversaciones
            // borradas. Con margen, por si el nombre se ha guardado y el trabajo aún no está en la lista.
            val ids = infos.map { it.id.toString() }.toSet()
            val old = System.currentTimeMillis() - STALE_MS
            val stale = saved.filter { (k, v) -> k !in ids && ((v as? String)?.substringBefore('|')?.toLongOrNull() ?: 0L) < old }.keys
            if (stale.isNotEmpty()) prefs.edit { stale.forEach(::remove) }
            infos.map { info ->
                val (at, name) = (saved[info.id.toString()] as? String)?.split('|', limit = 2)
                    ?.let { it[0].toLongOrNull() to it.getOrNull(1) } ?: (null to null)
                at to ImportProgress(
                    id = info.id,
                    name = name ?: context.getString(R.string.asr_import_default_name),
                    progress = if (info.state.isFinished) 1f else info.progress.getFloat(ImportWorker.KEY_PROGRESS, 0f),
                    done = info.state.isFinished,
                    error = when (info.state) {
                        WorkInfo.State.SUCCEEDED -> info.outputData.getString(ImportWorker.KEY_ERROR) // fallo esperable
                        WorkInfo.State.FAILED -> context.getString(R.string.asr_import_error_read)
                        WorkInfo.State.CANCELLED -> context.getString(R.string.asr_import_cancelled)
                        else -> null
                    },
                    startedAt = info.outputData.getLong(ImportWorker.KEY_STARTED_AT, 0L).takeIf { it > 0 },
                    partial = info.outputData.getBoolean(ImportWorker.KEY_PARTIAL, false),
                )
            }.sortedBy { it.first ?: 0L }.map { it.second }
        }

    /** Veces que se ha hecho "Borrar todo" en este proceso: ImportWorker no guarda lo que empezó antes. */
    @Volatile internal var wipes = 0
        private set

    /**
     * "Borrar todo", antes de vaciar la BD: cancela las importaciones en marcha y en cola y suelta el permiso de
     * leer sus ficheros. La que esté a punto de guardar lo ve en [wipes] dentro de su transacción.
     */
    suspend fun cancelAll(context: Context) {
        wipes++
        withContext(Dispatchers.IO) { WorkManager.getInstance(context).cancelUniqueWork(TAG).result.get() }
        // Solo las importaciones piden permisos persistentes (y la carpeta de llamadas, que además tiene el de borrar).
        context.contentResolver.persistedUriPermissions.forEach {
            val flags = (if (it.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
                (if (it.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
            try {
                context.contentResolver.releasePersistableUriPermission(it.uri, flags)
            } catch (_: SecurityException) {
            }
        }
    }

    /** "Borrar todo": fuera la lista de importaciones terminadas y sus nombres de fichero. */
    fun forgetHistory(context: Context) {
        WorkManager.getInstance(context).pruneWork()
        prefs(context).edit { clear() }
    }

    private fun prefs(context: Context) =context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    internal fun lastModified(context: Context, uri: Uri): Long? = try {
        context.contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null)
            ?.use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0).takeIf { ms -> ms > 0 } else null }
    } catch (_: RuntimeException) {
        null
    }

    internal fun durationMs(context: Context, uri: Uri): Long? = try {
        MediaMetadataRetriever().use {
            it.setDataSource(context, uri)
            it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.takeIf { ms -> ms > 0 }
        }
    } catch (_: Exception) { // IllegalArgumentException, RuntimeException del extractor, IOException al cerrar
        null
    }

    private fun displayName(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (_: RuntimeException) {
        null
    }
}

/**
 * La de [candidates] (importadas con la misma fecha de modificación) que dura [durationMs], la del fichero. La
 * conversación dura lo decodificado y la cabecera puede redondear o estimar (MP3 sin índice): 1 s de margen.
 */
// ponytail: fecha + duración, sin nombre ni tamaño (no se guardan); dos audios del mismo segundo y casi igual de largos se confunden.
internal fun sameAudio(candidates: List<Conversation>, durationMs: Long): Conversation? =
    candidates.firstOrNull { abs(it.endedAt - it.startedAt - durationMs) <= 1_000 }

/**
 * [startedAt]: inicio de la conversación creada (para decir en qué día ha quedado), si ya ha terminado bien.
 * [partial]: el final del fichero estaba dañado y solo se ha guardado lo anterior.
 */
data class ImportProgress(
    val id: UUID,
    val name: String,
    val progress: Float,
    val done: Boolean,
    val error: String?,
    val startedAt: Long? = null,
    val partial: Boolean = false,
)
