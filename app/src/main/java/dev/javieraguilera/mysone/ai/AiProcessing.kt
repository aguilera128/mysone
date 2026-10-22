package dev.javieraguilera.mysone.ai

import dev.javieraguilera.mysone.AppContainer
import dev.javieraguilera.mysone.R
import dev.javieraguilera.mysone.data.AiSettings
import dev.javieraguilera.mysone.data.ChatRole
import dev.javieraguilera.mysone.data.ConvState
import dev.javieraguilera.mysone.data.Segment
import io.sentry.Sentry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Procesado de conversaciones con IA (§7.3–7.6). */
class AiProcessing(private val container: AppContainer) {

    /** enabled + consentAt + clave. */
    val isConfigured: Flow<Boolean> =
        combine(container.settings.data, container.secrets.hasApiKey) { s, hasKey -> s.ai.isReady() && hasKey }
            .distinctUntilChanged()

    private val processor = ConversationProcessor(container)
    private val quickReminders = QuickReminders(container, processor)
    private val digests = DigestBuilder(container)

    // Una llamada que crea tareas cada vez: evita duplicados entre el worker, "Reprocesar" y los recordatorios
    // rápidos, y las llamadas seguidas aprovechan la caché del prompt. Con el cerrojo cogido, PROCESSING solo
    // puede ser un resto de un proceso que murió a medias.
    private val mutex = Mutex()

    /** No bloquea; recordatorio rápido si procede. */
    fun onSegmentTranscribed(segment: Segment) {
        if (!QuickReminders.matches(segment.text)) return
        container.appScope.launch {
            try {
                mutex.withLock { quickReminders.handle(segment) }
            } catch (e: Exception) {
                // Sin red o sin respuesta: el procesado completo de la conversación lo recogerá.
                if (e !is LlmException && e !is CancellationException) Sentry.captureException(e)
            }
        }
    }

    /** No bloquea: encola [ProcessWorker] (red requerida, reintentos), que procesa todo lo pendiente. */
    @Suppress("UNUSED_PARAMETER")
    fun onConversationReady(conversationId: Long) = ProcessWorker.enqueue(container.app)

    // Ajustes y clave se leen con el cerrojo cogido, justo antes de cada llamada: lo que esperaba (o lo que queda
    // de la cola) no sale hacia un proveedor que entretanto se ha apagado, cambiado o quedado sin clave.
    suspend fun processConversation(id: Long, force: Boolean = false): Result<Unit> = mutex.withLock {
        val cfg = aiConfig(container) ?: return Result.failure(notConfigured())
        processLocked(id, force, cfg).map { }
    }

    /** READY sin audio pendiente (y FAILED con intentos); devuelve cuántas. */
    suspend fun processPending(): Int = drain().processed

    internal data class Drain(val processed: Int, val retryLater: Boolean)

    /**
     * Procesa la cola en orden y la vuelve a mirar al acabar (pueden haber llegado conversaciones nuevas mientras).
     * Se para ante un error que afectaría a todas (clave, red, cuota, URL o modelo); [Drain.retryLater] si es pasajero.
     */
    internal suspend fun drain(): Drain {
        val dao = container.db.conversations()
        val tried = mutableSetOf<Long>()
        val retried = mutableSetOf<Long>()
        var processed = 0
        var lastRequestError: Int? = null
        while (true) {
            val queue = (dao.readyWithoutPending() + dao.byState(ConvState.PROCESSING) +
                dao.byState(ConvState.FAILED).filter { it.processAttempts < MAX_ATTEMPTS })
                .sortedBy { it.startedAt }
                // READY otra vez en la misma pasada: "olvidar" la ha dejado sin resumen y su aviso no llega (KEEP).
                .filter { tried.add(it.id) || (it.state == ConvState.READY && retried.add(it.id)) }
                .map { it.id }
            if (queue.isEmpty()) return Drain(processed, retryLater = false)
            for (id in queue) {
                val result = mutex.withLock {
                    val cfg = aiConfig(container) ?: return Drain(processed, retryLater = false)
                    processLocked(id, force = false, cfg)
                }
                if (result.getOrNull() == true) processed++
                val e = result.exceptionOrNull() as? LlmException
                if (e != null && e.isRequestError()) {
                    // Una suelta es de esa conversación; dos seguidas con el mismo código, de los ajustes (modelo, URL).
                    if (e.httpCode == lastRequestError) return Drain(processed, retryLater = false)
                    lastRequestError = e.httpCode
                    continue
                }
                lastRequestError = null
                if (e != null && !e.countsAsAttempt()) return Drain(processed, e.retryable)
            }
        }
    }

    /** true si se ha procesado; false si no tocaba (abierta, ya hecha o con audio pendiente). */
    private suspend fun processLocked(id: Long, force: Boolean, cfg: AiConfig): Result<Boolean> {
        val dao = container.db.conversations()
        val c = dao.get(id) ?: return Result.success(false)
        if (c.state == ConvState.OPEN) return Result.success(false)
        if (!force && (c.state == ConvState.DONE || container.db.pendingAudio().countForConversation(id) > 0)) {
            return Result.success(false)
        }
        // Ya resumida y sin transcripción (la retención la borra): reprocesarla la dejaría como ruido para siempre.
        if (c.processedAt != null && container.db.segments().countForConversation(id) == 0) return Result.success(false)
        val previous = if (c.state == ConvState.PROCESSING) ConvState.READY else c.state
        dao.setState(id, ConvState.PROCESSING)
        // Los fallos solo se apuntan si sigue en PROCESSING: si "olvidar" o borrar la han tocado, manda lo suyo.
        return try {
            val saved = processor.process(c.copy(state = ConvState.PROCESSING), cfg)
            // Descartado porque la transcripción ha cambiado mientras: que la cola la vuelva a mirar.
            if (!saved) onConversationReady(id)
            Result.success(saved)
        } catch (e: CancellationException) {
            withContext(NonCancellable) { dao.setStateIf(id, ConvState.PROCESSING, previous) }
            throw e
        } catch (e: LlmException) {
            // De esta conversación: cuenta como intento. Red o servidor también cuentan, pero vuelve a la cola hasta
            // MAX_TRANSIENT; clave o cuota son de la cuenta: vuelve a la cola sin contar.
            dao.endFailed(
                id,
                state = e.stateAfter(c.processAttempts),
                addAttempts = if (e.countsAsAttempt() || e.isTransient()) 1 else 0,
                // Se muestra tal cual en Conversación: solo el texto en español, sin el detalle técnico (§2).
                lastError = container.app.getString(e.kind.messageRes),
            )
            Result.failure(e)
        } catch (e: Exception) {
            Sentry.captureException(e)
            dao.endFailed(id, ConvState.FAILED, addAttempts = 1, lastError = null) // la UI da el mensaje genérico
            Result.failure(e)
        }
    }

    suspend fun buildMissingDigests(): Int = mutex.withLock { digests.buildMissing() }

    /** Tras cambiar los ajustes de IA: lo que falló por ellos (modelo, URL, clave) recupera sus intentos. */
    suspend fun resetFailures() {
        container.db.conversations().resetFailedAttempts()
        ProcessWorker.enqueue(container.app)
    }

    /** Para Ajustes: no exige haber activado la IA ni aceptado el aviso (no se envía nada del usuario). */
    suspend fun testConnection(): Result<String> {
        val ai = container.settings.current().ai
        val key = container.secrets.apiKey() ?: return Result.failure(notConfigured())
        if (ai.baseUrl.isBlank() || ai.modelBackground.isBlank()) return Result.failure(notConfigured())
        val client = llmClient(ai, key)
        return try {
            listOf(ai.modelBackground, ai.modelAnswer).filter { it.isNotBlank() }.distinct().forEach { model ->
                client.complete(
                    LlmRequest(Prompts.TEST, listOf(LlmMessage(ChatRole.USER, "OK")), model, 512, effort = "low", cacheSystem = false),
                )
            }
            Result.success(container.app.getString(R.string.ai_test_ok))
        } catch (e: LlmException) {
            Result.failure(e)
        }
    }

    internal companion object {
        /** Reintentos de FAILED en processPending (§7.3). */
        const val MAX_ATTEMPTS = 3

        /** Intentos, contando los de red o servidor, con los que una conversación se da por fallida (§7.3). */
        const val MAX_TRANSIENT = 6
    }
}

/** Ajustes y cliente listos para usar. */
class AiConfig internal constructor(val ai: AiSettings, val client: LlmClient)

/** null si la IA no está activada, con el aviso aceptado y con clave. */
internal suspend fun aiConfig(container: AppContainer): AiConfig? {
    val ai = container.settings.current().ai
    if (!ai.isReady()) return null
    val key = container.secrets.apiKey() ?: return null
    return AiConfig(ai, llmClient(ai, key))
}

private fun AiSettings.isReady() =
    enabled && consentAt != null && baseUrl.isNotBlank() && modelBackground.isNotBlank() && modelAnswer.isNotBlank()

internal fun notConfigured() = LlmException(LlmErrorKind.AUTH, "IA no configurada", retryable = false)

/** Error de esa conversación y no de los ajustes: cuenta como intento y la cola sigue. */
internal fun LlmException.countsAsAttempt() =
    kind == LlmErrorKind.BAD_OUTPUT || kind == LlmErrorKind.REFUSAL || isRequestError()

/**
 * Tiempo de espera o 5xx: puede ser un corte del proveedor o esta petición, que no pasa nunca (una parte de 60 000
 * caracteres con un modelo lento, un proxy que corta a los 100 s). [AiProcessing.drain] para en el primero: en un corte
 * solo suma la primera de la cola.
 */
internal fun LlmException.isTransient() = retryable && (kind == LlmErrorKind.NETWORK || kind == LlmErrorKind.SERVER)

/** Estado tras [this] en una conversación que llevaba [attempts]: sin tope, una que siempre falla pararía la cola. */
internal fun LlmException.stateAfter(attempts: Int): String = when {
    countsAsAttempt() -> ConvState.FAILED
    isTransient() && attempts + 1 >= AiProcessing.MAX_TRANSIENT -> ConvState.FAILED
    else -> ConvState.READY
}
