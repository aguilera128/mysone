package dev.javieraguilera.mysone.ui.ask

import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.javieraguilera.mysone.R
import dev.javieraguilera.mysone.ui.components.EmptyState

// Provisional (fase A): la pantalla real la escribe su módulo sin cambiar la firma.
@Composable
fun AskScreen(onOpenConversation: (Long, Long?) -> Unit, onOpenAiSettings: () -> Unit) {
    EmptyState(title = stringResource(R.string.nav_ask), modifier = Modifier.safeDrawingPadding())
}
