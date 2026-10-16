package dev.javieraguilera.mysone.ui.search

import dev.javieraguilera.mysone.data.SegmentHit
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchResultsTest {
    private val madrid = ZoneId.of("Europe/Madrid")
    private fun at(text: String) = LocalDateTime.parse(text).atZone(madrid).toInstant().toEpochMilli()
    private fun hit(id: Long, time: String) = SegmentHit(id, 1, at(time), "")

    @Test fun `quita las marcas y devuelve los tramos resaltados`() {
        val (text, ranges) = parseSnippet("…el «presupuesto» de la «obra»…")
        assertEquals("…el presupuesto de la obra…", text)
        assertEquals(listOf("presupuesto", "obra"), ranges.map { text.substring(it) })
    }

    @Test fun `una marca sin cerrar o un cierre suelto no rompen el texto`() {
        assertEquals("a » b" to emptyList<IntRange>(), parseSnippet("a » b"))
        assertEquals("ab", parseSnippet("a«b").first)
    }

    @Test fun `agrupa por día local, días y horas más recientes primero`() {
        // 23:30 en Madrid del 14 es otro día que las 00:30 del 15, aunque en UTC sean el mismo.
        val groups = groupByDay(
            listOf(hit(1, "2026-10-14T23:30"), hit(2, "2026-10-15T00:30"), hit(3, "2026-10-15T18:00")),
            madrid,
        )
        assertEquals(listOf("2026-10-15", "2026-10-14"), groups.map { it.first })
        assertEquals(listOf(3L, 2L), groups[0].second.map { it.segmentId })
    }
}
