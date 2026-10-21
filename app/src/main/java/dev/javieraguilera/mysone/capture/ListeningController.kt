package dev.javieraguilera.mysone.capture

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import dev.javieraguilera.mysone.R
import dev.javieraguilera.mysone.asr.ModelInstall
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine

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

/** Quién transcribe la cola de audio pendiente: solo la escucha (también en pausa), y a veces espera. */
sealed interface QueueStatus {
    data object Running : QueueStatus

    /** Batería baja sin cargar o móvil muy caliente; [reason] es el id de la cadena con el motivo. */
    data class Held(@StringRes val reason: Int) : QueueStatus

    /** Sin escucha: lo pendiente se transcribe al volver a escuchar. */
    data object Stopped : QueueStatus
}

/**
 * API de la escucha para la UI y los receivers (contrato de docs/arquitectura.md §4). Todo pasa por
 * [ListeningService]; el estado vive en memoria del proceso, así que en un proceso nuevo empieza en Off.
 */
object ListeningController {
    internal const val ACTION_START = "dev.javieraguilera.mysone.capture.START"
    internal const val ACTION_STOP = "dev.javieraguilera.mysone.capture.STOP"
    internal const val ACTION_PAUSE = "dev.javieraguilera.mysone.capture.PAUSE"
    internal const val ACTION_RESUME = "dev.javieraguilera.mysone.capture.RESUME"
    internal const val EXTRA_MINUTES = "minutes"
    private const val TAG = "ListeningController"

    private val _status = MutableStateFlow<ListeningStatus>(ListeningStatus.Off)
    val status: StateFlow<ListeningStatus> = _status.asStateFlow()

    /** Solo desde UI visible o interacción con una notificación (restricción while-in-use, 02 §3). */
    fun start(context: Context) = send(context, ACTION_START, foreground = true)

    fun stop(context: Context) = send(context, ACTION_STOP)

    fun pause(context: Context, minutes: Int) = send(context, ACTION_PAUSE, minutes.coerceAtLeast(1))

    fun resume(context: Context) = send(context, ACTION_RESUME, foreground = true)

    /** Lo usa el servicio para publicar su estado. */
    internal fun publish(s: ListeningStatus) {
        _status.value = s
    }

    /**
     * "Falta el modelo" deja de ser verdad en cuanto hay uno instalado (el elegido, o el que MysoneApp elige en su
     * lugar): la escucha queda detenida y "Escuchar" vuelve a funcionar. Nadie más lo quita, y sin esto la tarjeta de
     * Hoy se quedaba así hasta un proceso nuevo. Lo sigue MysoneApp mientras vive el proceso.
     */
    internal suspend fun leaveNeedsModelOnInstall(installs: Flow<Map<String, ModelInstall>>) {
        combine(status, installs) { s, m -> s == ListeningStatus.NeedsModel && m.values.any { it is ModelInstall.Installed } }
            .collect { if (it) _status.compareAndSet(ListeningStatus.NeedsModel, ListeningStatus.Off) }
    }

    private val _queue = MutableStateFlow<QueueStatus>(QueueStatus.Stopped)

    /** Para no decir "Transcribiendo…" de lo que espera a la escucha, a la carga o a que se enfríe el móvil. */
    val queue: StateFlow<QueueStatus> = _queue.asStateFlow()

    internal fun publishQueue(s: QueueStatus) {
        _queue.value = s
    }

    // Lo último que se ha olvidado, [desde, cuándo se pulsó]. Basta con el último: un trozo llega a la cola como
    // mucho unos 30 s tarde (VAD y canal) y cualquier olvido cubre al menos los 5 min anteriores.
    @Volatile private var forgotten = LongRange.EMPTY

    /** "Olvidar" desde [since]: lo que la captura aún tenga de antes de [at] se descarta al llegar a la cola. */
    internal fun markForgotten(since: Long, at: Long) {
        forgotten = since..at
    }

    /** El trozo [startMs, endMs] tiene voz del intervalo olvidado. */
    internal fun isForgotten(startMs: Long, endMs: Long): Boolean = overlaps(forgotten, startMs, endMs)

    internal fun overlaps(range: LongRange, startMs: Long, endMs: Long) = !range.isEmpty() && endMs > range.first && startMs < range.last

    internal fun intent(context: Context, action: String, minutes: Int = 0): Intent =
        Intent(context, ListeningService::class.java).setAction(action).putExtra(EXTRA_MINUTES, minutes)

    private fun send(context: Context, action: String, minutes: Int = 0, foreground: Boolean = false) {
        val intent = intent(context, action, minutes)
        try {
            if (foreground) ContextCompat.startForegroundService(context, intent) else context.startService(intent)
        } catch (e: IllegalStateException) { // ForegroundServiceStartNotAllowedException o arranque en segundo plano
            Log.w(TAG, "No se puede arrancar el servicio: ${e.javaClass.simpleName}")
            if (foreground) publish(ListeningStatus.Error(context.getString(R.string.capture_error_start)))
        } catch (e: SecurityException) {
            Log.w(TAG, "No se puede arrancar el servicio: ${e.javaClass.simpleName}")
            if (foreground) publish(ListeningStatus.Error(context.getString(R.string.capture_error_start)))
        }
    }
}
