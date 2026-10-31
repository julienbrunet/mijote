package app.mijote.ui

import app.mijote.R
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
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
import java.time.LocalDate

private const val MAX_DAYS = 21
private const val START_CHOICES = 14

/** Which meals of each day to plan. */
enum class MealKind(val plan: MealPlan, val perDay: Int) {
    LUNCH(MealPlan.LUNCH, 1), DINNER(MealPlan.DINNER, 1), BOTH(MealPlan.LUNCH_DINNER, 2)
}

/**
 * Step 1 of the menu generation, shaped like the reference "Add basket" card: a big Butter sheet with the title,
 * a white card with the day count stepper on its right, a Slate band to choose lunch, dinner or both,
 * then the start day choices and the button leading to the review screen.
 */
@Composable
fun GenerateScreen(
    info: GenerationInfo?,
    today: LocalDate,
    people: Int,
    busy: Boolean,
    error: String?,
    onGenerate: (start: LocalDate, meals: Int, plan: MealPlan) -> Unit,
) {
    var startDay by rememberSaveable { mutableLongStateOf(today.plusDays(1).toEpochDay()) }
    var kind by rememberSaveable { mutableStateOf(MealKind.BOTH) }
    var days by rememberSaveable(info?.horizonDays) { mutableIntStateOf((info?.horizonDays ?: 7).coerceIn(1, MAX_DAYS)) }
    val start = LocalDate.ofEpochDay(startDay)
    val end = start.plusDays(days - 1L)
    val meals = days * kind.perDay

    Column(
        Modifier.fillMaxSize().background(Sunny.Butter).verticalScroll(rememberScrollState())
            .statusBarsPadding().padding(bottom = 120.dp),
    ) {
        Box(Modifier.fillMaxWidth()) {
            CornerBlock(Sunny.Card, size = 80.dp, modifier = Modifier.align(Alignment.TopEnd)) {
                Image(painterResource(R.drawable.ic_mijote_cocotte), contentDescription = null, modifier = Modifier.size(52.dp))
            }
            Text("Nouveau menu", fontSize = 34.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold, color = Sunny.InkTitle,
                modifier = Modifier.padding(start = 28.dp, top = 22.dp, end = 100.dp))
        }
        Spacer(Modifier.height(22.dp))

        // The card: day stepper on the right, summary on the white face, meal choices underneath.
        Column(
            Modifier.padding(horizontal = 18.dp).fillMaxWidth()
                .shadow(14.dp, Shapes.Sheet, ambientColor = Sunny.PeachDeep, spotColor = Sunny.PeachDeep)
                .background(Sunny.Slate, Shapes.Sheet),
        ) {
            Box(Modifier.fillMaxWidth().height(172.dp).background(Color(0xFFF4F4F5), Shapes.Sheet)) {
                // Stepper on the right edge, within reach of the right thumb.
                Column(
                    Modifier.align(Alignment.CenterEnd).width(76.dp).fillMaxHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceEvenly,
                ) {
                    StepButton("+", "Un jour de plus", enabled = days < MAX_DAYS) { days++ }
                    Box(
                        Modifier.size(46.dp).background(Sunny.Card, RoundedCornerShape(14.dp)),
                        contentAlignment = Alignment.Center,
                    ) { Text("$days", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Sunny.Ink) }
                    StepButton("−", "Un jour de moins", enabled = days > 1) { days-- }
                }
                Column(
                    Modifier.padding(end = 76.dp).fillMaxSize()
                        .background(Sunny.Card, Shapes.Sheet).padding(horizontal = 24.dp, vertical = 20.dp),
                ) {
                    Text(if (days > 1) "$days jours" else "1 jour", fontSize = 24.sp, fontWeight = FontWeight.Bold,
                        color = Sunny.InkTitle)
                    Text(periodLabel(start, end), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Sunny.Ink,
                        modifier = Modifier.padding(top = 4.dp))
                    Text("${if (meals > 1) "$meals repas" else "1 repas"} · ${if (people > 1) "$people personnes" else "1 personne"}",
                        fontSize = 13.sp, color = Sunny.InkSoft, modifier = Modifier.padding(top = 8.dp))
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlanChip("Midi", "", kind == MealKind.LUNCH) { kind = MealKind.LUNCH }
                PlanChip("Soir", "", kind == MealKind.DINNER) { kind = MealKind.DINNER }
                PlanChip("Midi", "& soir", kind == MealKind.BOTH) { kind = MealKind.BOTH }
            }
        }

        Text("PREMIER JOUR", fontSize = 11.sp, letterSpacing = 1.sp, fontWeight = FontWeight.SemiBold, color = Sunny.InkTitle,
            modifier = Modifier.padding(start = 30.dp, top = 20.dp, bottom = 8.dp))
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            (0 until START_CHOICES).map { today.plusDays(it.toLong()) }.forEach { d ->
                val on = d == start
                Column(
                    Modifier.width(78.dp).height(62.dp).clip(Shapes.Control)
                        .background(if (on) Sunny.Card else Sunny.ButterLight).clickable { startDay = d.toEpochDay() }
                        .padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(if (d == today) "Auj." else dayLabel(d, today), fontSize = 13.sp, lineHeight = 15.sp, textAlign = TextAlign.Center, maxLines = 1,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Medium, color = if (on) Sunny.PeachDeep else Sunny.Ink)
                    Text(shortDate(d).substringAfter(' '), fontSize = 11.sp, color = Sunny.InkSoft)
                }
            }
        }

        Column(Modifier.padding(horizontal = 18.dp).padding(top = 6.dp)) {
            if (info != null && !info.enoughRecipes) {
                Text(
                    "Il faut au moins ${info.minRecipes} recettes pour générer un menu : il y en a ${info.recipeCount}. " +
                        "Ajoutez-en ${info.minRecipes - info.recipeCount} de plus.",
                    color = Sunny.Ink, fontSize = 14.sp,
                    modifier = Modifier.fillMaxWidth().background(Sunny.ButterLight, Shapes.Control).padding(14.dp),
                )
                Spacer(Modifier.height(12.dp))
            }
            ErrorText(error)
            PrimaryButton(if (busy) "Génération…" else "Voir le menu", busy) {
                onGenerate(start, meals, kind.plan)
            }
        }
    }
}

@Composable
private fun StepButton(sign: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (sign == "+") Icon(Icons.Filled.Add, contentDescription = description, tint = if (enabled) Sunny.Ink else Sunny.InkSoft)
        else Text(sign, fontSize = 26.sp, color = if (enabled) Sunny.Ink else Sunny.InkSoft)
    }
}

/** Meal plan choice in the Slate band; the selected one is a Butter tile with a check, as in the reference. */
@Composable
private fun PlanChip(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.height(52.dp).clip(Shapes.Control)
            .background(if (selected) Sunny.Butter else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, color = if (selected) Sunny.InkTitle else Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            if (subtitle.isNotEmpty()) Text(subtitle, color = if (selected) Sunny.InkTitle else Color.White.copy(alpha = 0.85f), fontSize = 11.sp)
        }
        if (selected) Icon(Icons.Filled.Check, contentDescription = null, tint = Sunny.InkTitle,
            modifier = Modifier.padding(start = 6.dp).size(16.dp))
    }
}
