package app.mijote.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mijote.R
import app.mijote.data.MenuSlot
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect

private val agendaDate = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.FRENCH)
private val agendaMeals = listOf("lunch", "dinner")
private val agendaCardHeight = 76.dp
private val agendaHomeCaptionHeight = 93.dp
private val agendaShape = RoundedCornerShape(17.dp)
private val agendaTodayShape = RoundedCornerShape(20.dp)

private data class TimelineCell(
    val moment: PlannerMoment,
    val slot: MenuSlot?,
    val touchBounds: Rect,
    val cardBounds: Rect,
)

@Stable
private class TimelineDrag {
    val cells = mutableMapOf<PlannerMoment, TimelineCell>()
    val optionsBounds = mutableMapOf<PlannerMoment, Rect>()
    var viewport = Rect.Zero
    var origin = Offset.Zero
    var position by mutableStateOf(Offset.Zero)
    var source by mutableStateOf<TimelineCell?>(null)
    var target by mutableStateOf<PlannerMoment?>(null)
    var grabOffset = Offset.Zero

    fun begin(cell: TimelineCell, finger: Offset) {
        grabOffset = finger - cell.cardBounds.topLeft
        position = finger
        source = cell
    }

    fun findTarget(allowed: (PlannerMoment) -> Boolean): PlannerMoment? =
        if (!viewport.contains(position)) null else cells.values.firstOrNull { cell ->
            val originDate = source?.moment?.date
            cell.moment != source?.moment && cell.touchBounds.contains(position) && allowed(cell.moment) &&
                (originDate == null || abs(cell.moment.date.toEpochDay() - originDate.toEpochDay()) < 60)
        }?.moment

    fun clear() {
        source = null
        target = null
    }
}

private data class TimelineRange(
    val firstVisible: LocalDate?,
    val firstDate: LocalDate?,
    val lastDate: LocalDate?,
    val nearStart: Boolean,
    val nearEnd: Boolean,
    val beforeBlocked: Boolean,
    val afterBlocked: Boolean,
)

@Composable
internal fun PlannerTimeline(
    modifier: Modifier = Modifier,
    dates: List<LocalDate>,
    today: LocalDate,
    slots: Map<PlannerMoment, MenuSlot>,
    listState: LazyListState,
    selected: PlannerMoment?,
    copyChanges: Set<PlannerMoment>,
    isReadOnly: (LocalDate) -> Boolean,
    isEnabled: (PlannerMoment, MenuSlot?) -> Boolean,
    canDrag: (PlannerMoment, MenuSlot) -> Boolean,
    canDrop: (PlannerMoment) -> Boolean,
    onSelect: (PlannerMoment, MenuSlot?) -> Unit,
    onKeyboardMove: (MenuSlot) -> Unit,
    onDrop: (MenuSlot, PlannerMoment) -> Unit,
    onBoundary: (before: Boolean, retry: Boolean) -> Unit,
    onVisibleDay: (LocalDate) -> Unit,
    loadingBefore: Boolean = false,
    loadingAfter: Boolean = false,
    beforeError: String? = null,
    afterError: String? = null,
    isDateLoaded: (LocalDate) -> Boolean = { true },
    boundaryEnabled: Boolean = true,
    largeCards: Boolean = false,
    hasMealOptions: (PlannerMoment, MenuSlot) -> Boolean = { _, _ -> false },
    onMealOptions: (PlannerMoment, MenuSlot) -> Unit = { _, _ -> },
    bottomPadding: Dp = 18.dp,
    onCopyPast: ((MenuSlot) -> Unit)? = null,
) {
    val gesture = remember { TimelineDrag() }
    val currentCanDrag by rememberUpdatedState(canDrag)
    val currentEnabled by rememberUpdatedState(isEnabled)
    val currentCopyPast by rememberUpdatedState(onCopyPast)
    val currentToday by rememberUpdatedState(today)
    val currentSlots by rememberUpdatedState(slots)
    val currentCanDrop by rememberUpdatedState(canDrop)
    val currentDrop by rememberUpdatedState(onDrop)
    val currentVisible by rememberUpdatedState(onVisibleDay)
    val currentBoundary by rememberUpdatedState(onBoundary)
    val currentDates by rememberUpdatedState(dates)
    val beforeBlocked by rememberUpdatedState(!boundaryEnabled || loadingBefore || beforeError != null)
    val afterBlocked by rememberUpdatedState(!boundaryEnabled || loadingAfter || afterError != null)
    val edge = with(LocalDensity.current) { 86.dp.toPx() }
    val speed = with(LocalDensity.current) { 20.dp.toPx() }
    val haptic = LocalHapticFeedback.current

    LaunchedEffect(listState) {
        var lastVisible: LocalDate? = null
        var requestedBefore: LocalDate? = null
        var requestedAfter: LocalDate? = null
        snapshotFlow {
            val window = currentDates
            val visibleItems = listState.layoutInfo.visibleItemsInfo
            val firstIndex = visibleItems.firstOrNull()?.index
            val lastIndex = visibleItems.lastOrNull()?.index
            TimelineRange(
                (visibleItems.firstOrNull()?.key as? Long)?.let(LocalDate::ofEpochDay),
                window.firstOrNull(), window.lastOrNull(),
                firstIndex != null && firstIndex <= 2,
                lastIndex != null && lastIndex >= window.lastIndex - 2,
                beforeBlocked, afterBlocked,
            )
        }.collect { range ->
            val day = range.firstVisible
            if (day != null && day != lastVisible) {
                lastVisible = day
                currentVisible(day)
            }
            if (range.beforeBlocked) requestedBefore = null
            if (range.afterBlocked) requestedAfter = null
            if (range.nearStart && !range.beforeBlocked && range.firstDate != null && requestedBefore != range.firstDate) {
                requestedBefore = range.firstDate
                currentBoundary(true, false)
            }
            if (range.nearEnd && !range.afterBlocked && range.lastDate != null && requestedAfter != range.lastDate) {
                requestedAfter = range.lastDate
                currentBoundary(false, false)
            }
        }
    }

    LaunchedEffect(gesture.source) {
        while (gesture.source != null) {
            val position = gesture.position
            val frame = gesture.viewport
            if (position.x in frame.left..frame.right) {
                val amount = when {
                    position.y <= frame.top + edge ->
                        -speed * ((frame.top + edge - position.y) / edge).coerceIn(0f, 1f)
                    position.y >= frame.bottom - edge ->
                        speed * ((position.y - frame.bottom + edge) / edge).coerceIn(0f, 1f)
                    else -> 0f
                }
                if (amount != 0f) listState.scrollBy(amount)
            }
            gesture.target = gesture.findTarget(currentCanDrop)
            delay(16)
        }
    }

    // Keep the recognizer on the list instead of its lazy cards. The source may scroll offscreen
    // while the gesture is active, but the original slot snapshot must survive until release.
    Box(
        modifier.clipToBounds().onGloballyPositioned { coords ->
            gesture.origin = coords.localToRoot(Offset.Zero)
            gesture.viewport = coords.boundsInRoot()
        }.pointerInput(listState) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val rootPoint = gesture.origin + down.position
                if (!gesture.viewport.contains(rootPoint)) return@awaitEachGesture
                val source = gesture.cells.values.firstOrNull { cell ->
                    val slot = currentSlots[cell.moment]
                    slot != null && cell.touchBounds.contains(rootPoint) &&
                        gesture.optionsBounds[cell.moment]?.contains(rootPoint) != true &&
                        (currentCanDrag(cell.moment, slot) ||
                            currentCopyPast != null && slot.isCopyablePast(currentToday) && currentEnabled(cell.moment, slot))
                } ?: return@awaitEachGesture
                val held = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                val slot = currentSlots[source.moment] ?: return@awaitEachGesture

                val heldRootPoint = gesture.origin + held.position
                val copyPast = currentCopyPast?.takeIf {
                    slot.isCopyablePast(currentToday) && currentEnabled(source.moment, slot)
                }
                if ((copyPast == null && !currentCanDrag(source.moment, slot)) ||
                    gesture.optionsBounds[source.moment]?.contains(heldRootPoint) == true
                ) return@awaitEachGesture
                held.consume()
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                if (copyPast != null) {
                    copyPast(slot)
                    // Consume the remainder of the hold so lifting does not open the original meal.
                    var pressed = held
                    while (pressed.pressed) {
                        pressed = awaitPointerEvent(PointerEventPass.Initial)
                            .changes.firstOrNull { it.id == held.id } ?: break
                        pressed.consume()
                    }
                    return@awaitEachGesture
                }
                gesture.begin(source.copy(slot = slot), heldRootPoint)
                try {
                    val completed = drag(held.id) { change ->
                        change.consume()
                        gesture.position = gesture.origin + change.position
                        gesture.target = gesture.findTarget(currentCanDrop)
                    }
                    if (completed) currentEvent.changes.filter { it.changedToUp() }.forEach { it.consume() }
                    val target = if (completed) gesture.findTarget(currentCanDrop) else null
                    if (target != null && currentCanDrag(source.moment, slot)) currentDrop(slot, target)
                } finally {
                    gesture.clear()
                }
            }
        },
    ) {
        val listInset = if (largeCards) 0.dp else 12.dp
        val dayInset = if (largeCards) 20.dp else 8.dp
        val boundaryInset = if (largeCards) 12.dp else 0.dp
        LazyColumn(
            state = listState,
            userScrollEnabled = gesture.source == null,
            contentPadding = PaddingValues(start = listInset, top = 8.dp, end = listInset, bottom = bottomPadding),
            verticalArrangement = Arrangement.spacedBy(if (largeCards) 20.dp else 16.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(dates.size, key = { dates[it].toEpochDay() }) { index ->
                val date = dates[index]
                val loaded = isDateLoaded(date)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (index == 0 && (loadingBefore || beforeError != null)) {
                        TimelineBoundary(loadingBefore, beforeError, boundaryEnabled, Modifier.padding(horizontal = boundaryInset)) {
                            onBoundary(true, true)
                        }
                    }
                    val isToday = date == today
                    val dayBackground = when {
                        !isToday -> Modifier
                        largeCards -> Modifier.background(Sunny.Cream)
                        else -> Modifier.background(Sunny.Card, agendaTodayShape)
                            .border(1.dp, Sunny.Track, agendaTodayShape)
                    }
                    Column(
                        Modifier.fillMaxWidth().then(dayBackground)
                            .padding(horizontal = dayInset, vertical = if (isToday) 8.dp else 0.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TimelineDayHeader(date, today)
                        val mealCard: @Composable (String, Modifier) -> Unit = { meal, cardModifier ->
                            val moment = PlannerMoment(date, meal)
                            val slot = slots[moment]
                            val readOnly = isReadOnly(date)
                            val enabled = loaded && isEnabled(moment, slot)
                            val moving = gesture.source
                            val placeholder = moving?.moment == moment
                            val accessibleMove = slot?.takeIf { loaded && canDrag(moment, it) }
                            val accessibleCopy = slot?.takeIf { enabled && it.isCopyablePast(today) }
                            val optionsSlot = slot?.takeIf { loaded && hasMealOptions(moment, it) }
                            DisposableEffect(moment) {
                                onDispose { gesture.cells.remove(moment) }
                            }
                            if (optionsSlot != null) {
                                DisposableEffect(moment) {
                                    onDispose { gesture.optionsBounds.remove(moment) }
                                }
                            }
                            TimelineMealCard(
                                moment = moment, slot = slot,
                                active = selected == moment, readOnly = readOnly, loaded = loaded,
                                copied = moment in copyChanges, hover = gesture.target == moment,
                                enabled = enabled && moving == null, large = largeCards,
                                onClick = { onSelect(moment, slot) },
                                onKeyboardMove = accessibleMove?.let { { onKeyboardMove(it) } },
                                onKeyboardCopy = accessibleCopy?.let { mealToCopy ->
                                    onCopyPast?.let { copy -> { copy(mealToCopy) } }
                                },
                                onOptions = optionsSlot?.let { { onMealOptions(moment, it) } },
                                onOptionsBounds = { gesture.optionsBounds[moment] = it },
                                modifier = cardModifier.onGloballyPositioned { coords ->
                                    if (loaded) {
                                        val origin = coords.localToRoot(Offset.Zero)
                                        val original = Rect(
                                            origin.x, origin.y, origin.x + coords.size.width, origin.y + coords.size.height,
                                        )
                                        gesture.cells[moment] = TimelineCell(moment, slot, coords.boundsInRoot(), original)
                                    } else gesture.cells.remove(moment)
                                }.alpha(if (placeholder) 0.35f else 1f),
                            )
                        }
                        if (largeCards) {
                            Row(
                                Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                agendaMeals.forEach { meal -> mealCard(meal, Modifier.weight(1f).fillMaxHeight()) }
                            }
                        } else {
                            agendaMeals.forEach { meal -> mealCard(meal, Modifier.fillMaxWidth()) }
                        }
                    }
                    if (index == dates.lastIndex && (loadingAfter || afterError != null)) {
                        TimelineBoundary(loadingAfter, afterError, boundaryEnabled, Modifier.padding(horizontal = boundaryInset)) {
                            onBoundary(false, true)
                        }
                    }
                }
            }
        }
        gesture.source?.let { source ->
            val density = LocalDensity.current
            val width = with(density) { source.cardBounds.width.toDp() }
            TimelineMealCard(
                moment = source.moment, slot = source.slot, active = false, readOnly = false,
                loaded = true, copied = false, hover = false, enabled = false, onClick = {},
                large = largeCards,
                modifier = Modifier.offset {
                    val maxX = (gesture.viewport.right - gesture.origin.x - source.cardBounds.width).coerceAtLeast(0f)
                    val x = (gesture.position.x - gesture.origin.x - gesture.grabOffset.x).coerceIn(0f, maxX)
                    val y = gesture.position.y - gesture.origin.y - gesture.grabOffset.y - 8.dp.toPx()
                    IntOffset(x.roundToInt(), y.roundToInt())
                }.width(width).scale(1.035f).shadow(14.dp, agendaShape)
                    .clearAndSetSemantics { },
            )
        }
    }
}

@Composable
private fun TimelineDayHeader(date: LocalDate, today: LocalDate) {
    val formatted = agendaDate.format(date).replaceFirstChar { it.uppercase() }
    val title = if (date.year == today.year) formatted else "$formatted ${date.year}"
    val isToday = date == today
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title, Modifier.weight(1f).semantics { heading() },
            color = Sunny.Ink, fontSize = if (isToday) 18.sp else 17.sp,
            lineHeight = if (isToday) 22.sp else 21.sp, fontWeight = FontWeight.Bold,
        )
        if (isToday) Text(
            "Aujourd’hui", Modifier.background(Sunny.Butter, CircleShape).padding(horizontal = 9.dp, vertical = 4.dp),
            color = Sunny.Ink, fontSize = 12.sp, lineHeight = 15.sp, fontWeight = FontWeight.Bold,
        )
    }
}

private fun MenuSlot.isCopyablePast(today: LocalDate): Boolean = date < today && (recipe != null || manual)

private fun agendaTitle(slot: MenuSlot?, readOnly: Boolean, loaded: Boolean) = when {
    !loaded -> "Chargement du créneau…"
    slot == null -> if (readOnly) "Aucun repas" else "Créneau libre"
    else -> slot.title ?: slot.recipe?.title ?: "Aucune recette disponible"
}

@Composable
private fun TimelineMealCard(
    moment: PlannerMoment,
    slot: MenuSlot?,
    active: Boolean,
    readOnly: Boolean,
    loaded: Boolean,
    copied: Boolean,
    hover: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
    large: Boolean,
    onKeyboardMove: (() -> Unit)? = null,
    onKeyboardCopy: (() -> Unit)? = null,
    onOptions: (() -> Unit)? = null,
    onOptionsBounds: (Rect) -> Unit = {},
) {
    val title = agendaTitle(slot, readOnly, loaded)
    val label = if (moment.meal == "lunch") "Midi" else "Soir"
    val info = when {
        slot?.manual == true -> "Repas libre"
        slot?.recipe?.minutes != null -> "${slot.recipe.minutes} min"
        else -> null
    }
    val hasArt = slot != null && (slot.recipe != null || slot.manual)
    val homeEmpty = large && slot == null
    val canAdd = homeEmpty && loaded && enabled
    val actionInset = if (onOptions == null) 12.dp else 58.dp
    val border = when {
        active || hover -> Sunny.Ink
        copied -> DishColors.veggie.second
        else -> Sunny.Track
    }
    val background = when {
        active || hover -> Sunny.ButterLight
        copied -> DishColors.veggie.first
        slot == null -> Sunny.Cream
        else -> Sunny.Card
    }
    val primaryAction = if (homeEmpty) Modifier else Modifier.clickable(
        enabled = enabled, role = Role.Button, onClick = onClick,
    )
    val action = Modifier.fillMaxWidth().then(primaryAction).semantics {
            contentDescription = listOfNotNull(mealMoment(moment.date, moment.meal), title, info).joinToString(" · ")
            selected = active
            if (hover) stateDescription = "Destination"
            else if (copied) stateDescription = "À copier"
            else if (slot?.locked == true) stateDescription = "Conservé lors de la génération"
            if (enabled && (onKeyboardMove != null || onKeyboardCopy != null)) {
                customActions = listOfNotNull(
                    onKeyboardMove?.let { CustomAccessibilityAction("Déplacer le repas") { it(); true } },
                    onKeyboardCopy?.let { CustomAccessibilityAction("Copier ce repas") { it(); true } },
                )
            }
        }
    val captionLabel: @Composable (Modifier) -> Unit = { modifier ->
        Text(
            listOfNotNull(label, info).joinToString(" · "),
            modifier, color = Sunny.InkTitle, fontSize = 12.sp, lineHeight = 15.sp,
            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
    val captionTitle: @Composable () -> Unit = {
        Text(
            if (hover && slot == null) "Déposer ici" else title,
            color = if (slot == null && !hover) Sunny.InkTitle else Sunny.Ink,
            fontSize = if (large) 16.sp else 15.sp,
            lineHeight = 19.sp,
            fontWeight = if (large) FontWeight.Bold else FontWeight.SemiBold,
            maxLines = if (large) 3 else 2, overflow = TextOverflow.Ellipsis,
        )
    }
    val indicators: @Composable () -> Unit = {
        when {
            active || hover || copied -> Icon(Icons.Filled.Check, null, Modifier.size(16.dp), tint = Sunny.Ink)
            slot?.locked == true -> Icon(Icons.Filled.Lock, null, Modifier.size(14.dp), tint = Sunny.InkTitle)
            !large && slot == null && loaded && enabled ->
                Icon(Icons.Filled.Add, null, Modifier.size(20.dp), tint = Sunny.Ink)
        }
    }

    Box(modifier.clip(agendaShape).background(background)
        .border(if (active || hover || copied) 2.dp else 1.dp, border, agendaShape)) {
        Column(action) {
            if (large) {
                val photo = Modifier.fillMaxWidth().aspectRatio(1.8f)
                if (hasArt && slot != null) {
                    TimelineMealArt(slot, photo, 34.sp)
                } else {
                    Box(photo.background(Sunny.ButterLight.copy(alpha = 0.62f)), contentAlignment = Alignment.Center) {
                        if (canAdd) {
                            IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
                                Box(Modifier.size(33.dp).background(Sunny.Card, CircleShape), contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Filled.Add, "Planifier · ${mealMoment(moment.date, moment.meal)}",
                                        tint = Sunny.Ink, modifier = Modifier.size(21.dp),
                                    )
                                }
                            }
                        }
                    }
                }
                Column(
                    Modifier.fillMaxWidth().heightIn(min = agendaHomeCaptionHeight)
                        .padding(horizontal = 10.dp, vertical = 9.dp).clearAndSetSemantics { },
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        captionLabel(Modifier.weight(1f))
                        indicators()
                    }
                    captionTitle()
                }
            } else {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = agendaCardHeight)
                        .padding(start = 12.dp, end = actionInset, top = 9.dp, bottom = 9.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (hasArt && slot != null) {
                        TimelineMealArt(slot, Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)), 21.sp)
                    }
                    Column(Modifier.weight(1f).clearAndSetSemantics { }, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        captionLabel(Modifier)
                        captionTitle()
                    }
                    indicators()
                }
            }
        }
        if (onOptions != null) {
            IconButton(
                onClick = onOptions,
                enabled = enabled,
                modifier = Modifier.align(if (large) Alignment.TopEnd else Alignment.CenterEnd)
                    .padding(top = if (large) 3.dp else 0.dp, end = 4.dp)
                    .size(48.dp)
                    .onGloballyPositioned { onOptionsBounds(it.boundsInRoot()) },
            ) {
                Box(
                    Modifier.size(30.dp).background(if (large) Sunny.Card else background, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Edit, "Modifier le repas · $title",
                        tint = Sunny.Ink, modifier = Modifier.size(17.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun TimelineMealArt(slot: MenuSlot, modifier: Modifier, emojiSize: androidx.compose.ui.unit.TextUnit) {
    val artwork = modifier.clearAndSetSemantics { }
    if (slot.manual) {
        Image(
            painterResource(R.drawable.manual_meal), contentDescription = null,
            contentScale = ContentScale.Crop, modifier = artwork,
        )
    } else {
        val recipe = slot.recipe
        val emoji = when (recipe?.dishType) {
            "meat" -> "🍗"
            "fish" -> "🐟"
            "vegetarian" -> "🥗"
            else -> "🍽️"
        }
        RecipeThumb(recipe?.image, emoji, artwork, emojiSize = emojiSize)
    }
}

@Composable
private fun TimelineBoundary(
    loading: Boolean,
    error: String?,
    enabled: Boolean,
    modifier: Modifier,
    onRetry: () -> Unit,
) {
    if (loading) Row(
        modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(Modifier.size(18.dp), color = Sunny.PeachDeep, strokeWidth = 2.dp)
        Text("Chargement des autres dates…", color = Sunny.InkTitle, fontSize = 12.sp)
    } else if (error != null) Column(
        modifier.fillMaxWidth().clip(agendaShape).background(Sunny.ErrorBg).padding(horizontal = 10.dp),
    ) {
        Text(error, color = Sunny.Ink, fontSize = 12.sp, modifier = Modifier.padding(top = 9.dp))
        TextButton(onClick = onRetry, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("Réessayer", color = Sunny.Ink, fontWeight = FontWeight.Bold)
        }
    }
}
