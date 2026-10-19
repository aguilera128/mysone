package dev.javieraguilera.mysone.ui.today

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.javieraguilera.mysone.R
import dev.javieraguilera.mysone.core.Time
import dev.javieraguilera.mysone.data.ConvState
import dev.javieraguilera.mysone.data.Conversation
import dev.javieraguilera.mysone.data.GapReason
import java.time.LocalDate
import java.time.format.DateTimeFormatter

// Formatos de fechas, duraciones y huecos que comparten Hoy, Conversación y Buscar.

private val DAY_FULL = DateTimeFormatter.ofPattern("EEEE d 'de' MMMM", Time.SPAIN)
private val DAY_FULL_YEAR = DateTimeFormatter.ofPattern("EEEE d 'de' MMMM 'de' yyyy", Time.SPAIN)
private val DAY_CHIP = DateTimeFormatter.ofPattern("EEE d", Time.SPAIN)

internal fun String.capitalized(): String = replaceFirstChar { it.titlecase(Time.SPAIN) }

/** "Jueves 15 de octubre"; con año si no es el actual. [day] = "yyyy-MM-dd". */
internal fun dayFull(day: String, today: String = Time.today()): String {
    val date = LocalDate.parse(day)
    val format = if (date.year == LocalDate.parse(today).year) DAY_FULL else DAY_FULL_YEAR
    return format.format(date).capitalized()
}

/** "Hoy", "Ayer" o null si es otro día. */
@Composable
internal fun relativeDay(day: String, today: String = Time.today()): String? = when (day) {
    today -> stringResource(R.string.ui_day_today)
    LocalDate.parse(today).minusDays(1).toString() -> stringResource(R.string.ui_day_yesterday)
    else -> null
}

/** "Hoy", "Ayer" o "Martes 13 de octubre". */
@Composable
internal fun dayHeading(day: String): String {
    val today = Time.today()
    return relativeDay(day, today) ?: dayFull(day, today)
}

/** Etiqueta corta para el selector de día: "Hoy", "Ayer", "mar 13". */
@Composable
internal fun dayChip(day: String): String = relativeDay(day) ?: DAY_CHIP.format(LocalDate.parse(day))

/** "27 min", "1 h 5 min", "menos de 1 min". */
@Composable
internal fun durationLabel(ms: Long): String {
    val minutes = (ms / 60_000).toInt()
    return when {
        minutes < 1 -> stringResource(R.string.ui_duration_under_min)
        minutes < 60 -> stringResource(R.string.ui_duration_min, minutes)
        minutes % 60 == 0 -> stringResource(R.string.ui_duration_h, minutes / 60)
        else -> stringResource(R.string.ui_duration_h_min, minutes / 60, minutes % 60)
    }
}

/** "10:05–10:32", o "desde las 10:05" si aún no ha terminado. */
@Composable
internal fun timeRange(from: Long, to: Long?): String =
    if (to == null) stringResource(R.string.ui_since, Time.hourMinute(from))
    else stringResource(R.string.ui_time_range, Time.hourMinute(from), Time.hourMinute(to))

/** "a · b · c" sin los vacíos. */
@Composable
internal fun joined(vararg parts: String?): String {
    val pattern = stringResource(R.string.ui_join) // "%1$s · %2$s" sin rellenar
    return parts.filterNotNull().filter { it.isNotBlank() }.reduceOrNull { a, b -> pattern.format(a, b) }.orEmpty()
}

@Composable
internal fun gapLabel(reason: String): String = stringResource(
    when (reason) {
        GapReason.PAUSED -> R.string.gap_paused
        GapReason.MIC_BUSY -> R.string.gap_mic_busy
        GapReason.BATTERY -> R.string.gap_battery
        GapReason.ERROR -> R.string.gap_error
        GapReason.KILLED -> R.string.gap_killed
        else -> R.string.gap_stopped
    },
)

/** "jue 16 oct, 09:45" (avisos y fechas límite de las tareas). */
@Composable
internal fun dayTime(ms: Long): String = stringResource(R.string.ui_day_time, Time.dayShort(ms), Time.hourMinute(ms))

/** El título de la IA o, si aún no lo hay, en qué punto está la conversación. */
@Composable
internal fun conversationTitle(conv: Conversation, transcribing: Boolean): String = conv.title ?: stringResource(
    when {
        transcribing -> R.string.conv_state_transcribing
        conv.state == ConvState.OPEN -> R.string.conv_state_open
        conv.state == ConvState.PROCESSING -> R.string.conv_state_processing
        conv.state == ConvState.FAILED -> R.string.conv_state_failed
        else -> R.string.conv_state_unprocessed
    },
)
