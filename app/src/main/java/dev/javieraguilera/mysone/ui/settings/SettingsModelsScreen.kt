package dev.javieraguilera.mysone.ui.settings

import android.net.Uri
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.javieraguilera.mysone.AppContainer
import dev.javieraguilera.mysone.R
import dev.javieraguilera.mysone.asr.AsrModel
import dev.javieraguilera.mysone.asr.AudioImporter
import dev.javieraguilera.mysone.asr.DownloadWait
import dev.javieraguilera.mysone.asr.ImportProgress
import dev.javieraguilera.mysone.asr.ModelInstall
import dev.javieraguilera.mysone.container
import dev.javieraguilera.mysone.core.Time
import dev.javieraguilera.mysone.ui.components.BackTopBar
import dev.javieraguilera.mysone.ui.components.ConfirmDialog
import dev.javieraguilera.mysone.ui.components.SectionHeader
import dev.javieraguilera.mysone.ui.theme.MysoneTheme
import dev.javieraguilera.mysone.ui.today.dayHeading
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Resultado de "Probar" (transcribe la frase de prueba incluida en la app). */
sealed interface SelfTest {
    data object Running : SelfTest
    data class Ok(val text: String) : SelfTest
    data class Failed(val message: String?) : SelfTest
}

class SettingsModelsViewModel(private val container: AppContainer) : ViewModel() {
    private val models = container.models
    val catalog = models.catalog
    val installs = models.installs
    val ramGb = models.deviceRamGb()

    val selectedId: StateFlow<String?> = container.settings.data.map { it.asrModelId }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val imports: StateFlow<List<ImportProgress>> = AudioImporter.observe(container.app)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _tests = MutableStateFlow<Map<String, SelfTest>>(emptyMap())
    val tests: StateFlow<Map<String, SelfTest>> = _tests.asStateFlow()

    fun download(id: String, wifiOnly: Boolean) = models.download(id, allowMetered = !wifiOnly)

    fun cancel(id: String) = models.cancel(id)

    fun delete(id: String) {
        _tests.update { it - id }
        viewModelScope.launch { models.delete(id) }
    }

    fun choose(id: String) {
        viewModelScope.launch { container.settings.update { it.copy(asrModelId = id) } }
    }

    fun selfTest(id: String) {
        if (_tests.value[id] == SelfTest.Running) return
        _tests.update { it + (id to SelfTest.Running) }
        viewModelScope.launch {
            val result = models.selfTest(id).fold({ SelfTest.Ok(it) }, { SelfTest.Failed(it.message) })
            _tests.update { it + (id to result) }
        }
    }

    fun import(uri: Uri) = AudioImporter.enqueue(container.app, uri)
}

/** Acciones de la pantalla; con valores vacíos por defecto para las vistas previas. */
class ModelActions(
    val download: (String, Boolean) -> Unit = { _, _ -> },
    val cancel: (String) -> Unit = {},
    val delete: (String) -> Unit = {},
    val choose: (String) -> Unit = {},
    val selfTest: (String) -> Unit = {},
    val pickAudio: () -> Unit = {},
)

@Composable
fun SettingsModelsScreen(onBack: () -> Unit) {
    val container = LocalContext.current.container
    val vm = viewModel { SettingsModelsViewModel(container) }
    val installs by vm.installs.collectAsStateWithLifecycle()
    val selectedId by vm.selectedId.collectAsStateWithLifecycle()
    val tests by vm.tests.collectAsStateWithLifecycle()
    val imports by vm.imports.collectAsStateWithLifecycle()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::import) }
    SettingsModelsContent(
        catalog = vm.catalog,
        installs = installs,
        selectedId = selectedId,
        ramGb = vm.ramGb,
        tests = tests,
        imports = imports,
        onBack = onBack,
        actions = ModelActions(
            download = vm::download,
            cancel = vm::cancel,
            delete = vm::delete,
            choose = vm::choose,
            selfTest = vm::selfTest,
            pickAudio = { pick.launch(arrayOf("audio/*")) },
        ),
    )
}

@Composable
private fun SettingsModelsContent(
    catalog: List<AsrModel>,
    installs: Map<String, ModelInstall>,
    selectedId: String?,
    ramGb: Int,
    tests: Map<String, SelfTest>,
    imports: List<ImportProgress>,
    onBack: () -> Unit,
    actions: ModelActions,
) {
    var wifiOnly by rememberSaveable { mutableStateOf(true) }
    var toDelete by rememberSaveable { mutableStateOf<String?>(null) }
    val anyInstalled = installs.values.any { it is ModelInstall.Installed }

    Scaffold(topBar = { BackTopBar(stringResource(R.string.title_settings_models), onBack) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).verticalScroll(rememberScrollState())) {
            Note(stringResource(R.string.models_intro), modifier = Modifier.padding(top = 8.dp))
            if (ramGb > 0) Note(stringResource(R.string.models_ram, ramGb), icon = Icons.Outlined.Memory)
            SwitchRow(
                title = stringResource(R.string.models_wifi_only),
                summary = stringResource(R.string.models_wifi_only_desc),
                checked = wifiOnly,
                onCheckedChange = { wifiOnly = it },
                // Se aplica al encolar: durante una descarga cambiarlo no haría nada.
                enabled = installs.values.none { it is ModelInstall.Downloading || it is ModelInstall.Verifying },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            SectionHeader(stringResource(R.string.models_section_catalog))
            Column(Modifier.selectableGroup()) {
                catalog.forEach { m ->
                    ModelRow(
                        model = m,
                        install = installs[m.id] ?: ModelInstall.NotInstalled,
                        selected = m.id == selectedId,
                        lowRam = ramGb in 1 until m.recommendedMinRamGb,
                        test = tests[m.id],
                        onDownload = { actions.download(m.id, wifiOnly) },
                        onDelete = { toDelete = m.id },
                        actions = actions,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(start = 16.dp))
                }
            }

            SectionHeader(stringResource(R.string.models_section_import))
            Note(stringResource(R.string.models_import_desc))
            Button(
                onClick = actions.pickAudio,
                enabled = anyInstalled,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            ) { Text(stringResource(R.string.models_import_pick)) }
            if (!anyInstalled) Note(stringResource(R.string.models_import_need_model))
            imports.forEach { ImportRow(it) }
            Spacer(Modifier.height(24.dp))
        }
    }

    toDelete?.let { id ->
        val m = catalog.firstOrNull { it.id == id }
        val context = LocalContext.current
        ConfirmDialog(
            title = stringResource(R.string.models_delete_title, m?.name.orEmpty()),
            message = stringResource(
                if (id == selectedId) R.string.models_delete_message_selected else R.string.models_delete_message,
                Formatter.formatShortFileSize(context, m?.sizeBytes ?: 0),
            ),
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = {
                toDelete = null
                actions.delete(id)
            },
            onDismiss = { toDelete = null },
            destructive = true,
        )
    }
}

@Composable
private fun ModelRow(
    model: AsrModel,
    install: ModelInstall,
    selected: Boolean,
    lowRam: Boolean,
    test: SelfTest?,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    actions: ModelActions,
) {
    val context = LocalContext.current
    val installed = install is ModelInstall.Installed
    val size = Formatter.formatShortFileSize(context, model.sizeBytes)
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        // Elegir solo tiene sentido con el modelo ya en el móvil.
        Row(
            Modifier
                .fillMaxWidth()
                .selectable(selected = selected, enabled = installed, role = Role.RadioButton, onClick = { actions.choose(model.id) })
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.Top,
        ) {
            RadioButton(selected = selected, onClick = null, enabled = installed, modifier = Modifier.padding(top = 2.dp, end = 16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(model.name, style = MaterialTheme.typography.titleMedium)
                Text(model.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    stringResource(R.string.models_meta, size, model.license),
                    style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Column(Modifier.padding(start = 56.dp, end = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (lowRam) {
                Text(
                    stringResource(R.string.models_low_ram, model.recommendedMinRamGb),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (selected && !installed) {
                Text(
                    stringResource(R.string.models_selected_missing),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            InstallState(install, size, test, onDownload, onDelete, { actions.cancel(model.id) }, { actions.selfTest(model.id) })
        }
    }
}

@Composable
private fun InstallState(
    install: ModelInstall,
    size: String,
    test: SelfTest?,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
    onTest: () -> Unit,
) {
    when (install) {
        ModelInstall.NotInstalled -> OutlinedButton(onClick = onDownload) { Text(stringResource(R.string.models_download, size)) }
        is ModelInstall.Downloading -> {
            val percent = (install.progress * 100).toInt().coerceIn(0, 100)
            val label = when (install.waiting) {
                DownloadWait.WIFI -> R.string.asr_download_waiting_wifi
                DownloadWait.NETWORK -> R.string.asr_download_waiting_network
                null -> R.string.models_downloading
            }
            Progress(stringResource(label, percent), install.progress.coerceIn(0f, 1f))
            TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
        }
        ModelInstall.Verifying -> Progress(stringResource(R.string.models_verifying), null)
        ModelInstall.Installed -> {
            StatusLine(stringResource(R.string.models_installed), ok = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onTest, enabled = test != SelfTest.Running) { Text(stringResource(R.string.models_test)) }
                TextButton(onClick = onDelete) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
            }
            when (test) {
                null -> Unit
                SelfTest.Running -> Progress(stringResource(R.string.models_testing), null)
                is SelfTest.Ok -> StatusLine(stringResource(R.string.models_test_ok, test.text), ok = true)
                is SelfTest.Failed -> StatusLine(test.message ?: stringResource(R.string.models_test_failed), ok = false)
            }
        }
        is ModelInstall.Failed -> {
            StatusLine(install.message, ok = false)
            OutlinedButton(onClick = onDownload) { Text(stringResource(R.string.action_retry)) }
        }
    }
}

/** [progress] null = indeterminado. */
@Composable
private fun Progress(label: String, progress: Float?) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"))
        if (progress == null) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun StatusLine(text: String, ok: Boolean) {
    Note(
        text,
        modifier = Modifier.padding(horizontal = 0.dp).semantics { liveRegion = LiveRegionMode.Polite },
        icon = if (ok) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline,
        color = if (ok) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
    )
}

@Composable
private fun ImportRow(p: ImportProgress) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            Icons.Outlined.AudioFile,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(p.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            when {
                p.error != null -> Text(p.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                p.done -> Text(
                    // Con la fecha del fichero puede quedar en otro día: se dice en cuál.
                    p.startedAt?.let { stringResource(R.string.models_import_done_at, dayHeading(Time.dayKey(it)), Time.hourMinute(it)) }
                        ?: stringResource(R.string.models_import_done),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
                else -> Progress(
                    stringResource(R.string.models_import_progress, (p.progress * 100).toInt().coerceIn(0, 100)),
                    p.progress.coerceIn(0f, 1f),
                )
            }
        }
    }
}

@Preview(showBackground = true, heightDp = 1200)
@Composable
private fun SettingsModelsPreview() {
    val catalog = listOf(
        AsrModel("canary-180m-es", "Canary 180M (español)", "Descarga de 207 MB, usa poca memoria y fija el idioma al español.", 207_170_046, 4, "CC-BY-4.0", emptyList()),
        AsrModel("parakeet-v3", "Parakeet TDT 0.6B v3", "Multilingüe (25 idiomas). Descarga de 670 MB; recomendado con 8 GB de RAM o más.", 670_478_772, 8, "CC-BY-4.0", emptyList()),
    )
    MysoneTheme {
        SettingsModelsContent(
            catalog = catalog,
            installs = mapOf("canary-180m-es" to ModelInstall.Installed, "parakeet-v3" to ModelInstall.Downloading(0.42f)),
            selectedId = "canary-180m-es",
            ramGb = 6,
            tests = mapOf("canary-180m-es" to SelfTest.Ok("Hola, esto es una prueba de transcripción.")),
            imports = listOf(
                ImportProgress(UUID.randomUUID(), "reunion-lunes.m4a", 0.6f, done = false, error = null),
                ImportProgress(UUID.randomUUID(), "nota-de-voz.ogg", 1f, done = true, error = null),
            ),
            onBack = {},
            actions = ModelActions(),
        )
    }
}
