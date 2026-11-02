package app.mijote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
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
private val DAY_SHORTCUTS = listOf(3, 5, 7, 14)

/** Which meals of each day to plan. */
enum class MealKind(val plan: MealPlan, val perDay: Int) {
    LUNCH(MealPlan.LUNCH, 1), DINNER(MealPlan.DINNER, 1), BOTH(MealPlan.LUNCH_DINNER, 2)
}

/**
 * Step 1 of the menu generation ("Idées de repas" tab): the home header, then four short questions
 * in the order people think of them (from when, how many days, which meals, for how many people),
 * and a panel fixed above the navigation with the summary, what happens to the meals already
 * planned, and "C'est parti".
 */
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
) {
    var startDay by rememberSaveable { mutableLongStateOf(today.plusDays(1).toEpochDay()) }
    var kind by rememberSaveable { mutableStateOf(MealKind.BOTH) }
    var days by rememberSaveable(info?.horizonDays) { mutableIntStateOf((info?.horizonDays ?: 7).coerceIn(1, MAX_DAYS)) }
    val start = LocalDate.ofEpochDay(startDay)
    val end = start.plusDays(days - 1L)
    val meals = days * kind.perDay
    val (replaced, kept) = periodImpact(slots, start, end)

    Box(Modifier.fillMaxSize().background(Sunny.Butter)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 330.dp)) {
            HomeHeader("Idées de repas", "Mijote vous propose un menu varié", onPreferences)
            Column(Modifier.padding(horizontal = 18.dp).padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (info != null && !info.enoughRecipes) {
                    Text(
                        "Il faut au moins ${info.minRecipes} recettes pour générer un menu : il y en a ${info.recipeCount}. " +
                            "Ajoutez-en ${info.minRecipes - info.recipeCount} de plus.",
                        color = Sunny.Ink, fontSize = 14.sp,
                        modifier = Modifier.fillMaxWidth().background(Sunny.ErrorBg, Shapes.Control).padding(14.dp),
                    )
                }
                Question("1", "À partir de quand ?") {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        (0 until START_CHOICES).map { today.plusDays(it.toLong()) }.forEach { d ->
                            DayTile(if (d == today) "Auj." else dayLabel(d, today), shortDate(d).substringAfter(' '), d == start) {
                                startDay = d.toEpochDay()
                            }
                        }
                    }
                }
                Question("2", "Combien de jours ?") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            DAY_SHORTCUTS.forEach { n -> Chip("$n", days == n, Modifier.weight(1f)) { days = n } }
                        }
                        Spacer(Modifier.width(12.dp))
                        Stepper(days, 1, MAX_DAYS, "Un jour de moins", "Un jour de plus") { days = it }
                    }
                }
                Question("3", "Quels repas ?") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip("Midi", kind == MealKind.LUNCH, Modifier.weight(1f)) { kind = MealKind.LUNCH }
                        Chip("Soir", kind == MealKind.DINNER, Modifier.weight(1f)) { kind = MealKind.DINNER }
                        Chip("Midi & soir", kind == MealKind.BOTH, Modifier.weight(1.3f)) { kind = MealKind.BOTH }
                    }
                }
                Question("4", "Pour combien de personnes ?") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Les quantités des recettes sont ajustées.", fontSize = 13.sp, color = Sunny.InkSoft, modifier = Modifier.weight(1f))
                        Stepper(people, 1, MAX_PEOPLE, "Une personne de moins", "Une personne de plus", onPeople)
                    }
                }
            }
        }
        // Summary and button fixed right above the navigation pill.
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .shadow(12.dp, RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp), ambientColor = Sunny.PeachDeep, spotColor = Sunny.PeachDeep)
                .background(Sunny.Card, RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp))
                .navigationBarsPadding().padding(start = 22.dp, end = 22.dp, top = 18.dp, bottom = 96.dp),
        ) {
            Text(periodSummary(start, end), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Sunny.Ink)
            Text("${if (meals > 1) "$meals repas" else "1 repas"} · ${if (people > 1) "$people personnes" else "1 personne"}",
                fontSize = 14.sp, color = Sunny.InkTitle, modifier = Modifier.padding(top = 2.dp))
            impactText(replaced, kept)?.let {
                Row(Modifier.padding(top = 10.dp).fillMaxWidth().background(Sunny.ButterLight, Shapes.Control).padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Info, null, tint = Sunny.PeachDeep, modifier = Modifier.size(18.dp))
                    Text(it, fontSize = 12.sp, color = Sunny.Ink, lineHeight = 16.sp, modifier = Modifier.padding(start = 8.dp))
                }
            }
            ErrorText(error)
            PrimaryButton(if (busy) "Génération…" else "C'est parti", busy) { onGenerate(start, meals, kind.plan) }
        }
    }
}

/** "Du 3 au 9 novembre", or the single day. */
private fun periodSummary(start: LocalDate, end: LocalDate): String =
    if (start == end) "Le ${periodLabel(start, end)}" else "Du ${periodLabel(start, end).replace(" → ", " au ")}"

/** What generating does to the meals already planned in the period, null when there are none. */
private fun impactText(replaced: Int, kept: Int): String? {
    val parts = listOfNotNull(
        when (replaced) { 0 -> null; 1 -> "1 repas déjà prévu sera remplacé."; else -> "$replaced repas déjà prévus seront remplacés." },
        when (kept) { 0 -> null; 1 -> "1 repas choisi ou libre est conservé."; else -> "$kept repas choisis ou libres sont conservés." },
    )
    return parts.joinToString(" ").ifEmpty { null }
}

@Composable
private fun Question(n: String, title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(Sunny.Card, Shapes.Card).padding(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
            Box(Modifier.size(24.dp).background(Sunny.Peach, CircleShape), contentAlignment = Alignment.Center) {
                Text(n, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Sunny.Ink, modifier = Modifier.padding(start = 10.dp))
        }
        content()
    }
}

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
