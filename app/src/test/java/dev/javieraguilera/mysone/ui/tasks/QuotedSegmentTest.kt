package dev.javieraguilera.mysone.ui.tasks

import dev.javieraguilera.mysone.data.Segment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QuotedSegmentTest {
    private fun seg(id: Long, text: String) = Segment(id, 1, id * 1_000, id * 1_000 + 900, text)

    private val segments = listOf(
        seg(1, "Vale, Valencia está lejos."),
        seg(2, "Mañana viene el paquete de Correos."),
        seg(3, "Recuérdame llamarle si no llega"),
        seg(4, "antes del mediodía, que luego cierra."),
    )

    @Test fun `abre en el segmento donde empieza la cita, aunque siga en el siguiente`() {
        assertEquals(3L, quotedSegment("Recuérdame llamarle si no llega antes del mediodía", segments))
        // Mayúsculas y puntuación no cuentan.
        assertEquals(2L, quotedSegment("mañana viene el paquete", segments))
    }

    @Test fun `palabras enteras, y sin coincidencia null`() {
        assertEquals(1L, quotedSegment("Valencia", segments))
        assertNull(quotedSegment("Valen", segments))
        assertNull(quotedSegment("lejos de Valencia", segments))
        assertNull(quotedSegment("¿?", segments))
    }
}
