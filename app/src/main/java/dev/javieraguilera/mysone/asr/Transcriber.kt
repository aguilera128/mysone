package dev.javieraguilera.mysone.asr

import android.content.Context
import dev.javieraguilera.mysone.data.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface TranscriberState {
    data object NoModel : TranscriberState
    data object Loading : TranscriberState
    data class Ready(val modelId: String) : TranscriberState
    data class Error(val message: String) : TranscriberState
}

/** Reconocedor offline compartido (una sola instancia, en AppContainer). Lo implementa B2. */
class Transcriber(
    private val context: Context,
    private val models: ModelManager,
    private val settings: SettingsStore,
) {
    private val _state = MutableStateFlow<TranscriberState>(TranscriberState.NoModel)
    val state: StateFlow<TranscriberState> = _state.asStateFlow()

    /** Carga el modelo elegido si hace falta (refcount); false si no hay modelo o falla. */
    suspend fun acquire(): Boolean = false

    /** Al llegar a 0 libera la memoria nativa. */
    fun release() {}

    /** Serializado con Mutex; devuelve texto limpio. */
    suspend fun transcribe(samples: FloatArray, sampleRate: Int = 16_000): String = ""
}
