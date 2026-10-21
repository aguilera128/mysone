package dev.javieraguilera.mysone.ui.onboarding

import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.javieraguilera.mysone.AppContainer
import dev.javieraguilera.mysone.asr.ModelInstall
import dev.javieraguilera.mysone.asr.recommendedModelId
import dev.javieraguilera.mysone.data.AppSettings
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class OnboardingStep { Intro, Legal, Mic, Notifications, Model, Ai, Start }

class OnboardingViewModel(private val container: AppContainer, private val saved: SavedStateHandle) : ViewModel() {
    // POST_NOTIFICATIONS solo existe desde Android 13; antes las notificaciones no piden permiso.
    val steps = OnboardingStep.entries.filter { it != OnboardingStep.Notifications || Build.VERSION.SDK_INT >= 33 }

    // En [saved]: si el sistema mata el proceso a mitad (bajando el modelo, en los ajustes del sistema), se vuelve
    // al mismo paso y no al primero.
    var index by mutableIntStateOf((saved.get<Int>(KEY_STEP) ?: 0).coerceIn(0, steps.lastIndex))
        private set
    val step: OnboardingStep get() = steps[index]

    var legalChecked by mutableStateOf(false)

    val legalAcceptedAt: StateFlow<Long?> = container.settings.data.map { it.legalAcceptedAt }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val models = container.models
    val ramGb: Int = models.deviceRamGb()
    val recommendedModelId = recommendedModelId(ramGb)
    var selectedModelId by mutableStateOf(recommendedModelId)
        private set
    var wifiOnly by mutableStateOf(AppSettings().downloadWifiOnly)
        private set

    init {
        viewModelScope.launch {
            val s = container.settings.current()
            legalChecked = s.legalAcceptedAt != null
            // Si el guardado ni está ni se está bajando, se propone (y guarda) el recomendado para esta RAM.
            if ((models.installs.value[s.asrModelId] ?: ModelInstall.NotInstalled) == ModelInstall.NotInstalled) selectModel(recommendedModelId)
            else selectedModelId = s.asrModelId
            wifiOnly = s.downloadWifiOnly
        }
    }

    fun next() {
        if (index < steps.lastIndex) go(index + 1)
    }

    fun back() {
        if (index > 0) go(index - 1)
    }

    private fun go(i: Int) {
        index = i
        saved[KEY_STEP] = i
    }

    /** Guarda la fecha de aceptación del aviso: sin ella no se debe arrancar la escucha. */
    fun acceptLegal() {
        val now = System.currentTimeMillis()
        container.appScope.launch { container.settings.update { it.copy(legalAcceptedAt = now) } }
        next()
    }

    fun selectModel(id: String) {
        selectedModelId = id
        container.appScope.launch { container.settings.update { it.copy(asrModelId = id) } }
    }

    fun changeWifiOnly(on: Boolean) {
        wifiOnly = on
        container.appScope.launch { container.settings.update { it.copy(downloadWifiOnly = on) } }
    }

    /** Guarda también el modelo: la escucha carga el de settings.asrModelId, no el que se haya descargado. */
    fun download() {
        selectModel(selectedModelId)
        models.download(selectedModelId, allowMetered = !wifiOnly)
    }

    fun cancelDownload() = models.cancel(selectedModelId)

    private companion object {
        const val KEY_STEP = "step"
    }
}
