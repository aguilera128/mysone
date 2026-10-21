@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package dev.javieraguilera.mysone.ui.ask

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.javieraguilera.mysone.R
import dev.javieraguilera.mysone.container
import dev.javieraguilera.mysone.core.Time
import dev.javieraguilera.mysone.ui.components.ConfirmDialog
import dev.javieraguilera.mysone.ui.components.EmptyState
import dev.javieraguilera.mysone.ui.settings.providerName
import dev.javieraguilera.mysone.ui.theme.MysoneTheme

@Composable
fun AskScreen(onOpenConversation: (Long, Long?) -> Unit, onOpenAiSettings: () -> Unit, onOpenSearch: () -> Unit) {
    val container = LocalContext.current.container
    val vm = viewModel { AskViewModel(container) }
    val messages by vm.messages.collectAsStateWithLifecycle()
    val configured by vm.configured.collectAsStateWithLifecycle()
    val canEnable by vm.canEnable.collectAsStateWithLifecycle()
    val ai by vm.ai.collectAsStateWithLifecycle()
    val pending by vm.pending.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()

    AskContent(
        messages = messages,
        configured = configured,
        provider = ai?.let { providerName(it) }.orEmpty(),
        pending = pending,
        error = error,
        onAsk = vm::ask,
        onRetry = vm::retry,
        onDismissError = vm::dismissError,
        onClearChat = vm::clearChat,
        onOpenConversation = { onOpenConversation(it, null) },
        onOpenAiSettings = onOpenAiSettings,
        onOpenSearch = onOpenSearch,
        canEnable = canEnable,
        onEnableAi = vm::enableAi,
    )
}

@Composable
private fun AskContent(
    messages: List<AskMessage>,
    configured: Boolean?,
    provider: String,
    pending: String?,
    error: AiError?,
    onAsk: (String) -> Unit,
    onRetry: () -> Unit,
    onDismissError: () -> Unit,
    onClearChat: () -> Unit,
    onOpenConversation: (Long) -> Unit,
    onOpenAiSettings: () -> Unit,
    onOpenSearch: () -> Unit,
    canEnable: Boolean = false,
    onEnableAi: () -> Unit = {},
) {
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.nav_ask)) },
                actions = {
                    if (messages.isNotEmpty() && pending == null) {
                        IconButton(onClick = { confirmClear = true }) {
                            Icon(Icons.Outlined.DeleteSweep, contentDescription = stringResource(R.string.ask_clear))
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            when (configured) {
                null -> Unit
                false -> EmptyState(
                    title = stringResource(if (canEnable) R.string.ask_disabled_title else R.string.ask_off_title),
                    message = if (canEnable) stringResource(R.string.ask_disabled_message, provider) else stringResource(R.string.ask_off_message),
                    icon = Icons.Outlined.AutoAwesome,
                    action = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            if (canEnable) {
                                Button(onClick = onEnableAi) { Text(stringResource(R.string.ask_disabled_action)) }
                            } else {
                                Button(onClick = onOpenAiSettings) { Text(stringResource(R.string.ask_off_action)) }
                            }
                            TextButton(onClick = onOpenSearch) { Text(stringResource(R.string.ask_off_search)) }
                        }
                    },
                )
                true -> {
                    Box(Modifier.weight(1f)) {
                        if (messages.isEmpty() && pending == null) {
                            Intro(onAsk)
                        } else {
                            MessageList(messages, pending, onOpenConversation)
                        }
                    }
                    if (error != null) {
                        ErrorBar(error, onRetry, onDismissError, onOpenAiSettings)
                    }
                    Composer(provider = provider, busy = pending != null, onAsk = onAsk)
                }
            }
        }
    }
    if (confirmClear) {
        ConfirmDialog(
            title = stringResource(R.string.ask_clear_title),
            message = stringResource(R.string.ask_clear_message),
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = {
                confirmClear = false
                onClearChat()
            },
            onDismiss = { confirmClear = false },
            destructive = true,
        )
    }
}

@Composable
private fun Intro(onAsk: (String) -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.ask_intro_title), style = MaterialTheme.typography.headlineSmall)
        Text(
            stringResource(R.string.ask_intro_message),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(8.dp))
        listOf(R.string.ask_suggestion_pending, R.string.ask_suggestion_yesterday, R.string.ask_suggestion_budget).forEach { res ->
            val text = stringResource(res)
            SuggestionChip(onClick = { onAsk(text) }, label = { Text(text) })
        }
    }
}

@Composable
private fun MessageList(messages: List<AskMessage>, pending: String?, onOpenConversation: (Long) -> Unit) {
    val state = rememberLazyListState()
    // Si el motor ya guardó la pregunta, no se repite como pendiente.
    val showPendingQuestion = pending != null && messages.lastOrNull()?.let { it.fromUser && it.text == pending } != true
    val count = messages.size + (if (showPendingQuestion) 1 else 0) + (if (pending != null) 1 else 0)
    // También con el último id: la respuesta sustituye al "pensando" sin cambiar el recuento y quedaba medio tapada.
    LaunchedEffect(count, messages.lastOrNull()?.id) {
        if (count > 0) state.animateScrollToItem(count - 1)
    }
    LazyColumn(
        state = state,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        items(messages, key = { it.id }) { m ->
            if (m.fromUser) UserBubble(m.text) else Answer(m, onOpenConversation)
        }
        if (pending != null) {
            if (showPendingQuestion) item(key = "pending-q") { UserBubble(pending) }
            item(key = "thinking") { Thinking() }
        }
    }
}

@Composable
private fun UserBubble(text: String) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 4.dp),
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
        }
    }
}

@Composable
private fun Answer(m: AskMessage, onOpenConversation: (Long) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SelectionContainer { Text(m.text, style = MaterialTheme.typography.bodyLarge) }
        if (m.citations.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                m.citations.forEach { c -> CitationChip(c, onClick = { onOpenConversation(c.conversationId) }) }
            }
        }
    }
}

@Composable
private fun CitationChip(c: Citation, onClick: () -> Unit) {
    val title = c.title ?: stringResource(R.string.ask_untitled)
    val time = Time.hourMinute(c.startedAt)
    val label = if (Time.dayKey(c.startedAt) == Time.today()) {
        stringResource(R.string.ask_citation, time, title)
    } else {
        stringResource(R.string.ask_citation_day, Time.dayShort(c.startedAt), time, title)
    }
    AssistChip(
        onClick = onClick,
        label = {
            Text(
                label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
            )
        },
        leadingIcon = { Icon(Icons.Outlined.Forum, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize)) },
        modifier = Modifier.widthIn(max = 300.dp),
    )
}

@Composable
private fun Thinking() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        Text(
            stringResource(R.string.ask_thinking),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ErrorBar(error: AiError, onRetry: () -> Unit, onDismiss: () -> Unit, onOpenAiSettings: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
            Text(
                stringResource(error.messageRes()),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Row(Modifier.align(Alignment.End)) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
                if (error == AiError.AUTH) {
                    TextButton(onClick = onOpenAiSettings) { Text(stringResource(R.string.ask_open_ai_settings)) }
                } else if (error == AiError.INSECURE_URL || error == AiError.TLS) {
                    TextButton(onClick = onOpenAiSettings) { Text(stringResource(R.string.ask_open_ai_url)) }
                } else if (error != AiError.REFUSAL) {
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
                }
            }
        }
    }
}

@Composable
private fun Composer(provider: String, busy: Boolean, onAsk: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val send = {
        if (text.isNotBlank() && !busy) {
            onAsk(text)
            text = ""
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(
                Icons.Outlined.Info,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.ask_notice, provider),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(stringResource(R.string.ask_placeholder)) },
                maxLines = 4,
                shape = MaterialTheme.shapes.large,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                modifier = Modifier.weight(1f),
            )
            FilledIconButton(onClick = send, enabled = text.isNotBlank() && !busy, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.ask_send))
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun AskPreview() {
    val now = System.currentTimeMillis()
    MysoneTheme {
        AskContent(
            messages = listOf(
                AskMessage(1, true, "¿Qué dije del presupuesto?", emptyList()),
                AskMessage(
                    2,
                    false,
                    "Quedaste en revisar el presupuesto de la reforma con Marta antes del viernes y pediste dos presupuestos más.",
                    listOf(Citation(10, now - 3_600_000, "Reforma de la cocina"), Citation(11, now - 90_000_000, "Llamada con Marta")),
                ),
            ),
            configured = true,
            provider = "Claude (Anthropic)",
            pending = "¿Y qué tengo pendiente?",
            error = null,
            onAsk = {},
            onRetry = {},
            onDismissError = {},
            onClearChat = {},
            onOpenConversation = {},
            onOpenAiSettings = {},
            onOpenSearch = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun AskEmptyPreview() {
    MysoneTheme {
        AskContent(emptyList(), true, "Mistral (UE)", null, AiError.RATE, {}, {}, {}, {}, {}, {}, {})
    }
}
