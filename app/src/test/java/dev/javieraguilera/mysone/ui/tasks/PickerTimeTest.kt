package dev.javieraguilera.mysone.ui.tasks

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class PickerTimeTest {
    private val madrid = ZoneId.of("Europe/Madrid")

    @Test fun `ida y vuelta entre el DatePicker y el instante local`() {
        // 23:30 en Madrid es ya el día siguiente en UTC: el picker tiene que mostrar el día local.
        val ms = ZonedDateTime.of(2026, 10, 15, 23, 30, 0, 0, madrid).toInstant().toEpochMilli()
        val picker = pickerDateOf(ms, madrid)
        assertEquals("2026-10-15", pickerLocalDate(picker).toString())
        assertEquals(ms, pickerToEpoch(picker, 23, 30, madrid))
    }

    @Test fun `el dia del cambio de hora conserva la hora de pared`() {
        val picker = pickerDateOf(ZonedDateTime.of(2026, 10, 25, 12, 0, 0, 0, madrid).toInstant().toEpochMilli(), madrid)
        val expected = ZonedDateTime.of(2026, 10, 25, 9, 0, 0, 0, madrid).toInstant().toEpochMilli()
        assertEquals(expected, pickerToEpoch(picker, 9, 0, madrid))
    }

    @Test fun `aviso nuevo a las 9, o a la próxima hora en punto si hoy ya han pasado`() {
        fun at(d: Int, h: Int, m: Int) = ZonedDateTime.of(2026, 10, d, h, m, 0, 0, madrid).toInstant().toEpochMilli()
        val today = pickerDateOf(at(16, 13, 31), madrid)
        assertEquals(9 to 0, defaultReminderTime(today, at(16, 8, 15), madrid))
        assertEquals(14 to 0, defaultReminderTime(today, at(16, 13, 31), madrid))
        assertEquals(23 to 59, defaultReminderTime(today, at(16, 23, 30), madrid))
        assertEquals(9 to 0, defaultReminderTime(pickerDateOf(at(17, 12, 0), madrid), at(16, 13, 31), madrid))
    }
}
