package app.mijote.ui

import app.mijote.R
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import app.mijote.data.defaultMealCount
import app.mijote.data.nextMonday
import app.mijote.data.periodEnd
import java.time.LocalDate

private const val MAX_MEALS = 42

/** Start of the generated period. */
enum class StartChoice(val label: String) { TODAY("Aujourd'hui"), TOMORROW("Demain"), MONDAY("Lundi\nprochain") }

private fun StartChoice.date(today: LocalDate) = when (this) {
    StartChoice.TODAY -> today
    StartChoice.TOMORROW -> today.plusDays(1)
    StartChoice.MONDAY -> nextMonday(today)
}

/**
 * Menu generation, shaped like the reference "Add basket" card: a big Butter sheet with the title,
 * a white card with the meal count stepper on its right, a Slate band of meal plan choices
 * under it, then the start day choices and the generate button.
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
    val defaults = info?.defaultMeals.orEmpty()
    var start by rememberSaveable { mutableStateOf(StartChoice.TOMORROW) }
    var plan by rememberSaveable { mutableStateOf(MealPlan.DEFAULT) }
    val startDate = start.date(today)
    var count by rememberSaveable(info) {
        mutableIntStateOf(defaultMealCount(defaults, startDate, info?.horizonDays ?: 7).coerceIn(1, MAX_MEALS))
    }
    val end = periodEnd(plan, defaults, startDate, count)

    Column(
        Modifier.fillMaxSize().background(Sunny.Butter).verticalScroll(rememberScrollState())
            .statusBarsPadding().padding(bottom = 120.dp),
    ) {
        Box(Modifier.fillMaxWidth()) {
            CornerBlock(Sunny.Card, size = 88.dp, modifier = Modifier.align(Alignment.TopEnd)) {
                Image(painterResource(R.drawable.ic_mijote_cocotte), contentDescription = null, modifier = Modifier.size(60.dp))
            }
            Column(Modifier.padding(start = 28.dp, top = 40.dp, end = 100.dp)) {
                Text("🧺", fontSize = 34.sp)
                Spacer(Modifier.height(18.dp))
                Text("Nouveau\nmenu", fontSize = 42.sp, lineHeight = 46.sp, fontWeight = FontWeight.Bold, color = Sunny.InkTitle)
            }
        }
        Spacer(Modifier.height(30.dp))

        // The card: count stepper on the left, summary on the white face, plan choices underneath.
        Column(
            Modifier.padding(horizontal = 18.dp).fillMaxWidth()
                .shadow(14.dp, Shapes.Sheet, ambientColor = Sunny.PeachDeep, spotColor = Sunny.PeachDeep)
                .background(Sunny.Slate, Shapes.Sheet),
        ) {
            Box(Modifier.fillMaxWidth().height(232.dp).background(Color(0xFFF4F4F5), Shapes.Sheet)) {
                // Stepper on the right edge, within reach of the right thumb.
                Column(
                    Modifier.align(Alignment.CenterEnd).width(76.dp).fillMaxHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceEvenly,
                ) {
                    StepButton("+", "Un repas de plus", enabled = count < MAX_MEALS) { count++ }
                    Box(
                        Modifier.size(52.dp).background(Sunny.Card, RoundedCornerShape(14.dp)),
                        contentAlignment = Alignment.Center,
                    ) { Text("$count", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Sunny.Ink) }
                    StepButton("−", "Un repas de moins", enabled = count > 1) { count-- }
                }
                Column(
                    Modifier.padding(end = 76.dp).fillMaxSize()
                        .background(Sunny.Card, Shapes.Sheet).padding(horizontal = 24.dp, vertical = 22.dp),
                ) {
                    Text("🥗", fontSize = 40.sp)
                    Spacer(Modifier.height(10.dp))
                    Text(if (count > 1) "$count repas" else "1 repas", fontSize = 24.sp, fontWeight = FontWeight.Bold,
                        color = Sunny.InkTitle)
                    Text("${periodLabel(startDate, end)} · ${if (people > 1) "$people personnes" else "1 personne"}", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Sunny.Ink,
                        modifier = Modifier.padding(top = 4.dp))
                    Text(planDescription(plan), fontSize = 13.sp, color = Sunny.InkSoft, lineHeight = 18.sp,
                        modifier = Modifier.padding(top = 10.dp))
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlanChip("Mes", "réglages", plan == MealPlan.DEFAULT) { plan = MealPlan.DEFAULT }
                PlanChip("Midi", "& soir", plan == MealPlan.LUNCH_DINNER) { plan = MealPlan.LUNCH_DINNER }
                PlanChip("Soir", "seulement", plan == MealPlan.DINNER) { plan = MealPlan.DINNER }
            }
        }

        Text("À PARTIR DE", fontSize = 11.sp, letterSpacing = 1.sp, fontWeight = FontWeight.SemiBold, color = Sunny.InkTitle,
            modifier = Modifier.padding(start = 30.dp, top = 28.dp, bottom = 10.dp))
        Row(Modifier.padding(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StartChoice.entries.forEach { c ->
                val on = c == start
                Column(
                    Modifier.weight(1f).height(72.dp).clip(Shapes.Control)
                        .background(if (on) Sunny.Card else Sunny.ButterLight).clickable { start = c }
                        .padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(c.label, fontSize = 13.sp, lineHeight = 15.sp, textAlign = TextAlign.Center,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Medium, color = if (on) Sunny.PeachDeep else Sunny.Ink)
                    Text(shortDate(c.date(today)), fontSize = 11.sp, color = Sunny.InkSoft)
                }
            }
        }

        Column(Modifier.padding(horizontal = 18.dp).padding(top = 24.dp)) {
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
            PrimaryButton(if (busy) "Génération…" else "Générer le menu", busy) {
                onGenerate(startDate, count, plan)
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
        Modifier.height(64.dp).clip(Shapes.Control)
            .background(if (selected) Sunny.Butter else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, color = if (selected) Sunny.InkTitle else Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(subtitle, color = if (selected) Sunny.InkTitle else Color.White.copy(alpha = 0.85f), fontSize = 11.sp)
        }
        if (selected) Icon(Icons.Filled.Check, contentDescription = null, tint = Sunny.InkTitle,
            modifier = Modifier.padding(start = 6.dp).size(16.dp))
    }
}

private fun planDescription(plan: MealPlan) = when (plan) {
    MealPlan.DEFAULT -> "Repas prévus selon vos réglages. Recettes de saison et pas servies récemment en priorité."
    MealPlan.LUNCH_DINNER -> "Midi et soir chaque jour. Recettes de saison et pas servies récemment en priorité."
    MealPlan.DINNER -> "Le soir uniquement. Recettes de saison et pas servies récemment en priorité."
}
