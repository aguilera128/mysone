package dev.javieraguilera.mysone.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.javieraguilera.mysone.BuildConfig
import dev.javieraguilera.mysone.R
import dev.javieraguilera.mysone.ui.components.BackTopBar
import dev.javieraguilera.mysone.ui.components.SectionHeader
import dev.javieraguilera.mysone.ui.theme.MysoneTheme

/** Componente de terceros con su licencia (docs/investigacion/07 F11). Nombres y licencias son nombres propios. */
private class Credit(val name: String, val license: String, val url: String, @StringRes val note: Int)

private val CREDITS = listOf(
    Credit("sherpa-onnx", "Apache-2.0", "https://github.com/k2-fsa/sherpa-onnx", R.string.about_sherpa),
    Credit("Silero VAD", "MIT", "https://github.com/snakers4/silero-vad", R.string.about_silero),
    Credit("NVIDIA Canary 180M Flash", "CC-BY-4.0", "https://huggingface.co/nvidia/canary-180m-flash", R.string.about_nvidia_model),
    Credit("NVIDIA Parakeet TDT 0.6B v3", "CC-BY-4.0", "https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3", R.string.about_nvidia_model),
    Credit("3D-Speaker ERes2Net (VoxCeleb)", "Apache-2.0", "https://github.com/modelscope/3D-Speaker", R.string.about_speaker_model),
)

// Sin /deed.xx: Creative Commons la enseña en el idioma del navegador.
private const val CC_BY_URL = "https://creativecommons.org/licenses/by/4.0/"
private const val SOURCE_URL = "https://github.com/aguilera128/mysone"

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    AboutContent(version = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", onBack = onBack, onOpen = { context.openUrl(it) })
}

@Composable
private fun AboutContent(version: String, onBack: () -> Unit, onOpen: (String) -> Unit) {
    Scaffold(topBar = { BackTopBar(stringResource(R.string.title_about), onBack) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).verticalScroll(rememberScrollState())) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
                Text(
                    stringResource(R.string.about_version, version),
                    style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(R.string.about_description),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            CreditRow(stringResource(R.string.app_name), stringResource(R.string.about_source), "GPL-3.0", onClick = { onOpen(SOURCE_URL) })
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            SectionHeader(stringResource(R.string.about_section_licenses))
            CREDITS.forEach { c ->
                CreditRow(c.name, stringResource(c.note), c.license, onClick = { onOpen(c.url) })
            }
            Note(stringResource(R.string.about_cc_by), modifier = Modifier.padding(top = 8.dp))
            Text(
                stringResource(R.string.about_cc_by_link),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) { onOpen(CC_BY_URL) }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            )
            Note(stringResource(R.string.about_other_libs))
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun CreditRow(name: String, note: String, license: String, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = stringResource(R.string.about_open_web), onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(name, style = MaterialTheme.typography.titleMedium)
        Text(license, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Text(note, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Preview(showBackground = true, heightDp = 1100)
@Composable
private fun AboutPreview() {
    MysoneTheme { AboutContent(version = "0.1.0 (1)", onBack = {}, onOpen = {}) }
}
