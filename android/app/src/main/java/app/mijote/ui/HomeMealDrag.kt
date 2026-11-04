package app.mijote.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** Home uses the same platform long-press recognizer as the generation review. */
@Stable
internal class HomeMealDrag {
    val bounds = mutableMapOf<String, Rect>()
    var viewport by mutableStateOf(Rect.Zero)
    var source by mutableStateOf<MealUi?>(null)
    var target by mutableStateOf<MealUi?>(null)
    var pointerY by mutableFloatStateOf(0f)
    var grabOffset by mutableFloatStateOf(0f)

    fun updateTarget(meals: List<MealUi>) {
        if (pointerY !in viewport.top..viewport.bottom) { target = null; return }
        target = meals.firstOrNull { it.id != source?.id && bounds[it.id]?.let { r -> pointerY in r.top..r.bottom } == true }
    }

    fun cancel() { source = null; target = null }
}

@Composable
internal fun rememberHomeMealDrag(scroll: ScrollState, meals: List<MealUi>, enabled: Boolean): HomeMealDrag {
    val drag = remember { HomeMealDrag() }
    val currentMeals by rememberUpdatedState(meals)
    val edge = with(androidx.compose.ui.platform.LocalDensity.current) { 64.dp.toPx() }
    LaunchedEffect(enabled, meals) { drag.cancel() }
    LaunchedEffect(drag.source) {
        while (drag.source != null) {
            val speed = when {
                drag.pointerY < drag.viewport.top + edge -> -16f
                drag.pointerY > drag.viewport.bottom - edge -> 16f
                else -> 0f
            }
            if (speed != 0f) { scroll.scrollBy(speed); drag.updateTarget(currentMeals) }
            delay(16)
        }
    }
    return drag
}

@Composable
internal fun HomeMealDragItem(drag: HomeMealDrag?, meal: MealUi, content: @Composable () -> Unit) {
    DisposableEffect(drag, meal.id) { onDispose { drag?.bounds?.remove(meal.id) } }
    Box(Modifier.fillMaxWidth()
        .onGloballyPositioned { drag?.bounds?.set(meal.id, it.boundsInRoot()) }
        .then(if (drag?.target?.id == meal.id) Modifier.border(2.dp, Sunny.PeachDeep, Shapes.Card) else Modifier)
        .alpha(if (drag?.source?.id == meal.id) 0.3f else 1f)) { content() }
}

@Composable
internal fun HomeMealDragHandle(
    drag: HomeMealDrag, meal: MealUi, meals: List<MealUi>, enabled: Boolean,
    onChoose: (MealUi) -> Unit, onMove: (MealUi, MealUi) -> Unit,
) {
    var handleTop by remember { mutableFloatStateOf(0f) }
    val currentMove by rememberUpdatedState(onMove)
    val currentMeals by rememberUpdatedState(meals)
    IconButton(onClick = { onChoose(meal) }, enabled = enabled,
        modifier = Modifier.width(48.dp).height(32.dp)
            .onGloballyPositioned { handleTop = it.boundsInRoot().top }
            .semantics {
                contentDescription = "Déplacer ${meal.title} : maintenir puis glisser"
                val index = meals.indexOfFirst { it.id == meal.id }
                customActions = if (!enabled) emptyList() else listOfNotNull(
                    if (index > 0) CustomAccessibilityAction("Monter d'un repas") { currentMove(meal, meals[index - 1]); true } else null,
                    if (index < meals.lastIndex) CustomAccessibilityAction("Descendre d'un repas") { currentMove(meal, meals[index + 1]); true } else null)
            }
            .pointerInput(meal.id, enabled) {
                if (enabled) detectDragGesturesAfterLongPress(
                    onDragStart = { position ->
                        drag.source = meal
                        drag.pointerY = handleTop + position.y
                        drag.grabOffset = drag.pointerY - (drag.bounds[meal.id]?.top ?: drag.pointerY)
                    },
                    onDrag = { change, amount -> change.consume(); drag.pointerY += amount.y; drag.updateTarget(currentMeals) },
                    onDragCancel = drag::cancel,
                    onDragEnd = {
                        val destination = drag.target
                        drag.cancel()
                        if (destination != null) currentMove(meal, destination)
                    })
            }) {
        Canvas(Modifier.size(16.dp, 20.dp)) {
            for (x in listOf(0.3f, 0.7f)) for (y in listOf(0.2f, 0.5f, 0.8f))
                drawCircle(if (enabled) Sunny.InkSoft else Sunny.Track, 1.6.dp.toPx(), Offset(size.width * x, size.height * y))
        }
    }
}

/** An overlay outside the scroll container keeps the dragged meal above every day and row. */
@Composable
internal fun HomeMealDragOverlay(drag: HomeMealDrag) {
    drag.source?.let { meal ->
        Column(Modifier.offset { IntOffset(0, (drag.pointerY - drag.grabOffset - drag.viewport.top).roundToInt()) }
            .padding(horizontal = 20.dp).fillMaxWidth().shadow(8.dp, Shapes.Card)
            .background(Sunny.Card, Shapes.Card).border(2.dp, Sunny.PeachDeep, Shapes.Card).padding(16.dp)) {
            Text(meal.slot, color = Sunny.InkTitle, fontSize = 12.sp)
            Text(meal.title, color = Sunny.Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
    }
}

@Composable
internal fun HomeEmptyMealTarget(slot: String) {
    Column(Modifier.fillMaxWidth().heightIn(min = 64.dp).background(Sunny.ButterLight, Shapes.Card)
        .border(1.dp, Sunny.PeachDeep, Shapes.Card).padding(16.dp)) {
        Text(slot, fontSize = 12.sp, color = Sunny.InkTitle)
        Text("Déposer ici", fontSize = 15.sp, color = Sunny.Ink)
    }
}
