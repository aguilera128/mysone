@file:OptIn(ExperimentalMaterial3Api::class)

package dev.javieraguilera.mysone.ui.tasks

import android.text.format.DateFormat
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.AlarmAdd
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDialog
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.javieraguilera.mysone.R
import dev.javieraguilera.mysone.container
import dev.javieraguilera.mysone.core.Time
import dev.javieraguilera.mysone.data.TaskItem
import dev.javieraguilera.mysone.data.TaskState
import dev.javieraguilera.mysone.tasks.ReminderReceiver
import dev.javieraguilera.mysone.ui.components.EmptyState
import dev.javieraguilera.mysone.ui.components.jsonSaver
import dev.javieraguilera.mysone.ui.settings.Note
import dev.javieraguilera.mysone.ui.theme.MysoneTheme
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun TasksScreen(highlightTaskId: Long?, onOpenConversation: (Long, Long?) -> Unit) {
    val container = LocalContext.current.container
    val vm = viewModel { TasksViewModel(container) }
    val pending by vm.pending.collectAsStateWithLifecycle()
    val suggested by vm.suggested.collectAsStateWithLifecycle()
    val done by vm.done.collectAsStateWithLifecycle()
    // Sin notificaciones los recordatorios no se ven (ni se dan por avisados): se mira al volver a la pantalla.
    val context = LocalContext.current
    var notificationsOff by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        notificationsOff = !ReminderReceiver.canNotify(context)
        onPauseOrDispose { }
    }
    TasksContent(
        lists = listOf(pending, suggested, done),
        highlightTaskId = highlightTaskId,
        notificationsOff = notificationsOff,
        actions = TaskActions(
            accept = vm::accept,
            dismiss = vm::dismiss,
            setDone = vm::setDone,
            edit = vm::edit,
            create = vm::create,
            delete = vm::delete,
            restore = vm::restore,
            openConversation = { vm.openConversation(it, onOpenConversation) },
        ),
    )
}

class TaskActions(
    val accept: (TaskItem) -> Unit = {},
    val dismiss: (TaskItem) -> Unit = {},
    val setDone: (TaskItem, Boolean) -> Unit = { _, _ -> },
    val edit: (TaskItem, String, Long?) -> Unit = { _, _, _ -> },
    val create: (String, Long?) -> Unit = { _, _ -> },
    val delete: (TaskItem) -> Unit = {},
    val restore: (TaskItem) -> Unit = {},
    val openConversation: (TaskItem) -> Unit = {},
)

private val TABS = listOf(R.string.tasks_tab_pending, R.string.tasks_tab_suggested, R.string.tasks_tab_done)

/** [lists]: pendientes, sugeridas y hechas, en el orden de las pestañas. */
@Composable
private fun TasksContent(lists: List<List<TaskItem>>, highlightTaskId: Long?, actions: TaskActions, notificationsOff: Boolean = false) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val listStates = List(TABS.size) { rememberLazyListState() }
    var highlight by remember { mutableStateOf(highlightTaskId) }
    // La tarea tal como estaba al abrir el diálogo: TasksViewModel.edit la usa para no pisar lo cambiado mientras.
    var editing by rememberSaveable(saver = jsonSaver<TaskItem>()) { mutableStateOf(null) }
    var creating by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val deletedMsg = stringResource(R.string.tasks_deleted)
    val undo = stringResource(R.string.tasks_undo)

    // Al venir de una notificación: pestaña de la tarea, scroll hasta ella y resalte unos segundos.
    LaunchedEffect(highlight, lists) {
        val id = highlight ?: return@LaunchedEffect
        val t = lists.indexOfFirst { l -> l.any { it.id == id } }
        if (t < 0) return@LaunchedEffect
        tab = t
        listStates[t].animateScrollToItem(lists[t].indexOfFirst { it.id == id })
        delay(4_000)
        highlight = null
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.nav_tasks)) }) },
        floatingActionButton = {
            FloatingActionButton(onClick = { creating = true }) {
                Icon(Icons.Outlined.AlarmAdd, contentDescription = stringResource(R.string.tasks_new))
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            PrimaryTabRow(selectedTabIndex = tab) {
                TABS.forEachIndexed { i, label ->
                    Tab(
                        selected = tab == i,
                        onClick = { tab = i },
                        text = {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(label))
                                if (lists[i].isNotEmpty()) {
                                    Text(
                                        lists[i].size.toString(),
                                        style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        },
                    )
                }
            }
            if (notificationsOff) {
                Note(
                    stringResource(R.string.tasks_notifications_off),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    icon = Icons.Outlined.NotificationsOff,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            val items = lists[tab]
            if (items.isEmpty()) {
                TabEmpty(tab)
            } else {
                TaskList(items, listStates[tab], highlight, actions, onEdit = { editing = it })
            }
        }
    }

    val delete: (TaskItem) -> Unit = { t ->
        editing = null
        actions.delete(t)
        scope.launch {
            if (snackbar.showSnackbar(deletedMsg, undo, duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) {
                actions.restore(t)
            }
        }
    }
    editing?.let { t ->
        TaskDialog(
            initial = t,
            current = lists.firstNotNullOfOrNull { l -> l.find { it.id == t.id } } ?: t,
            onSave = { text, remindAt ->
                editing = null
                actions.edit(t, text, remindAt)
            },
            onDelete = { delete(t) },
            onDismiss = { editing = null },
        )
    }
    if (creating) {
        TaskDialog(
            initial = null,
            onSave = { text, remindAt ->
                creating = false
                actions.create(text, remindAt)
            },
            onDelete = null,
            onDismiss = { creating = false },
        )
    }
}

@Composable
private fun TabEmpty(tab: Int) {
    val (title, message, icon) = when (tab) {
        0 -> Triple(R.string.tasks_empty_pending_title, R.string.tasks_empty_pending, Icons.Outlined.TaskAlt)
        1 -> Triple(R.string.tasks_empty_suggested_title, R.string.tasks_empty_suggested, Icons.Outlined.AutoAwesome)
        else -> Triple(R.string.tasks_empty_done_title, R.string.tasks_empty_done, Icons.Outlined.TaskAlt)
    }
    EmptyState(title = stringResource(title), message = stringResource(message), icon = icon)
}

@Composable
private fun TaskList(
    items: List<TaskItem>,
    state: LazyListState,
    highlight: Long?,
    actions: TaskActions,
    onEdit: (TaskItem) -> Unit,
) {
    LazyColumn(state = state, contentPadding = PaddingValues(bottom = 96.dp), modifier = Modifier.fillMaxSize()) {
        items(items, key = { it.id }) { t ->
            TaskRow(t, highlighted = t.id == highlight, actions = actions, onEdit = { onEdit(t) })
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun TaskRow(t: TaskItem, highlighted: Boolean, actions: TaskActions, onEdit: () -> Unit) {
    val bg by animateColorAsState(
        if (highlighted) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        label = "resalte",
    )
    val isDone = t.state == TaskState.DONE
    Row(
        Modifier
            .fillMaxWidth()
            .background(bg)
            .clickable(onClickLabel = stringResource(R.string.action_edit), onClick = onEdit)
            .padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (t.state == TaskState.SUGGESTED) {
            Icon(
                Icons.Outlined.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(12.dp).size(24.dp),
            )
        } else {
            IconButton(onClick = { actions.setDone(t, !isDone) }) {
                Icon(
                    if (isDone) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                    contentDescription = stringResource(if (isDone) R.string.tasks_mark_open else R.string.tasks_mark_done),
                    tint = if (isDone) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Column(Modifier.weight(1f).padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                t.text,
                style = MaterialTheme.typography.bodyLarge,
                textDecoration = if (isDone) TextDecoration.LineThrough else null,
                color = if (isDone) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            if (t.remindAt != null || t.dueAt != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    t.remindAt?.let { ReminderPill(it, ringing = t.state == TaskState.OPEN) }
                    t.dueAt?.let { DuePill(it) }
                }
            }
            t.sourceQuote?.takeIf { it.isNotBlank() }?.let {
                Text(
                    stringResource(R.string.tasks_quote, it),
                    style = MaterialTheme.typography.bodySmall,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (t.state == TaskState.SUGGESTED) {
                    FilledTonalButton(onClick = { actions.accept(t) }) { Text(stringResource(R.string.tasks_accept)) }
                    TextButton(onClick = { actions.dismiss(t) }) { Text(stringResource(R.string.tasks_dismiss)) }
                }
                if (t.conversationId != null) {
                    TextButton(onClick = { actions.openConversation(t) }) { Text(stringResource(R.string.tasks_open_conversation)) }
                }
            }
        }
    }
}

/** Recordatorio en ámbar (el color de los avisos); atenuado si ya pasó o la tarea no está abierta. */
@Composable
private fun ReminderPill(at: Long, ringing: Boolean) {
    val active = ringing && at > System.currentTimeMillis()
    val label = whenLabel(at)
    Pill(
        icon = Icons.Outlined.Alarm,
        text = label,
        description = stringResource(R.string.tasks_reminder_cd, label),
        container = if (active) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        content = if (active) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun DuePill(at: Long) {
    val label = whenLabel(at)
    Pill(
        icon = Icons.Outlined.Event,
        text = label,
        description = stringResource(R.string.tasks_due_cd, label),
        container = MaterialTheme.colorScheme.surfaceContainer,
        content = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun Pill(icon: ImageVector, text: String, description: String, container: Color, content: Color) {
    Surface(color = container, contentColor = content, shape = MaterialTheme.shapes.small, modifier = Modifier.clearAndSetSemantics { contentDescription = description }) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp))
            Text(text, style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"))
        }
    }
}

/** "Hoy, 09:45", "Mañana, 09:45", "Ayer, 09:45" o "jue 16 oct, 09:45". */
@Composable
private fun whenLabel(ms: Long): String {
    val today = LocalDate.now(Time.zone())
    val time = Time.hourMinute(ms)
    return when (Time.dayKey(ms)) {
        today.toString() -> stringResource(R.string.tasks_today_at, time)
        today.plusDays(1).toString() -> stringResource(R.string.tasks_tomorrow_at, time)
        today.minusDays(1).toString() -> stringResource(R.string.tasks_yesterday_at, time)
        else -> stringResource(R.string.tasks_day_at, Time.dayShort(ms), time)
    }
}

/** Solo se pueden elegir días desde hoy. */
private object FromToday : SelectableDates {
    override fun isSelectableDate(utcTimeMillis: Long) = !pickerLocalDate(utcTimeMillis).isBefore(LocalDate.now(Time.zone()))

    override fun isSelectableYear(year: Int) = year >= LocalDate.now(Time.zone()).year
}

private const val STEP_NONE = 0
private const val STEP_DATE = 1
private const val STEP_TIME = 2

/**
 * Crear ([initial] null) o editar una tarea: texto y recordatorio con los pickers de Material 3. [current]: la tarea
 * como está ahora; mientras no se toque el aviso se enseña el suyo (la notificación puede posponerlo con esto abierto).
 */
@Composable
private fun TaskDialog(
    initial: TaskItem?,
    onSave: (String, Long?) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
    current: TaskItem? = initial,
) {
    var text by rememberSaveable { mutableStateOf(initial?.text.orEmpty()) }
    var picked by rememberSaveable { mutableStateOf<Long?>(null) }
    var touched by rememberSaveable { mutableStateOf(false) }
    val remindAt = if (touched) picked else current?.remindAt
    val setRemindAt = { at: Long? ->
        picked = at
        touched = true
    }
    var pickedDate by rememberSaveable { mutableStateOf<Long?>(null) }
    var step by rememberSaveable { mutableIntStateOf(STEP_NONE) }
    val now = System.currentTimeMillis()
    // Un aviso ya pasado se puede conservar tal cual; uno nuevo o cambiado tiene que ser futuro.
    val reminderInPast = remindAt.let { it != null && it <= now && it != current?.remindAt }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (initial == null) R.string.tasks_new else R.string.tasks_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.tasks_text_label)) },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AssistChip(
                        onClick = { step = STEP_DATE },
                        label = { Text(remindAt?.let { whenLabel(it) } ?: stringResource(R.string.tasks_add_reminder)) },
                        leadingIcon = { Icon(Icons.Outlined.Alarm, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize)) },
                    )
                    if (remindAt != null) {
                        IconButton(onClick = { setRemindAt(null) }) {
                            Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.tasks_remove_reminder))
                        }
                    }
                }
                if (reminderInPast) {
                    Text(
                        stringResource(R.string.tasks_reminder_past),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text, remindAt) }, enabled = text.isNotBlank() && !reminderInPast) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )

    if (step == STEP_DATE) {
        val state = rememberDatePickerState(initialSelectedDateMillis = pickerDateOf(remindAt ?: now), selectableDates = FromToday)
        DatePickerDialog(
            onDismissRequest = { step = STEP_NONE },
            confirmButton = {
                TextButton(
                    onClick = {
                        pickedDate = state.selectedDateMillis
                        step = STEP_TIME
                    },
                    enabled = state.selectedDateMillis != null,
                ) { Text(stringResource(R.string.tasks_next)) }
            },
            dismissButton = { TextButton(onClick = { step = STEP_NONE }) { Text(stringResource(R.string.action_cancel)) } },
        ) {
            DatePicker(state = state)
        }
    }
    if (step == STEP_TIME) {
        // Sin hora previa, las 09:00 o, si hoy ya han pasado, la próxima hora en punto.
        val current = remindAt?.let { Instant.ofEpochMilli(it).atZone(Time.zone()) }
        val (defaultHour, defaultMinute) = defaultReminderTime(pickedDate ?: pickerDateOf(now), now)
        val state = rememberTimePickerState(
            initialHour = current?.hour ?: defaultHour,
            initialMinute = current?.minute ?: defaultMinute,
            is24Hour = DateFormat.is24HourFormat(LocalContext.current),
        )
        TimePickerDialog(
            onDismissRequest = { step = STEP_NONE },
            title = { Text(stringResource(R.string.tasks_pick_time)) },
            confirmButton = {
                TextButton(onClick = {
                    setRemindAt(pickerToEpoch(pickedDate ?: pickerDateOf(now), state.hour, state.minute))
                    step = STEP_NONE
                }) { Text(stringResource(R.string.action_accept)) }
            },
            dismissButton = { TextButton(onClick = { step = STEP_NONE }) { Text(stringResource(R.string.action_cancel)) } },
        ) {
            TimePicker(state = state)
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun TasksPreview() {
    val now = System.currentTimeMillis()
    MysoneTheme {
        TasksContent(
            lists = listOf(
                listOf(
                    TaskItem(1, "Llamar a Marta por el presupuesto", TaskState.OPEN, remindAt = now + 3_600_000, conversationId = 3, sourceQuote = "recuérdame llamar a Marta", createdAt = now),
                    TaskItem(2, "Comprar pilas para el mando", TaskState.OPEN, dueAt = now + 86_400_000, createdAt = now),
                    TaskItem(3, "Pedir cita en el taller", TaskState.OPEN, createdAt = now),
                ),
                listOf(TaskItem(4, "Enviar las fotos del viaje", TaskState.SUGGESTED, conversationId = 5, createdAt = now)),
                emptyList(),
            ),
            highlightTaskId = 2,
            actions = TaskActions(),
        )
    }
}
