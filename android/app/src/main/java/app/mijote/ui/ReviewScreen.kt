package app.mijote.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate

/**
 * Step 2 of the menu generation: the proposed menu, day by day. Each meal can be opened, changed or removed,
 * and a meal can be added to any day (a lunch next to a dinner…). Only "Valider ce menu" saves the draft.
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
    onMealPeople: (MealUi, Int) -> Unit = { _, _ -> },
    onMoveMeal: (MealUi, MealUi) -> Unit = { _, _ -> },
    onTab: (Tab) -> Unit = {},
) {
    val scroll = rememberScrollState()
    val meals = days.flatMap { it.meals }
    val bounds = remember { mutableMapOf<String, Rect>() }
    var viewport by remember { mutableStateOf(Rect.Zero) }
    val footerHeight = with(LocalDensity.current) { 184.dp.toPx() }
    var dragging by remember { mutableStateOf<MealUi?>(null) }
    var dragY by remember { mutableFloatStateOf(0f) }
    var dragStartY by remember { mutableFloatStateOf(0f) }
    var target by remember { mutableStateOf<MealUi?>(null) }
    var moving by remember { mutableStateOf<MealUi?>(null) }
    val currentMove by rememberUpdatedState(onMoveMeal)
    fun targetAtPointer(): MealUi? = meals.firstOrNull { meal ->
        meal.id != dragging?.id && bounds[meal.id]?.let { dragY >= it.top && dragY <= it.bottom } == true
    }
    LaunchedEffect(dragging) {
        while (dragging != null) {
            val edge = 72f
            val speed = when {
                dragY < viewport.top + edge -> -18f
                dragY > viewport.bottom - edge -> 18f
                else -> 0f
            }
            if (speed != 0f) { scroll.scrollBy(speed); target = targetAtPointer() }
            delay(16)
        }
    }
    moving?.let { source ->
        AlertDialog(onDismissRequest = { moving = null }, containerColor = Sunny.Card,
            title = { Text("Déplacer ce repas", color = Sunny.Ink, fontWeight = FontWeight.Bold) },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    days.forEach { day -> day.meals.filter { it.id != source.id }.forEach { destination ->
                        AddChoice("${day.label} · ${day.date} · ${destination.slot}", destination.title) {
                            moving = null; currentMove(source, destination)
                        }
                    } }
                }
            },
            confirmButton = { TextButton(onClick = { moving = null }) { Text("Annuler") } })
    }
    // Covers of one meal, changed from the discreet "N pers." pill of its card.
    var peopleFor by remember { mutableStateOf<MealUi?>(null) }
    peopleFor?.let { m ->
        PeopleDialog(m.title, m.people ?: 1, onDismiss = { peopleFor = null }) { peopleFor = null; onMealPeople(m, it) }
    }
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
        Column(Modifier.fillMaxSize().onGloballyPositioned {
            val rect = it.boundsInRoot()
            viewport = Rect(rect.left, rect.top, rect.right, rect.bottom - footerHeight)
        }.verticalScroll(scroll)) {
            Box(
                Modifier.fillMaxWidth()
                    .shadow(6.dp, RoundedCornerShape(bottomStart = 40.dp, bottomEnd = 40.dp), ambientColor = Sunny.PeachDeep, spotColor = Sunny.PeachDeep)
                    .background(Sunny.Card, RoundedCornerShape(bottomStart = 40.dp, bottomEnd = 40.dp)),
            ) {
                Column(Modifier.statusBarsPadding().padding(start = 24.dp, end = 24.dp, top = 28.dp, bottom = 26.dp)) {
                    Text("ÉTAPE 2 SUR 2", fontSize = 11.sp, letterSpacing = 1.sp, fontWeight = FontWeight.SemiBold, color = Sunny.PeachDeep)
                    Spacer(Modifier.height(6.dp))
                    ScreenTitle("Proposition de menu", period, size = 30)
                    Text("Brouillon · pas encore enregistré.", fontSize = 13.sp,
                        color = Sunny.InkTitle, modifier = Modifier.padding(top = 10.dp))

                }
            }
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 220.dp)) {
                ErrorText(error)
                days.forEachIndexed { dayIndex, day ->
                    Row(Modifier.fillMaxWidth().padding(top = if (dayIndex == 0) 18.dp else 34.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(day.label, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Sunny.Ink, modifier = Modifier.weight(1f))
                        Text(day.date.uppercase(), fontSize = 12.sp, color = Sunny.InkTitle)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        day.meals.forEach { meal ->
                            key(meal.id) {
                                var handleTop by remember { mutableFloatStateOf(0f) }
                                Box(Modifier.fillMaxWidth().onGloballyPositioned { bounds[meal.id] = it.boundsInRoot() }
                                    .zIndex(if (dragging?.id == meal.id) 1f else 0f)) {
                                    Row(Modifier.fillMaxWidth().graphicsLayer {
                                        if (dragging?.id == meal.id) { translationY = dragY - dragStartY; alpha = 0.8f }
                                    }.then(if (target?.id == meal.id) Modifier.border(2.dp, Sunny.PeachDeep, Shapes.Card) else Modifier),
                                        verticalAlignment = Alignment.CenterVertically) {
                                        IconButton(onClick = { moving = meal }, enabled = working == null,
                                            modifier = Modifier.width(40.dp).height(56.dp)
                                                .onGloballyPositioned { handleTop = it.boundsInRoot().top }
                                                .semantics {
                                                    contentDescription = "Déplacer ${meal.title}"
                                                    val index = meals.indexOfFirst { it.id == meal.id }
                                                    customActions = if (working != null) emptyList() else listOfNotNull(
                                                        if (index > 0) CustomAccessibilityAction("Monter d'un repas") { currentMove(meal, meals[index - 1]); true } else null,
                                                        if (index < meals.lastIndex) CustomAccessibilityAction("Descendre d'un repas") { currentMove(meal, meals[index + 1]); true } else null)
                                                }
                                                .pointerInput(meal.id, working) {
                                                    if (working == null) detectDragGesturesAfterLongPress(
                                                        onDragStart = { position -> dragging = meal; dragY = handleTop + position.y; dragStartY = dragY },
                                                        onDrag = { change, amount -> change.consume(); dragY += amount.y; target = targetAtPointer() },
                                                        onDragCancel = { dragging = null; target = null },
                                                        onDragEnd = {
                                                            val destination = target
                                                            dragging = null; target = null
                                                            if (destination != null) currentMove(meal, destination)
                                                        })
                                                }) {
                                            androidx.compose.foundation.Canvas(Modifier.size(18.dp, 24.dp)) {
                                                for (x in listOf(0.3f, 0.7f)) for (y in listOf(0.2f, 0.5f, 0.8f)) {
                                                    drawCircle(Sunny.PeachDeep, radius = 2.dp.toPx(), center = androidx.compose.ui.geometry.Offset(size.width * x, size.height * y))
                                                }
                                            }
                                        }
                                        Box(Modifier.weight(1f)) {
                                            MealCard(meal, Sunny.Peach, busy = working == meal.id,
                                                onOpen = { onOpenMeal(meal) }, onEdit = { editing = meal }, onPeople = { peopleFor = meal })
                                        }
                                    }
                                }
                            }
                        }
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
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding()
            .padding(start = 20.dp, end = 20.dp, bottom = 96.dp)) {
            Button(onClick = onValidate, enabled = working == null && dragging == null,
                shape = Shapes.Control,
                colors = ButtonDefaults.buttonColors(containerColor = Sunny.PeachDeep, contentColor = Color.White),
                modifier = Modifier.fillMaxWidth().height(54.dp)) {
                Text(if (working == "confirm") "Enregistrement…" else "Valider ce menu", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }
        BottomBar(Tab.GENERATE, modifier = Modifier.align(Alignment.BottomEnd), onSelect = onTab)
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

/** Number of people of one meal (1–20), shared by the family. */
@Composable
fun PeopleDialog(title: String, initial: Int, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    var n by remember { mutableIntStateOf(initial.coerceIn(1, 20)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Sunny.Card,
        title = {
            Column {
                Text("NOMBRE DE PERSONNES", fontSize = 11.sp, letterSpacing = 1.sp, fontWeight = FontWeight.SemiBold, color = Sunny.PeachDeep)
                Text(title, fontWeight = FontWeight.Bold, color = Sunny.Ink, fontSize = 18.sp, maxLines = 2)
            }
        },
        text = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                StepBox("−", n > 1) { n-- }
                Text("$n", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Sunny.Ink, modifier = Modifier.width(64.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                StepBox("+", n < 20) { n++ }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(n) }) { Text("Enregistrer", color = Sunny.PeachDeep, fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler", color = Sunny.Slate) } },
    )
}

@Composable
private fun StepBox(sign: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).clip(Shapes.Control).background(Sunny.ButterLight).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(sign, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = if (enabled) Sunny.Ink else Sunny.InkSoft) }
}
