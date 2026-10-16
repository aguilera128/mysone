package dev.javieraguilera.mysone.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.javieraguilera.mysone.R
import dev.javieraguilera.mysone.container
import dev.javieraguilera.mysone.core.Time
import dev.javieraguilera.mysone.data.SegmentHit
import dev.javieraguilera.mysone.ui.components.EmptyState
import dev.javieraguilera.mysone.ui.components.SectionHeader
import dev.javieraguilera.mysone.ui.theme.MysoneTheme
import dev.javieraguilera.mysone.ui.today.TimeMargin
import dev.javieraguilera.mysone.ui.today.dayHeading

@Composable
fun SearchScreen(onOpenConversation: (Long, Long?) -> Unit, onBack: () -> Unit) {
    val container = LocalContext.current.container
    val vm = viewModel { SearchViewModel(container) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    SearchContent(
        query = vm.query,
        ui = ui,
        onQueryChange = vm::onQueryChange,
        onOpen = { onOpenConversation(it.conversationId, it.segmentId) },
        onBack = onBack,
    )
}

@Composable
internal fun SearchContent(
    query: String,
    ui: SearchUi,
    onQueryChange: (String) -> Unit,
    onOpen: (SegmentHit) -> Unit,
    onBack: () -> Unit,
    autoFocus: Boolean = true,
) {
    // Mientras corre el debounce o la consulta, lo que se ve es de la consulta anterior.
    val searching = query.isNotBlank() && query != ui.query
    Scaffold(topBar = { SearchField(query, onQueryChange, onBack, autoFocus) }) { inner ->
        Column(Modifier.padding(inner).fillMaxSize()) {
            if (searching) LinearProgressIndicator(Modifier.fillMaxWidth()) else Spacer(Modifier.height(4.dp))
            val groups = ui.groups
            when {
                groups == null -> EmptyState(
                    title = stringResource(R.string.search_idle_title),
                    icon = Icons.Outlined.Search,
                    message = stringResource(R.string.search_idle_body),
                )
                groups.isEmpty() -> EmptyState(
                    title = stringResource(R.string.search_empty_title),
                    icon = Icons.Outlined.SearchOff,
                    message = stringResource(R.string.search_empty_body, ui.query.trim()),
                )
                else -> Results(groups, onOpen)
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit, onBack: () -> Unit, autoFocus: Boolean) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { if (autoFocus && query.isEmpty()) focus.requestFocus() }
    Surface(color = MaterialTheme.colorScheme.background) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.statusBarsPadding().padding(horizontal = 4.dp, vertical = 8.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
            }
            TextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = { Text(stringResource(R.string.search_hint)) },
                singleLine = true,
                shape = MaterialTheme.shapes.extraLarge,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.search_clear))
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                modifier = Modifier.weight(1f).padding(end = 12.dp).focusRequester(focus),
            )
        }
    }
}

@Composable
private fun Results(groups: List<DayGroup>, onOpen: (SegmentHit) -> Unit) {
    val total = groups.sumOf { it.hits.size }
    LazyColumn(Modifier.fillMaxSize()) {
        item(key = "count") {
            Text(
                pluralStringResource(R.plurals.search_results, total, total),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        groups.forEach { group ->
            item(key = "d${group.day}") { SectionHeader(dayHeading(group.day)) }
            items(group.hits, key = { it.hit.segmentId }) { ResultRow(it, onClick = { onOpen(it.hit) }) }
        }
        item(key = "end") { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun ResultRow(item: SearchHitUi, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val (text, marks) = remember(item.hit.snippet) { parseSnippet(item.hit.snippet) }
    // Subrayado de rotulador: el término encontrado en tinta, sobre un fondo apenas teñido.
    val snippet = remember(text, marks, c) {
        buildAnnotatedString {
            append(text)
            marks.forEach { addStyle(SpanStyle(fontWeight = FontWeight.SemiBold, background = c.primaryContainer), it.first, it.last + 1) }
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = stringResource(R.string.search_open), onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        TimeMargin(Time.hourMinute(item.hit.startedAt), Modifier.padding(top = 1.dp))
        Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                item.conversationTitle ?: stringResource(R.string.conv_untitled),
                style = MaterialTheme.typography.labelLarge,
                color = c.onSurfaceVariant,
                maxLines = 1,
            )
            Text(snippet, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

// --- Vistas previas con datos falsos ---

private fun previewUi(): SearchUi {
    val (today, _) = Time.dayBounds(Time.today())
    val h = 3_600_000L
    fun hit(id: Long, at: Long, snippet: String) = SegmentHit(id, id, at, snippet)
    return SearchUi(
        "presupuesto",
        listOf(
            DayGroup(Time.today(), listOf(
                SearchHitUi(hit(1, today + 9 * h, "…entonces el «presupuesto» de la cocina se queda en unos ocho mil…"), "Presupuesto de la reforma con Lucía"),
                SearchHitUi(hit(2, today + 9 * h + 600_000, "…le mando el «presupuesto» al fontanero mañana…"), "Presupuesto de la reforma con Lucía"),
            )),
            DayGroup(Time.dayKey(today - 30 * h), listOf(
                SearchHitUi(hit(3, today - 30 * h, "…no, el «presupuesto» del viaje todavía no lo tenemos…"), null),
            )),
        ),
    )
}

@Preview(name = "Buscar", showBackground = true, heightDp = 640)
@Composable
private fun SearchPreview() {
    MysoneTheme { SearchContent("presupuesto", previewUi(), {}, {}, {}, autoFocus = false) }
}

@Preview(name = "Buscar · sin resultados, oscuro", showBackground = true, heightDp = 640, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SearchEmptyPreview() {
    MysoneTheme(darkTheme = true) { SearchContent("dentista", SearchUi("dentista", emptyList()), {}, {}, {}, autoFocus = false) }
}
