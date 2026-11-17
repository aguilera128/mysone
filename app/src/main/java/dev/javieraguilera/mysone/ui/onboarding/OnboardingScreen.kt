package dev.javieraguilera.mysone.ui.onboarding

import android.Manifest
import android.annotation.SuppressLint
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.Hearing
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.javieraguilera.mysone.R
import dev.javieraguilera.mysone.asr.AsrModel
import dev.javieraguilera.mysone.asr.DownloadWait
import dev.javieraguilera.mysone.asr.ModelInstall
import dev.javieraguilera.mysone.capture.ListeningController
import dev.javieraguilera.mysone.container
import dev.javieraguilera.mysone.core.Time
import dev.javieraguilera.mysone.data.AppSettings
import dev.javieraguilera.mysone.ui.settings.RETENTION_OPTIONS
import dev.javieraguilera.mysone.ui.settings.RadioRow
import dev.javieraguilera.mysone.ui.settings.SettingsAiScreen
import dev.javieraguilera.mysone.ui.settings.SwitchRow
import dev.javieraguilera.mysone.ui.settings.providerName
import dev.javieraguilera.mysone.ui.settings.requestUnrestricted
import dev.javieraguilera.mysone.ui.theme.MysoneTheme

// MainActivity marca onboardingDone al llamar a onFinished.
@Composable
fun OnboardingScreen(onFinished: () -> Unit) {
    val context = LocalContext.current
    val container = context.container
    val vm = viewModel { OnboardingViewModel(container, createSavedStateHandle()) }
    val installs by vm.installs.collectAsStateWithLifecycle()
    val legalAcceptedAt by vm.legalAcceptedAt.collectAsStateWithLifecycle()
    val mic = rememberPermissionState(Manifest.permission.RECORD_AUDIO)
    @SuppressLint("InlinedApi") // el paso de notificaciones solo existe desde Android 13 (OnboardingViewModel.steps)
    val notifications = rememberPermissionState(Manifest.permission.POST_NOTIFICATIONS)
    val install = installs[vm.selectedModelId] ?: ModelInstall.NotInstalled
    // isInstalled mira el disco: solo se vuelve a preguntar cuando cambia el estado de la descarga.
    val modelReady = remember(install, vm.selectedModelId) {
        install == ModelInstall.Installed || vm.models.isInstalled(vm.selectedModelId)
    }

    val settings by vm.settings.collectAsStateWithLifecycle()
    // Exención de batería: se vuelve a mirar al volver del diálogo de Android.
    val power = remember { context.getSystemService(android.os.PowerManager::class.java) }
    var unrestricted by remember { mutableStateOf(power.isIgnoringBatteryOptimizations(context.packageName)) }
    LifecycleResumeEffect(Unit) {
        unrestricted = power.isIgnoringBatteryOptimizations(context.packageName)
        onPauseOrDispose { }
    }
    // La configuración de la IA se abre encima de la bienvenida, con la misma pantalla que en Ajustes.
    var aiOpen by rememberSaveable { mutableStateOf(false) }
    if (aiOpen) {
        BackHandler { aiOpen = false }
        SettingsAiScreen(onBack = { aiOpen = false })
        return
    }

    BackHandler(enabled = vm.index > 0) { vm.back() }

    OnboardingFrame(index = vm.index, count = vm.steps.size, onBack = vm::back) {
        when (vm.step) {
            OnboardingStep.Intro -> IntroStep(onNext = vm::next)
            OnboardingStep.HowItWorks -> HowItWorksStep(onNext = vm::next)
            OnboardingStep.Legal -> LegalStep(
                checked = vm.legalChecked,
                acceptedAt = legalAcceptedAt,
                onCheckedChange = { vm.legalChecked = it },
                onAccept = vm::acceptLegal,
            )
            OnboardingStep.Mic -> PermissionStep(
                icon = Icons.Outlined.Mic,
                title = stringResource(R.string.onb_mic_title),
                body = stringResource(R.string.onb_mic_body),
                grantLabel = stringResource(R.string.onb_mic_grant),
                grantedLabel = stringResource(R.string.onb_mic_granted),
                deniedLabel = stringResource(R.string.onb_mic_denied),
                state = mic,
                onNext = vm::next,
            )
            OnboardingStep.Notifications -> PermissionStep(
                icon = Icons.Outlined.NotificationsActive,
                title = stringResource(R.string.onb_notif_title),
                body = stringResource(R.string.onb_notif_body),
                grantLabel = stringResource(R.string.onb_notif_grant),
                grantedLabel = stringResource(R.string.onb_notif_granted),
                deniedLabel = stringResource(R.string.onb_notif_denied),
                state = notifications,
                onNext = vm::next,
            )
            OnboardingStep.Model -> ModelStep(
                catalog = vm.models.catalog,
                ramGb = vm.ramGb,
                recommendedId = vm.recommendedModelId,
                selectedId = vm.selectedModelId,
                install = if (modelReady) ModelInstall.Installed else install,
                wifiOnly = vm.wifiOnly,
                onSelect = vm::selectModel,
                onWifiOnlyChange = vm::changeWifiOnly,
                onDownload = vm::download,
                onCancel = vm::cancelDownload,
                onNext = vm::next,
            )
            OnboardingStep.Options -> OptionsStep(
                settings = settings ?: AppSettings(),
                unrestricted = unrestricted,
                onUpdate = vm::updateSettings,
                onRequestUnrestricted = { requestUnrestricted(context) },
                onNext = vm::next,
            )
            OnboardingStep.Ai -> AiStep(
                aiEnabled = settings?.ai?.enabled == true,
                provider = settings?.ai?.let { providerName(it) }.orEmpty(),
                onConfigure = { aiOpen = true },
                onNext = vm::next,
            )
            OnboardingStep.Start -> StartStep(
                micReady = mic.granted,
                modelReady = modelReady,
                onStart = {
                    ListeningController.start(context)
                    onFinished()
                },
                onFinish = onFinished,
            )
        }
    }
}

/** Cabecera común: volver y progreso. El contenido de cada paso trae sus propios botones. */
@Composable
private fun OnboardingFrame(index: Int, count: Int, onBack: () -> Unit, content: @Composable () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 8.dp),
            ) {
                if (index > 0) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.onb_back))
                    }
                } else {
                    Spacer(Modifier.size(48.dp))
                }
                Text(
                    stringResource(R.string.onb_step, index + 1, count),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            LinearProgressIndicator(
                progress = { (index + 1f) / count },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            content()
        }
    }
}

/** Cuerpo desplazable con icono y título, y los botones abajo, siempre a la vista. */
@Composable
private fun StepLayout(
    icon: ImageVector,
    title: String,
    buttons: @Composable ColumnScope.() -> Unit,
    body: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                Modifier.size(56.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
            body()
        }
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            content = buttons,
        )
    }
}

@Composable
private fun Paragraph(text: String, muted: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun PrimaryButton(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(text) }
}

@Composable
private fun SecondaryButton(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(text) }
}

/** Fila de lista con icono: marca de "sí" en salvia o de "no" en neutro. */
@Composable
private fun CheckLine(text: String, positive: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(
            if (positive) Icons.Filled.Check else Icons.Filled.Close,
            contentDescription = null,
            tint = if (positive) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp).padding(top = 2.dp),
        )
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    }
}

// --- Pasos ---

@Composable
private fun IntroStep(onNext: () -> Unit) {
    StepLayout(
        icon = Icons.Outlined.Hearing,
        title = stringResource(R.string.onb_intro_title),
        buttons = { PrimaryButton(stringResource(R.string.onb_next), onNext) },
    ) {
        Paragraph(stringResource(R.string.onb_intro_body))
        Text(stringResource(R.string.onb_intro_does), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        CheckLine(stringResource(R.string.onb_intro_does_1), positive = true)
        CheckLine(stringResource(R.string.onb_intro_does_2), positive = true)
        CheckLine(stringResource(R.string.onb_intro_does_3), positive = true)
        Text(stringResource(R.string.onb_intro_doesnt), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        CheckLine(stringResource(R.string.onb_intro_doesnt_1), positive = false)
        CheckLine(stringResource(R.string.onb_intro_doesnt_2), positive = false)
        CheckLine(stringResource(R.string.onb_intro_doesnt_3), positive = false)
    }
}

@Composable
private fun LegalStep(checked: Boolean, acceptedAt: Long?, onCheckedChange: (Boolean) -> Unit, onAccept: () -> Unit) {
    StepLayout(
        icon = Icons.Outlined.Gavel,
        title = stringResource(R.string.onb_legal_title),
        buttons = {
            // La casilla va junto al botón para que no se pueda aceptar sin verla.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .toggleable(value = checked, role = Role.Checkbox, onValueChange = onCheckedChange)
                    .padding(vertical = 8.dp),
            ) {
                Checkbox(checked = checked, onCheckedChange = null)
                Text(stringResource(R.string.onb_legal_check), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 12.dp))
            }
            PrimaryButton(stringResource(R.string.onb_legal_accept), onAccept, enabled = checked)
        },
    ) {
        Paragraph(stringResource(R.string.onb_legal_intro))
        LegalPoint(R.string.onb_legal_1_lead, R.string.onb_legal_1_body)
        LegalPoint(R.string.onb_legal_2_lead, R.string.onb_legal_2_body)
        LegalPoint(R.string.onb_legal_3_lead, R.string.onb_legal_3_body)
        LegalPoint(R.string.onb_legal_4_lead, R.string.onb_legal_4_body)
        LegalPoint(R.string.onb_legal_5_lead, R.string.onb_legal_5_body)
        Paragraph(stringResource(R.string.onb_legal_disclaimer), muted = true)
        if (acceptedAt != null) {
            Text(
                stringResource(R.string.onb_legal_accepted, Time.dateTimeUi(acceptedAt)),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LegalPoint(lead: Int, body: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(stringResource(lead), style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold))
        Text(stringResource(body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PermissionStep(
    icon: ImageVector,
    title: String,
    body: String,
    grantLabel: String,
    grantedLabel: String,
    deniedLabel: String,
    state: PermissionState,
    onNext: () -> Unit,
) {
    val context = LocalContext.current
    StepLayout(
        icon = icon,
        title = title,
        buttons = {
            if (state.granted) {
                PrimaryButton(stringResource(R.string.onb_next), onNext)
            } else {
                PrimaryButton(grantLabel, state.request)
                SecondaryButton(stringResource(R.string.onb_not_now), onNext)
            }
        },
    ) {
        Paragraph(body)
        when {
            state.granted -> StatusLine(Icons.Filled.Check, grantedLabel, MaterialTheme.colorScheme.tertiary)
            state.denied -> {
                Paragraph(deniedLabel, muted = true)
                TextButton(onClick = { openAppSettings(context) }) { Text(stringResource(R.string.onb_open_settings)) }
            }
        }
    }
}

@Composable
private fun StatusLine(icon: ImageVector, text: String, tint: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Text(text, style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun ModelStep(
    catalog: List<AsrModel>,
    ramGb: Int,
    recommendedId: String,
    selectedId: String,
    install: ModelInstall,
    wifiOnly: Boolean,
    onSelect: (String) -> Unit,
    onWifiOnlyChange: (Boolean) -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onNext: () -> Unit,
) {
    val context = LocalContext.current
    val selected = catalog.firstOrNull { it.id == selectedId }
    val size = selected?.let { Formatter.formatShortFileSize(context, it.sizeBytes) }.orEmpty()
    val busy = install is ModelInstall.Downloading || install is ModelInstall.Verifying
    StepLayout(
        icon = Icons.Outlined.Download,
        title = stringResource(R.string.onb_model_title),
        buttons = {
            // Junto a los botones y no al final del cuerpo: en una pantalla alta quedaba fuera de la vista.
            if (install != ModelInstall.NotInstalled) Box(Modifier.padding(bottom = 8.dp)) { InstallProgress(install) }
            when (install) {
                ModelInstall.NotInstalled, is ModelInstall.Failed -> {
                    PrimaryButton(stringResource(if (install is ModelInstall.Failed) R.string.action_retry else R.string.onb_model_download, size), onDownload)
                    SecondaryButton(stringResource(R.string.onb_not_now), onNext)
                }
                is ModelInstall.Downloading -> {
                    PrimaryButton(stringResource(R.string.onb_next), onNext)
                    SecondaryButton(stringResource(R.string.onb_model_cancel), onCancel) // a la vista, no al final del scroll
                }
                else -> PrimaryButton(stringResource(R.string.onb_next), onNext)
            }
        },
    ) {
        Paragraph(stringResource(R.string.onb_model_body))
        if (ramGb > 0) Paragraph(stringResource(R.string.onb_model_ram, ramGb), muted = true)
        catalog.forEach { model ->
            val fits = ramGb == 0 || ramGb >= model.recommendedMinRamGb
            ModelOption(
                model = model,
                size = Formatter.formatShortFileSize(context, model.sizeBytes),
                // Con menos RAM de la que pide no se recomienda (Ajustes › Transcripción avisa igual).
                recommended = model.id == recommendedId && fits,
                fits = fits,
                selected = model.id == selectedId,
                enabled = !busy,
                onSelect = { onSelect(model.id) },
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                // Se aplica al encolar: durante la descarga cambiarlo no haría nada.
                .toggleable(value = wifiOnly, enabled = !busy, role = Role.Switch, onValueChange = onWifiOnlyChange),
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.onb_wifi_only), style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(R.string.onb_wifi_only_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = wifiOnly, onCheckedChange = null, enabled = !busy)
        }
    }
}

@Composable
private fun ModelOption(model: AsrModel, size: String, recommended: Boolean, fits: Boolean, selected: Boolean, enabled: Boolean, onSelect: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Surface(
        color = if (selected) c.surfaceContainerLow else c.background,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, if (selected) c.primary else c.outlineVariant),
        modifier = Modifier.fillMaxWidth().selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect),
    ) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RadioButton(selected = selected, onClick = null, enabled = enabled, modifier = Modifier.padding(top = 2.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(model.name, style = MaterialTheme.typography.titleMedium)
                if (recommended) {
                    Text(stringResource(R.string.onb_model_recommended), style = MaterialTheme.typography.labelMedium, color = c.tertiary)
                }
                Text(model.description, style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
                Text(size, style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant)
                if (!fits) {
                    Text(stringResource(R.string.models_low_ram, model.recommendedMinRamGb), style = MaterialTheme.typography.bodySmall, color = c.error)
                }
            }
        }
    }
}

@Composable
private fun InstallProgress(install: ModelInstall) {
    when (install) {
        is ModelInstall.Downloading -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val progress = install.progress.coerceIn(0f, 1f)
            val percent = (progress * 100).toInt()
            val label = when (install.waiting) {
                DownloadWait.WIFI -> R.string.asr_download_waiting_wifi
                DownloadWait.NETWORK -> R.string.asr_download_waiting_network
                null -> R.string.onb_model_downloading
            }
            Text(stringResource(label, percent), style = MaterialTheme.typography.titleSmall)
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            Text(stringResource(R.string.onb_model_keep_going), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        ModelInstall.Verifying -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.onb_model_verifying), style = MaterialTheme.typography.titleSmall)
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        ModelInstall.Installed -> StatusLine(Icons.Filled.Check, stringResource(R.string.onb_model_installed), MaterialTheme.colorScheme.tertiary)
        is ModelInstall.Failed -> Text(
            stringResource(R.string.onb_model_failed, install.message),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        ModelInstall.NotInstalled -> Unit
    }
}

@Composable
private fun HowItWorksStep(onNext: () -> Unit) {
    StepLayout(
        icon = Icons.Outlined.Lightbulb,
        title = stringResource(R.string.onb_how_title),
        buttons = { PrimaryButton(stringResource(R.string.onb_next), onNext) },
    ) {
        LegalPoint(R.string.onb_how_1_lead, R.string.onb_how_1_body)
        LegalPoint(R.string.onb_how_2_lead, R.string.onb_how_2_body)
        LegalPoint(R.string.onb_how_3_lead, R.string.onb_how_3_body)
        LegalPoint(R.string.onb_how_4_lead, R.string.onb_how_4_body)
        LegalPoint(R.string.onb_how_5_lead, R.string.onb_how_5_body)
    }
}

/** Las opciones que más cambian el día a día; el resto se queda en Ajustes. Se guardan al tocarlas. */
@Composable
private fun OptionsStep(
    settings: AppSettings,
    unrestricted: Boolean,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
    onRequestUnrestricted: () -> Unit,
    onNext: () -> Unit,
) {
    StepLayout(
        icon = Icons.Outlined.Tune,
        title = stringResource(R.string.onb_options_title),
        buttons = { PrimaryButton(stringResource(R.string.onb_next), onNext) },
    ) {
        Paragraph(stringResource(R.string.onb_options_body), muted = true)
        Text(stringResource(R.string.privacy_section_retention), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        Paragraph(stringResource(R.string.privacy_retention_desc), muted = true)
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
        Column {
            SwitchRow(
                title = stringResource(R.string.settings_quick_reminders),
                summary = stringResource(R.string.settings_quick_reminders_desc),
                checked = settings.quickReminders,
                onCheckedChange = { on -> onUpdate { it.copy(quickReminders = on) } },
            )
            SwitchRow(
                title = stringResource(R.string.settings_battery_saver),
                summary = stringResource(R.string.settings_battery_saver_desc),
                checked = settings.batterySaver,
                onCheckedChange = { on -> onUpdate { it.copy(batterySaver = on) } },
            )
            SwitchRow(
                title = stringResource(R.string.privacy_secure_screen),
                summary = stringResource(R.string.privacy_secure_screen_desc),
                checked = settings.secureScreen,
                onCheckedChange = { on -> onUpdate { it.copy(secureScreen = on) } },
            )
        }
        if (unrestricted) {
            CheckLine(stringResource(R.string.settings_battery_ok), positive = true)
        } else {
            Paragraph(stringResource(R.string.settings_battery_optimized))
            Button(onClick = onRequestUnrestricted, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(R.string.settings_battery_request))
            }
        }
        Paragraph(stringResource(R.string.onb_options_later), muted = true)
    }
}

@Composable
private fun AiStep(aiEnabled: Boolean, provider: String, onConfigure: () -> Unit, onNext: () -> Unit) {
    StepLayout(
        icon = Icons.Outlined.AutoAwesome,
        title = stringResource(R.string.onb_ai_title),
        buttons = {
            if (aiEnabled) {
                PrimaryButton(stringResource(R.string.onb_next), onNext)
                SecondaryButton(stringResource(R.string.onb_ai_change), onConfigure)
            } else {
                PrimaryButton(stringResource(R.string.onb_ai_configure), onConfigure)
                SecondaryButton(stringResource(R.string.onb_ai_skip), onNext)
            }
        },
    ) {
        Paragraph(stringResource(R.string.onb_ai_body_1))
        Paragraph(stringResource(R.string.onb_ai_body_2), muted = true)
        if (aiEnabled) {
            CheckLine(stringResource(R.string.onb_ai_enabled, provider), positive = true)
        } else {
            Text(stringResource(R.string.onb_ai_question), style = MaterialTheme.typography.titleMedium)
            Paragraph(stringResource(R.string.onb_ai_later), muted = true)
        }
    }
}

@Composable
private fun StartStep(micReady: Boolean, modelReady: Boolean, onStart: () -> Unit, onFinish: () -> Unit) {
    val ready = micReady && modelReady
    StepLayout(
        icon = Icons.Outlined.TaskAlt,
        title = stringResource(R.string.onb_start_title),
        buttons = {
            if (ready) {
                PrimaryButton(stringResource(R.string.onb_start_listen), onStart)
                SecondaryButton(stringResource(R.string.onb_not_now), onFinish)
            } else {
                PrimaryButton(stringResource(R.string.onb_start_go_today), onFinish)
            }
        },
    ) {
        Paragraph(stringResource(R.string.onb_start_body))
        ReadyLine(stringResource(R.string.onb_start_mic), micReady)
        ReadyLine(stringResource(R.string.onb_start_model), modelReady)
        if (!ready) Paragraph(stringResource(R.string.onb_start_missing), muted = true)
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ReadyLine(label: String, ok: Boolean) {
    val state = stringResource(if (ok) R.string.onb_check_ok else R.string.onb_check_missing)
    CheckLine("$label: $state", positive = ok)
}

// --- Vistas previas con datos falsos ---

private val previewModels = listOf(
    AsrModel("canary-180m-es", "Canary 180M (español)", "Ligero: descarga de 207 MB y poca memoria. Fija el idioma al español.", 207_170_046, 4, "CC-BY-4.0", emptyList()),
    AsrModel("parakeet-v3", "Parakeet TDT 0.6B v3", "El más preciso y escribe los números en cifras. Multilingüe (25 idiomas). Descarga de 670 MB.", 670_478_772, 6, "CC-BY-4.0", emptyList()),
)

@Preview(name = "Bienvenida · aviso legal", showBackground = true, heightDp = 900)
@Composable
private fun LegalPreview() {
    MysoneTheme { OnboardingFrame(index = 1, count = 7, onBack = {}) { LegalStep(checked = false, acceptedAt = null, onCheckedChange = {}, onAccept = {}) } }
}

@Preview(name = "Bienvenida · modelo", showBackground = true, heightDp = 900)
@Composable
private fun ModelPreview() {
    MysoneTheme {
        OnboardingFrame(index = 4, count = 7, onBack = {}) {
            ModelStep(previewModels, ramGb = 6, recommendedId = "parakeet-v3", selectedId = "parakeet-v3", install = ModelInstall.Downloading(0.42f), wifiOnly = true,
                onSelect = {}, onWifiOnlyChange = {}, onDownload = {}, onCancel = {}, onNext = {})
        }
    }
}

@Preview(name = "Bienvenida · inicio, oscuro", showBackground = true, heightDp = 800, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun IntroPreview() {
    MysoneTheme(darkTheme = true) { OnboardingFrame(index = 0, count = 7, onBack = {}) { IntroStep(onNext = {}) } }
}

@Preview(name = "Preferencias · letra grande", showBackground = true, heightDp = 1400, fontScale = 1.5f)
@Composable
private fun OptionsPreview() {
    MysoneTheme { OptionsStep(settings = AppSettings(), unrestricted = false, onUpdate = {}, onRequestUnrestricted = {}, onNext = {}) }
}

@Preview(name = "IA · sin configurar", showBackground = true)
@Composable
private fun AiOffPreview() {
    MysoneTheme { AiStep(aiEnabled = false, provider = "", onConfigure = {}, onNext = {}) }
}

@Preview(name = "IA · activada", showBackground = true, locale = "en")
@Composable
private fun AiOnPreview() {
    MysoneTheme { AiStep(aiEnabled = true, provider = "Anthropic", onConfigure = {}, onNext = {}) }
}

@Preview(name = "Cómo funciona · estrecho", showBackground = true, widthDp = 320, heightDp = 900)
@Composable
private fun HowItWorksPreview() {
    MysoneTheme { HowItWorksStep(onNext = {}) }
}
