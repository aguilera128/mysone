package dev.javieraguilera.mysone.capture

import dev.javieraguilera.mysone.data.ConvSource
import dev.javieraguilera.mysone.data.ConvState
import dev.javieraguilera.mysone.data.Conversation
import dev.javieraguilera.mysone.data.ConversationDao

/**
 * Agrupa los trozos de voz del micro en conversaciones (arquitectura §5.3). Sin hilos ni Android: el
 * servicio lo llama siempre bajo el mismo Mutex (cola y temporizador), así que no necesita locks.
 *
 * [gapMs] se lee en cada llamada porque el ajuste puede cambiar con la escucha en marcha.
 * [onReady] recibe cada conversación que pasa a READY (el servicio decide si avisar a la IA o borrarla).
 */
class ConversationTracker(
    private val dao: ConversationDao,
    private val gapMs: suspend () -> Long,
    private val onReady: suspend (Long) -> Unit,
) {
    private var openId: Long? = null

    /** Al arrancar: las OPEN del micro de un proceso anterior ya no recibirán más voz. Las de importación no se tocan. */
    suspend fun recoverOrphans() {
        openId = null
        dao.byState(ConvState.OPEN).filter { it.source == ConvSource.MIC }.forEach { markReady(it.id) }
    }

    /** Conversación del trozo [startMs, endMs]. Si no casa con la abierta, la cierra y abre otra. */
    suspend fun assign(startMs: Long, endMs: Long): Long {
        val gap = gapMs()
        val open = current()
        // "Casa" si está a menos de un corte por cualquiera de los dos lados: así un trozo que llega algo
        // desordenado se queda en su conversación y uno muy anterior (reloj atrasado) abre otra.
        if (open != null && startMs <= open.endedAt + gap && endMs >= open.startedAt - gap) {
            val joined = open.copy(startedAt = minOf(open.startedAt, startMs), endedAt = maxOf(open.endedAt, endMs))
            if (joined != open) dao.update(joined)
            return open.id
        }
        if (open != null) markReady(open.id)
        return dao.insert(Conversation(startedAt = startMs, endedAt = endMs, source = ConvSource.MIC, state = ConvState.OPEN))
            .also { openId = it }
    }

    /**
     * Temporizador: cierra la abierta si lleva un corte entero sin voz, o si el reloj de pared ha retrocedido por
     * detrás de su final (hora cambiada a mano o automática): si no, seguiría abierta lo que se ha atrasado más el
     * corte. Con un salto así AudioCapture también resincroniza: lo siguiente va con la hora nueva, en otra.
     */
    suspend fun closeIfIdle(nowMs: Long) {
        val open = current() ?: return
        val idle = nowMs - open.endedAt
        if (idle >= gapMs() || idle < -AudioCapture.CLOCK_JUMP_MS) markReady(open.id)
    }

    /** Detener, error o fin del servicio. */
    suspend fun close() {
        current()?.let { markReady(it.id) }
    }

    /** La abierta según la BD: "olvidar" o borrar pueden haberla quitado o cambiado de estado por fuera. */
    private suspend fun current(): Conversation? {
        val id = openId ?: return null
        return dao.get(id)?.takeIf { it.state == ConvState.OPEN } ?: run {
            openId = null
            null
        }
    }

    private suspend fun markReady(id: Long) {
        dao.setState(id, ConvState.READY)
        if (openId == id) openId = null
        onReady(id)
    }
}
