package dev.javieraguilera.mysone.ui.conversation

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.javieraguilera.mysone.R
import dev.javieraguilera.mysone.container
import dev.javieraguilera.mysone.core.Time
import dev.javieraguilera.mysone.data.ConvSource
import dev.javieraguilera.mysone.data.ConvState
import dev.javieraguilera.mysone.data.Conversation
import dev.javieraguilera.mysone.data.Fact
import dev.javieraguilera.mysone.data.FactState
import dev.javieraguilera.mysone.data.Segment
import dev.javieraguilera.mysone.data.TaskItem
import dev.javieraguilera.mysone.data.TaskState
import dev.javieraguilera.mysone.ui.components.BackTopBar
import dev.javieraguilera.mysone.ui.components.ConfirmDialog
import dev.javieraguilera.mysone.ui.components.EmptyState
import dev.javieraguilera.mysone.ui.components.SectionHeader
import dev.javieraguilera.mysone.ui.theme.MysoneTheme
import dev.javieraguilera.mysone.ui.today.Bullet
import dev.javieraguilera.mysone.ui.today.TimeMargin
import dev.javieraguilera.mysone.ui.today.conversationTitle
import dev.javieraguilera.mysone.ui.today.dayHeading
import dev.javieraguilera.mysone.ui.today.dayTime
import dev.javieraguilera.mysone.ui.today.durationLabel
import dev.javieraguilera.mysone.ui.today.joined
import dev.javieraguilera.mysone.ui.today.timeRange
import kotlinx.coroutines.launch

@Composable
fun ConversationScreen(id: Long, segmentId: Long?, onBack: () -> Unit) {
    val context = LocalContext.current
    val container = context.container
    val vm = viewModel { ConversationViewModel(container, id) }
    val state by vm.state.collectAsStateWithLifecycle()
    val aiConfigured by vm.aiConfigured.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val resources = LocalResources.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(message) {
        val res = message ?: return@LaunchedEffect
        snackbar.showSnackbar(resources.getString(res))
        vm.message.value = null
    }

    ConversationContent(
        state = state,
        segmentId = segmentId,
        aiConfigured = aiConfigured,
        snackbar = snackbar,
        onBack = onBack,
        onCopy = {
            vm.copyTranscript()
            // Desde Android 13 el sistema ya confirma la copia; no duplicar el aviso.
            if (Build.VERSION.SDK_INT < 33) scope.launch { snackbar.showSnackbar(resources.getString(R.string.conv_copied)) }
        },
        onReprocess = vm::reprocess,
        onDelete = {
            vm.delete()
            onBack()
        },
        onAcceptTask = vm::acceptTask,
        onDismissTask = vm::dismissTask,
        onConfirmFact = vm::confirmFact,
        onDismissFact = vm::dismissFact,
    )
}

@Composable
internal fun ConversationContent(
    state: ConversationState,
    segmentId: Long?,
    aiConfigured: Boolean,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onCopy: () -> Unit,
    onReprocess: () -> Unit,
    onDelete: () -> Unit,
    onAcceptTask: (TaskItem) -> Unit,
    onDismissTask: (TaskItem) -> Unit,
    onConfirmFact: (Fact) -> Unit,
    onDismissFact: (Fact) -> Unit,
) {
    val loaded = state as? ConversationState.Loaded
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            BackTopBar(
                title = loaded?.let { dayHeading(Time.dayKey(it.conversation.startedAt)) } ?: stringResource(R.string.title_conversation),
                onBack = onBack,
                actions = {
                    if (loaded != null) {
                        Actions(
                            canCopy = loaded.segments.isNotEmpty(),
                            canReprocess = aiConfigured && !loaded.transcribing &&
                                loaded.conversation.state !in setOf(ConvState.OPEN, ConvState.PROCESSING),
                            onCopy = onCopy,
                            onReprocess = onReprocess,
                            onDelete = { confirmDelete = true },
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { inner ->
        when (state) {
            ConversationState.Loading -> Unit
            ConversationState.NotFound -> EmptyState(
                title = stringResource(R.string.conv_not_found),
                modifier = Modifier.padding(inner),
                action = { Button(onClick = onBack) { Text(stringResource(R.string.action_back)) } },
            )
            is ConversationState.Loaded -> Body(
                state, segmentId, aiConfigured, Modifier.padding(inner),
                onAcceptTask, onDismissTask, onConfirmFact, onDismissFact,
            )
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = stringResource(R.string.conv_delete_title),
            message = stringResource(R.string.conv_delete_message),
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = { confirmDelete = false; onDelete() },
            onDismiss = { confirmDelete = false },
            destructive = true,
        )
    }
}

@Composable
private fun Actions(canCopy: Boolean, canReprocess: Boolean, onCopy: () -> Unit, onReprocess: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    IconButton(onClick = onCopy, enabled = canCopy) {
        Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.conv_action_copy))
    }
    IconButton(onClick = { menu = true }) {
        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.conv_more))
    }
    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.conv_action_reprocess)) },
            onClick = { menu = false; onReprocess() },
            enabled = canReprocess,
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.conv_action_delete), color = MaterialTheme.colorScheme.error) },
            onClick = { menu = false; onDelete() },
        )
    }
}

@Composable
private fun Body(
    state: ConversationState.Loaded,
    segmentId: Long?,
    aiConfigured: Boolean,
    modifier: Modifier,
    onAcceptTask: (TaskItem) -> Unit,
    onDismissTask: (TaskItem) -> Unit,
    onConfirmFact: (Fact) -> Unit,
    onDismissFact: (Fact) -> Unit,
) {
    val conv = state.conversation
    val list = rememberLazyListState()
    var scrolled by rememberSaveable { mutableStateOf(false) }
    // Todo lo anterior a la transcripción va en un solo elemento: el segmento i está en la posición i + 1.
    LaunchedEffect(state.segments.size) {
        if (scrolled || segmentId == null) return@LaunchedEffect
        val index = state.segments.indexOfFirst { it.id == segmentId }
        if (index >= 0) {
            list.animateScrollToItem(index + 1)
            scrolled = true
        }
    }

    LazyColumn(state = list, modifier = modifier.fillMaxSize()) {
        item(key = "header") {
            Column {
                Header(conv, state.transcribing)
                Banners(conv, state.transcribing, aiConfigured)
                conv.summary?.let {
                    SectionHeader(stringResource(R.string.conv_summary))
                    Text(it, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 16.dp))
                }
                if (state.keyPoints.isNotEmpty()) {
                    SectionHeader(stringResource(R.string.conv_key_points))
                    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        state.keyPoints.forEach { Bullet(it) }
                    }
                }
                if (state.tasks.isNotEmpty()) {
                    SectionHeader(stringResource(R.string.conv_tasks))
                    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.tasks.forEach { TaskCard(it, onAccept = { onAcceptTask(it) }, onDismiss = { onDismissTask(it) }) }
                    }
                }
                if (state.facts.isNotEmpty()) {
                    SectionHeader(stringResource(R.string.conv_facts))
                    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.facts.forEach { FactCard(it, onConfirm = { onConfirmFact(it) }, onDismiss = { onDismissFact(it) }) }
                    }
                }
                SectionHeader(stringResource(R.string.conv_transcript))
                if (state.segments.isEmpty() && !state.transcribing) {
                    Text(
                        stringResource(R.string.conv_transcript_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
        }
        items(state.segments, key = { it.id }) { SegmentRow(it, cited = it.id == segmentId) }
        item(key = "end") { Spacer(Modifier.height(32.dp)) }
    }
}

@Composable
private fun Header(conv: Conversation, transcribing: Boolean) {
    val open = conv.state == ConvState.OPEN
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            conversationTitle(conv, transcribing),
            style = MaterialTheme.typography.headlineSmall,
            color = if (conv.title == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            joined(
                timeRange(conv.startedAt, if (open) null else conv.endedAt),
                durationLabel(conv.endedAt - conv.startedAt),
                if (conv.source == ConvSource.IMPORT) stringResource(R.string.conv_imported) else null,
            ),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Avisos de estado bajo el título: uno por motivo, en el orden en que importan. */
@Composable
private fun Banners(conv: Conversation, transcribing: Boolean, aiConfigured: Boolean) {
    val c = MaterialTheme.colorScheme
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (transcribing) Banner(Icons.Outlined.GraphicEq, stringResource(R.string.conv_banner_transcribing), c.tertiary)
        when {
            conv.state == ConvState.PROCESSING -> Banner(null, stringResource(R.string.conv_banner_processing), c.primary, busy = true)
            conv.state == ConvState.FAILED -> Banner(
                Icons.Outlined.ErrorOutline,
                conv.lastError?.let { stringResource(R.string.conv_banner_failed, it) } ?: stringResource(R.string.conv_banner_failed_generic),
                c.error,
            )
            conv.summary == null && !aiConfigured && conv.state != ConvState.OPEN && !transcribing ->
                Banner(Icons.Outlined.AutoAwesome, stringResource(R.string.conv_banner_no_ai), c.onSurfaceVariant)
        }
        if (conv.isNoise) Banner(Icons.Outlined.Tv, stringResource(R.string.conv_banner_noise), c.onSurfaceVariant)
    }
}

@Composable
private fun Banner(icon: ImageVector?, text: String, tint: Color, busy: Boolean = false) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = tint, strokeWidth = 2.dp)
            else if (icon != null) Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Ficha de tarea o dato: borde fino sobre el papel, como una nota adhesiva sin color. */
@Composable
private fun DerivedCard(
    icon: ImageVector,
    tint: Color,
    text: String,
    meta: String,
    quote: String?,
    actions: (@Composable () -> Unit)?,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = if (actions == null) 12.dp else 4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp).padding(top = 2.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(text, style = MaterialTheme.typography.bodyLarge)
                    Text(meta, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    quote?.let {
                        Text(
                            stringResource(R.string.conv_quote, it),
                            style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (actions != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) { actions() }
            }
        }
    }
}

@Composable
private fun TaskCard(task: TaskItem, onAccept: () -> Unit, onDismiss: () -> Unit) {
    val reminder = task.remindAt != null
    DerivedCard(
        icon = if (reminder) Icons.Outlined.Alarm else Icons.Outlined.TaskAlt,
        tint = if (reminder) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
        text = task.text,
        meta = joined(
            stringResource(
                when (task.state) {
                    TaskState.SUGGESTED -> R.string.task_suggested
                    TaskState.DONE -> R.string.task_done
                    else -> R.string.task_open
                },
            ),
            task.remindAt?.let { stringResource(R.string.task_remind_at, dayTime(it)) },
            task.dueAt?.let { stringResource(R.string.task_due_at, dayTime(it)) },
        ),
        quote = task.sourceQuote?.takeIf { it.isNotBlank() },
        actions = if (task.state == TaskState.SUGGESTED) {
            {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.task_dismiss)) }
                FilledTonalButton(onClick = onAccept) { Text(stringResource(R.string.task_accept)) }
            }
        } else {
            null
        },
    )
}

@Composable
private fun FactCard(fact: Fact, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val suggested = fact.state == FactState.SUGGESTED
    DerivedCard(
        icon = Icons.Outlined.Lightbulb,
        tint = MaterialTheme.colorScheme.primary,
        text = fact.text,
        meta = stringResource(if (suggested) R.string.fact_suggested else R.string.fact_confirmed),
        quote = null,
        actions = if (suggested) {
            {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.fact_dismiss)) }
                FilledTonalButton(onClick = onConfirm) { Text(stringResource(R.string.fact_confirm)) }
            }
        } else {
            null
        },
    )
}

/** Línea de la transcripción con su hora. La citada lleva una barra de tinta en el margen. */
@Composable
private fun SegmentRow(segment: Segment, cited: Boolean) {
    val c = MaterialTheme.colorScheme
    val citedLabel = stringResource(R.string.conv_cited)
    Row(
        Modifier
            .fillMaxWidth()
            .then(
                if (cited) {
                    Modifier
                        .drawBehind {
                            drawRect(c.surfaceContainer)
                            drawRect(c.primary, topLeft = Offset.Zero, size = Size(3.dp.toPx(), size.height))
                        }
                        .semantics { stateDescription = citedLabel }
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        TimeMargin(Time.hourMinuteSecond(segment.startedAt), Modifier.widthIn(min = 72.dp).padding(top = 2.dp))
        Text(segment.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(start = 8.dp))
    }
}

// --- Vistas previas con datos falsos ---

private fun previewState(): ConversationState.Loaded {
    val (from, _) = Time.dayBounds(Time.today())
    val t0 = from + 9 * 3_600_000L + 5 * 60_000L
    val conv = Conversation(
        id = 1, startedAt = t0, endedAt = t0 + 27 * 60_000L, source = ConvSource.MIC, state = ConvState.DONE,
        title = "Presupuesto de la reforma con Lucía",
        summary = "Repasamos el presupuesto de la cocina. Lucía enviará la oferta del fontanero el viernes y queda pendiente confirmar la fecha de obra.",
    )
    val lines = listOf(
        "Bueno, ¿cómo vamos con lo de la cocina?",
        "Pues el presupuesto se queda en unos ocho mil, más o menos.",
        "Vale. ¿Y el fontanero?",
        "Me manda la oferta el viernes. Recuérdame llamarle si no llega.",
        "Hecho. Y hay que cerrar la fecha de la obra.",
    )
    return ConversationState.Loaded(
        conversation = conv,
        keyPoints = listOf("Presupuesto de unos 8.000 €", "Oferta del fontanero el viernes"),
        segments = lines.mapIndexed { i, text -> Segment(i + 1L, 1, t0 + i * 40_000L, t0 + i * 40_000L + 30_000L, text) },
        tasks = listOf(
            TaskItem(1, "Llamar al fontanero si no llega la oferta", TaskState.OPEN, remindAt = t0 + 4 * 86_400_000L,
                conversationId = 1, sourceQuote = "Recuérdame llamarle si no llega", createdAt = t0),
            TaskItem(2, "Confirmar la fecha de la obra", TaskState.SUGGESTED, conversationId = 1, createdAt = t0),
        ),
        facts = listOf(Fact(1, "Lucía lleva la reforma de la cocina", FactState.SUGGESTED, 1, t0)),
        transcribing = false,
    )
}

@Preview(name = "Conversación", showBackground = true, heightDp = 1400)
@Composable
private fun ConversationPreview() {
    MysoneTheme {
        ConversationContent(previewState(), segmentId = 4, aiConfigured = true, snackbar = remember { SnackbarHostState() },
            onBack = {}, onCopy = {}, onReprocess = {}, onDelete = {}, onAcceptTask = {}, onDismissTask = {}, onConfirmFact = {}, onDismissFact = {})
    }
}

@Preview(name = "Conversación · sin IA, oscuro", showBackground = true, heightDp = 800, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ConversationNoAiPreview() {
    val s = previewState()
    MysoneTheme(darkTheme = true) {
        ConversationContent(
            s.copy(conversation = s.conversation.copy(title = null, summary = null, state = ConvState.READY), keyPoints = emptyList(), tasks = emptyList(), facts = emptyList()),
            segmentId = null, aiConfigured = false, snackbar = remember { SnackbarHostState() },
            onBack = {}, onCopy = {}, onReprocess = {}, onDelete = {}, onAcceptTask = {}, onDismissTask = {}, onConfirmFact = {}, onDismissFact = {},
        )
    }
}
