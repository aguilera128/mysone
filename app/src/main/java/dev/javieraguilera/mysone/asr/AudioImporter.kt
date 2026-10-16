package dev.javieraguilera.mysone.asr

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import dev.javieraguilera.mysone.R
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Importar un fichero de audio como conversación (§5.5). Las importaciones van de una en una. */
object AudioImporter {
    private const val TAG = "import"
    private const val NAME_TAG = "import-name:" // WorkInfo no expone los datos de entrada: el nombre va en una etiqueta

    /** [uri] de SAF (`OpenDocument` con tipo audio). ImportWorker decodifica, pasa el VAD, transcribe y crea la conversación IMPORT → READY. */
    fun enqueue(context: Context, uri: Uri) {
        // El permiso de OpenDocument no sobrevive a la actividad: lo hacemos persistente hasta que acabe el worker.
        try {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
        }
        val name = displayName(context, uri) ?: context.getString(R.string.asr_import_default_name)
        val request = OneTimeWorkRequestBuilder<ImportWorker>()
            .setInputData(workDataOf(ImportWorker.KEY_URI to uri.toString()))
            .addTag(TAG)
            .addTag(NAME_TAG + name)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(TAG, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    fun observe(context: Context): Flow<List<ImportProgress>> =
        WorkManager.getInstance(context).getWorkInfosByTagFlow(TAG).map { infos ->
            infos.map { info ->
                ImportProgress(
                    id = info.id,
                    name = info.tags.firstOrNull { it.startsWith(NAME_TAG) }?.removePrefix(NAME_TAG)
                        ?: context.getString(R.string.asr_import_default_name),
                    progress = if (info.state.isFinished) 1f else info.progress.getFloat(ImportWorker.KEY_PROGRESS, 0f),
                    done = info.state.isFinished,
                    error = when (info.state) {
                        WorkInfo.State.SUCCEEDED -> info.outputData.getString(ImportWorker.KEY_ERROR) // fallo esperable
                        WorkInfo.State.FAILED -> context.getString(R.string.asr_import_error_read)
                        WorkInfo.State.CANCELLED -> context.getString(R.string.asr_import_cancelled)
                        else -> null
                    },
                )
            }
        }

    private fun displayName(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (_: RuntimeException) {
        null
    }
}

data class ImportProgress(val id: UUID, val name: String, val progress: Float, val done: Boolean, val error: String?)
