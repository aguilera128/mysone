package dev.javieraguilera.mysone.ui.ask

import androidx.annotation.StringRes
import dev.javieraguilera.mysone.R
import dev.javieraguilera.mysone.ai.LlmException
import java.io.IOException

/** Respuesta sin los marcadores [c:ID] y los IDs citados, en orden de aparición y sin repetir. */
data class CitedText(val text: String, val ids: List<Long>)

private val MARKER = Regex("""[ \t]*\[c:([^\]]*)]""")
private val NUMBER = Regex("""\d+""")

/** Admite [c:12], [c:12, 13] y [c:12, c:13]. Un marcador sin números se deja tal cual. */
fun parseCitations(raw: String): CitedText {
    val ids = LinkedHashSet<Long>()
    val text = MARKER.replace(raw) { m ->
        val found = NUMBER.findAll(m.groupValues[1]).mapNotNull { it.value.toLongOrNull() }.toList()
        if (found.isEmpty()) m.value else "".also { ids += found }
    }
    return CitedText(text.trim(), ids.toList())
}

/** Tipos de fallo de la IA que la UI explica de forma distinta (los de LlmErrorKind de B3). */
enum class AiError { NETWORK, AUTH, RATE, SERVER, BAD_OUTPUT, REFUSAL, OTHER }

fun classifyAiError(e: Throwable): AiError {
    val chain = generateSequence(e) { it.cause }
    val kind = chain.firstNotNullOfOrNull { (it as? LlmException)?.kind }
    return when {
        kind != null -> AiError.valueOf(kind.name)
        chain.any { it is IOException } -> AiError.NETWORK
        else -> AiError.OTHER
    }
}

@StringRes
fun AiError.messageRes(): Int = when (this) {
    AiError.NETWORK -> R.string.ask_error_network
    AiError.AUTH -> R.string.ask_error_auth
    AiError.RATE -> R.string.ask_error_rate
    AiError.SERVER -> R.string.ask_error_server
    AiError.BAD_OUTPUT -> R.string.ask_error_bad_output
    AiError.REFUSAL -> R.string.ask_error_refusal
    AiError.OTHER -> R.string.ask_error_other
}
