package dev.javieraguilera.mysone.ui.ask

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.javieraguilera.mysone.AppContainer
import dev.javieraguilera.mysone.data.AiSettings
import dev.javieraguilera.mysone.data.ChatRole
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    val ai: StateFlow<AiSettings?> = container.settings.data.map { it.ai }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _pending = MutableStateFlow<String?>(null)

    /** Pregunta en curso ("pensando"). */
    val pending: StateFlow<String?> = _pending.asStateFlow()

    private val _error = MutableStateFlow<AiError?>(null)
    val error: StateFlow<AiError?> = _error.asStateFlow()

    private var lastFailed: String? = null

    fun ask(question: String) {
        val q = question.trim()
        if (q.isEmpty() || _pending.value != null) return
        _error.value = null
        _pending.value = q
        // En appScope: si cambias de pestaña a mitad, la respuesta se guarda igual en el chat.
        container.appScope.launch {
            container.ask.ask(q)
                .onSuccess { lastFailed = null }
                .onFailure {
                    lastFailed = q
                    _error.value = classifyAiError(it)
                }
            _pending.value = null
        }
    }

    fun retry() {
        lastFailed?.let(::ask)
    }

    fun dismissError() {
        _error.value = null
    }

    fun clearChat() {
        viewModelScope.launch { container.ask.clearChat() }
    }
}
