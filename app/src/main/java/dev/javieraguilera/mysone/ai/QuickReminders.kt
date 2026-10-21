package dev.javieraguilera.mysone.ai

import androidx.room3.withWriteTransaction
import dev.javieraguilera.mysone.AppContainer
import dev.javieraguilera.mysone.core.Time
import dev.javieraguilera.mysone.data.ChatRole
import dev.javieraguilera.mysone.data.ConvState
import dev.javieraguilera.mysone.data.Segment
import java.text.Normalizer
import java.time.ZoneId

/**
 * "Recuérdame…" al momento (§7.4): con lo dicho en los últimos 3 min se piden al modelo de segundo plano
 * solo los recordatorios explícitos, que se guardan como OPEN y se programan. El procesado completo de la
 * conversación los recibe después como "ya registrados".
 */
class QuickReminders(private val container: AppContainer, private val processor: ConversationProcessor) {

    /** Lo llama [AiProcessing] con su cerrojo cogido: así no se cruza con el procesado ni con otro "recuérdame". */
    suspend fun handle(segment: Segment, zone: ZoneId = Time.zone()) {
        if (!container.settings.current().quickReminders) return
        val cfg = aiConfig(container) ?: return
        val db = container.db
        // Solo lo que sigue en la BD: mientras esperaba el cerrojo, "olvidar" pudo quitarlo y entonces no se envía.
        if (!stillThere(segment)) return
        // Ya procesada (la cola de transcripción iba con retraso): el procesado completo ya lo ha recogido.
        if (db.conversations().get(segment.conversationId)?.state == ConvState.DONE) return
        val recent = db.segments().textForConversation(segment.conversationId)
            .filter { it.endedAt >= segment.startedAt - WINDOW_MS && it.startedAt <= segment.startedAt }
            .ifEmpty { return }
        val registered = db.tasks().forConversation(segment.conversationId).map { it.text }
        val out: ReminderOut = cfg.client.completeJson(
            LlmRequest(
                Prompts.REMINDER,
                listOf(LlmMessage(ChatRole.USER, reminderInput(segment.startedAt, recent, registered, zone))),
                cfg.ai.modelBackground, 1024, Schemas.REMINDER, "low", cacheSystem = false,
            ),
        )
        val now = System.currentTimeMillis()
        val tasks = validTasks(out.tasks.filter { it.explicitReminder }, segment.startedAt, zone, registered)
        // Olvidado durante la llamada: sin tarea ni alarma con lo que se dijo.
        db.withWriteTransaction {
            if (stillThere(segment)) tasks.forEach { processor.saveTask(it, segment.conversationId, now) }
        }
    }

    private suspend fun stillThere(segment: Segment) = container.db.segments().byIds(listOf(segment.id)).isNotEmpty()

    companion object {
        private const val WINDOW_MS = 3 * 60_000L

        // La de §7.4, más "avisame" sin tilde (el ASR a veces la pierde) y "que no se me olvide".
        private val TRIGGER = Regex(
            """\b(recu[eé]rd(a|ame|amelo|ale)|av[ií]s(ame|ale)|no ((se|me|te|nos) ){1,2}(olvides|olvide|dejes olvidar)|ap[uú]nta(me|lo))\b""",
            RegexOption.IGNORE_CASE,
        )

        fun matches(text: String): Boolean = TRIGGER.containsMatchIn(Normalizer.normalize(text, Normalizer.Form.NFC))
    }
}

/** El momento de referencia es cuando se dijo, no cuando se transcribió (la cola puede ir con retraso). */
internal fun reminderInput(saidAt: Long, recent: List<Segment>, registered: List<String>, zone: ZoneId): String = buildString {
    appendLine("<momento>")
    appendLine("dicho: ${Time.dateTimeLong(saidAt, zone)} (${Time.toLocalDateTime(saidAt, zone)})")
    appendLine("zona: ${zone.id}")
    appendLine("</momento>")
    appendRegistered(registered)
    appendTranscript(recent, zone)
}
