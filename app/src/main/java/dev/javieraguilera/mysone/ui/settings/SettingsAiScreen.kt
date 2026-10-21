package dev.javieraguilera.mysone.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.javieraguilera.mysone.BuildConfig
import dev.javieraguilera.mysone.AppContainer
import dev.javieraguilera.mysone.R
import dev.javieraguilera.mysone.ai.AiPreset
import dev.javieraguilera.mysone.ai.AiPresets
import dev.javieraguilera.mysone.container
import dev.javieraguilera.mysone.core.Time
import dev.javieraguilera.mysone.data.AiSettings
import dev.javieraguilera.mysone.ui.ask.AiError
import dev.javieraguilera.mysone.ui.ask.classifyAiError
import dev.javieraguilera.mysone.ui.ask.messageRes
import dev.javieraguilera.mysone.ui.components.BackTopBar
import dev.javieraguilera.mysone.ui.components.ConfirmDialog
import dev.javieraguilera.mysone.ui.components.SectionHeader
import dev.javieraguilera.mysone.ui.theme.MysoneTheme
import java.net.URI
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface ConnectionTest {
    data object Idle : ConnectionTest
    data object Running : ConnectionTest
    data class Ok(val reply: String) : ConnectionTest
    data class Failed(val error: AiError) : ConnectionTest
}

class SettingsAiViewModel(private val container: AppContainer) : ViewModel() {
    val ai: StateFlow<AiSettings?> = container.settings.data.map { it.ai }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val hasKey: StateFlow<Boolean> = container.secrets.hasApiKey
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _test = MutableStateFlow<ConnectionTest>(ConnectionTest.Idle)
    val test: StateFlow<ConnectionTest> = _test.asStateFlow()

    /** Cambiar de proveedor borra la clave y el consentimiento: eran para el anterior. */
    fun choosePreset(p: AiPreset) {
        viewModelScope.launch {
            if (container.settings.current().ai.presetId == p.id) return@launch
            container.secrets.clearApiKey()
            container.settings.update { it.copy(ai = p.applyTo(it.ai).copy(enabled = false, consentAt = null)) }
            _test.value = ConnectionTest.Idle
        }
    }

    fun saveConnection(baseUrl: String, modelBackground: String, modelAnswer: String) = updateAi {
        val url = baseUrl.trim().trimEnd('/')
        val changed = it.copy(baseUrl = url, modelBackground = modelBackground.trim(), modelAnswer = modelAnswer.trim())
        // Otra URL es otro destinatario: hay que volver a aceptar el aviso.
        if (url == it.baseUrl) changed else changed.copy(enabled = false, consentAt = null)
    }

    fun saveKey(key: String) {
        viewModelScope.launch {
            container.secrets.setApiKey(key.trim())
            _test.value = ConnectionTest.Idle
        }
    }

    fun clearKey() {
        viewModelScope.launch {
            container.secrets.clearApiKey()
            container.settings.update { it.copy(ai = it.ai.copy(enabled = false)) }
        }
    }

    fun accept() = updateAi { it.copy(enabled = true, consentAt = System.currentTimeMillis()) }

    fun setEnabled(on: Boolean) = updateAi { it.copy(enabled = on) }

    fun testConnection() {
        if (_test.value == ConnectionTest.Running) return
        _test.value = ConnectionTest.Running
        viewModelScope.launch {
            _test.value = container.ai.testConnection().fold({ ConnectionTest.Ok(it) }, { ConnectionTest.Failed(classifyAiError(it)) })
        }
    }

    private fun updateAi(transform: (AiSettings) -> AiSettings) {
        viewModelScope.launch { container.settings.update { it.copy(ai = transform(it.ai)) } }
    }
}

private val LOCAL_HOSTS = setOf("10.0.2.2", "localhost", "127.0.0.1")

/**
 * https, o http solo hacia el propio equipo (servidor falso de pruebas en el emulador) y solo en debug y qa: en
 * release el networkSecurityConfig no deja texto plano y la petición fallaría como si no hubiera red (§9).
 */
fun isValidBaseUrl(url: String, allowLocalHttp: Boolean = BuildConfig.ALLOW_LOCAL_HTTP): Boolean {
    val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return false
    val host = uri.host ?: return false
    return uri.scheme == "https" || (allowLocalHttp && uri.scheme == "http" && host in LOCAL_HOSTS)
}

/** La URL es la del preset, sin tocar: solo entonces valen su nombre y su aviso de privacidad. */
fun isPresetUrl(ai: AiSettings): Boolean {
    val preset = AiPresets.byId(ai.presetId)
    return preset != null && !preset.editable && ai.baseUrl == preset.baseUrl
}

/** Nombre del proveedor para los avisos; en el personalizado o con otra URL, el dominio al que se envía. */
@Composable
fun providerName(ai: AiSettings): String {
    val preset = AiPresets.byId(ai.presetId)
    return if (preset != null && isPresetUrl(ai)) {
        stringResource(preset.nameRes)
    } else {
        runCatching { URI(ai.baseUrl).host }.getOrNull() ?: stringResource(R.string.ai_preset_custom)
    }
}

@Composable
fun SettingsAiScreen(onBack: () -> Unit) {
    val container = LocalContext.current.container
    val vm = viewModel { SettingsAiViewModel(container) }
    val hasKey by vm.hasKey.collectAsStateWithLifecycle()
    val test by vm.test.collectAsStateWithLifecycle()
    val ai = vm.ai.collectAsStateWithLifecycle().value ?: return
    SettingsAiContent(
        ai = ai,
        hasKey = hasKey,
        test = test,
        onBack = onBack,
        onChoosePreset = vm::choosePreset,
        onSaveConnection = vm::saveConnection,
        onSaveKey = vm::saveKey,
        onClearKey = vm::clearKey,
        onAccept = vm::accept,
        onSetEnabled = vm::setEnabled,
        onTest = vm::testConnection,
    )
}

@Composable
private fun SettingsAiContent(
    ai: AiSettings,
    hasKey: Boolean,
    test: ConnectionTest,
    onBack: () -> Unit,
    onChoosePreset: (AiPreset) -> Unit,
    onSaveConnection: (String, String, String) -> Unit,
    onSaveKey: (String) -> Unit,
    onClearKey: () -> Unit,
    onAccept: () -> Unit,
    onSetEnabled: (Boolean) -> Unit,
    onTest: () -> Unit,
) {
    val preset = AiPresets.byId(ai.presetId)
    val provider = providerName(ai)
    var showConsent by rememberSaveable { mutableStateOf(false) }
    var presetToConfirm by remember { mutableStateOf<AiPreset?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val needKey = stringResource(R.string.settings_ai_need_key)
    val needConnection = stringResource(R.string.settings_ai_need_connection)
    val ready = isValidBaseUrl(ai.baseUrl) && ai.modelBackground.isNotBlank() && ai.modelAnswer.isNotBlank()

    val toggle: (Boolean) -> Unit = { on ->
        when {
            !on -> onSetEnabled(false)
            !hasKey -> scope.launch { snackbar.showSnackbar(needKey) }
            !ready -> scope.launch { snackbar.showSnackbar(needConnection) }
            ai.consentAt == null -> showConsent = true
            else -> onSetEnabled(true)
        }
    }

    Scaffold(
        topBar = { BackTopBar(stringResource(R.string.title_settings_ai), onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding().verticalScroll(rememberScrollState()),
        ) {
            Note(stringResource(R.string.settings_ai_intro), modifier = Modifier.padding(top = 8.dp))
            SwitchRow(
                title = stringResource(R.string.settings_ai_use),
                summary = when {
                    ai.enabled && ai.consentAt != null -> stringResource(R.string.settings_ai_on_since, provider, Time.dayShort(ai.consentAt))
                    !hasKey -> stringResource(R.string.settings_ai_off_no_key)
                    else -> stringResource(R.string.settings_ai_off)
                },
                checked = ai.enabled,
                onCheckedChange = toggle,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            SectionHeader(stringResource(R.string.settings_ai_section_provider))
            Column(Modifier.selectableGroup()) {
                AiPresets.all.forEach { p ->
                    PresetRow(p, selected = p.id == ai.presetId) {
                        if (p.id != ai.presetId) {
                            if (hasKey) presetToConfirm = p else onChoosePreset(p)
                        }
                    }
                }
            }
            Note(stringResource(R.string.settings_ai_preset_note))

            SectionHeader(stringResource(R.string.settings_ai_section_key))
            KeyField(hasKey, onSaveKey, onClearKey)

            SectionHeader(stringResource(R.string.settings_ai_section_connection))
            ConnectionFields(ai, hasKey, test, onSaveConnection, onTest)

            SectionHeader(stringResource(R.string.settings_ai_section_cost))
            Note(
                stringResource(
                    when (ai.presetId) {
                        "anthropic" -> R.string.settings_ai_cost_anthropic
                        "mistral" -> R.string.settings_ai_cost_mistral
                        "deepseek" -> R.string.settings_ai_cost_deepseek
                        else -> R.string.settings_ai_cost_custom
                    },
                ),
            )
            if (ai.modelBackground.contains("opus", ignoreCase = true)) {
                Note(stringResource(R.string.settings_ai_cost_opus), icon = Icons.Outlined.WarningAmber, color = MaterialTheme.colorScheme.error)
            }
            Note(stringResource(R.string.settings_ai_cost_note), modifier = Modifier.padding(bottom = 24.dp))
        }
    }

    if (showConsent) {
        ConsentDialog(
            provider = provider,
            host = runCatching { URI(ai.baseUrl).host }.getOrNull() ?: ai.baseUrl,
            preset = preset,
            presetUrl = isPresetUrl(ai),
            onAccept = {
                showConsent = false
                onAccept()
            },
            onDismiss = { showConsent = false },
        )
    }
    presetToConfirm?.let { p ->
        ConfirmDialog(
            title = stringResource(R.string.settings_ai_change_title, stringResource(p.nameRes)),
            message = stringResource(R.string.settings_ai_change_message),
            confirmLabel = stringResource(R.string.settings_ai_change_confirm),
            onConfirm = {
                presetToConfirm = null
                onChoosePreset(p)
            },
            onDismiss = { presetToConfirm = null },
        )
    }
}

@Composable
private fun PresetRow(p: AiPreset, selected: Boolean, onClick: () -> Unit) {
    val warn = if (p.discouraged) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    ListItem(
        headlineContent = { Text(stringResource(p.nameRes)) },
        overlineContent = if (p.discouraged) {
            { Text(stringResource(R.string.settings_ai_discouraged), color = MaterialTheme.colorScheme.error) }
        } else {
            null
        },
        supportingContent = p.noticeRes?.let { res -> { Text(stringResource(res), color = warn) } },
        leadingContent = { RadioButton(selected = selected, onClick = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
    )
}

@Composable
private fun KeyField(hasKey: Boolean, onSave: (String) -> Unit, onClear: () -> Unit) {
    // La clave guardada nunca vuelve a la pantalla: este campo solo sirve para escribir una nueva.
    var key by rememberSaveable { mutableStateOf("") }
    var visible by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = key,
            onValueChange = { key = it },
            label = { Text(stringResource(if (hasKey) R.string.settings_ai_key_replace else R.string.settings_ai_key)) },
            singleLine = true,
            leadingIcon = { Icon(Icons.Outlined.Key, contentDescription = null) },
            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            trailingIcon = {
                IconButton(onClick = { visible = !visible }) {
                    Icon(
                        if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        contentDescription = stringResource(if (visible) R.string.settings_ai_key_hide else R.string.settings_ai_key_show),
                    )
                }
            },
            supportingText = { Text(stringResource(if (hasKey) R.string.settings_ai_key_saved else R.string.settings_ai_key_help)) },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    onSave(key)
                    key = ""
                    visible = false
                },
                enabled = key.isNotBlank(),
            ) { Text(stringResource(R.string.settings_ai_key_save)) }
            if (hasKey) {
                TextButton(onClick = onClear) { Text(stringResource(R.string.settings_ai_key_clear), color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@Composable
private fun ConnectionFields(
    ai: AiSettings,
    hasKey: Boolean,
    test: ConnectionTest,
    onSave: (String, String, String) -> Unit,
    onTest: () -> Unit,
) {
    var url by rememberSaveable(ai.baseUrl) { mutableStateOf(ai.baseUrl) }
    var background by rememberSaveable(ai.modelBackground) { mutableStateOf(ai.modelBackground) }
    var answer by rememberSaveable(ai.modelAnswer) { mutableStateOf(ai.modelAnswer) }
    val urlOk = isValidBaseUrl(url)
    val dirty = url.trim().trimEnd('/') != ai.baseUrl || background.trim() != ai.modelBackground || answer.trim() != ai.modelAnswer
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text(stringResource(R.string.settings_ai_url)) },
            singleLine = true,
            isError = url.isNotBlank() && !urlOk,
            supportingText = if (url.isNotBlank() && !urlOk) {
                { Text(stringResource(R.string.settings_ai_url_invalid)) }
            } else {
                null
            },
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = background,
            onValueChange = { background = it },
            label = { Text(stringResource(R.string.settings_ai_model_background)) },
            supportingText = { Text(stringResource(R.string.settings_ai_model_background_help)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = answer,
            onValueChange = { answer = it },
            label = { Text(stringResource(R.string.settings_ai_model_answer)) },
            supportingText = { Text(stringResource(R.string.settings_ai_model_answer_help)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { onSave(url, background, answer) },
                enabled = dirty && urlOk && background.isNotBlank() && answer.isNotBlank(),
            ) { Text(stringResource(R.string.settings_ai_save_connection)) }
            OutlinedButton(onClick = onTest, enabled = hasKey && !dirty && urlOk && test != ConnectionTest.Running) {
                Text(stringResource(R.string.settings_ai_test))
            }
        }
        when (test) {
            ConnectionTest.Idle -> Unit
            ConnectionTest.Running -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.settings_ai_testing), style = MaterialTheme.typography.bodyMedium)
            }
            is ConnectionTest.Ok -> Note(
                test.reply.take(200).ifBlank { stringResource(R.string.settings_ai_test_ok) },
                modifier = Modifier.padding(horizontal = 0.dp),
                icon = Icons.Outlined.CheckCircle,
                color = MaterialTheme.colorScheme.tertiary,
            )
            is ConnectionTest.Failed -> Note(
                stringResource(test.error.messageRes()),
                icon = Icons.Outlined.ErrorOutline,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun ConsentDialog(provider: String, host: String, preset: AiPreset?, presetUrl: Boolean, onAccept: () -> Unit, onDismiss: () -> Unit) {
    val discouraged = preset?.discouraged == true
    var understood by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_ai_consent_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Con otra URL, el destino es ese servidor: ni se repite el dominio ni valen las garantías del proveedor.
                Text(
                    if (presetUrl) stringResource(R.string.settings_ai_consent_body, provider, host)
                    else stringResource(R.string.settings_ai_consent_body_host, host),
                )
                val notice = if (presetUrl) preset?.noticeRes else R.string.settings_ai_consent_other_server
                notice?.let { Text(stringResource(it), style = MaterialTheme.typography.bodyMedium) }
                if (discouraged) {
                    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
                        Text(
                            stringResource(R.string.settings_ai_consent_deepseek),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth().toggleable(value = understood, role = Role.Checkbox, onValueChange = { understood = it }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = understood, onCheckedChange = null)
                        Text(stringResource(R.string.settings_ai_consent_understood), modifier = Modifier.padding(start = 12.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onAccept, enabled = !discouraged || understood) { Text(stringResource(R.string.settings_ai_consent_accept)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Preview(showBackground = true, heightDp = 1600)
@Composable
private fun SettingsAiPreview() {
    MysoneTheme {
        SettingsAiContent(
            ai = AiSettings(),
            hasKey = true,
            test = ConnectionTest.Failed(AiError.AUTH),
            onBack = {},
            onChoosePreset = {},
            onSaveConnection = { _, _, _ -> },
            onSaveKey = {},
            onClearKey = {},
            onAccept = {},
            onSetEnabled = {},
            onTest = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ConsentPreview() {
    MysoneTheme { ConsentDialog("DeepSeek", "api.deepseek.com", AiPresets.byId("deepseek"), presetUrl = true, {}, {}) }
}
