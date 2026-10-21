package dev.javieraguilera.mysone.ui.search

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.javieraguilera.mysone.AppContainer
import dev.javieraguilera.mysone.core.Time
import dev.javieraguilera.mysone.data.FtsQuery
import dev.javieraguilera.mysone.data.SegmentHit
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn

data class SearchHitUi(val hit: SegmentHit, val conversationTitle: String?)

data class DayGroup(val day: String, val hits: List<SearchHitUi>)

/**
 * [query] es la consulta a la que corresponden [groups]; [groups] null = nada que buscar. [more]: hay más de
 * [LIMIT] y solo están los que más se parecen.
 */
data class SearchUi(val query: String, val groups: List<DayGroup>?, val more: Boolean = false)

private const val LIMIT = 100

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class SearchViewModel(container: AppContainer) : ViewModel() {
    private val db = container.db

    // Estado de Compose y no StateFlow: el campo de texto lo necesita síncrono para no saltar el cursor.
    var query by mutableStateOf("")
        private set

    val ui: StateFlow<SearchUi> = snapshotFlow { query }
        .debounce { if (it.isBlank()) 0L else 300L }
        .mapLatest(::search)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchUi("", null))

    fun onQueryChange(q: String) {
        query = q
    }

    private suspend fun search(q: String): SearchUi {
        val fts = FtsQuery.all(q) ?: return SearchUi(q, null)
        // Uno de más para saber si hay más: si no, "100 resultados" parecería el total y faltarían días sin aviso.
        val found = db.segments().search(fts, 0, Long.MAX_VALUE, LIMIT + 1)
        val hits = found.take(LIMIT)
        val titles = db.conversations().byIds(hits.map { it.conversationId }.distinct()).associate { it.id to it.title }
        val groups = groupByDay(hits).map { (day, dayHits) -> DayGroup(day, dayHits.map { SearchHitUi(it, titles[it.conversationId]) }) }
        return SearchUi(q, groups, more = found.size > LIMIT)
    }
}

/** Resultados por día local, el día más reciente primero y, dentro de cada día, el más reciente arriba. */
fun groupByDay(hits: List<SegmentHit>, zone: ZoneId = Time.zone()): List<Pair<String, List<SegmentHit>>> =
    hits.groupBy { Time.dayKey(it.startedAt, zone) }
        .toSortedMap(reverseOrder())
        .map { (day, list) -> day to list.sortedByDescending { it.startedAt } }

/** Quita las marcas «» que pone snippet() de FTS5 y devuelve el texto limpio y los tramos que iban dentro. */
fun parseSnippet(snippet: String): Pair<String, List<IntRange>> {
    val text = StringBuilder()
    val ranges = mutableListOf<IntRange>()
    var start = -1
    for (ch in snippet) {
        when {
            ch == '«' && start < 0 -> start = text.length
            ch == '»' && start >= 0 -> {
                if (text.length > start) ranges += start until text.length
                start = -1
            }
            else -> text.append(ch)
        }
    }
    return text.toString() to ranges
}
