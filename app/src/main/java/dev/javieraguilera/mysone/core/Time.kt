package dev.javieraguilera.mysone.core

import android.text.format.DateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * Fechas y horas. En la BD todo va en epoch ms UTC; aquí se pasa a la zona del dispositivo.
 * Los días se identifican con su fecha local ISO ("2026-10-15"), igual que `DailyDigest.day`.
 * Todas las funciones aceptan la zona para poder probarlas; por defecto, la del sistema.
 * [dayLong] y [dateTimeLong] van siempre en español porque son entrada de los prompts (que están en español);
 * lo que se enseña en pantalla usa [dayShort], [dateTimeUi] y [uiFormat], en el idioma del teléfono.
 */
object Time {
    val SPAIN: Locale = Locale.forLanguageTag("es-ES")

    private val HOUR_MINUTE = DateTimeFormatter.ofPattern("HH:mm", SPAIN)
    private val HOUR_MINUTE_SECOND = DateTimeFormatter.ofPattern("HH:mm:ss", SPAIN)
    private val DAY_LONG = DateTimeFormatter.ofPattern("EEEE d 'de' MMMM 'de' yyyy", SPAIN)
    private val LOCAL_DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")

    fun zone(): ZoneId = ZoneId.systemDefault()

    /** "18:42" */
    fun hourMinute(ms: Long, zone: ZoneId = zone()): String = HOUR_MINUTE.format(at(ms, zone))

    /** "18:42:05" (marcas de la transcripción) */
    fun hourMinuteSecond(ms: Long, zone: ZoneId = zone()): String = HOUR_MINUTE_SECOND.format(at(ms, zone))

    /** "jueves 15 de octubre de 2026" */
    fun dayLong(ms: Long, zone: ZoneId = zone()): String = DAY_LONG.format(at(ms, zone))

    /** "jue, 15 oct" / "Thu, Oct 15", según el idioma del teléfono (pantalla). */
    fun dayShort(ms: Long, zone: ZoneId = zone()): String = uiFormat("EEEdMMM").format(at(ms, zone))

    /** "jueves, 15 de octubre de 2026, 18:42" / "Thursday, October 15, 2026, 18:42" (pantalla). */
    fun dateTimeUi(ms: Long, zone: ZoneId = zone()): String = "${uiFormat("EEEEdMMMMyyyy").format(at(ms, zone))}, ${hourMinute(ms, zone)}"

    /** El idioma de la pantalla: la app solo está en inglés y español; con el teléfono en otro, sale en inglés. */
    fun uiLocale(): Locale = Locale.getDefault().takeIf { it.language == "en" || it.language == "es" } ?: Locale.ENGLISH

    /** Formato de fecha de Android en el idioma de la pantalla a partir de un esqueleto ("EEEdMMM"). */
    fun uiFormat(skeleton: String, locale: Locale = uiLocale()): DateTimeFormatter =
        DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale)

    /** "jueves 15 de octubre de 2026, 18:42" (entrada de los prompts) */
    fun dateTimeLong(ms: Long, zone: ZoneId = zone()): String = "${dayLong(ms, zone)}, ${hourMinute(ms, zone)}"

    /** Fecha local ISO del instante: "2026-10-15". */
    fun dayKey(ms: Long, zone: ZoneId = zone()): String = at(ms, zone).toLocalDate().toString()

    fun today(zone: ZoneId = zone()): String = LocalDate.now(zone).toString()

    /** [inicio, fin) del día local en epoch ms. Respeta los días de 23 y 25 horas del cambio de hora. */
    fun dayBounds(day: String, zone: ZoneId = zone()): Pair<Long, Long> {
        val date = LocalDate.parse(day)
        return startOf(date, zone) to startOf(date.plusDays(1), zone)
    }

    fun startOfDay(ms: Long, zone: ZoneId = zone()): Long = startOf(at(ms, zone).toLocalDate(), zone)

    /** Hora local sin zona, el formato que intercambiamos con la IA: "2026-10-16T09:45". */
    fun toLocalDateTime(ms: Long, zone: ZoneId = zone()): String = LOCAL_DATE_TIME.format(at(ms, zone))

    /** Inverso de [toLocalDateTime] (acepta también segundos). null si el texto no es una fecha válida. */
    fun parseLocalDateTime(text: String, zone: ZoneId = zone()): Long? = try {
        LocalDateTime.parse(text.trim()).atZone(zone).toInstant().toEpochMilli()
    } catch (_: DateTimeParseException) {
        null
    }

    private fun at(ms: Long, zone: ZoneId) = Instant.ofEpochMilli(ms).atZone(zone)

    private fun startOf(date: LocalDate, zone: ZoneId) = date.atStartOfDay(zone).toInstant().toEpochMilli()
}
