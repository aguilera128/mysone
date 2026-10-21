package dev.javieraguilera.mysone.ui.ask

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.javieraguilera.mysone.AppContainer
import dev.javieraguilera.mysone.data.AiSettings
import dev.javieraguilera.mysone.data.ChatRole
import dev.javieraguilera.mysone.ui.settings.isValidBaseUrl
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Conversación citada en una respuesta (chip "HH:mm · título"). */
data class Citation(val conversationId: Long, val startedAt: Long, val title: String?)

data class AskMessage(val id: Long, val fromUser: Boolean, val text: String, val citations: List<Citation>)

class AskViewModel(private val container: AppContainer) : ViewModel() {

    val messages: StateFlow<List<AskMessage>> = container.db.chat().observeAll()
        .map { list ->
            val parsed = list.map { if (it.role == ChatRole.ASSISTANT) parseCitations(it.text) else CitedText(it.text, emptyList()) }
            val ids = parsed.flatMap { it.ids }.distinct()
            // Las citas de conversaciones ya borradas no se muestran.
            val conversations = if (ids.isEmpty()) emptyMap() else container.db.conversations().byIds(ids).associateBy { it.id }
            list.zip(parsed) { m, p ->
                AskMessage(
                    id = m.id,
                    fromUser = m.role == ChatRole.USER,
                    text = p.text,
                    citations = p.ids.mapNotNull { id -> conversations[id]?.let { Citation(it.id, it.startedAt, it.title) } },
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** null mientras carga. */
    val configured: StateFlow<Boolean?> = container.ai.isConfigured
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Solo está apagada: proveedor, clave y aviso ya están, basta con volver a activarla. */
    val canEnable: StateFlow<Boolean> = combine(container.settings.data, container.secrets.hasApiKey) { s, hasKey ->
        val ai = s.ai
        !ai.enabled && ai.consentAt != null && hasKey && isValidBaseUrl(ai.baseUrl) &&
            ai.modelBackground.isNotBlank() && ai.modelAnswer.isNotBlank()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val ai: StateFlow<AiSettings?> = container.settings.data.map { it.ai }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Pregunta en curso ("pensando"); sigue al cambiar de pestaña (vive en AskEngine). */
    val pending: StateFlow<String?> = container.ask.pending

    val error: StateFlow<AiError?> = container.ask.failure.map { it?.let { f -> classifyAiError(f.error) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        viewModelScope.launch { container.ask.flagInterrupted() }
    }

    fun ask(question: String) = container.ask.submit(question)

    fun retry() = container.ask.retry()

    fun dismissError() = container.ask.dismissFailure()

    fun enableAi() {
        viewModelScope.launch { container.settings.update { it.copy(ai = it.ai.copy(enabled = true)) } }
    }

    fun clearChat() {
        viewModelScope.launch { container.ask.clearChat() }
    }
}
