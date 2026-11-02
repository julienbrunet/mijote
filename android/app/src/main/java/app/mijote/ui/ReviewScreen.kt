package app.mijote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate

/**
 * Step 2 of the menu generation: the proposed menu, day by day. Each meal can be opened, changed or removed,
 * and a meal can be added to any day (a lunch next to a dinner…). "Valider" closes the screen: the menu is saved.
 */
@Composable
fun ReviewScreen(
    period: String,
    days: List<DayUi>,
    working: String?,
    error: String?,
    onOpenMeal: (MealUi) -> Unit,
    onSwapMeal: (MealUi) -> Unit,
    onRemoveMeal: (MealUi) -> Unit,
    onAddMeal: (LocalDate, String) -> Unit,
    onValidate: () -> Unit,
    onAddManualMeal: (LocalDate, String, String) -> Unit = { _, _, _ -> },
    onRenameMeal: (MealUi, String) -> Unit = { _, _ -> },
) {
    var editing by remember { mutableStateOf<MealUi?>(null) }
    MealActionsDialog(editing, onDismiss = { editing = null }, onOpen = onOpenMeal, onSwap = onSwapMeal, onRemove = onRemoveMeal,
        onRename = onRenameMeal)
    // A meal to add: (day, "lunch" | "dinner"), then a random recipe or a manual meal.
    var adding by remember { mutableStateOf<Pair<LocalDate, String>?>(null) }
    var manualFor by remember { mutableStateOf<Pair<LocalDate, String>?>(null) }
    adding?.let { (day, meal) ->
        AlertDialog(
            onDismissRequest = { adding = null },
            containerColor = Sunny.Card,
            title = { Text(if (meal == "lunch") "Ajouter le midi" else "Ajouter le soir", fontWeight = FontWeight.Bold, color = Sunny.Ink, fontSize = 18.sp) },
            text = {
                Column {
                    AddChoice("Une recette au hasard", "Choisie parmi celles qui conviennent") { adding = null; onAddMeal(day, meal) }
                    AddChoice("Un repas libre", "Commander une pizza, repas chez des amis…") { adding = null; manualFor = day to meal }
                }
            },
            confirmButton = { TextButton(onClick = { adding = null }) { Text("Annuler", color = Sunny.Slate) } },
        )
    }
    manualFor?.let { (day, meal) ->
        MealTitleDialog("", onDismiss = { manualFor = null }) { manualFor = null; onAddManualMeal(day, meal, it) }
    }
    Box(Modifier.fillMaxSize().background(Sunny.Butter)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Box(
                Modifier.fillMaxWidth()
                    .shadow(6.dp, RoundedCornerShape(bottomStart = 40.dp, bottomEnd = 40.dp), ambientColor = Sunny.PeachDeep, spotColor = Sunny.PeachDeep)
                    .background(Sunny.Card, RoundedCornerShape(bottomStart = 40.dp, bottomEnd = 40.dp)),
            ) {
                Column(Modifier.statusBarsPadding().padding(start = 24.dp, end = 24.dp, top = 28.dp, bottom = 26.dp)) {
                    Text("ÉTAPE 2 SUR 2", fontSize = 11.sp, letterSpacing = 1.sp, fontWeight = FontWeight.SemiBold, color = Sunny.PeachDeep)
                    Spacer(Modifier.height(6.dp))
                    ScreenTitle("Votre menu", period, size = 32)
                    Text("Touchez un repas pour voir la recette, ou le crayon pour le changer.", fontSize = 13.sp,
                        color = Sunny.InkSoft, modifier = Modifier.padding(top = 10.dp))
                }
            }
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 130.dp + 16.dp)) {
                ErrorText(error)
                days.forEachIndexed { i, day ->
                    DaySection(day, first = i == 0, working = working, onOpen = onOpenMeal, onEdit = { editing = it }) {
                        day.missing.forEach { meal ->
                            val busy = working == "${day.day}|$meal"
                            AddMealButton(if (meal == "lunch") "Ajouter le midi" else "Ajouter le soir", busy) {
                                day.day?.let { adding = it to meal }
                            }
                        }
                    }
                }
            }
        }
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Sunny.Butter.copy(alpha = 0.96f)).navigationBarsPadding().padding(horizontal = 20.dp, vertical = 14.dp),
        ) {
            Column {
                PrimaryButton("Valider le menu", busy = false, onClick = onValidate)
            }
        }
    }
}

@Composable
private fun AddChoice(title: String, hint: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(Shapes.Control).clickable(onClick = onClick).padding(vertical = 12.dp, horizontal = 4.dp)) {
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Sunny.Ink)
        Text(hint, fontSize = 13.sp, color = Sunny.InkSoft)
    }
}

@Composable
fun AddMealButton(text: String, busy: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(52.dp).clip(Shapes.Card).background(Sunny.ButterLight)
            .clickable(enabled = !busy, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = Sunny.PeachDeep, strokeWidth = 2.dp)
        else {
            Icon(Icons.Filled.Add, contentDescription = null, tint = Sunny.PeachDeep, modifier = Modifier.size(20.dp))
            Text(text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Sunny.PeachDeep, modifier = Modifier.padding(start = 8.dp))
        }
    }
}
