package dev.javieraguilera.mysone.ui.today

import android.Manifest
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Hearing
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material.icons.outlined.PauseCircle
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.javieraguilera.mysone.R
import dev.javieraguilera.mysone.capture.ListeningController
import dev.javieraguilera.mysone.capture.ListeningStatus
import dev.javieraguilera.mysone.container
import dev.javieraguilera.mysone.core.Time
import dev.javieraguilera.mysone.data.ConvSource
import dev.javieraguilera.mysone.data.ConvState
import dev.javieraguilera.mysone.data.Conversation
import dev.javieraguilera.mysone.data.GapReason
import dev.javieraguilera.mysone.data.ListeningGap
import dev.javieraguilera.mysone.data.TaskItem
import dev.javieraguilera.mysone.data.TaskState
import dev.javieraguilera.mysone.ui.components.ConfirmDialog
import dev.javieraguilera.mysone.ui.components.EmptyState
import dev.javieraguilera.mysone.ui.components.SectionHeader
import dev.javieraguilera.mysone.ui.onboarding.openAppSettings
import dev.javieraguilera.mysone.ui.onboarding.rememberPermissionState
import dev.javieraguilera.mysone.ui.theme.MysoneTheme
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.launch

/** Días que ofrece la tira de arriba (hoy incluido); los anteriores, con el calendario. */
private const val STRIP_DAYS = 14

@Composable
fun TodayScreen(day: String?, onOpenConversation: (Long, Long?) -> Unit, onOpenSearch: () -> Unit, onOpenSettings: () -> Unit, onOpenModels: () -> Unit) {
    val context = LocalContext.current
    val container = context.container
    val vm = viewModel { TodayViewModel(container, day) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val selectedDay by vm.selectedDay.collectAsStateWithLifecycle()
    val status by ListeningController.status.collectAsStateWithLifecycle()
    val pending by vm.pendingChunks.collectAsStateWithLifecycle()
    val listeningEnabled by vm.listeningEnabled.collectAsStateWithLifecycle()

    val resources = LocalResources.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val mic = rememberPermissionState(Manifest.permission.RECORD_AUDIO) { granted ->
        if (granted) {
            ListeningController.start(context)
        } else {
            scope.launch {
                val result = snackbar.showSnackbar(
                    resources.getString(R.string.today_mic_denied),
                    actionLabel = resources.getString(R.string.today_open_system_settings),
                )
                if (result == SnackbarResult.ActionPerformed) openAppSettings(context)
            }
        }
    }
    val start = { if (mic.granted) ListeningController.start(context) else mic.request() }

    TodayContent(
        ui = ui,
        selectedDay = selectedDay,
        snackbar = snackbar,
        onSelectDay = vm::selectDay,
        onOpenConversation = { onOpenConversation(it, null) },
        onOpenSearch = onOpenSearch,
        onOpenSettings = onOpenSettings,
        statusCard = {
            StatusCard(
                status = status,
                listeningEnabled = listeningEnabled,
                pendingChunks = pending,
                onStart = start,
                onStop = { ListeningController.stop(context) },
                onPause = { ListeningController.pause(context, it) },
                onResume = { ListeningController.resume(context) },
                onOpenModels = onOpenModels,
                onForget = { minutes ->
                    vm.forget(minutes)
                    scope.launch { snackbar.showSnackbar(resources.getString(R.string.today_forget_done, minutes)) }
                },
            )
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TodayContent(
    ui: TodayUi?,
    selectedDay: String,
    snackbar: SnackbarHostState,
    onSelectDay: (String) -> Unit,
    onOpenConversation: (Long) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    statusCard: @Composable () -> Unit,
) {
    val isToday = selectedDay == Time.today()
    var picking by rememberSaveable { mutableStateOf(false) }
    val heading = dayHeading(selectedDay)
    val fullDate = dayFull(selectedDay)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(heading)
                        if (heading != fullDate) { // "Hoy" y "Ayer" llevan la fecha debajo
                            Text(
                                fullDate,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSearch) {
                        Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.action_search))
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.action_settings))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { inner ->
        LazyColumn(contentPadding = inner, modifier = Modifier.fillMaxWidth()) {
            item(key = "days") { DayStrip(selectedDay, onSelectDay, onPickOther = { picking = true }) }
            if (isToday) {
                item(key = "status") { Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { statusCard() } }
            }
            ui?.digest?.let { digest -> item(key = "digest") { DigestCard(digest) } }
            if (ui != null) {
                if (ui.items.isEmpty()) {
                    item(key = "empty") {
                        EmptyState(
                            title = stringResource(if (isToday) R.string.today_empty_today else R.string.today_empty_day),
                            message = stringResource(if (isToday) R.string.today_empty_today_body else R.string.today_empty_day_body),
                        )
                    }
                } else {
                    item(key = "header") { SectionHeader(stringResource(R.string.today_timeline_title)) }
                    items(ui.items, key = { it.key }) { item ->
                        when (item) {
                            is TimelineItem.Conv -> ConversationRow(item, onClick = { onOpenConversation(item.conversation.id) })
                            is TimelineItem.Gap -> GapRow(item.gap)
                        }
                    }
                    item(key = "end") { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }

    if (picking) DayPickerDialog(selectedDay, onPick = { picking = false; onSelectDay(it) }, onDismiss = { picking = false })
}

// --- Selector de día ---

@Composable
private fun DayStrip(selectedDay: String, onSelect: (String) -> Unit, onPickOther: () -> Unit) {
    val today = LocalDate.parse(Time.today())
    val days = remember(today) { (0 until STRIP_DAYS).map { today.minusDays(it.toLong()).toString() } }
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(days, key = { it }) { day ->
            FilterChip(selected = day == selectedDay, onClick = { onSelect(day) }, label = { Text(dayChip(day)) })
        }
        item(key = "other") {
            AssistChip(
                onClick = onPickOther,
                label = { Text(stringResource(R.string.today_other_day)) },
                leadingIcon = { Icon(Icons.Outlined.CalendarMonth, contentDescription = null, Modifier.size(18.dp)) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DayPickerDialog(selectedDay: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    // DatePicker trabaja con medianoche UTC del día elegido.
    val todayUtc = LocalDate.parse(Time.today()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    val state = rememberDatePickerState(
        initialSelectedDateMillis = LocalDate.parse(selectedDay).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= todayUtc
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString()) } ?: onDismiss()
            }) { Text(stringResource(R.string.action_accept)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    ) { DatePicker(state) }
}

// --- Tarjeta de estado ---

private class StatusLook(val icon: ImageVector, val tint: Color, val badge: Color, val title: String, val body: String?)

@Composable
internal fun StatusCard(
    status: ListeningStatus,
    listeningEnabled: Boolean,
    pendingChunks: Int,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onPause: (Int) -> Unit,
    onResume: () -> Unit,
    onOpenModels: () -> Unit,
    onForget: (Int) -> Unit,
) {
    val c = MaterialTheme.colorScheme
    val look = when (status) {
        is ListeningStatus.Listening -> StatusLook(
            Icons.Outlined.Hearing, c.onTertiaryContainer, c.tertiaryContainer,
            stringResource(R.string.today_status_listening), stringResource(R.string.today_status_listening_body),
        )
        ListeningStatus.Starting -> StatusLook(
            Icons.Outlined.Hearing, c.onSurfaceVariant, c.surfaceVariant, stringResource(R.string.today_status_starting), null,
        )
        is ListeningStatus.Paused -> StatusLook(
            Icons.Outlined.PauseCircle, c.onSurfaceVariant, c.surfaceVariant,
            stringResource(R.string.today_status_paused, Time.hourMinute(status.untilMs)), stringResource(R.string.today_status_paused_body),
        )
        ListeningStatus.MicBusy -> StatusLook(
            Icons.Outlined.MicOff, c.onSecondaryContainer, c.secondaryContainer,
            stringResource(R.string.today_status_mic_busy), stringResource(R.string.today_status_mic_busy_body),
        )
        ListeningStatus.NeedsModel -> StatusLook(
            Icons.Outlined.Download, c.onPrimaryContainer, c.primaryContainer,
            stringResource(R.string.today_status_needs_model), stringResource(R.string.today_status_needs_model_body),
        )
        ListeningStatus.NeedsPermission -> StatusLook(
            Icons.Outlined.MicOff, c.onErrorContainer, c.errorContainer,
            stringResource(R.string.today_status_needs_permission), stringResource(R.string.today_status_needs_permission_body),
        )
        is ListeningStatus.Error -> StatusLook(
            Icons.Outlined.ErrorOutline, c.onErrorContainer, c.errorContainer,
            stringResource(R.string.today_status_error), status.message,
        )
        ListeningStatus.Off -> if (listeningEnabled) {
            StatusLook(
                Icons.Outlined.ErrorOutline, c.onErrorContainer, c.errorContainer,
                stringResource(R.string.today_status_interrupted), stringResource(R.string.today_status_interrupted_body),
            )
        } else {
            StatusLook(
                Icons.Outlined.MicOff, c.onSurfaceVariant, c.surfaceVariant,
                stringResource(R.string.today_status_off), stringResource(R.string.today_status_off_body),
            )
        }
    }

    Surface(
        color = c.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, c.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(40.dp).background(look.badge, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(look.icon, contentDescription = null, tint = look.tint)
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        look.title,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    look.body?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant) }
                }
                if (status is ListeningStatus.Listening) LevelMeter(status.level)
            }
            if (status == ListeningStatus.Starting) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (pendingChunks > 0) {
                Text(
                    pluralStringResource(R.plurals.today_pending, pendingChunks, pendingChunks),
                    style = MaterialTheme.typography.labelMedium,
                    color = c.onSurfaceVariant,
                )
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                when (status) {
                    // Con el micro ocupado la escucha sigue en marcha: las mismas acciones que la notificación (§5.1).
                    is ListeningStatus.Listening, ListeningStatus.MicBusy -> {
                        FilledTonalButton(onClick = { onPause(15) }) { Text(stringResource(R.string.today_action_pause_15)) }
                        OutlinedButton(onClick = { onPause(60) }) { Text(stringResource(R.string.today_action_pause_60)) }
                        OutlinedButton(onClick = onStop) { Text(stringResource(R.string.today_action_stop)) }
                    }
                    is ListeningStatus.Paused -> {
                        Button(onClick = onResume) { Text(stringResource(R.string.today_action_resume)) }
                        OutlinedButton(onClick = onStop) { Text(stringResource(R.string.today_action_stop)) }
                    }
                    ListeningStatus.Starting ->
                        OutlinedButton(onClick = onStop) { Text(stringResource(R.string.today_action_stop)) }
                    ListeningStatus.NeedsModel ->
                        Button(onClick = onOpenModels) { Text(stringResource(R.string.today_action_download_model)) }
                    ListeningStatus.NeedsPermission ->
                        Button(onClick = onStart) { Text(stringResource(R.string.today_action_grant)) }
                    is ListeningStatus.Error ->
                        Button(onClick = onStart) { Text(stringResource(R.string.action_retry)) }
                    ListeningStatus.Off -> Button(onClick = onStart) {
                        Text(stringResource(if (listeningEnabled) R.string.today_action_resume else R.string.today_action_listen))
                    }
                }
                ForgetButton(onForget)
            }
        }
    }
}

@Composable
private fun ForgetButton(onForget: (Int) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var confirm by rememberSaveable { mutableStateOf<Int?>(null) }
    Box {
        TextButton(onClick = { menu = true }) {
            Icon(Icons.Outlined.History, contentDescription = null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.today_action_forget))
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            listOf(5 to R.string.today_forget_5, 15 to R.string.today_forget_15, 60 to R.string.today_forget_60).forEach { (minutes, label) ->
                DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = { menu = false; confirm = minutes })
            }
        }
    }
    confirm?.let { minutes ->
        ConfirmDialog(
            title = stringResource(R.string.today_forget_title, minutes),
            message = stringResource(R.string.today_forget_message),
            confirmLabel = stringResource(R.string.today_forget_confirm),
            onConfirm = { confirm = null; onForget(minutes) },
            onDismiss = { confirm = null },
            destructive = true,
        )
    }
}

/**
 * Medidor de nivel: los últimos segundos como barras que se desplazan, la más reciente a la derecha.
 * Suavizado para que respire con la voz en vez de temblar con cada lectura (llegan ~4 por segundo).
 */
@Composable
private fun LevelMeter(level: Float) {
    val bars = 16
    val history = remember { mutableStateListOf<Float>().apply { repeat(bars) { add(0f) } } }
    var smoothed by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(level) {
        // El nivel ya llega en escala de -60 a 0 dBFS (AudioCapture.level).
        smoothed = smoothed * 0.6f + level.coerceIn(0f, 1f) * 0.4f
        history.removeAt(0)
        history.add(smoothed)
    }
    val color = MaterialTheme.colorScheme.tertiary
    val description = stringResource(R.string.today_level_meter)
    Canvas(Modifier.size(width = 64.dp, height = 28.dp).clearAndSetSemantics { contentDescription = description }) {
        val slot = size.width / bars
        val barWidth = slot * 0.55f
        val minHeight = 2.dp.toPx()
        history.forEachIndexed { i, v ->
            val h = (v * size.height).coerceAtLeast(minHeight)
            drawRoundRect(
                color = color.copy(alpha = 0.3f + 0.7f * i / (bars - 1)),
                topLeft = Offset(i * slot + (slot - barWidth) / 2, (size.height - h) / 2),
                size = Size(barWidth, h),
                cornerRadius = CornerRadius(barWidth / 2),
            )
        }
    }
}

// --- Resumen y línea de tiempo ---

@Composable
private fun DigestCard(digest: DigestUi) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.today_digest_title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Text(digest.summary, style = MaterialTheme.typography.bodyLarge)
            digest.highlights.forEach { Bullet(it) }
        }
    }
}

@Composable
internal fun Bullet(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("•", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Margen de cuaderno: la hora a la izquierda, en cifras tabulares. Lo comparten Hoy, Conversación y Buscar. */
@Composable
internal fun TimeMargin(time: String, modifier: Modifier = Modifier.padding(top = 14.dp)) {
    Text(
        time,
        style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.widthIn(min = 48.dp).then(modifier),
    )
}

/** [node] null = sin punto (huecos); [hollow] = punto hueco (aún transcribiendo). */
@Composable
private fun Rule(dashed: Boolean, node: Color?, hollow: Boolean = false) {
    val line = MaterialTheme.colorScheme.outlineVariant
    val paper = MaterialTheme.colorScheme.background
    Box(
        Modifier.width(24.dp).fillMaxHeight().drawBehind {
            val x = size.width / 2
            drawLine(
                line, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.dp.toPx(),
                pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 4.dp.toPx())) else null,
            )
            if (node != null) {
                val center = Offset(x, 23.dp.toPx())
                drawCircle(paper, radius = 6.dp.toPx(), center = center) // corta la línea alrededor del punto
                if (hollow) drawCircle(node, radius = 4.dp.toPx(), center = center, style = Stroke(1.5.dp.toPx()))
                else drawCircle(node, radius = 4.dp.toPx(), center = center)
            }
        },
    )
}

@Composable
private fun ConversationRow(item: TimelineItem.Conv, onClick: () -> Unit) {
    val conv = item.conversation
    val c = MaterialTheme.colorScheme
    val title = conversationTitle(conv, item.transcribing)
    val open = conv.state == ConvState.OPEN
    val meta = joined(
        timeRange(conv.startedAt, if (open) null else conv.endedAt),
        durationLabel(conv.endedAt - conv.startedAt),
        if (conv.source == ConvSource.IMPORT) stringResource(R.string.conv_imported) else null,
        if (conv.isNoise) stringResource(R.string.conv_noise) else null,
    )
    val node = when {
        open || item.transcribing -> c.tertiary
        conv.isNoise -> c.outlineVariant
        conv.state == ConvState.DONE -> c.primary
        else -> c.outline
    }
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clickable(onClickLabel = stringResource(R.string.today_open_conversation), onClick = onClick)
            .padding(horizontal = 16.dp)
            .alpha(if (conv.isNoise) 0.7f else 1f),
    ) {
        TimeMargin(Time.hourMinute(conv.startedAt))
        Rule(dashed = false, node = node, hollow = item.transcribing && !open)
        Column(Modifier.weight(1f).padding(top = 12.dp, bottom = 12.dp, start = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = if (conv.title == null) c.onSurfaceVariant else c.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(meta, style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant)
            conv.summary?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (item.reminders > 0 || item.otherTasks > 0) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 2.dp)) {
                    if (item.reminders > 0) {
                        Count(Icons.Outlined.Alarm, pluralStringResource(R.plurals.conv_reminders_count, item.reminders, item.reminders), c.secondary)
                    }
                    if (item.otherTasks > 0) {
                        Count(Icons.Outlined.TaskAlt, pluralStringResource(R.plurals.conv_tasks_count, item.otherTasks, item.otherTasks), c.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun Count(icon: ImageVector, text: String, tint: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = tint)
    }
}

@Composable
private fun GapRow(gap: ListeningGap) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(horizontal = 16.dp)) {
        TimeMargin(Time.hourMinute(gap.startedAt))
        Rule(dashed = true, node = null)
        Text(
            joined(gapLabel(gap.reason), timeRange(gap.startedAt, gap.endedAt)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).padding(start = 4.dp, top = 14.dp, bottom = 14.dp),
        )
    }
}

// --- Vistas previas con datos falsos ---

private fun previewDay(): TodayUi {
    val (from, _) = Time.dayBounds(Time.today())
    val h = 3_600_000L
    val m = 60_000L
    val convs = listOf(
        Conversation(id = 1, startedAt = from + 9 * h + 5 * m, endedAt = from + 9 * h + 32 * m, source = ConvSource.MIC, state = ConvState.DONE,
            title = "Presupuesto de la reforma con Lucía", summary = "Repasamos el presupuesto de la cocina. Lucía enviará la oferta del fontanero el viernes y hay que confirmar la fecha de obra."),
        Conversation(id = 2, startedAt = from + 11 * h, endedAt = from + 11 * h + 40 * m, source = ConvSource.MIC, state = ConvState.DONE,
            title = "Tele de fondo", summary = "Noticias de la radio.", isNoise = true),
        Conversation(id = 3, startedAt = from + 16 * h + 12 * m, endedAt = from + 16 * h + 19 * m, source = ConvSource.MIC, state = ConvState.READY),
        Conversation(id = 4, startedAt = from + 18 * h + 40 * m, endedAt = from + 18 * h + 52 * m, source = ConvSource.MIC, state = ConvState.OPEN),
    )
    val gaps = listOf(
        ListeningGap(id = 1, startedAt = from + 13 * h + 30 * m, endedAt = from + 14 * h + 30 * m, reason = GapReason.PAUSED),
        ListeningGap(id = 2, startedAt = from + 17 * h, endedAt = from + 17 * h + 3 * m, reason = GapReason.MIC_BUSY),
    )
    val tasks = listOf(
        TaskItem(id = 1, text = "Llamar al fontanero", state = TaskState.OPEN, remindAt = from + 34 * h, conversationId = 1, createdAt = from),
        TaskItem(id = 2, text = "Confirmar la fecha de obra", state = TaskState.SUGGESTED, conversationId = 1, createdAt = from),
    )
    return TodayUi(Time.today(), buildTimeline(convs, gaps, tasks, transcribingIds = listOf(3L)), digest = null)
}

@Preview(name = "Hoy", showBackground = true, heightDp = 900)
@Composable
private fun TodayPreview() {
    MysoneTheme {
        TodayContent(
            ui = previewDay(),
            selectedDay = Time.today(),
            snackbar = remember { SnackbarHostState() },
            onSelectDay = {}, onOpenConversation = {}, onOpenSearch = {}, onOpenSettings = {},
            statusCard = {
                StatusCard(ListeningStatus.Listening(0.3f, 2), true, 2, {}, {}, {}, {}, {}, {})
            },
        )
    }
}

@Preview(name = "Hoy · oscuro, en pausa", showBackground = true, heightDp = 900, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun TodayDarkPreview() {
    MysoneTheme(darkTheme = true) {
        TodayContent(
            ui = previewDay().copy(digest = DigestUi("Día tranquilo: reforma de la cocina y poco más.", listOf("Oferta del fontanero el viernes"))),
            selectedDay = Time.today(),
            snackbar = remember { SnackbarHostState() },
            onSelectDay = {}, onOpenConversation = {}, onOpenSearch = {}, onOpenSettings = {},
            statusCard = {
                StatusCard(ListeningStatus.Paused(System.currentTimeMillis() + 900_000), true, 0, {}, {}, {}, {}, {}, {})
            },
        )
    }
}

@Preview(name = "Estados de la escucha", showBackground = true, widthDp = 380)
@Composable
private fun StatusPreview() {
    MysoneTheme {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(ListeningStatus.MicBusy, ListeningStatus.NeedsModel, ListeningStatus.Off).forEach {
                StatusCard(it, listeningEnabled = it == ListeningStatus.Off, pendingChunks = 0, {}, {}, {}, {}, {}, {})
            }
        }
    }
}
