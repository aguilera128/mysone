package dev.javieraguilera.mysone.ui.ask

import dev.javieraguilera.mysone.ai.LlmErrorKind
import dev.javieraguilera.mysone.ai.LlmException
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Test

class AskTextTest {
    @Test fun `quita los marcadores y devuelve los ids en orden y sin repetir`() {
        val r = parseCitations("Quedaste con Marta el viernes [c:12]. Lo repetiste luego [c:7, c:12] y [c:3,4].")
        assertEquals("Quedaste con Marta el viernes. Lo repetiste luego y.", r.text)
        assertEquals(listOf(12L, 7L, 3L, 4L), r.ids)
    }

    @Test fun `un marcador sin numeros se deja tal cual`() {
        val r = parseCitations("Nada citado [c:] aquí")
        assertEquals("Nada citado [c:] aquí", r.text)
        assertEquals(emptyList<Long>(), r.ids)
    }

    private fun llm(kind: LlmErrorKind) = LlmException(kind, "HTTP")

    @Test fun `clasifica por kind, por la causa o como red`() {
        assertEquals(AiError.AUTH, classifyAiError(llm(LlmErrorKind.AUTH)))
        assertEquals(AiError.RATE, classifyAiError(RuntimeException(llm(LlmErrorKind.RATE))))
        assertEquals(AiError.BAD_OUTPUT, classifyAiError(llm(LlmErrorKind.BAD_OUTPUT)))
        assertEquals(AiError.NETWORK, classifyAiError(IOException("timeout")))
        assertEquals(AiError.OTHER, classifyAiError(IllegalStateException()))
    }
}
