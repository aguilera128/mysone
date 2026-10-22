package dev.javieraguilera.mysone.ui.settings

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
import dev.javieraguilera.mysone.asr.ModelInstall
import dev.javieraguilera.mysone.capture.ListeningService
import dev.javieraguilera.mysone.container
import dev.javieraguilera.mysone.core.Time
import dev.javieraguilera.mysone.data.AppSettings
import dev.javieraguilera.mysone.ui.components.BackTopBar
import dev.javieraguilera.mysone.ui.components.SectionHeader
import dev.javieraguilera.mysone.ui.theme.MysoneTheme
import java.util.Locale
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val container: AppContainer) : ViewModel() {
    val settings: StateFlow<AppSettings?> = container.settings.data
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val installs = container.models.installs
    val catalog = container.models.catalog

    fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { container.settings.update(transform) }
    }
}

private const val AUDIO_VOICE = "VOICE_RECOGNITION"
private const val AUDIO_MIC = "MIC"

/** [lastExit]: texto del motivo y hora del último cierre de la app con la escucha en marcha (ListeningService.lastExit). */
data class BatteryState(val unrestricted: Boolean, val backgroundRestricted: Boolean, val lastExit: Pair<Int, Long>? = null)

private fun batteryState(context: Context) = BatteryState(
    unrestricted = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName),
    backgroundRestricted = context.getSystemService(ActivityManager::class.java).isBackgroundRestricted,
    lastExit = ListeningService.lastExit(context),
)

/** Guía de dontkillmyapp.com para el fabricante ("Xiaomi" → https://dontkillmyapp.com/xiaomi). */
fun dontKillMyAppUrl(manufacturer: String): String =
    "https://dontkillmyapp.com/" + manufacturer.trim().lowercase(Locale.ROOT).replace(' ', '-').filter { it.isLetterOrDigit() || it == '-' }

@Composable
fun SettingsScreen(onBack: () -> Unit, onOpenAi: () -> Unit, onOpenModels: () -> Unit, onOpenPrivacy: () -> Unit, onOpenAbout: () -> Unit) {
    val context = LocalContext.current
    val container = context.container
    val vm = viewModel { SettingsViewModel(container) }
    val installs by vm.installs.collectAsStateWithLifecycle()
    var battery by remember { mutableStateOf(batteryState(context)) }
    // Vuelve de los ajustes del sistema con la exención ya concedida (o no).
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { battery = batteryState(context) }
    val settings = vm.settings.collectAsStateWithLifecycle().value ?: return

    val model = vm.catalog.firstOrNull { it.id == settings.asrModelId }
    val modelSummary = when {
        model == null -> stringResource(R.string.settings_models_none)
        installs[model.id] is ModelInstall.Installed -> model.name
        else -> stringResource(R.string.settings_models_missing, model.name)
    }

    SettingsContent(
        settings = settings,
        modelSummary = modelSummary,
        aiSummary = if (settings.ai.enabled) stringResource(R.string.settings_ai_on, providerName(settings.ai)) else stringResource(R.string.settings_ai_off),
        battery = battery,
        manufacturer = Build.MANUFACTURER,
        onUpdate = vm::update,
        onBack = onBack,
        onOpenAi = onOpenAi,
        onOpenModels = onOpenModels,
        onOpenPrivacy = onOpenPrivacy,
        onOpenAbout = onOpenAbout,
        onRequestUnrestricted = { requestUnrestricted(context) },
        onOpenAppDetails = {
            context.openSafely(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()))
        },
        onOpenGuide = { context.openUrl(dontKillMyAppUrl(Build.MANUFACTURER)) },
    )
}

// App instalada a mano: pedir la exención directamente está bien (docs/investigacion/02 §batería).
@SuppressLint("BatteryLife")
private fun requestUnrestricted(context: Context) {
    val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${context.packageName}".toUri())
    if (!context.openSafely(direct)) context.openSafely(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
}

@Composable
private fun SettingsContent(
    settings: AppSettings,
    modelSummary: String,
    aiSummary: String,
    battery: BatteryState,
    manufacturer: String,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
    onBack: () -> Unit,
    onOpenAi: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onOpenAbout: () -> Unit,
    onRequestUnrestricted: () -> Unit,
    onOpenAppDetails: () -> Unit,
    onOpenGuide: () -> Unit,
) {
    Scaffold(topBar = { BackTopBar(stringResource(R.string.title_settings), onBack) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).verticalScroll(rememberScrollState()),
        ) {
            SectionHeader(stringResource(R.string.settings_section_listening))
            Column(Modifier.selectableGroup()) {
                RadioRow(
                    title = stringResource(R.string.settings_source_voice),
                    summary = stringResource(R.string.settings_source_voice_desc),
                    selected = settings.audioSource != AUDIO_MIC,
                    onClick = { onUpdate { it.copy(audioSource = AUDIO_VOICE) } },
                )
                RadioRow(
                    title = stringResource(R.string.settings_source_mic),
                    summary = stringResource(R.string.settings_source_mic_desc),
                    selected = settings.audioSource == AUDIO_MIC,
                    onClick = { onUpdate { it.copy(audioSource = AUDIO_MIC) } },
                )
            }
            Note(stringResource(R.string.settings_source_note))
            GapSlider(settings.conversationGapMin) { minutes -> onUpdate { it.copy(conversationGapMin = minutes) } }
            SwitchRow(
                title = stringResource(R.string.settings_battery_saver),
                summary = stringResource(R.string.settings_battery_saver_desc),
                checked = settings.batterySaver,
                onCheckedChange = { on -> onUpdate { it.copy(batterySaver = on) } },
            )
            SwitchRow(
                title = stringResource(R.string.settings_quick_reminders),
                summary = stringResource(R.string.settings_quick_reminders_desc),
                checked = settings.quickReminders,
                onCheckedChange = { on -> onUpdate { it.copy(quickReminders = on) } },
            )

            HorizontalDivider(Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
            NavRow(Icons.Outlined.GraphicEq, stringResource(R.string.title_settings_models), modelSummary, onOpenModels)
            NavRow(Icons.Outlined.AutoAwesome, stringResource(R.string.title_settings_ai), aiSummary, onOpenAi)
            NavRow(
                Icons.Outlined.Shield,
                stringResource(R.string.title_settings_privacy),
                if (settings.retentionDays == 0) {
                    stringResource(R.string.settings_privacy_summary_keep)
                } else {
                    stringResource(R.string.settings_privacy_summary, settings.retentionDays)
                },
                onOpenPrivacy,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            SectionHeader(stringResource(R.string.settings_section_battery))
            BatterySection(battery, manufacturer, onRequestUnrestricted, onOpenAppDetails, onOpenGuide)

            HorizontalDivider(Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
            NavRow(Icons.Outlined.Info, stringResource(R.string.title_about), null, onOpenAbout)
        }
    }
}

@Composable
private fun GapSlider(minutes: Int, onChange: (Int) -> Unit) {
    // Valor local mientras se arrastra; se guarda al soltar.
    var value by remember(minutes) { mutableFloatStateOf(minutes.toFloat()) }
    val label = stringResource(R.string.settings_gap_value, value.toInt())
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.settings_gap), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(label, style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"), color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = value,
            onValueChange = { value = it },
            valueRange = 2f..15f,
            steps = 12,
            onValueChangeFinished = { onChange(value.toInt()) },
            modifier = Modifier.semantics { stateDescription = label },
        )
        Text(
            stringResource(R.string.settings_gap_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BatterySection(
    battery: BatteryState,
    manufacturer: String,
    onRequestUnrestricted: () -> Unit,
    onOpenAppDetails: () -> Unit,
    onOpenGuide: () -> Unit,
) {
    val brand = manufacturer.replaceFirstChar { it.titlecase(Locale.ROOT) }
    Column(Modifier.padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (battery.unrestricted) {
            Note(stringResource(R.string.settings_battery_ok), icon = Icons.Outlined.CheckCircle, color = MaterialTheme.colorScheme.tertiary)
        } else {
            Note(stringResource(R.string.settings_battery_optimized), icon = Icons.Outlined.BatteryAlert)
            Button(onClick = onRequestUnrestricted, modifier = Modifier.padding(horizontal = 16.dp)) {
                Text(stringResource(R.string.settings_battery_request))
            }
        }
        if (battery.backgroundRestricted) {
            Note(stringResource(R.string.settings_battery_restricted), icon = Icons.Outlined.BatteryAlert, color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = onOpenAppDetails, modifier = Modifier.padding(horizontal = 16.dp)) {
                Text(stringResource(R.string.settings_battery_app_details))
            }
        }
        battery.lastExit?.let { (reason, at) ->
            val time = stringResource(R.string.ui_day_time, Time.dayShort(at), Time.hourMinute(at))
            Note(stringResource(R.string.capture_last_exit, stringResource(reason), time))
        }
        val tip = when (manufacturer.lowercase(Locale.ROOT)) {
            "samsung" -> R.string.settings_battery_tip_samsung
            "xiaomi", "redmi", "poco" -> R.string.settings_battery_tip_xiaomi
            "google" -> R.string.settings_battery_tip_pixel
            else -> R.string.settings_battery_tip_other
        }
        Note(stringResource(tip, brand), modifier = Modifier.padding(top = 4.dp))
        TextButton(onClick = onOpenGuide, modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
            Text(stringResource(R.string.settings_battery_guide, brand), modifier = Modifier.weight(1f))
            Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null)
        }
    }
}

@Preview(showBackground = true, heightDp = 1400)
@Composable
private fun SettingsPreview() {
    MysoneTheme {
        SettingsContent(
            settings = AppSettings(),
            modelSummary = "Canary 180M (español)",
            aiSummary = "Desactivada",
            battery = BatteryState(unrestricted = false, backgroundRestricted = false),
            manufacturer = "samsung",
            onUpdate = {},
            onBack = {},
            onOpenAi = {},
            onOpenModels = {},
            onOpenPrivacy = {},
            onOpenAbout = {},
            onRequestUnrestricted = {},
            onOpenAppDetails = {},
            onOpenGuide = {},
        )
    }
}
