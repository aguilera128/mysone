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

    suspend fun processConversation(id: Long, force: Boolean = false): Result<Unit> {
        val cfg = aiConfig(container) ?: return Result.failure(notConfigured())
        return mutex.withLock { processLocked(id, force, cfg) }.map { }
    }

    /** READY sin audio pendiente (y FAILED con intentos); devuelve cuántas. */
    suspend fun processPending(): Int = drain().processed

    internal data class Drain(val processed: Int, val retryLater: Boolean)

    /**
     * Procesa la cola en orden y la vuelve a mirar al acabar (pueden haber llegado conversaciones nuevas mientras).
     * Se para ante un error que afectaría a todas (clave, red, cuota, URL o modelo); [Drain.retryLater] si es pasajero.
     */
    internal suspend fun drain(): Drain {
        val cfg = aiConfig(container) ?: return Drain(0, retryLater = false)
        val dao = container.db.conversations()
        val tried = mutableSetOf<Long>()
        var processed = 0
        while (true) {
            val queue = (dao.readyWithoutPending() + dao.byState(ConvState.PROCESSING) +
                dao.byState(ConvState.FAILED).filter { it.processAttempts < MAX_ATTEMPTS })
                .sortedBy { it.startedAt }.map { it.id }.filter { tried.add(it) }
            if (queue.isEmpty()) return Drain(processed, retryLater = false)
            for (id in queue) {
                val result = mutex.withLock { processLocked(id, force = false, cfg) }
                if (result.getOrNull() == true) processed++
                val e = result.exceptionOrNull() as? LlmException ?: continue
                if (e.kind != LlmErrorKind.BAD_OUTPUT && e.kind != LlmErrorKind.REFUSAL) return Drain(processed, e.retryable)
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
        val previous = if (c.state == ConvState.PROCESSING) ConvState.READY else c.state
        dao.setState(id, ConvState.PROCESSING)
        return try {
            processor.process(c.copy(state = ConvState.PROCESSING), cfg)
            Result.success(true)
        } catch (e: CancellationException) {
            withContext(NonCancellable) { dao.setState(id, previous) }
            throw e
        } catch (e: LlmException) {
            // De formato o rechazo: cuenta como intento. Clave, red, cuota o servidor: vuelve a la cola.
            val contentError = e.kind == LlmErrorKind.BAD_OUTPUT || e.kind == LlmErrorKind.REFUSAL
            dao.update(
                c.copy(
                    state = if (contentError) ConvState.FAILED else ConvState.READY,
                    processAttempts = c.processAttempts + if (contentError) 1 else 0,
                    lastError = "${container.app.getString(e.kind.messageRes)} (${e.message})", // se muestra tal cual en Conversación
                ),
            )
            Result.failure(e)
        } catch (e: Exception) {
            Sentry.captureException(e)
            dao.update(c.copy(state = ConvState.FAILED, processAttempts = c.processAttempts + 1, lastError = null)) // la UI da el mensaje genérico
            Result.failure(e)
        }
    }

    suspend fun buildMissingDigests(): Int {
        val cfg = aiConfig(container) ?: return 0
        return mutex.withLock { digests.buildMissing(cfg) }
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
