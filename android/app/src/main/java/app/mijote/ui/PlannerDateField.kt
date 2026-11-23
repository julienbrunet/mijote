package app.mijote.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

private val plannerFullDate = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.FRENCH)

private fun LocalDate.calendarMillis() = atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
private fun Long.calendarDate() = Instant.ofEpochMilli(this).atOffset(ZoneOffset.UTC).toLocalDate()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlannerDateField(
    label: String,
    selected: LocalDate,
    minimum: LocalDate,
    maximum: LocalDate,
    enabled: Boolean,
    onDate: (LocalDate) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Text(label, color = Sunny.InkTitle)
    AgendaAction(
        plannerFullDate.format(selected).replaceFirstChar { it.uppercase() },
        { open = true }, enabled = enabled, icon = Icons.Filled.DateRange,
    )
    if (open) {
        val dates = remember(minimum, maximum) {
            object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis.calendarDate() in minimum..maximum
                override fun isSelectableYear(year: Int) = year in minimum.year..maximum.year
            }
        }
        val picker = rememberDatePickerState(
            initialSelectedDateMillis = selected.coerceIn(minimum, maximum).calendarMillis(),
            yearRange = minimum.year..maximum.year,
            selectableDates = dates,
        )
        val picked = picker.selectedDateMillis?.calendarDate()
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(
                    enabled = picked != null && picked in minimum..maximum,
                    onClick = { picked?.let(onDate); open = false },
                ) { Text("Choisir cette date", color = Sunny.Ink) }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Annuler", color = Sunny.Ink) } },
        ) {
            DatePicker(state = picker)
        }
    }
}
