package dev.javieraguilera.mysone.ui.tasks

import dev.javieraguilera.mysone.core.Time
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

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
