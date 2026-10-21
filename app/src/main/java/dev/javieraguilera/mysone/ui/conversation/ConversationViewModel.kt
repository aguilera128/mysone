package dev.javieraguilera.mysone.ui.conversation

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.os.PersistableBundle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.javieraguilera.mysone.AppContainer
import dev.javieraguilera.mysone.R
import dev.javieraguilera.mysone.core.AppJson
import dev.javieraguilera.mysone.core.Time
import dev.javieraguilera.mysone.data.Conversation
import dev.javieraguilera.mysone.data.Fact
import dev.javieraguilera.mysone.data.FactState
import dev.javieraguilera.mysone.data.Segment
import dev.javieraguilera.mysone.data.TaskItem
import dev.javieraguilera.mysone.data.TaskState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface ConversationState {
    data object Loading : ConversationState
    data object NotFound : ConversationState
    data class Loaded(
        val conversation: Conversation,
        val keyPoints: List<String>,
        val segments: List<Segment>,
        val tasks: List<TaskItem>, // sin las descartadas
        val facts: List<Fact>,
        val transcribing: Boolean,
    ) : ConversationState
}

class ConversationViewModel(private val container: AppContainer, private val id: Long) : ViewModel() {
    private val db = container.db

    val state: StateFlow<ConversationState> = combine(
        db.conversations().observe(id),
        db.segments().forConversation(id),
        db.tasks().observeForConversation(id),
        db.facts().observeForConversation(id),
        db.pendingAudio().observeConversationIds().map { id in it }.distinctUntilChanged(),
    ) { conversation, segments, tasks, facts, transcribing ->
        if (conversation == null) {
            ConversationState.NotFound
        } else {
            ConversationState.Loaded(
                conversation = conversation,
                keyPoints = conversation.keyPoints
                    ?.let { runCatching { AppJson.decodeFromString<List<String>>(it) }.getOrNull() }
                    .orEmpty(),
                segments = segments,
                tasks = tasks.filter { it.state != TaskState.DISMISSED },
                facts = facts,
                transcribing = transcribing,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConversationState.Loading)

    val aiConfigured: StateFlow<Boolean> = container.ai.isConfigured
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Aviso puntual (id de cadena) para la barra inferior; la UI lo pone a null al mostrarlo. */
    val message = MutableStateFlow<Int?>(null)

    // Las escrituras van en appScope para que terminen aunque se cierre la pantalla.

    /** Solo el estado, y la alarma con la fila de ahora: no se pisa lo que haya cambiado desde que se pintó. */
    fun acceptTask(task: TaskItem) {
        container.appScope.launch {
            db.tasks().setState(task.id, TaskState.OPEN)
            db.tasks().get(task.id)?.let { container.reminders.schedule(it) }
        }
    }

    fun dismissTask(task: TaskItem) {
        container.appScope.launch {
            db.tasks().setState(task.id, TaskState.DISMISSED)
            container.reminders.cancel(task.id)
        }
    }

    fun confirmFact(fact: Fact) {
        container.appScope.launch { db.facts().setState(fact.id, FactState.CONFIRMED) }
    }

    fun dismissFact(fact: Fact) {
        container.appScope.launch { db.facts().delete(fact.id) }
    }

    fun reprocess() {
        container.appScope.launch {
            if (container.ai.processConversation(id, force = true).isFailure) message.value = R.string.conv_reprocess_failed
        }
    }

    fun delete() {
        container.appScope.launch { container.data.deleteConversation(id) }
    }

    /** Copia la transcripción con sus horas, marcada como sensible para que Android no la muestre en la vista previa. */
    fun copyTranscript() {
        val loaded = state.value as? ConversationState.Loaded ?: return
        val text = buildString {
            loaded.conversation.title?.let { appendLine(it) }
            loaded.segments.forEach { appendLine("[${Time.hourMinuteSecond(it.startedAt)}] ${it.text}") }
        }.trimEnd()
        val clip = ClipData.newPlainText(container.app.getString(R.string.app_name), text)
        // Antes de Android 13 la constante no existe, pero teclados y portapapeles leen la misma clave literal.
        @SuppressLint("InlinedApi")
        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        container.app.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
    }
}
