package app.mijote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mijote.data.MenuSlot
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect

private val agendaDate = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.FRENCH)
private val agendaScrubDate = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.FRENCH)
private val agendaMeals = listOf("lunch", "dinner")
private val agendaCardHeight = 76.dp
private val agendaScrubberWidth = 52.dp
private val agendaThumbHeight = 70.dp
private val agendaShape = RoundedCornerShape(17.dp)

private data class TimelineCell(
    val moment: PlannerMoment,
    val slot: MenuSlot?,
    val touchBounds: Rect,
    val cardBounds: Rect,
)

@Stable
private class TimelineDrag {
    val cells = mutableMapOf<PlannerMoment, TimelineCell>()
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

private data class TimelinePreview(val date: LocalDate, val thumbTop: Int)

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
) {
    val gesture = remember { TimelineDrag() }
    var preview by remember { mutableStateOf<TimelinePreview?>(null) }
    val currentCanDrag by rememberUpdatedState(canDrag)
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
                    slot != null && cell.touchBounds.contains(rootPoint) && currentCanDrag(cell.moment, slot)
                } ?: return@awaitEachGesture
                val held = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                val slot = currentSlots[source.moment] ?: return@awaitEachGesture

                if (!currentCanDrag(source.moment, slot)) return@awaitEachGesture
                held.consume()
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                gesture.begin(source.copy(slot = slot), gesture.origin + held.position)
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
        LazyColumn(
            state = listState,
            userScrollEnabled = gesture.source == null,
            contentPadding = PaddingValues(start = 12.dp, top = 7.dp, end = agendaScrubberWidth + 3.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(dates.size, key = { dates[it].toEpochDay() }) { index ->
                val date = dates[index]
                val loaded = isDateLoaded(date)
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    if (index == 0 && (loadingBefore || beforeError != null)) {
                        TimelineBoundary(loadingBefore, beforeError, boundaryEnabled) { onBoundary(true, true) }
                    }
                    TimelineDayHeader(date, today)
                    agendaMeals.forEach { meal ->
                        val moment = PlannerMoment(date, meal)
                        val slot = slots[moment]
                        val readOnly = isReadOnly(date)
                        val enabled = loaded && isEnabled(moment, slot)
                        val moving = gesture.source
                        val placeholder = moving?.moment == moment
                        val accessibleMove = slot?.takeIf { loaded && canDrag(moment, it) }
                        DisposableEffect(moment) {
                            onDispose { gesture.cells.remove(moment) }
                        }
                        TimelineMealCard(
                            moment = moment, slot = slot,
                            active = selected == moment, readOnly = readOnly, loaded = loaded,
                            copied = moment in copyChanges, hover = gesture.target == moment,
                            enabled = enabled && moving == null,
                            onClick = { onSelect(moment, slot) },
                            onKeyboardMove = accessibleMove?.let { { onKeyboardMove(it) } },
                            modifier = Modifier.fillMaxWidth().onGloballyPositioned { coords ->
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
                    if (index == dates.lastIndex && (loadingAfter || afterError != null)) {
                        TimelineBoundary(loadingAfter, afterError, boundaryEnabled) { onBoundary(false, true) }
                    }
                }
            }
        }
        TimelineScrubber(
            dates, listState, enabled = gesture.source == null,
            modifier = Modifier.align(Alignment.CenterEnd).width(agendaScrubberWidth).fillMaxHeight(),
            onPreview = { date, top -> preview = date?.let { TimelinePreview(it, top) } },
        )
        preview?.let { item ->
            Text(
                agendaScrubDate.format(item.date),
                Modifier.align(Alignment.TopEnd).padding(end = agendaScrubberWidth)
                    .offset { IntOffset(0, item.thumbTop) }
                    .shadow(4.dp, agendaShape).background(Sunny.Ink, agendaShape)
                    .padding(horizontal = 11.dp, vertical = 8.dp).clearAndSetSemantics { },
                color = Sunny.Card, fontSize = 12.sp, lineHeight = 15.sp, fontWeight = FontWeight.SemiBold,
            )
        }
        gesture.source?.let { source ->
            val density = LocalDensity.current
            val width = with(density) { source.cardBounds.width.toDp() }
            TimelineMealCard(
                moment = source.moment, slot = source.slot, active = false, readOnly = false,
                loaded = true, copied = false, hover = false, enabled = false, onClick = {},
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
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title, Modifier.weight(1f).semantics { heading() },
            color = Sunny.Ink, fontSize = 17.sp, lineHeight = 21.sp, fontWeight = FontWeight.Bold,
        )
        if (date == today) Text(
            "Aujourd’hui", Modifier.background(Sunny.Butter, CircleShape).padding(horizontal = 8.dp, vertical = 4.dp),
            color = Sunny.Ink, fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold,
        )
    }
}

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
    onKeyboardMove: (() -> Unit)? = null,
) {
    val title = agendaTitle(slot, readOnly, loaded)
    val label = if (moment.meal == "lunch") "Midi" else "Soir"
    val info = when {
        slot?.manual == true -> "Repas libre"
        slot?.recipe?.minutes != null -> "${slot.recipe.minutes} min"
        else -> null
    }
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
    Row(
        modifier.heightIn(min = agendaCardHeight).clip(agendaShape).background(background)
            .border(if (active || hover || copied) 2.dp else 1.dp, border, agendaShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = listOfNotNull(mealMoment(moment.date, moment.meal), title, info).joinToString(" · ")
                selected = active
                if (hover) stateDescription = "Destination"
                else if (copied) stateDescription = "À copier"
                else if (slot?.locked == true) stateDescription = "Conservé lors de la génération"
                if (onKeyboardMove != null && enabled) {
                    customActions = listOf(CustomAccessibilityAction("Déplacer le repas") { onKeyboardMove(); true })
                }
            }
            .padding(horizontal = 11.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (slot != null && (slot.recipe != null || slot.manual)) {
            val emoji = when (slot.recipe?.dishType) {
                "meat" -> "🍗"
                "fish" -> "🐟"
                "vegetarian" -> "🥗"
                else -> "📝"
            }
            RecipeThumb(
                slot.recipe?.image, emoji, Modifier.size(42.dp).clip(RoundedCornerShape(12.dp))
                    .clearAndSetSemantics { }, emojiSize = 21.sp,
            )
        }
        Column(Modifier.weight(1f).clearAndSetSemantics { }, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                listOfNotNull(label, info).joinToString(" · "),
                color = Sunny.InkTitle, fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold,
            )
            Text(
                if (hover && slot == null) "Déposer ici" else title,
                color = if (slot == null && !hover) Sunny.InkTitle else Sunny.Ink,
                fontSize = 15.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        when {
            active || hover || copied -> Icon(Icons.Filled.Check, null, Modifier.size(19.dp), tint = Sunny.Ink)
            slot?.locked == true -> Icon(Icons.Filled.Lock, null, Modifier.size(15.dp), tint = Sunny.InkTitle)
            slot == null && loaded && enabled -> Icon(Icons.Filled.Add, null, Modifier.size(20.dp), tint = Sunny.Ink)
        }
    }
}

@Composable
private fun TimelineBoundary(loading: Boolean, error: String?, enabled: Boolean, onRetry: () -> Unit) {
    if (loading) Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(Modifier.size(18.dp), color = Sunny.PeachDeep, strokeWidth = 2.dp)
        Text("Chargement des autres dates…", color = Sunny.InkTitle, fontSize = 12.sp)
    } else if (error != null) Column(
        Modifier.fillMaxWidth().clip(agendaShape).background(Sunny.ErrorBg).padding(horizontal = 10.dp),
    ) {
        Text(error, color = Sunny.Ink, fontSize = 12.sp, modifier = Modifier.padding(top = 9.dp))
        TextButton(onClick = onRetry, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("Réessayer", color = Sunny.Ink, fontWeight = FontWeight.Bold)
        }
    }
}

/** Date-oriented thumb with a large touch region, independent of variable card heights. */
@Composable
private fun TimelineScrubber(
    dates: List<LocalDate>,
    listState: LazyListState,
    enabled: Boolean,
    modifier: Modifier,
    onPreview: (LocalDate?, Int) -> Unit,
) {
    val currentDates by rememberUpdatedState(dates)
    val currentPreview by rememberUpdatedState(onPreview)
    val last = dates.lastIndex
    val ready = enabled && last > 0
    var dragging by remember { mutableStateOf(false) }
    var draggedFraction by remember { mutableFloatStateOf(0f) }
    val listFraction by remember(listState, last) {
        derivedStateOf {
            val visible = listState.layoutInfo.visibleItemsInfo
            val index = listState.firstVisibleItemIndex
            when {
                last <= 0 || visible.isEmpty() -> 0f
                !listState.canScrollForward -> 1f
                else -> {
                    val itemHeight = visible.firstOrNull { it.index == index }?.size ?: 1
                    ((index + listState.firstVisibleItemScrollOffset / itemHeight.coerceAtLeast(1).toFloat()) / last)
                        .coerceIn(0f, 1f)
                }
            }
        }
    }
    val actualFraction by rememberUpdatedState(if (dragging) draggedFraction else listFraction)
    val shownDate = dates.getOrNull(
        (if (dragging) draggedFraction * last else listState.firstVisibleItemIndex.toFloat()).roundToInt(),
    )
    val seek: (Int) -> Boolean = { index ->
        if (ready) {
            listState.requestScrollToItem(index.coerceIn(0, last))
            true
        } else false
    }

    BoxWithConstraints(
        modifier.semantics {
            contentDescription = "Parcourir les dates"
            if (shownDate != null) stateDescription = agendaScrubDate.format(shownDate)
            if (ready) {
                progressBarRangeInfo = ProgressBarRangeInfo((actualFraction * last).coerceIn(0f, last.toFloat()), 0f..last.toFloat(), last - 1)
                setProgress { seek(it.roundToInt()) }
                customActions = listOf(
                    CustomAccessibilityAction("Reculer d’une semaine") { seek(listState.firstVisibleItemIndex - 7) },
                    CustomAccessibilityAction("Avancer d’une semaine") { seek(listState.firstVisibleItemIndex + 7) },
                )
            }
        }.pointerInput(listState, ready) {
            if (!ready) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown()
                down.consume()
                val thumbSize = agendaThumbHeight.toPx().coerceAtMost(size.height.toFloat())
                val travel = (size.height - thumbSize).coerceAtLeast(1f)
                val currentTop = actualFraction * travel
                val onThumb = down.position.y in currentTop..currentTop + thumbSize
                val grip = if (onThumb) down.position.y - currentTop else thumbSize / 2f
                var lastSeek = -1

                fun seekByFinger(y: Float, scroll: Boolean) {
                    val fraction = ((y - grip) / travel).coerceIn(0f, 1f)
                    draggedFraction = fraction
                    val window = currentDates
                    if (window.isEmpty()) return
                    val index = (fraction * window.lastIndex).roundToInt().coerceIn(window.indices)
                    if (scroll && lastSeek != index) {
                        listState.requestScrollToItem(index)
                        lastSeek = index
                    }
                    currentPreview(window[index], (fraction * travel).roundToInt())
                }

                dragging = true
                try {
                    seekByFinger(down.position.y, scroll = !onThumb)
                    drag(down.id) { change ->
                        change.consume()
                        seekByFinger(change.position.y, scroll = true)
                    }
                } finally {
                    dragging = false
                    currentPreview(null, 0)
                }
            }
        },
    ) {
        val thumb = agendaThumbHeight.coerceAtMost(maxHeight)
        val travel = (maxHeight - thumb).coerceAtLeast(0.dp)
        Box(
            Modifier.align(Alignment.CenterEnd).padding(end = 21.dp, top = 10.dp, bottom = 10.dp)
                .width(5.dp).fillMaxHeight().background(Sunny.Track, CircleShape),
        )
        Box(
            Modifier.align(Alignment.TopEnd).padding(end = 7.dp).offset(y = travel * actualFraction)
                .size(width = 34.dp, height = thumb)
                .shadow(if (dragging) 7.dp else 2.dp, RoundedCornerShape(14.dp))
                .background(if (ready) Sunny.Butter else Sunny.Track, RoundedCornerShape(14.dp))
                .border(1.dp, if (dragging) Sunny.Ink else Sunny.Track, RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                repeat(3) { Box(Modifier.width(13.dp).height(2.dp).background(Sunny.Ink, CircleShape)) }
            }
        }
    }
}
