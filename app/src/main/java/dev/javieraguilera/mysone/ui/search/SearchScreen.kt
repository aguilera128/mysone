package dev.javieraguilera.mysone.ui.search

import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.javieraguilera.mysone.R
import dev.javieraguilera.mysone.ui.components.EmptyState

// Provisional (fase A): la pantalla real la escribe su módulo sin cambiar la firma.
@Composable
fun SearchScreen(onOpenConversation: (Long, Long?) -> Unit, onBack: () -> Unit) {
    EmptyState(title = stringResource(R.string.title_search), modifier = Modifier.safeDrawingPadding())
}
