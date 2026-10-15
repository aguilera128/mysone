package dev.javieraguilera.mysone.ui.nav

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

// Rutas Nav3 (docs/arquitectura.md §8). La raíz de la pila es siempre Today, salvo en Onboarding.

@Serializable data object Onboarding : NavKey

@Serializable data class Today(val day: String? = null) : NavKey // "yyyy-MM-dd"; null = hoy

@Serializable data class ConversationDetail(val id: Long, val segmentId: Long? = null) : NavKey

@Serializable data object Search : NavKey

@Serializable data object Ask : NavKey

@Serializable data class Tasks(val highlightTaskId: Long? = null) : NavKey

@Serializable data object Memory : NavKey

@Serializable data object Settings : NavKey

@Serializable data object SettingsAi : NavKey

@Serializable data object SettingsModels : NavKey

@Serializable data object SettingsPrivacy : NavKey

@Serializable data object About : NavKey

/**
 * Pila de navegación para el extra "open" de MainActivity: "today", "ask", "models", "settings",
 * "tasks:<taskId>", "conversation:<id>". null si el valor no se reconoce.
 */
fun stackForOpen(open: String): List<NavKey>? {
    val arg = open.substringAfter(':', "")
    return when (open.substringBefore(':')) {
        "today" -> listOf(Today())
        "ask" -> listOf(Today(), Ask)
        "tasks" -> listOf(Today(), Tasks(arg.toLongOrNull()))
        "conversation" -> arg.toLongOrNull()?.let { listOf(Today(), ConversationDetail(it)) }
        "models" -> listOf(Today(), Settings, SettingsModels)
        "settings" -> listOf(Today(), Settings)
        else -> null
    }
}
