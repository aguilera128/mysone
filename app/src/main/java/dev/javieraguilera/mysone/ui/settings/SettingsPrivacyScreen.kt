package dev.javieraguilera.mysone.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.javieraguilera.mysone.AppContainer
import dev.javieraguilera.mysone.R
import dev.javieraguilera.mysone.capture.ListeningController
import dev.javieraguilera.mysone.capture.ListeningStatus
import dev.javieraguilera.mysone.container
import dev.javieraguilera.mysone.core.Time
import dev.javieraguilera.mysone.data.AppSettings
import dev.javieraguilera.mysone.ui.components.BackTopBar
import dev.javieraguilera.mysone.ui.components.ConfirmDialog
import dev.javieraguilera.mysone.ui.components.SectionHeader
import dev.javieraguilera.mysone.ui.theme.MysoneTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class SettingsPrivacyViewModel(private val container: AppContainer) : ViewModel() {
    val settings: StateFlow<AppSettings?> = container.settings.data
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** Resultado de la última operación, para el aviso; la pantalla lo consume con [messageShown]. */
    private val _message = MutableStateFlow<Int?>(null)
    val message: StateFlow<Int?> = _message.asStateFlow()

    fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { container.settings.update(transform) }
    }

    // Exportar y borrar van en appScope: si se sale de la pantalla a mitad, no se quedan a medias.
    fun export(uri: Uri) = run(R.string.privacy_export_done, R.string.privacy_export_failed) { container.data.exportJson(uri) }

    fun deleteAll() = run(R.string.privacy_delete_done, R.string.privacy_delete_failed) {
        // Con la escucha en marcha, el servicio seguiría escribiendo en una conversación ya borrada. Si ya está
        // parada no se le manda nada: "detener" otra vez abriría un hueco nuevo justo después de borrar.
        if (ListeningController.status.value.isActive()) {
            ListeningController.stop(container.app)
            // Off llega cuando lo que deja la escucha (cola, conversación y hueco) ya está en la BD: así se borra también.
            withTimeoutOrNull(5_000) { ListeningController.status.first { !it.isActive() } }
        }
        container.data.deleteAll()
    }

    fun messageShown() {
        _message.value = null
    }

    private fun run(@StringRes ok: Int, @StringRes failed: Int, block: suspend () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        container.appScope.launch {
            _message.value = if (runCatching { block() }.isSuccess) ok else failed
            _busy.value = false
        }
    }
}

private fun ListeningStatus.isActive() =
    this is ListeningStatus.Listening || this is ListeningStatus.MicBusy || this is ListeningStatus.Starting || this is ListeningStatus.Paused

/** Días de retención de la transcripción literal; 0 = no borrar. */
private val RETENTION_OPTIONS = listOf(7, 30, 90, 180, 365, 0)

private const val STEP_NONE = 0
private const val STEP_FIRST = 1
private const val STEP_SECOND = 2

@Composable
fun SettingsPrivacyScreen(onBack: () -> Unit) {
    val container = LocalContext.current.container
    val vm = viewModel { SettingsPrivacyViewModel(container) }
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let(vm::export)
    }
    val settings = vm.settings.collectAsStateWithLifecycle().value ?: return
    SettingsPrivacyContent(
        settings = settings,
        busy = busy,
        message = message,
        onMessageShown = vm::messageShown,
        onUpdate = vm::update,
        onExport = { export.launch("mysone-${Time.today()}.json") },
        onDeleteAll = vm::deleteAll,
        onBack = onBack,
    )
}

@Composable
private fun SettingsPrivacyContent(
    settings: AppSettings,
    busy: Boolean,
    message: Int?,
    onMessageShown: () -> Unit,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
    onExport: () -> Unit,
    onDeleteAll: () -> Unit,
    onBack: () -> Unit,
) {
    var exportNotice by rememberSaveable { mutableStateOf(false) }
    var deleteStep by rememberSaveable { mutableIntStateOf(STEP_NONE) }
    val snackbar = remember { SnackbarHostState() }
    val text = message?.let { stringResource(it) }
    LaunchedEffect(text) {
        if (text != null) {
            snackbar.showSnackbar(text)
            onMessageShown()
        }
    }

    Scaffold(
        topBar = { BackTopBar(stringResource(R.string.title_settings_privacy), onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).verticalScroll(rememberScrollState())) {
            SectionHeader(stringResource(R.string.privacy_section_retention))
            Note(stringResource(R.string.privacy_retention_desc))
            Column(Modifier.selectableGroup()) {
                RETENTION_OPTIONS.forEach { days ->
                    RadioRow(
                        title = if (days == 0) stringResource(R.string.privacy_retention_never) else stringResource(R.string.privacy_retention_days, days),
                        summary = if (days == 90) stringResource(R.string.privacy_retention_default) else null,
                        selected = settings.retentionDays == days,
                        onClick = { onUpdate { it.copy(retentionDays = days) } },
                    )
                }
            }
            Note(stringResource(R.string.privacy_retention_note))
            HorizontalDivider(Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)

            SwitchRow(
                title = stringResource(R.string.privacy_secure_screen),
                summary = stringResource(R.string.privacy_secure_screen_desc),
                checked = settings.secureScreen,
                onCheckedChange = { on -> onUpdate { it.copy(secureScreen = on) } },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            SectionHeader(stringResource(R.string.privacy_section_export))
            Note(stringResource(R.string.privacy_export_desc))
            OutlinedButton(
                onClick = { exportNotice = true },
                enabled = !busy,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            ) { Text(stringResource(R.string.privacy_export)) }

            SectionHeader(stringResource(R.string.privacy_section_delete))
            Note(stringResource(R.string.privacy_delete_desc))
            Button(
                onClick = { deleteStep = STEP_FIRST },
                enabled = !busy,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            ) { Text(stringResource(R.string.privacy_delete_all)) }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (exportNotice) {
        ConfirmDialog(
            title = stringResource(R.string.privacy_export_title),
            message = stringResource(R.string.privacy_export_warning),
            confirmLabel = stringResource(R.string.privacy_export_confirm),
            onConfirm = {
                exportNotice = false
                onExport()
            },
            onDismiss = { exportNotice = false },
        )
    }
    // Doble confirmación: la primera explica qué se borra; la segunda recuerda que no hay vuelta atrás.
    when (deleteStep) {
        STEP_FIRST -> ConfirmDialog(
            title = stringResource(R.string.privacy_delete_title),
            message = stringResource(R.string.privacy_delete_message),
            confirmLabel = stringResource(R.string.privacy_delete_continue),
            onConfirm = { deleteStep = STEP_SECOND },
            onDismiss = { deleteStep = STEP_NONE },
            destructive = true,
        )
        STEP_SECOND -> ConfirmDialog(
            title = stringResource(R.string.privacy_delete_title2),
            message = stringResource(R.string.privacy_delete_message2),
            confirmLabel = stringResource(R.string.privacy_delete_all),
            onConfirm = {
                deleteStep = STEP_NONE
                onDeleteAll()
            },
            onDismiss = { deleteStep = STEP_NONE },
            destructive = true,
        )
    }
}

@Preview(showBackground = true, heightDp = 1300)
@Composable
private fun SettingsPrivacyPreview() {
    MysoneTheme {
        SettingsPrivacyContent(
            settings = AppSettings(),
            busy = false,
            message = null,
            onMessageShown = {},
            onUpdate = {},
            onExport = {},
            onDeleteAll = {},
            onBack = {},
        )
    }
}
