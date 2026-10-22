package dev.javieraguilera.mysone.ui.settings

import android.media.MediaRecorder
import android.net.Uri
import android.text.format.Formatter
import androidx.activity.compose.LocalActivity
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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.keepScreenOn
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.javieraguilera.mysone.AppContainer
import dev.javieraguilera.mysone.R
import dev.javieraguilera.mysone.asr.AsrModel
import dev.javieraguilera.mysone.asr.AudioImporter
import dev.javieraguilera.mysone.asr.CallImporter
import dev.javieraguilera.mysone.asr.DownloadWait
import dev.javieraguilera.mysone.asr.ImportProgress
import dev.javieraguilera.mysone.asr.ModelInstall
import dev.javieraguilera.mysone.asr.OwnerVoice
import dev.javieraguilera.mysone.asr.SAMPLE_RATE
import dev.javieraguilera.mysone.asr.SPEAKER_MODEL_ID
import dev.javieraguilera.mysone.capture.AudioCapture
import dev.javieraguilera.mysone.capture.ListeningController
import dev.javieraguilera.mysone.capture.ListeningStatus
import dev.javieraguilera.mysone.asr.recommendedModelId
import dev.javieraguilera.mysone.container
import dev.javieraguilera.mysone.core.Time
import dev.javieraguilera.mysone.data.AppSettings
import dev.javieraguilera.mysone.ui.components.BackTopBar
import dev.javieraguilera.mysone.ui.components.ConfirmDialog
import dev.javieraguilera.mysone.ui.components.SectionHeader
import dev.javieraguilera.mysone.ui.theme.MysoneTheme
import dev.javieraguilera.mysone.ui.today.dayFull
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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
    val recommendedId = recommendedModelId(ramGb)

    val selectedId: StateFlow<String?> = container.settings.data.map { it.asrModelId }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val wifiOnly: StateFlow<Boolean> = container.settings.data.map { it.downloadWifiOnly }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings().downloadWifiOnly)

    val imports: StateFlow<List<ImportProgress>> = AudioImporter.observe(container.app)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _tests = MutableStateFlow<Map<String, SelfTest>>(emptyMap())
    val tests: StateFlow<Map<String, SelfTest>> = _tests.asStateFlow()

    fun download(id: String, wifiOnly: Boolean) = models.download(id, allowMetered = !wifiOnly)

    fun cancel(id: String) = models.cancel(id)

    fun setWifiOnly(on: Boolean) {
        viewModelScope.launch { container.settings.update { it.copy(downloadWifiOnly = on) } }
    }

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

    /** Audio ya importado a la espera de que se confirme repetirlo, con el inicio de su conversación. */
    private val _duplicate = MutableStateFlow<Pair<Uri, Long>?>(null)
    val duplicate: StateFlow<Pair<Uri, Long>?> = _duplicate.asStateFlow()

    /** Si ese fichero ya está importado se pregunta antes: repetirlo duplica la conversación y su procesado con IA. */
    fun import(uri: Uri) {
        viewModelScope.launch {
            val existing = AudioImporter.alreadyImported(container.app, uri)
            if (existing == null) AudioImporter.enqueue(container.app, uri) else _duplicate.value = uri to existing
        }
    }

    fun resolveDuplicate(importAgain: Boolean) {
        val uri = _duplicate.value?.first ?: return
        _duplicate.value = null
        if (importAgain) AudioImporter.enqueue(container.app, uri)
    }

    // ---- Llamadas (CallImporter) ----
    // Lo que cambia ajustes o permisos va en appScope: al salir de la pantalla viewModelScope se cancela.

    val callFolder: StateFlow<String?> = container.settings.data.map { it.callFolder }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val callDeleteAfter: StateFlow<Boolean> = container.settings.data.map { it.callDeleteAfter }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings().callDeleteAfter)

    /** Para volver a comprobar si se lee al elegir otra vez la misma carpeta (callFolder no cambia y no emitiría). */
    private val recheck = MutableStateFlow(0)

    /** Si se ha retirado el permiso o se ha borrado la carpeta, para pedir que se elija otra vez. */
    val callFolderReadable: StateFlow<Boolean> = combine(callFolder, recheck) { f, _ -> f }
        .map { f -> f == null || CallImporter.recordings(container.app, f.toUri()) != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /** Carpeta recién elegida con grabaciones aún sin transcribir, con cuántas, a la espera de decidir si se importan. */
    private val _pendingFolder = MutableStateFlow<Pair<Uri, Int>?>(null)
    val pendingFolder: StateFlow<Pair<Uri, Int>?> = _pendingFolder.asStateFlow()

    // El permiso persistente solo se pide en setFolder, ya decidido: sin respuesta al diálogo no se queda ninguno.
    fun pickCallFolder(tree: Uri) {
        container.appScope.launch {
            val pending = CallImporter.pendingCount(container.app, tree) ?: return@launch // ilegible
            if (pending == 0) setCallFolder(tree, importExisting = false) else _pendingFolder.value = tree to pending
        }
    }

    /** [importExisting] null: el dueño ha cancelado y la carpeta no se usa. */
    fun confirmCallFolder(importExisting: Boolean?) {
        val tree = _pendingFolder.value?.first ?: return
        _pendingFolder.value = null
        if (importExisting != null) container.appScope.launch { setCallFolder(tree, importExisting) }
    }

    private suspend fun setCallFolder(tree: Uri, importExisting: Boolean) {
        CallImporter.setFolder(container.app, tree, importExisting)
        recheck.update { it + 1 }
    }

    fun setCallDeleteAfter(on: Boolean) {
        container.appScope.launch { container.settings.update { it.copy(callDeleteAfter = on) } }
    }

    fun stopCalls() {
        container.appScope.launch { CallImporter.clearFolder(container.app) }
    }

    // ---- Quién habla (§5.6) ----

    val speakerModel = models.speakerModel
    val speakerInstall = models.speakerInstall

    private val _ownerVoice = MutableStateFlow(OwnerVoice.exists(container.app))
    val ownerVoice: StateFlow<Boolean> = _ownerVoice.asStateFlow()

    private val _enroll = MutableStateFlow<Enroll?>(null)
    val enroll: StateFlow<Enroll?> = _enroll.asStateFlow()

    fun downloadSpeaker(wifiOnly: Boolean) = models.download(SPEAKER_MODEL_ID, allowMetered = !wifiOnly)

    fun cancelSpeaker() = models.cancel(SPEAKER_MODEL_ID)

    /** También la voz del dueño: sin el modelo no sirve, y sin él tampoco se ve «Borrar mi voz». La voz antes, por si se corta. */
    fun deleteSpeaker() {
        container.appScope.launch {
            OwnerVoice.delete(container.app)
            _ownerVoice.value = false
            models.delete(SPEAKER_MODEL_ID)
        }
    }

    fun deleteOwnerVoice() {
        container.appScope.launch {
            OwnerVoice.delete(container.app)
            _ownerVoice.value = false
        }
    }

    private var capture: AudioCapture? = null
    private val speech = ArrayList<FloatArray>() // lo grabado; lo llena el hilo de la captura
    private var pausedListening = false

    fun openEnroll() {
        if (_enroll.value == null) _enroll.value = Enroll.Ready
    }

    /** Graba hasta juntar [OwnerVoice.ENROLL_SAMPLES] de voz (el VAD quita los silencios) y guarda la huella. */
    fun startEnroll() {
        if (_enroll.value !is Enroll.Ready && _enroll.value !is Enroll.Failed) return
        synchronized(speech) { speech.clear() }
        // La escucha se pausa mientras: si no, lo leído acabaría también como conversación.
        pausedListening = ListeningController.status.value is ListeningStatus.Listening
        if (pausedListening) ListeningController.pause(container.app, ENROLL_PAUSE_MIN)
        val cap = AudioCapture(
            container.app,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            onChunk = { chunk ->
                val got = synchronized(speech) {
                    speech += chunk.samples
                    speech.sumOf { it.size }
                }
                _enroll.update { (it as? Enroll.Recording)?.copy(progress = (got.toFloat() / OwnerVoice.ENROLL_SAMPLES).coerceAtMost(1f)) ?: it }
                if (got >= OwnerVoice.ENROLL_SAMPLES) viewModelScope.launch { finishEnroll() }
            },
            onLevel = { level -> _enroll.update { (it as? Enroll.Recording)?.copy(level = level) ?: it } },
            onSilenced = {},
            onFailure = { viewModelScope.launch { failEnroll(R.string.voice_error_mic) } },
        )
        if (!cap.start()) return failEnroll(R.string.voice_error_mic)
        capture = cap
        _enroll.value = Enroll.Recording(0f, 0f)
    }

    fun cancelEnroll() {
        stopCapture()
        _enroll.value = null
    }

    private fun finishEnroll() {
        if (_enroll.value !is Enroll.Recording) return
        _enroll.value = Enroll.Saving
        stopCapture()
        container.appScope.launch {
            val ok = OwnerVoice.enroll(container.app, models, synchronized(speech) { speech.toList() })
            synchronized(speech) { speech.clear() }
            if (ok) {
                _ownerVoice.value = true
                _enroll.value = null
            } else {
                _enroll.value = Enroll.Failed(R.string.voice_error_model)
            }
        }
    }

    private fun failEnroll(message: Int) {
        stopCapture()
        _enroll.value = Enroll.Failed(message)
    }

    /** Suelta el micro (espera al hilo de la captura fuera del principal) y devuelve la escucha si se pausó. */
    private fun stopCapture() {
        capture?.let { cap -> container.appScope.launch(Dispatchers.IO) { cap.stop() } }
        capture = null
        if (pausedListening) {
            pausedListening = false
            ListeningController.resume(container.app)
        }
    }

    override fun onCleared() {
        stopCapture()
    }
}

/** Registro de la voz del dueño; null = sin diálogo. */
sealed interface Enroll {
    data object Ready : Enroll
    data class Recording(val progress: Float, val level: Float) : Enroll
    data object Saving : Enroll
    data class Failed(val message: Int) : Enroll
}

/** Lo que se pausa la escucha al registrar la voz: de sobra para leer el texto; si se tarda más, vuelve sola. */
private const val ENROLL_PAUSE_MIN = 2

/** Acciones de la pantalla; con valores vacíos por defecto para las vistas previas. */
class ModelActions(
    val download: (String, Boolean) -> Unit = { _, _ -> },
    val cancel: (String) -> Unit = {},
    val delete: (String) -> Unit = {},
    val choose: (String) -> Unit = {},
    val selfTest: (String) -> Unit = {},
    val pickAudio: () -> Unit = {},
    val setWifiOnly: (Boolean) -> Unit = {},
    val pickCallFolder: () -> Unit = {},
    val setCallDeleteAfter: (Boolean) -> Unit = {},
    val stopCalls: () -> Unit = {},
    val downloadSpeaker: (Boolean) -> Unit = {},
    val cancelSpeaker: () -> Unit = {},
    val deleteSpeaker: () -> Unit = {},
    val openEnroll: () -> Unit = {},
    val deleteOwnerVoice: () -> Unit = {},
)

/** Modelo de voces y voz del dueño para la pantalla (§5.6). */
class VoicesState(val model: AsrModel? = null, val install: ModelInstall = ModelInstall.NotInstalled, val ownerVoice: Boolean = false)

/** Carpeta de llamadas para la pantalla; null sin elegir. */
class CallsState(val folder: String? = null, val deleteAfter: Boolean = true, val readable: Boolean = true)

@Composable
fun SettingsModelsScreen(onBack: () -> Unit) {
    val container = LocalContext.current.container
    val vm = viewModel { SettingsModelsViewModel(container) }
    val installs by vm.installs.collectAsStateWithLifecycle()
    val selectedId by vm.selectedId.collectAsStateWithLifecycle()
    val wifiOnly by vm.wifiOnly.collectAsStateWithLifecycle()
    val tests by vm.tests.collectAsStateWithLifecycle()
    val imports by vm.imports.collectAsStateWithLifecycle()
    val duplicate by vm.duplicate.collectAsStateWithLifecycle()
    val callFolder by vm.callFolder.collectAsStateWithLifecycle()
    val callDeleteAfter by vm.callDeleteAfter.collectAsStateWithLifecycle()
    val callReadable by vm.callFolderReadable.collectAsStateWithLifecycle()
    val pendingFolder by vm.pendingFolder.collectAsStateWithLifecycle()
    val speakerInstall by vm.speakerInstall.collectAsStateWithLifecycle()
    val ownerVoice by vm.ownerVoice.collectAsStateWithLifecycle()
    val enroll by vm.enroll.collectAsStateWithLifecycle()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::import) }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let(vm::pickCallFolder) }
    SettingsModelsContent(
        catalog = vm.catalog,
        installs = installs,
        selectedId = selectedId,
        wifiOnly = wifiOnly,
        ramGb = vm.ramGb,
        recommendedId = vm.recommendedId,
        tests = tests,
        imports = imports,
        calls = CallsState(callFolder, callDeleteAfter, callReadable),
        voices = VoicesState(vm.speakerModel, speakerInstall, ownerVoice),
        onBack = onBack,
        actions = ModelActions(
            download = vm::download,
            cancel = vm::cancel,
            delete = vm::delete,
            choose = vm::choose,
            selfTest = vm::selfTest,
            pickAudio = { pick.launch(arrayOf("audio/*")) },
            setWifiOnly = vm::setWifiOnly,
            pickCallFolder = { pickFolder.launch(CallImporter.SAMSUNG_FOLDER) },
            setCallDeleteAfter = vm::setCallDeleteAfter,
            stopCalls = vm::stopCalls,
            downloadSpeaker = vm::downloadSpeaker,
            cancelSpeaker = vm::cancelSpeaker,
            deleteSpeaker = vm::deleteSpeaker,
            openEnroll = vm::openEnroll,
            deleteOwnerVoice = vm::deleteOwnerVoice,
        ),
    )
    enroll?.let { EnrollDialog(it, onStart = vm::startEnroll, onCancel = vm::cancelEnroll) }
    // Fuera de la pantalla (Inicio, otra app, pantalla apagada) el micro sigue abierto con la escucha en pausa y juntaría
    // como tuya la voz de quien se oiga: se cancela. Al girar no, que la actividad se recrea y el ViewModel sigue.
    val activity = LocalActivity.current
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        if (vm.enroll.value is Enroll.Recording && activity?.isChangingConfigurations != true) vm.cancelEnroll()
    }
    pendingFolder?.let { (_, count) ->
        AlertDialog(
            onDismissRequest = { vm.confirmCallFolder(null) },
            title = { Text(stringResource(R.string.models_calls_existing_title)) },
            text = {
                val res = if (callDeleteAfter) R.plurals.models_calls_existing_message_delete else R.plurals.models_calls_existing_message
                Text(pluralStringResource(res, count, count))
            },
            confirmButton = { TextButton(onClick = { vm.confirmCallFolder(true) }) { Text(stringResource(R.string.models_calls_existing_all)) } },
            dismissButton = { TextButton(onClick = { vm.confirmCallFolder(false) }) { Text(stringResource(R.string.models_calls_existing_new)) } },
        )
    }
    duplicate?.let { (_, startedAt) ->
        ConfirmDialog(
            title = stringResource(R.string.models_import_duplicate_title),
            message = stringResource(R.string.models_import_duplicate_message, whereIs(startedAt)),
            confirmLabel = stringResource(R.string.models_import_duplicate_confirm),
            onConfirm = { vm.resolveDuplicate(importAgain = true) },
            onDismiss = { vm.resolveDuplicate(importAgain = false) },
        )
    }
}

/** "Lo tienes en Hoy, a las 18:47.", "Lo tienes en el día de ayer…" o "Lo tienes el jueves 15 de octubre, a las 18:47." */
@Composable
private fun whereIs(startedAt: Long): String {
    val day = Time.dayKey(startedAt)
    val time = Time.hourMinute(startedAt)
    val today = Time.today()
    return when (day) {
        // "Hoy" es la pestaña; "Ayer" no lo es, y "en Ayer" no se dice.
        today -> stringResource(R.string.models_import_where_tab, stringResource(R.string.ui_day_today), time)
        LocalDate.parse(today).minusDays(1).toString() -> stringResource(R.string.models_import_where_yesterday, time)
        else -> stringResource(R.string.models_import_where_day, dayFull(day).lowercase(Time.SPAIN), time)
    }
}

@Composable
private fun SettingsModelsContent(
    catalog: List<AsrModel>,
    installs: Map<String, ModelInstall>,
    selectedId: String?,
    wifiOnly: Boolean,
    ramGb: Int,
    recommendedId: String,
    tests: Map<String, SelfTest>,
    imports: List<ImportProgress>,
    onBack: () -> Unit,
    actions: ModelActions,
    calls: CallsState = CallsState(),
    voices: VoicesState = VoicesState(),
) {
    var toDelete by rememberSaveable { mutableStateOf<String?>(null) }
    // La importación usa el elegido (MysoneApp elige otro instalado si falta), no uno cualquiera.
    val selectedInstalled = installs[selectedId] is ModelInstall.Installed

    Scaffold(topBar = { BackTopBar(stringResource(R.string.title_settings_models), onBack) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).verticalScroll(rememberScrollState())) {
            Note(stringResource(R.string.models_intro), modifier = Modifier.padding(top = 8.dp))
            if (ramGb > 0) Note(stringResource(R.string.models_ram, ramGb), icon = Icons.Outlined.Memory)
            SwitchRow(
                title = stringResource(R.string.models_wifi_only),
                summary = stringResource(R.string.models_wifi_only_desc),
                checked = wifiOnly,
                onCheckedChange = actions.setWifiOnly,
                // Se aplica al encolar: durante una descarga cambiarlo no haría nada.
                enabled = installs.values.none { it is ModelInstall.Downloading || it is ModelInstall.Verifying },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            SectionHeader(stringResource(R.string.models_section_catalog))
            Column(Modifier.selectableGroup()) {
                catalog.forEach { m ->
                    val lowRam = ramGb in 1 until m.recommendedMinRamGb
                    ModelRow(
                        model = m,
                        install = installs[m.id] ?: ModelInstall.NotInstalled,
                        selected = m.id == selectedId,
                        recommended = m.id == recommendedId && !lowRam,
                        lowRam = lowRam,
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
                enabled = selectedInstalled,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            ) { Text(stringResource(R.string.models_import_pick)) }
            if (!selectedInstalled) Note(stringResource(R.string.models_import_need_model))
            imports.forEach { ImportRow(it) }

            SectionHeader(stringResource(R.string.models_section_calls))
            Note(stringResource(R.string.models_calls_desc))
            if (calls.folder == null || !calls.readable) {
                if (calls.folder != null) Note(stringResource(R.string.models_calls_unreadable), color = MaterialTheme.colorScheme.error)
                Button(
                    onClick = actions.pickCallFolder,
                    enabled = selectedInstalled,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                ) { Text(stringResource(R.string.models_calls_pick)) }
                if (!selectedInstalled) Note(stringResource(R.string.models_import_need_model))
            }
            // Antes de elegir la carpeta también: así se puede decidir conservar el audio del primer lote.
            SwitchRow(
                title = stringResource(R.string.models_calls_delete),
                summary = stringResource(R.string.models_calls_delete_desc),
                checked = calls.deleteAfter,
                onCheckedChange = actions.setCallDeleteAfter,
            )
            if (calls.folder != null) {
                if (calls.readable) Note(stringResource(R.string.models_calls_folder, CallImporter.folderName(calls.folder.toUri())))
                OutlinedButton(
                    onClick = actions.stopCalls,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                ) { Text(stringResource(R.string.models_calls_stop)) }
            }

            voices.model?.let { m -> VoicesSection(m, voices, wifiOnly, actions, onDelete = { toDelete = m.id }) }
            Spacer(Modifier.height(24.dp))
        }
    }

    toDelete?.takeIf { it == voices.model?.id }?.let {
        val size = Formatter.formatShortFileSize(LocalContext.current, voices.model?.sizeBytes ?: 0)
        ConfirmDialog(
            title = stringResource(R.string.models_delete_title, voices.model?.name.orEmpty()),
            message = stringResource(R.string.voices_delete_message, size),
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = {
                toDelete = null
                actions.deleteSpeaker()
            },
            onDismiss = { toDelete = null },
            destructive = true,
        )
    }
    toDelete?.takeIf { it != voices.model?.id }?.let { id ->
        val m = catalog.firstOrNull { it.id == id }
        val context = LocalContext.current
        val size = Formatter.formatShortFileSize(context, m?.sizeBytes ?: 0)
        // Borrar el elegido con otro instalado pasa a usar ese (MysoneApp); sin otro, la escucha se queda sin modelo.
        val next = catalog.firstOrNull { it.id != id && installs[it.id] is ModelInstall.Installed }
        ConfirmDialog(
            title = stringResource(R.string.models_delete_title, m?.name.orEmpty()),
            message = when {
                id != selectedId -> stringResource(R.string.models_delete_message, size)
                next != null -> stringResource(R.string.models_delete_message_switch, next.name, size)
                else -> stringResource(R.string.models_delete_message_selected, size)
            },
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
    recommended: Boolean,
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
                if (recommended) {
                    Text(stringResource(R.string.models_recommended), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
                }
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
    onTest: (() -> Unit)?, // null: sin «Probar»
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
                if (onTest != null) OutlinedButton(onClick = onTest, enabled = test != SelfTest.Running) { Text(stringResource(R.string.models_test)) }
                // Con la prueba en marcha el borrado tendría que esperar a que acabe (ModelManager.delete).
                val testing = test == SelfTest.Running
                TextButton(onClick = onDelete, enabled = !testing) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error.copy(alpha = if (testing) 0.38f else 1f))
                }
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
                    p.startedAt?.let { stringResource(if (p.partial) R.string.models_import_partial_at else R.string.models_import_done_at, whereIs(it)) }
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

/** «Quién habla»: el modelo de voces y, con él, la voz del dueño (§5.6). */
@Composable
private fun VoicesSection(model: AsrModel, voices: VoicesState, wifiOnly: Boolean, actions: ModelActions, onDelete: () -> Unit) {
    val size = Formatter.formatShortFileSize(LocalContext.current, model.sizeBytes)
    SectionHeader(stringResource(R.string.voices_section))
    Note(stringResource(R.string.voices_desc))
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(model.name, style = MaterialTheme.typography.titleMedium)
        Text(model.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            stringResource(R.string.models_meta, size, model.license),
            style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        InstallState(voices.install, size, null, { actions.downloadSpeaker(wifiOnly) }, onDelete, actions.cancelSpeaker, onTest = null)
    }
    if (voices.install !is ModelInstall.Installed) return
    if (voices.ownerVoice) {
        Note(stringResource(R.string.voices_owner_done), icon = Icons.Outlined.CheckCircle, color = MaterialTheme.colorScheme.tertiary)
        Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = actions.openEnroll) { Text(stringResource(R.string.voices_owner_again)) }
            TextButton(onClick = actions.deleteOwnerVoice) { Text(stringResource(R.string.voices_owner_delete), color = MaterialTheme.colorScheme.error) }
        }
    } else {
        Note(stringResource(R.string.voices_owner_desc))
        Button(onClick = actions.openEnroll, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(stringResource(R.string.voices_owner_record))
        }
    }
}

/** Lectura en voz alta para registrar la voz del dueño. */
@Composable
private fun EnrollDialog(state: Enroll, onStart: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = {}, // solo con «Cancelar»: tocar fuera a media lectura la tiraría
        title = { Text(stringResource(R.string.voices_enroll_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.voices_enroll_intro))
                Text(stringResource(R.string.voices_enroll_text), style = MaterialTheme.typography.bodyLarge)
                when (state) {
                    Enroll.Ready -> Unit
                    is Enroll.Recording -> {
                        val total = OwnerVoice.ENROLL_SAMPLES / SAMPLE_RATE
                        Progress(stringResource(R.string.voices_enroll_progress, (state.progress * total).toInt(), total), state.progress)
                        // Que no se apague a media lectura: apagada, el registro se cancela.
                        LinearProgressIndicator(progress = { state.level }, modifier = Modifier.fillMaxWidth().height(4.dp).keepScreenOn())
                    }
                    Enroll.Saving -> Progress(stringResource(R.string.voices_enroll_saving), null)
                    is Enroll.Failed -> StatusLine(stringResource(state.message), ok = false)
                }
            }
        },
        confirmButton = {
            if (state == Enroll.Ready || state is Enroll.Failed) {
                TextButton(onClick = onStart) { Text(stringResource(if (state is Enroll.Failed) R.string.action_retry else R.string.voices_enroll_start)) }
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel, enabled = state != Enroll.Saving) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Preview(showBackground = true, heightDp = 1200)
@Composable
private fun SettingsModelsPreview() {
    val catalog = listOf(
        AsrModel("canary-180m-es", "Canary 180M (español)", "Ligero: descarga de 207 MB y poca memoria. Fija el idioma al español.", 207_170_046, 4, "CC-BY-4.0", emptyList()),
        AsrModel("parakeet-v3", "Parakeet TDT 0.6B v3", "El más preciso y escribe los números en cifras. Multilingüe (25 idiomas). Descarga de 670 MB.", 670_478_772, 6, "CC-BY-4.0", emptyList()),
    )
    MysoneTheme {
        SettingsModelsContent(
            catalog = catalog,
            installs = mapOf("canary-180m-es" to ModelInstall.Installed, "parakeet-v3" to ModelInstall.Downloading(0.42f)),
            selectedId = "canary-180m-es",
            wifiOnly = true,
            ramGb = 6,
            recommendedId = "parakeet-v3",
            tests = mapOf("canary-180m-es" to SelfTest.Ok("Hola, esto es una prueba de transcripción de voz.")),
            imports = listOf(
                ImportProgress(UUID.randomUUID(), "reunion-lunes.m4a", 0.6f, done = false, error = null),
                ImportProgress(UUID.randomUUID(), "nota-de-voz.ogg", 1f, done = true, error = null),
            ),
            onBack = {},
            actions = ModelActions(),
        )
    }
}
