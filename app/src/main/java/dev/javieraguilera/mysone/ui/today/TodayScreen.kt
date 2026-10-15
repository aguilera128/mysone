package dev.javieraguilera.mysone.ui.today

import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.javieraguilera.mysone.R
import dev.javieraguilera.mysone.ui.components.EmptyState

// Provisional (fase A): la pantalla real la escribe su módulo sin cambiar la firma.
@Composable
fun TodayScreen(day: String?, onOpenConversation: (Long, Long?) -> Unit, onOpenSearch: () -> Unit, onOpenSettings: () -> Unit, onOpenModels: () -> Unit) {
    EmptyState(title = stringResource(R.string.nav_today), modifier = Modifier.safeDrawingPadding())
}
