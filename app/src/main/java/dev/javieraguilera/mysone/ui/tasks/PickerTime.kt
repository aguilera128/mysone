package dev.javieraguilera.mysone.ui.tasks

import dev.javieraguilera.mysone.core.Time
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

// El DatePicker de Material 3 trabaja con la medianoche UTC del día elegido; los recordatorios, con
// el instante real en la zona del dispositivo. Estas funciones pasan de uno a otro.

/** Día local de [ms] como medianoche UTC (valor inicial del DatePicker). */
fun pickerDateOf(ms: Long, zone: ZoneId = Time.zone()): Long =
    Instant.ofEpochMilli(ms).atZone(zone).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

/** Fecha que representa un valor del DatePicker. */
fun pickerLocalDate(utcMidnightMs: Long): LocalDate = Instant.ofEpochMilli(utcMidnightMs).atZone(ZoneOffset.UTC).toLocalDate()

/** Instante del día elegido en el DatePicker a la hora y minuto del TimePicker, en [zone]. */
fun pickerToEpoch(utcMidnightMs: Long, hour: Int, minute: Int, zone: ZoneId = Time.zone()): Long =
    pickerLocalDate(utcMidnightMs).atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

/**
 * Hora inicial del TimePicker si el aviso no tenía hora: las 09:00 (como hace la IA con las fechas sin hora) o,
 * si el día elegido es hoy y ya han pasado, la próxima hora en punto (23:59 si esa ya es mañana).
 */
fun defaultReminderTime(utcMidnightMs: Long, now: Long, zone: ZoneId = Time.zone()): Pair<Int, Int> {
    if (pickerToEpoch(utcMidnightMs, 9, 0, zone) > now) return 9 to 0
    val next = Instant.ofEpochMilli(now).atZone(zone).truncatedTo(ChronoUnit.HOURS).plusHours(1)
    return if (next.toLocalDate() == pickerLocalDate(utcMidnightMs)) next.hour to 0 else 23 to 59
}
