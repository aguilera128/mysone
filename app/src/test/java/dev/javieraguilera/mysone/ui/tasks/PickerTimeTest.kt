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
}
