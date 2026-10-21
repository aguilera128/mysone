package dev.javieraguilera.mysone.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.javieraguilera.mysone.AppContainer
import dev.javieraguilera.mysone.core.AppJson
import dev.javieraguilera.mysone.core.Time
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DigestUi(val summary: String, val highlights: List<String>)

data class TodayUi(val day: String, val items: List<TimelineItem>, val digest: DigestUi?)

/** La fecha de hoy; vuelve a emitir al pasar la medianoche o cambiar la hora o la zona. */
// ponytail: se mira cada minuto mientras se ve la pantalla, en vez de escuchar ACTION_DATE_CHANGED y TIME_SET.
private fun todayFlow(): Flow<String> = flow {
    while (true) {
        emit(Time.today())
        delay(60_000)
    }
}.distinctUntilChanged()

@OptIn(ExperimentalCoroutinesApi::class)
class TodayViewModel(private val container: AppContainer, initialDay: String?) : ViewModel() {
    private val db = container.db

    // null = hoy, sea cual sea: este ViewModel vive días (Hoy es la raíz de la pila y el servicio en primer plano
    // mantiene el proceso), así que una fecha fija dejaría la pantalla en el día anterior tras la medianoche.
    private val chosenDay = MutableStateFlow(initialDay?.takeIf { it != Time.today() })

    /** "yyyy-MM-dd" del día que se está viendo. */
    val selectedDay: StateFlow<String> = combine(chosenDay, todayFlow()) { chosen, today -> chosen ?: today }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initialDay ?: Time.today())

    // Lo que pinta la tarjeta y la línea de tiempo se sigue también sin la pantalla a la vista: con WhileSubscribed, al
    // volver se veía un momento lo de la última vez (p. ej. «Todavía no hay conversaciones hoy» con una importada
    // entretanto) hasta releer la BD.
    // ponytail: consultas pequeñas en cada cambio de la BD mientras vive el ViewModel, también en segundo plano; si
    // pesa, volver a WhileSubscribed y no afirmar nada con lo guardado hasta la primera lectura.
    private val started = SharingStarted.Eagerly

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
    }.stateIn(viewModelScope, started, null)

    val pendingChunks: StateFlow<Int> = db.pendingAudio().count()
        .stateIn(viewModelScope, started, 0)

    /** Intención del usuario: si es true y la escucha está apagada, es que algo la paró. */
    val listeningEnabled: StateFlow<Boolean> = container.settings.data.map { it.listeningEnabled }
        .stateIn(viewModelScope, started, false)

    fun selectDay(day: String) {
        chosenDay.value = day.takeIf { it != Time.today() }
    }

    /** En appScope: el borrado tiene que terminar aunque se cierre la pantalla. */
    fun forget(minutes: Int) {
        container.appScope.launch { container.data.forgetLast(minutes) }
    }
}
