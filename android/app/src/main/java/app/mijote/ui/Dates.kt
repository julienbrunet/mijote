package app.mijote.ui

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val french = Locale.FRENCH
private val dayMonth = DateTimeFormatter.ofPattern("d MMMM", french)
private val short = DateTimeFormatter.ofPattern("EEE d MMM", french)

/** "lun. 2 nov." */
fun shortDate(d: LocalDate): String = short.format(d)

/** "2 → 8 novembre", "30 octobre → 5 novembre", or a single day. */
fun periodLabel(start: LocalDate, end: LocalDate?): String = when {
    end == null -> "à partir du ${dayMonth.format(start)}"
    end == start -> dayMonth.format(start)
    end.month == start.month -> "${start.dayOfMonth} → ${dayMonth.format(end)}"
    else -> "${dayMonth.format(start)} → ${dayMonth.format(end)}"
}

/** Day sheet title: "Aujourd'hui", "Demain" or the weekday ("Mercredi"). */
fun dayLabel(d: LocalDate, today: LocalDate): String = when (d) {
    today -> "Aujourd'hui"
    today.plusDays(1) -> "Demain"
    else -> DateTimeFormatter.ofPattern("EEEE", french).format(d).replaceFirstChar { it.uppercase() }
}
