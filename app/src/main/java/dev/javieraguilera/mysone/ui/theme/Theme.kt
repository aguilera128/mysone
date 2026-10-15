package dev.javieraguilera.mysone.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Paleta de docs/arquitectura.md §8: tinta (primary), ámbar para recordatorios (secondary),
// salvia para "escuchando" (tertiary). Sin color dinámico.
// Ojo: el ámbar claro (#B5701F) da 4:1 con blanco; para texto pequeño usa secondaryContainer.

private val Light = lightColorScheme(
    primary = Color(0xFF2E4A7A),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD8E2F7),
    onPrimaryContainer = Color(0xFF0F2547),
    secondary = Color(0xFFB5701F),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFADDB8),
    onSecondaryContainer = Color(0xFF3D2300),
    tertiary = Color(0xFF3E7F6A),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFC4EADB),
    onTertiaryContainer = Color(0xFF0A3326),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    background = Color(0xFFF7F5F0),
    onBackground = Color(0xFF1B1C1E),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1B1C1E),
    surfaceVariant = Color(0xFFE6E1D8),
    onSurfaceVariant = Color(0xFF4A4740),
    outline = Color(0xFF7A766D),
    outlineVariant = Color(0xFFCFC9BE),
    inverseSurface = Color(0xFF303134),
    inverseOnSurface = Color(0xFFF2F0EC),
    inversePrimary = Color(0xFFA9C1F0),
    surfaceDim = Color(0xFFE0DBD2),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF9F7F3),
    surfaceContainer = Color(0xFFEFEBE4),
    surfaceContainerHigh = Color(0xFFE9E4DC),
    surfaceContainerHighest = Color(0xFFE3DDD4),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFA9C1F0),
    onPrimary = Color(0xFF10284F),
    primaryContainer = Color(0xFF284374),
    onPrimaryContainer = Color(0xFFD8E2F7),
    secondary = Color(0xFFF0B366),
    onSecondary = Color(0xFF452A00),
    secondaryContainer = Color(0xFF6B4510),
    onSecondaryContainer = Color(0xFFFADDB8),
    tertiary = Color(0xFF86C9AF),
    onTertiary = Color(0xFF003829),
    tertiaryContainer = Color(0xFF1F5A47),
    onTertiaryContainer = Color(0xFFC4EADB),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
    background = Color(0xFF111315),
    onBackground = Color(0xFFE3E2E0),
    surface = Color(0xFF191C1F),
    onSurface = Color(0xFFE3E2E0),
    surfaceVariant = Color(0xFF3A3F44),
    onSurfaceVariant = Color(0xFFC3C6CB),
    outline = Color(0xFF8D9196),
    outlineVariant = Color(0xFF42474C),
    inverseSurface = Color(0xFFE3E2E0),
    inverseOnSurface = Color(0xFF2E3033),
    inversePrimary = Color(0xFF2E4A7A),
    surfaceDim = Color(0xFF111315),
    surfaceBright = Color(0xFF373B40),
    surfaceContainerLowest = Color(0xFF0C0E10),
    surfaceContainerLow = Color(0xFF15181B),
    surfaceContainer = Color(0xFF22262A),
    surfaceContainerHigh = Color(0xFF2A2E33),
    surfaceContainerHighest = Color(0xFF33383D),
)

// Fuente del sistema; la jerarquía sale de tamaño y peso, no de tipografías decorativas.
private val Base = Typography()
private val AppTypography = Typography(
    displaySmall = Base.displaySmall.copy(fontWeight = FontWeight.SemiBold),
    headlineMedium = Base.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
    headlineSmall = Base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = Base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = Base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = Base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    bodyLarge = Base.bodyLarge.copy(lineHeight = 24.sp),
    bodyMedium = Base.bodyMedium.copy(lineHeight = 21.sp),
    labelLarge = Base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

@Composable
fun MysoneTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) Dark else Light,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
