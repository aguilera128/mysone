package dev.javieraguilera.mysone.tasks

import android.net.Uri
import dev.javieraguilera.mysone.AppContainer

/** Olvidar, borrar, exportar y retención (§6, §10). Lo implementa B4. */
class DataManager(private val container: AppContainer) {
    /** Segmentos, cola pendiente y conversaciones vaciadas. */
    suspend fun forgetLast(minutes: Int) {}

    suspend fun deleteConversation(id: Long) {}

    /** Todo menos la clave. */
    suspend fun exportJson(target: Uri) {}

    /** BD, cola, chat, ajustes de IA y clave; conserva los modelos ASR. */
    suspend fun deleteAll() {}

    suspend fun applyRetention() {}
}
