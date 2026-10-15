package dev.javieraguilera.mysone.ai

import dev.javieraguilera.mysone.AppContainer
import dev.javieraguilera.mysone.data.Segment
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** Procesado de conversaciones con IA (§7.3–7.6). Lo implementa B3. */
class AiProcessing(private val container: AppContainer) {
    /** enabled + consentAt + clave. */
    val isConfigured: Flow<Boolean> = flowOf(false)

    /** No bloquea; recordatorio rápido si procede. */
    fun onSegmentTranscribed(segment: Segment) {}

    /** No bloquea; procesa en appScope o encola ProcessWorker. */
    fun onConversationReady(conversationId: Long) {}

    suspend fun processConversation(id: Long, force: Boolean = false): Result<Unit> =
        Result.failure(UnsupportedOperationException())

    /** READY sin audio pendiente; devuelve cuántas. */
    suspend fun processPending(): Int = 0

    suspend fun buildMissingDigests(): Int = 0

    /** Para Ajustes. */
    suspend fun testConnection(): Result<String> = Result.failure(UnsupportedOperationException())
}
