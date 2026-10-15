package dev.javieraguilera.mysone.asr

import android.content.Context
import android.net.Uri
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** Importar un fichero de audio como conversación (§5.5). Lo implementa B2. */
object AudioImporter {
    /** Copia/lee con SAF; ImportWorker decodifica, pasa el VAD, transcribe y crea la conversación IMPORT → READY. */
    fun enqueue(context: Context, uri: Uri) {}

    fun observe(context: Context): Flow<List<ImportProgress>> = flowOf(emptyList())
}

data class ImportProgress(val id: UUID, val name: String, val progress: Float, val done: Boolean, val error: String?)
