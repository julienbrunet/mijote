package app.mijote.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mijote.data.GenerationInfo
import app.mijote.data.MealPlan
import app.mijote.data.MenuSlot
import app.mijote.data.periodImpact
import java.time.LocalDate

private const val MAX_DAYS = 21
private const val START_CHOICES = 14
private const val MAX_PEOPLE = 20

/** Which meals of each day to plan. */
enum class MealKind(val plan: MealPlan, val perDay: Int) {
    LUNCH(MealPlan.LUNCH, 1), DINNER(MealPlan.DINNER, 1), BOTH(MealPlan.LUNCH_DINNER, 2)
}

/** Generation settings and summary scroll together, above the shared navigation. */
@Composable
fun GenerateScreen(
    info: GenerationInfo?,
    today: LocalDate,
    people: Int,
    busy: Boolean,
    error: String?,
    onGenerate: (start: LocalDate, meals: Int, plan: MealPlan) -> Unit,
    onPreferences: () -> Unit = {},
    onPeople: (Int) -> Unit = {},
    slots: List<MenuSlot> = emptyList(),
    scrollState: ScrollState = rememberScrollState(),
) {
    var startDay by rememberSaveable { mutableLongStateOf(today.plusDays(1).toEpochDay()) }
    var kind by rememberSaveable { mutableStateOf(MealKind.BOTH) }
    var days by rememberSaveable(info?.horizonDays) { mutableIntStateOf((info?.horizonDays ?: 7).coerceIn(1, MAX_DAYS)) }
    val start = LocalDate.ofEpochDay(startDay)
    val end = start.plusDays(days - 1L)
    val meals = days * kind.perDay
    val (replaced, kept) = periodImpact(slots, start, end)

    Column(Modifier.fillMaxSize().background(Sunny.Butter).padding(bottom = 108.dp)
        .navigationBarsPadding().verticalScroll(scrollState)) {
        HomeHeader("Idées de repas", "Un menu qui s'adapte à votre semaine", onPreferences)
        Column(Modifier.padding(horizontal = 20.dp).padding(top = 18.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (info != null && !info.enoughRecipes) {
                Text("Il faut au moins ${info.minRecipes} recettes pour générer un menu : il y en a ${info.recipeCount}. Ajoutez-en ${info.minRecipes - info.recipeCount} de plus.",
                    color = Sunny.Ink, fontSize = 14.sp,
                    modifier = Modifier.fillMaxWidth().background(Sunny.ErrorBg, Shapes.Control).padding(14.dp))
            }
            FormCard {
                FormLabel("À partir de quand ?")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (0 until START_CHOICES).map { today.plusDays(it.toLong()) }.forEach { d ->
                        DayTile(if (d == today) "Auj." else dayLabel(d, today), shortDate(d).substringAfter(' '), d == start) { startDay = d.toEpochDay() }
                    }
                }
                FormDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Durée", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Sunny.Ink, modifier = Modifier.weight(1f))
                    Stepper(days, 1, MAX_DAYS, "Un jour de moins", "Un jour de plus") { days = it }
                    Text(if (days == 1) " jour" else " jours", fontSize = 13.sp, color = Sunny.InkTitle)
                }
                FormDivider()
                FormLabel("Quels repas ?")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip("Midi", kind == MealKind.LUNCH, Modifier.weight(1f)) { kind = MealKind.LUNCH }
                    Chip("Soir", kind == MealKind.DINNER, Modifier.weight(1f)) { kind = MealKind.DINNER }
                    Chip("Les deux", kind == MealKind.BOTH, Modifier.weight(1.3f)) { kind = MealKind.BOTH }
                }
                FormDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        FormLabel("À table")
                        Text("Nombre de personnes", fontSize = 12.sp, color = Sunny.InkTitle, modifier = Modifier.padding(top = 4.dp))
                    }
                    Stepper(people, 1, MAX_PEOPLE, "Une personne de moins", "Une personne de plus", onPeople)
                }
            }
            Column(Modifier.fillMaxWidth().background(Sunny.Card, Shapes.Card).padding(18.dp)) {
                Text("Votre menu", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Sunny.InkTitle)
                Text(periodSummary(start, end), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Sunny.Ink, modifier = Modifier.padding(top = 5.dp))
                Text("$meals repas · $people ${if (people > 1) "personnes" else "personne"}", fontSize = 14.sp, color = Sunny.InkTitle, modifier = Modifier.padding(top = 4.dp))
                compactImpactText(replaced, kept)?.let {
                    Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.Top) {
                        Icon(Icons.Filled.Info, null, tint = Sunny.PeachDeep, modifier = Modifier.size(18.dp))
                        Text(it, fontSize = 12.sp, color = Sunny.Ink, lineHeight = 17.sp, modifier = Modifier.padding(start = 8.dp))
                    }
                }
                ErrorText(error)
                Button(
                    onClick = { onGenerate(start, meals, kind.plan) },
                    enabled = !busy && info?.enoughRecipes == true,
                    shape = Shapes.Control,
                    colors = ButtonDefaults.buttonColors(containerColor = Sunny.PeachDeep, contentColor = Color.White),
                    modifier = Modifier.fillMaxWidth().padding(top = 14.dp).height(50.dp),
                ) {
                    Text(if (busy) "Génération…" else "Trouver mes idées de repas", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun FormCard(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(Sunny.Card, Shapes.Card).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
}

@Composable
private fun FormLabel(title: String) {
    Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Sunny.Ink)
}

@Composable
private fun FormDivider() {
    HorizontalDivider(color = Sunny.Butter, thickness = 1.dp)
}

/** "Du 3 au 9 novembre", or the single day. */
private fun periodSummary(start: LocalDate, end: LocalDate): String =
    if (start == end) "Le ${periodLabel(start, end)}" else "Du ${periodLabel(start, end).replace(" → ", " au ")}"

/** Compact contextual notice without hiding replacement information. */
private fun compactImpactText(replaced: Int, kept: Int): String? = listOfNotNull(
    if (replaced > 0) "$replaced repas ${if (replaced == 1) "sera remplacé" else "seront remplacés"}" else null,
    if (kept > 0) "$kept repas ${if (kept == 1) "sera conservé" else "seront conservés"}" else null,
).joinToString(" · ").ifEmpty { null }

@Composable
private fun DayTile(day: String, date: String, on: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.width(76.dp).clip(Shapes.Control).background(if (on) Sunny.PeachDeep else Sunny.ButterLight)
            .clickable(onClick = onClick).padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(day, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (on) Color.White else Sunny.Ink, maxLines = 1)
        Text(date, fontSize = 11.sp, color = if (on) Color.White else Sunny.InkSoft)
    }
}

@Composable
private fun Stepper(value: Int, min: Int, max: Int, less: String, more: String, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StepTile(value > min, less, { onChange(value - 1) }) { Text("−", fontSize = 22.sp, color = if (value > min) Sunny.Ink else Sunny.InkSoft) }
        Text("$value", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Sunny.Ink, modifier = Modifier.width(40.dp), textAlign = TextAlign.Center)
        StepTile(value < max, more, { onChange(value + 1) }) {
            Icon(Icons.Filled.Add, contentDescription = more, tint = if (value < max) Sunny.Ink else Sunny.InkSoft)
        }
    }
}

@Composable
private fun StepTile(enabled: Boolean, label: String, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier.size(40.dp).clip(Shapes.Control).background(Sunny.ButterLight).clickable(enabled = enabled, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}
