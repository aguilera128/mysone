@file:OptIn(ExperimentalMaterial3Api::class)

package dev.javieraguilera.mysone.ui.memory

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.javieraguilera.mysone.R
import dev.javieraguilera.mysone.container
import dev.javieraguilera.mysone.data.Fact
import dev.javieraguilera.mysone.data.FactState
import dev.javieraguilera.mysone.ui.components.EmptyState
import dev.javieraguilera.mysone.ui.components.SectionHeader
import dev.javieraguilera.mysone.ui.theme.MysoneTheme
import kotlinx.coroutines.launch

@Composable
fun MemoryScreen(onOpenConversation: (Long, Long?) -> Unit) {
    val container = LocalContext.current.container
    val vm = viewModel { MemoryViewModel(container) }
    val confirmed by vm.confirmed.collectAsStateWithLifecycle()
    val suggested by vm.suggested.collectAsStateWithLifecycle()
    MemoryContent(
        confirmed = confirmed,
        suggested = suggested,
        onConfirm = vm::confirm,
        onEdit = vm::edit,
        onAdd = vm::add,
        onDelete = vm::delete,
        onRestore = vm::restore,
        onOpenConversation = { onOpenConversation(it, null) },
    )
}

@Composable
private fun MemoryContent(
    confirmed: List<Fact>,
    suggested: List<Fact>,
    onConfirm: (Fact) -> Unit,
    onEdit: (Fact, String) -> Unit,
    onAdd: (String) -> Unit,
    onDelete: (Fact) -> Unit,
    onRestore: (Fact) -> Unit,
    onOpenConversation: (Long) -> Unit,
) {
    var editing by remember { mutableStateOf<Fact?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val deletedMsg = stringResource(R.string.memory_deleted)
    val undo = stringResource(R.string.tasks_undo)
    val delete: (Fact) -> Unit = { f ->
        editing = null
        onDelete(f)
        scope.launch {
            if (snackbar.showSnackbar(deletedMsg, undo, duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) onRestore(f)
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.nav_memory)) }) },
        floatingActionButton = {
            FloatingActionButton(onClick = { adding = true }) {
                Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.memory_add))
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
        if (confirmed.isEmpty() && suggested.isEmpty()) {
            EmptyState(
                title = stringResource(R.string.memory_empty_title),
                message = stringResource(R.string.memory_empty),
                icon = Icons.Outlined.Lightbulb,
                modifier = modifier,
            )
            return@Scaffold
        }
        // Claves por sección: al confirmar, los dos Flows de Room emiten por separado y el mismo recuerdo puede
        // estar un frame en las dos listas; con la misma clave, LazyColumn lanza IllegalArgumentException.
        LazyColumn(modifier, contentPadding = PaddingValues(bottom = 96.dp)) {
            item(key = "intro") {
                Text(
                    stringResource(R.string.memory_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            if (suggested.isNotEmpty()) {
                item(key = "suggested") { SectionHeader(stringResource(R.string.memory_section_suggested)) }
                items(suggested, key = { "s${it.id}" }) { f ->
                    FactRow(f, onClick = { editing = f }, onOpenConversation = onOpenConversation) {
                        FilledTonalButton(onClick = { onConfirm(f) }) { Text(stringResource(R.string.memory_confirm)) }
                        TextButton(onClick = { delete(f) }) { Text(stringResource(R.string.tasks_dismiss)) }
                    }
                }
            }
            if (confirmed.isNotEmpty()) {
                item(key = "confirmed") { SectionHeader(stringResource(R.string.memory_section_confirmed)) }
                items(confirmed, key = { "c${it.id}" }) { f ->
                    FactRow(f, onClick = { editing = f }, onOpenConversation = onOpenConversation)
                }
            }
        }
    }

    editing?.let { f ->
        FactDialog(
            initial = f,
            onSave = {
                editing = null
                onEdit(f, it)
            },
            onDelete = { delete(f) },
            onDismiss = { editing = null },
        )
    }
    if (adding) {
        FactDialog(
            initial = null,
            onSave = {
                adding = false
                onAdd(it)
            },
            onDelete = null,
            onDismiss = { adding = false },
        )
    }
}

@Composable
private fun FactRow(
    f: Fact,
    onClick: () -> Unit,
    onOpenConversation: (Long) -> Unit,
    actions: @Composable () -> Unit = {},
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = stringResource(R.string.action_edit), onClick = onClick)
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
    ) {
        Text(f.text, style = MaterialTheme.typography.bodyLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            actions()
            f.conversationId?.let { id ->
                TextButton(onClick = { onOpenConversation(id) }) { Text(stringResource(R.string.tasks_open_conversation)) }
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(start = 16.dp))
}

@Composable
private fun FactDialog(initial: Fact?, onSave: (String) -> Unit, onDelete: (() -> Unit)?, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial?.text.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (initial == null) R.string.memory_add else R.string.memory_edit)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(stringResource(R.string.memory_placeholder)) },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }, enabled = text.isNotBlank()) { Text(stringResource(R.string.action_save)) }
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
}

@Preview(showBackground = true)
@Composable
private fun MemoryPreview() {
    val now = System.currentTimeMillis()
    MysoneTheme {
        MemoryContent(
            confirmed = listOf(
                Fact(1, "Marta es la arquitecta de la reforma", FactState.CONFIRMED, conversationId = 3, createdAt = now),
                Fact(2, "El cumpleaños de Lucía es el 3 de marzo", FactState.CONFIRMED, createdAt = now),
            ),
            suggested = listOf(Fact(3, "Prefieres las reuniones por la mañana", FactState.SUGGESTED, conversationId = 4, createdAt = now)),
            onConfirm = {},
            onEdit = { _, _ -> },
            onAdd = {},
            onDelete = {},
            onRestore = {},
            onOpenConversation = {},
        )
    }
}
