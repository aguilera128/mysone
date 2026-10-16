package dev.javieraguilera.mysone.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri

// Filas de ajustes sobre el fondo de la pantalla (sin tarjetas): la jerarquía la dan las cabeceras
// de sección y los divisores finos, no las superficies.

@Composable
private fun transparent() = ListItemDefaults.colors(containerColor = Color.Transparent)

@Composable
fun SwitchRow(title: String, summary: String?, checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean = true) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = summary?.let { { Text(it) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
        colors = transparent(),
        modifier = Modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange),
    )
}

@Composable
fun RadioRow(title: String, summary: String?, selected: Boolean, onClick: () -> Unit, enabled: Boolean = true) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = summary?.let { { Text(it) } },
        leadingContent = { RadioButton(selected = selected, onClick = null, enabled = enabled) },
        colors = transparent(),
        modifier = Modifier.selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
    )
}

@Composable
fun NavRow(icon: ImageVector, title: String, summary: String?, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = summary?.let { { Text(it) } },
        leadingContent = { Icon(icon, contentDescription = null) },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
        colors = transparent(),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/** Texto explicativo de una sección. [icon] opcional a la izquierda; [color] para avisos. */
@Composable
fun Note(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = color, modifier = Modifier.padding(top = 2.dp).size(18.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
    }
}

/** Abre una web o un ajuste del sistema; si no hay quien lo abra, no hace nada. */
fun Context.openSafely(intent: Intent): Boolean = try {
    startActivity(intent)
    true
} catch (_: ActivityNotFoundException) {
    false
}

fun Context.openUrl(url: String) = openSafely(Intent(Intent.ACTION_VIEW, url.toUri()))
