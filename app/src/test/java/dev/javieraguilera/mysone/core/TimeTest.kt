package dev.javieraguilera.mysone.core

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimeTest {
    private val madrid = ZoneId.of("Europe/Madrid")

    /** 15-oct-2026 18:42:05 en Madrid (UTC+2). */
    private val ms = ZonedDateTime.of(2026, 10, 15, 18, 42, 5, 0, madrid).toInstant().toEpochMilli()

    @Test fun `formatos en español de España`() {
        assertEquals("18:42", Time.hourMinute(ms, madrid))
        assertEquals("18:42:05", Time.hourMinuteSecond(ms, madrid))
        assertEquals("jueves 15 de octubre de 2026", Time.dayLong(ms, madrid))
        assertEquals("jueves 15 de octubre de 2026, 18:42", Time.dateTimeLong(ms, madrid))
    }

    @Test fun `el día depende de la zona`() {
        assertEquals("2026-10-15", Time.dayKey(ms, madrid))
        assertEquals("2026-10-16", Time.dayKey(ms, ZoneId.of("Asia/Tokyo")))
    }

    @Test fun `límites de un día normal`() {
        val (from, to) = Time.dayBounds("2026-10-15", madrid)
        assertEquals(ZonedDateTime.of(2026, 10, 15, 0, 0, 0, 0, madrid).toInstant().toEpochMilli(), from)
        assertEquals(24 * 3_600_000L, to - from)
        assertEquals(from, Time.startOfDay(ms, madrid))
    }

    @Test fun `el día del cambio al horario de invierno dura 25 horas`() {
        val (from, to) = Time.dayBounds("2026-10-25", madrid)
        assertEquals(25 * 3_600_000L, to - from)
    }

    @Test fun `hora local de ida y vuelta para la IA`() {
        assertEquals("2026-10-15T18:42", Time.toLocalDateTime(ms, madrid))
        assertEquals(ms - 5_000, Time.parseLocalDateTime("2026-10-15T18:42", madrid))
        assertEquals(ms, Time.parseLocalDateTime(" 2026-10-15T18:42:05 ", madrid))
    }

    @Test fun `fechas inválidas devuelven null`() {
        assertNull(Time.parseLocalDateTime("2026-13-01T10:00", madrid))
        assertNull(Time.parseLocalDateTime("mañana a las 10", madrid))
        assertNull(Time.parseLocalDateTime("2026-10-16", madrid))
        assertNull(Time.parseLocalDateTime("", madrid))
    }
}
