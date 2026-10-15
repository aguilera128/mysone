package dev.javieraguilera.mysone.capture

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface ListeningStatus {
    data object Off : ListeningStatus
    data object Starting : ListeningStatus
    data class Listening(val level: Float /* 0..1, para el medidor */, val pendingChunks: Int) : ListeningStatus
    data class Paused(val untilMs: Long) : ListeningStatus
    data object MicBusy : ListeningStatus // isClientSilenced: llamada, otra app grabando
    data object NeedsModel : ListeningStatus
    data object NeedsPermission : ListeningStatus
    data class Error(val message: String) : ListeningStatus
}

/** API de la escucha para la UI y los receivers. Contrato de docs/arquitectura.md §4 (lo implementa B1). */
object ListeningController {
    private val _status = MutableStateFlow<ListeningStatus>(ListeningStatus.Off)
    val status: StateFlow<ListeningStatus> = _status.asStateFlow()

    /** Solo desde UI visible o interacción con una notificación (restricción while-in-use). */
    fun start(context: Context) {}

    fun stop(context: Context) {}

    fun pause(context: Context, minutes: Int) {}

    fun resume(context: Context) {}

    /** Lo usa el servicio para publicar su estado. */
    internal fun publish(s: ListeningStatus) {
        _status.value = s
    }
}
