package dev.javieraguilera.mysone.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.javieraguilera.mysone.AppContainer
import dev.javieraguilera.mysone.core.AppJson
import dev.javieraguilera.mysone.core.Time
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DigestUi(val summary: String, val highlights: List<String>)

data class TodayUi(val day: String, val items: List<TimelineItem>, val digest: DigestUi?)

@OptIn(ExperimentalCoroutinesApi::class)
class TodayViewModel(private val container: AppContainer, initialDay: String?) : ViewModel() {
    private val db = container.db

    /** "yyyy-MM-dd" del día que se está viendo. */
    val selectedDay = MutableStateFlow(initialDay ?: Time.today())

    /** null mientras carga el día. */
    val ui: StateFlow<TodayUi?> = selectedDay.flatMapLatest { day ->
        val (from, to) = Time.dayBounds(day)
        combine(
            db.conversations().observeDay(from, to),
            db.gaps().observeRange(from, to),
            db.tasks().observeInDay(from, to),
            db.pendingAudio().observeConversationIds(),
            db.digests().observe(day),
        ) { conversations, gaps, tasks, transcribing, digest ->
            TodayUi(
                day = day,
                items = buildTimeline(conversations, gaps, tasks, transcribing),
                digest = digest?.let {
                    DigestUi(it.summary, runCatching { AppJson.decodeFromString<List<String>>(it.highlights) }.getOrDefault(emptyList()))
                },
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val pendingChunks: StateFlow<Int> = db.pendingAudio().count()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** Intención del usuario: si es true y la escucha está apagada, es que algo la paró. */
    val listeningEnabled: StateFlow<Boolean> = container.settings.data.map { it.listeningEnabled }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun selectDay(day: String) {
        selectedDay.value = day
    }

    /** En appScope: el borrado tiene que terminar aunque se cierre la pantalla. */
    fun forget(minutes: Int) {
        container.appScope.launch { container.data.forgetLast(minutes) }
    }
}
