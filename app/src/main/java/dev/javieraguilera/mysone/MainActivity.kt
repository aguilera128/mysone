package dev.javieraguilera.mysone

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.filled.Today
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import dev.javieraguilera.mysone.core.Time
import dev.javieraguilera.mysone.ui.ask.AskScreen
import dev.javieraguilera.mysone.ui.conversation.ConversationScreen
import dev.javieraguilera.mysone.ui.memory.MemoryScreen
import dev.javieraguilera.mysone.ui.nav.About
import dev.javieraguilera.mysone.ui.nav.Ask
import dev.javieraguilera.mysone.ui.nav.ConversationDetail
import dev.javieraguilera.mysone.ui.nav.Memory
import dev.javieraguilera.mysone.ui.nav.Onboarding
import dev.javieraguilera.mysone.ui.nav.Search
import dev.javieraguilera.mysone.ui.nav.Settings
import dev.javieraguilera.mysone.ui.nav.SettingsAi
import dev.javieraguilera.mysone.ui.nav.SettingsModels
import dev.javieraguilera.mysone.ui.nav.SettingsPrivacy
import dev.javieraguilera.mysone.ui.nav.Tasks
import dev.javieraguilera.mysone.ui.nav.Today
import dev.javieraguilera.mysone.ui.nav.stackForOpen
import dev.javieraguilera.mysone.ui.onboarding.OnboardingScreen
import dev.javieraguilera.mysone.ui.search.SearchScreen
import dev.javieraguilera.mysone.ui.settings.AboutScreen
import dev.javieraguilera.mysone.ui.settings.SettingsAiScreen
import dev.javieraguilera.mysone.ui.settings.SettingsModelsScreen
import dev.javieraguilera.mysone.ui.settings.SettingsPrivacyScreen
import dev.javieraguilera.mysone.ui.settings.SettingsScreen
import dev.javieraguilera.mysone.ui.tasks.TasksScreen
import dev.javieraguilera.mysone.ui.theme.MysoneTheme
import dev.javieraguilera.mysone.ui.today.TodayScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    // Toda la interfaz en español aunque el sistema esté en otro idioma (§8): los textos propios ya lo están,
    // pero los selectores de fecha y hora de Material 3 toman idioma y formato de 24 h de la configuración.
    override fun attachBaseContext(newBase: Context) {
        val config = Configuration(newBase.resources.configuration).apply { setLocale(Time.SPAIN) }
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    /** Valor del extra [EXTRA_OPEN] aún sin aplicar a la navegación. */
    private var pendingOpen by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Oculto en capturas desde el primer fotograma; el ajuste puede quitarlo al cargar.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (savedInstanceState == null) pendingOpen = intent.getStringExtra(EXTRA_OPEN)

        setContent {
            MysoneTheme {
                val settings = container.settings.data.collectAsStateWithLifecycle(initialValue = null).value
                    ?: return@MysoneTheme // el fondo de la ventana ya es el del tema
                LaunchedEffect(settings.secureScreen) {
                    if (settings.secureScreen) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
                MysoneNav(
                    onboardingDone = settings.onboardingDone,
                    pendingOpen = pendingOpen,
                    onOpenHandled = { pendingOpen = null },
                    onOnboardingFinished = {
                        container.appScope.launch { container.settings.update { it.copy(onboardingDone = true) } }
                    },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(EXTRA_OPEN)?.let { pendingOpen = it }
    }

    companion object {
        /** Destino al abrir: "today", "tasks:<taskId>", "conversation:<id>", "ask", "models", "settings". */
        const val EXTRA_OPEN = "open"

        /** Intent para notificaciones: reutiliza la actividad si ya está abierta. */
        fun intent(context: Context, open: String? = null): Intent =
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .apply { if (open != null) putExtra(EXTRA_OPEN, open) }
    }
}

private class TabItem(val key: NavKey, @StringRes val label: Int, val icon: ImageVector, val selectedIcon: ImageVector)

private val TABS = listOf(
    TabItem(Today(), R.string.nav_today, Icons.Outlined.Today, Icons.Filled.Today),
    TabItem(Ask, R.string.nav_ask, Icons.Outlined.Forum, Icons.Filled.Forum),
    TabItem(Tasks(), R.string.nav_tasks, Icons.Outlined.TaskAlt, Icons.Filled.TaskAlt),
    TabItem(Memory, R.string.nav_memory, Icons.Outlined.Lightbulb, Icons.Filled.Lightbulb),
)

@Composable
private fun MysoneNav(
    onboardingDone: Boolean,
    pendingOpen: String?,
    onOpenHandled: () -> Unit,
    onOnboardingFinished: () -> Unit,
) {
    val backStack = rememberNavBackStack(if (onboardingDone) Today() else Onboarding)

    LaunchedEffect(pendingOpen) {
        val open = pendingOpen ?: return@LaunchedEffect
        onOpenHandled()
        if (backStack.firstOrNull() is Onboarding) return@LaunchedEffect // sin onboarding no se navega
        stackForOpen(open)?.let {
            backStack.clear()
            backStack.addAll(it)
        }
    }

    val back: () -> Unit = { if (backStack.size > 1) backStack.removeLastOrNull() }
    val openConversation: (Long, Long?) -> Unit = { id, segmentId -> backStack.add(ConversationDetail(id, segmentId)) }
    val selectTab: (NavKey) -> Unit = { key ->
        // La raíz (Today) se conserva; las demás pestañas van encima.
        repeat(backStack.size - 1) { backStack.removeLastOrNull() }
        if (key !is Today) backStack.add(key)
    }

    NavDisplay(
        backStack = backStack,
        onBack = back,
        entryDecorators = listOf(
            rememberTabStateDecorator(),
            rememberViewModelStoreNavEntryDecorator(),
        ),
        entryProvider = entryProvider {
            entry<Onboarding> {
                OnboardingScreen(onFinished = {
                    onOnboardingFinished()
                    backStack.clear()
                    backStack.add(Today())
                })
            }
            entry<Today> { key ->
                TabScaffold(key, selectTab) {
                    TodayScreen(
                        day = key.day,
                        onOpenConversation = openConversation,
                        onOpenSearch = { backStack.add(Search) },
                        onOpenSettings = { backStack.add(Settings) },
                        onOpenModels = { backStack.add(SettingsModels) },
                    )
                }
            }
            entry<ConversationDetail> { key -> ConversationScreen(id = key.id, segmentId = key.segmentId, onBack = back) }
            entry<Search> { SearchScreen(onOpenConversation = openConversation, onBack = back) }
            entry<Ask>(clazzContentKey = { "${TAB_STATE}ask" }) {
                TabScaffold(Ask, selectTab) {
                    AskScreen(
                        onOpenConversation = openConversation,
                        onOpenAiSettings = { backStack.add(SettingsAi) },
                        onOpenSearch = { backStack.add(Search) },
                    )
                }
            }
            // La que viene de un aviso (con tarea que resaltar) es otra entrada, con su propio estado.
            entry<Tasks>(clazzContentKey = { if (it.highlightTaskId == null) "${TAB_STATE}tasks" else it.toString() }) { key ->
                TabScaffold(key, selectTab) { TasksScreen(highlightTaskId = key.highlightTaskId, onOpenConversation = openConversation) }
            }
            entry<Memory>(clazzContentKey = { "${TAB_STATE}memory" }) {
                TabScaffold(Memory, selectTab) { MemoryScreen(onOpenConversation = openConversation) }
            }
            entry<Settings> {
                SettingsScreen(
                    onBack = back,
                    onOpenAi = { backStack.add(SettingsAi) },
                    onOpenModels = { backStack.add(SettingsModels) },
                    onOpenPrivacy = { backStack.add(SettingsPrivacy) },
                    onOpenAbout = { backStack.add(About) },
                )
            }
            entry<SettingsAi> { SettingsAiScreen(onBack = back) }
            entry<SettingsModels> { SettingsModelsScreen(onBack = back) }
            entry<SettingsPrivacy> { SettingsPrivacyScreen(onBack = back) }
            entry<About> { AboutScreen(onBack = back) }
        },
    )
}

/** Clave de estado de las raíces de Preguntar, Tareas y Memoria (Hoy es la raíz de la pila y no sale de ella). */
private const val TAB_STATE = "tab:"

/**
 * Como rememberSaveableStateHolderNavEntryDecorator, salvo que no borra el estado guardado de las raíces de las
 * pestañas al sacarlas de la pila. Cambiar de pestaña las saca (selectTab) y, si no, al volver se habría perdido lo
 * escrito en Preguntar, la pestaña elegida en Tareas o el scroll. Lo que va encima (Buscar, una conversación) sí se
 * borra al salir. Sus ViewModels se recrean igual: solo leen de la BD.
 */
@Composable
private fun rememberTabStateDecorator(): NavEntryDecorator<NavKey> {
    val holder = rememberSaveableStateHolder()
    return remember(holder) {
        NavEntryDecorator<NavKey>(
            onPop = { key -> if (!(key is String && key.startsWith(TAB_STATE))) holder.removeState(key) },
            decorate = { entry -> holder.SaveableStateProvider(entry.contentKey) { entry.Content() } },
        )
    }
}

/**
 * Pestaña con la barra inferior. La barra va dentro de cada entrada y no en un Scaffold alrededor de NavDisplay:
 * así entra y sale con su pantalla en el mismo fundido, en vez de aparecer de golpe sobre la que se va.
 */
@Composable
private fun TabScaffold(current: NavKey, onSelect: (NavKey) -> Unit, content: @Composable () -> Unit) {
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0), // cada pantalla gestiona sus insets
        bottomBar = {
            NavigationBar {
                TABS.forEach { tab ->
                    val selected = tab.key::class == current::class
                    NavigationBarItem(
                        selected = selected,
                        onClick = { onSelect(tab.key) },
                        icon = { Icon(if (selected) tab.selectedIcon else tab.icon, contentDescription = null) },
                        label = { Text(stringResource(tab.label)) },
                    )
                }
            }
        },
    ) { inner ->
        Box(Modifier.fillMaxSize().padding(inner).consumeWindowInsets(inner)) { content() }
    }
}
