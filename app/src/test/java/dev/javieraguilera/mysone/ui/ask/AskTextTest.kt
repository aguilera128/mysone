package dev.javieraguilera.mysone.ui.ask

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

    /** Imita a LlmException de B3: lo que cuenta es la propiedad `kind`. */
    enum class Kind { AUTH, RATE, BAD_OUTPUT }

    class FakeLlmException(val kind: Kind) : Exception("sin contenido")

    @Test fun `clasifica por kind, por la causa o como red`() {
        assertEquals(AiError.AUTH, classifyAiError(FakeLlmException(Kind.AUTH)))
        assertEquals(AiError.RATE, classifyAiError(RuntimeException(FakeLlmException(Kind.RATE))))
        assertEquals(AiError.BAD_OUTPUT, classifyAiError(FakeLlmException(Kind.BAD_OUTPUT)))
        assertEquals(AiError.NETWORK, classifyAiError(IOException("timeout")))
        assertEquals(AiError.OTHER, classifyAiError(IllegalStateException()))
    }
}
