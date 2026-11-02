package app.mijote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mijote.data.MenuSlot
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** How many days ahead a meal can be planned from the dialog. */
private const val PLAN_DAYS = 21

/** Examples shown under the text field of a manual meal. */
private val manualExamples = listOf("Commander une pizza", "Passer au McDo", "Repas chez des amis")

/**
 * Picks the day and the meal (midi / soir) where to plan something: a recipe ([manual] false, its
 * [heading] is the recipe title) or a manual meal ([manual] true: a free text is typed too). The meal
 * already planned at the chosen time is shown: confirming replaces it.
 */
@Composable
fun PlanMealDialog(
    heading: String,
    today: LocalDate,
    slots: List<MenuSlot>,
    manual: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (date: LocalDate, meal: String, text: String?, replace: Boolean) -> Unit,
    initialDate: LocalDate? = null,
    initialMeal: String? = null,
) {
    var date by remember { mutableStateOf(initialDate ?: today) }
    var meal by remember { mutableStateOf(initialMeal ?: "dinner") }
    var text by remember { mutableStateOf("") }
    val taken = slots.firstOrNull { it.date == date && it.meal == meal }
    val takenTitle = taken?.let { it.title ?: it.recipe?.title ?: "un repas sans recette" }
    val ready = !manual || text.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Sunny.Card,
        title = {
            Column {
                Text(if (manual) "Ajouter un repas libre" else "Planifier", fontSize = 13.sp, letterSpacing = 1.sp,
                    fontWeight = FontWeight.SemiBold, color = Sunny.PeachDeep)
                if (!manual) Text(heading, fontWeight = FontWeight.Bold, color = Sunny.Ink, fontSize = 18.sp, maxLines = 2)
            }
        },
        text = {
            Column {
                if (manual) {
                    MealTitleField(text, { text = it })
                    Row(Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        manualExamples.forEach { ex -> Chip(ex, selected = false) { text = ex } }
                    }
                    Spacer(Modifier.height(16.dp))
                }
                Text("JOUR", fontSize = 11.sp, letterSpacing = 1.sp, fontWeight = FontWeight.SemiBold, color = Sunny.InkSoft)
                DayRow(today, date) { date = it }
                Text("REPAS", fontSize = 11.sp, letterSpacing = 1.sp, fontWeight = FontWeight.SemiBold, color = Sunny.InkSoft,
                    modifier = Modifier.padding(top = 14.dp))
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("Midi", meal == "lunch", Modifier.weight(1f)) { meal = "lunch" }
                    Chip("Soir", meal == "dinner", Modifier.weight(1f)) { meal = "dinner" }
                }
                Text(
                    if (takenTitle != null) "Remplacera « $takenTitle »." else "Rien n'est encore prévu à ce moment-là.",
                    fontSize = 13.sp, color = if (takenTitle != null) Sunny.Error else Sunny.InkSoft,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(date, meal, if (manual) text.trim() else null, taken != null) },
                enabled = ready, shape = Shapes.Control,
                colors = ButtonDefaults.buttonColors(containerColor = Sunny.PeachDeep, contentColor = Color.White),
            ) { Text(if (taken != null) "Remplacer" else "Planifier", fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler", color = Sunny.Slate) } },
    )
}

/** Free text of a manual meal, with a small dialog to rename one already planned. */
@Composable
fun MealTitleDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Sunny.Card,
        title = { Text("Repas libre", fontWeight = FontWeight.Bold, color = Sunny.Ink, fontSize = 18.sp) },
        text = { MealTitleField(text, { text = it }) },
        confirmButton = {
            Button(onClick = { onConfirm(text.trim()) }, enabled = text.isNotBlank(), shape = Shapes.Control,
                colors = ButtonDefaults.buttonColors(containerColor = Sunny.PeachDeep, contentColor = Color.White),
            ) { Text("Enregistrer", fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler", color = Sunny.Slate) } },
    )
}

@Composable
private fun MealTitleField(value: String, onChange: (String) -> Unit) {
    TextField(
        value = value, onValueChange = { if (it.length <= 100) onChange(it) },
        label = { Text("Intitulé du repas") }, singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
            focusedIndicatorColor = Sunny.PeachDeep, unfocusedIndicatorColor = Sunny.Track,
            focusedLabelColor = Sunny.PeachDeep, unfocusedLabelColor = Sunny.InkSoft, cursorColor = Sunny.PeachDeep,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

private val weekday = DateTimeFormatter.ofPattern("EEE", Locale.FRENCH)

/** The next days as a scrollable row of tiles: weekday and day of month. */
@Composable
private fun DayRow(today: LocalDate, selected: LocalDate, onSelect: (LocalDate) -> Unit) {
    Row(Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        (0 until PLAN_DAYS).map { today.plusDays(it.toLong()) }.forEach { d ->
            val on = d == selected
            Column(
                Modifier.width(52.dp).clip(Shapes.Control).background(if (on) Sunny.PeachDeep else Sunny.ButterLight)
                    .clickable { onSelect(d) }.padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(if (d == today) "auj." else weekday.format(d), fontSize = 11.sp, color = if (on) Color.White else Sunny.InkTitle)
                Text("${d.dayOfMonth}", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = if (on) Color.White else Sunny.Ink)
            }
        }
    }
}

/** Selectable pill (choice of meal, dish type, season…). */
@Composable
fun Chip(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(
        text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
        color = if (selected) Color.White else Sunny.Ink,
        modifier = modifier.clip(Shapes.Control).background(if (selected) Sunny.PeachDeep else Sunny.ButterLight)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
    )
}
