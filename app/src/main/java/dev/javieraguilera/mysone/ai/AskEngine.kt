package dev.javieraguilera.mysone.ai

import dev.javieraguilera.mysone.AppContainer
import dev.javieraguilera.mysone.data.ChatMessage

/**
 * Preguntas sobre lo escuchado (§7.5). Lo implementa B3.
 * Citas en la respuesta: marcadores literales [c:123] (id de conversación). La UI los convierte en chips.
 */
class AskEngine(private val container: AppContainer) {
    /** Guarda pregunta y respuesta en chat_message. */
    suspend fun ask(question: String): Result<ChatMessage> = Result.failure(UnsupportedOperationException())

    suspend fun clearChat() {}
}
