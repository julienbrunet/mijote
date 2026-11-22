package app.mijote.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mijote.MenuState
import app.mijote.PlacementRecipe
import app.mijote.PlannerWeekState
import app.mijote.RecipesState
import app.mijote.WeekCopyState
import app.mijote.data.GenerationInfo
import app.mijote.data.MealPlan
import app.mijote.data.MenuSlot
import app.mijote.data.RecipeSummary
import app.mijote.data.User
import app.mijote.data.periodImpact
import java.time.DayOfWeek
import java.time.temporal.TemporalAdjusters
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay

private val plannerWeekday = DateTimeFormatter.ofPattern("EEE", Locale.FRENCH)
private val plannerMonthDay = DateTimeFormatter.ofPattern("d MMMM", Locale.FRENCH)
private val plannerShape = RoundedCornerShape(12.dp)
private val plannerDayWidth = 39.dp
private val plannerGap = 5.dp
private val plannerCellHeight = 48.dp
private val plannerMeals = listOf("lunch", "dinner")

private fun serviceLabel(meal: String) = if (meal == "lunch") "Midi" else "Soir"

private fun mealTitle(slot: MenuSlot?, past: Boolean = false) = when {
    slot == null -> if (past) "Aucun repas" else "Libre"
    else -> slot.title ?: slot.recipe?.title ?: "Aucune recette disponible"
}

@Composable
private fun PlannerWeekNav(
    start: LocalDate,
    pastWeek: Boolean,
    previous: Boolean,
    next: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onHistoryOrCurrent: (() -> Unit)?,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PlannerWeekArrow(previous, "Semaine précédente", onPrevious, pointsLeft = true)
        Column(
            Modifier.weight(1f).heightIn(min = 48.dp).clip(plannerShape)
                .then(if (onHistoryOrCurrent != null) Modifier.clickable(role = Role.Button, onClick = onHistoryOrCurrent) else Modifier)
                .padding(horizontal = 4.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                periodLabel(start, start.plusDays(6)),
                fontSize = 16.sp, lineHeight = 19.sp, fontWeight = FontWeight.Bold, color = Sunny.Ink,
            )
            if (onHistoryOrCurrent != null) {
                Text(
                    if (pastWeek) "Revenir à cette semaine" else "Semaines passées",
                    fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.SemiBold, color = Sunny.Ink,
                )
            }
        }
        PlannerWeekArrow(next, "Semaine suivante", onNext, pointsLeft = false)
    }
}

@Composable
private fun PlannerWeekArrow(enabled: Boolean, label: String, onClick: () -> Unit, pointsLeft: Boolean) {
    Box(
        Modifier.size(48.dp).clip(plannerShape).background(Sunny.Card)
            .border(1.dp, Sunny.Track, plannerShape).clickable(enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (pointsLeft) Icons.AutoMirrored.Filled.KeyboardArrowLeft else Icons.AutoMirrored.Filled.KeyboardArrowRight,
            label,
            tint = if (enabled) Sunny.Ink else Sunny.InkSoft,
        )
    }
}

@Composable
private fun PlannerWeekGrid(
    week: LocalDate,
    today: LocalDate,
    slots: List<MenuSlot>,
    selectedDate: LocalDate?,
    selectedMeal: String?,
    compact: Boolean,
    draft: Boolean,
    readOnly: (LocalDate) -> Boolean,
    enabled: (LocalDate, String, MenuSlot?) -> Boolean,
    onSelect: (LocalDate, String, MenuSlot?) -> Unit,
    copyChanges: Set<PlannerMoment> = emptySet(),
    drag: PlannerDrag,
    canDrag: (LocalDate, MenuSlot?) -> Boolean,
    canDrop: (LocalDate, MenuSlot?) -> Boolean,
    onStartMove: (MenuSlot) -> Unit,
    onDrop: (MenuSlot, PlannerMoment) -> Unit,
) {
    val byMoment = remember(slots) { slots.associateBy { PlannerMoment(it.date, it.meal) } }
    val eligible = rememberUpdatedState<(PlannerMoment) -> Boolean> { canDrop(it.date, byMoment[it]) }
    val drop = rememberUpdatedState(onDrop)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(plannerGap)) {
            Spacer(Modifier.width(plannerDayWidth))
            plannerMeals.forEach { meal ->
                Text(
                    serviceLabel(meal),
                    Modifier.weight(1f).padding(start = 6.dp).semantics { heading() },
                    fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold, color = Sunny.InkTitle,
                )
            }
        }
        repeat(7) { index ->
            val date = week.plusDays(index.toLong())
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(plannerGap)) {
                Column(
                    Modifier.width(plannerDayWidth).fillMaxHeight().heightIn(min = plannerCellHeight)
                        .clip(RoundedCornerShape(9.dp))
                        .background(if (date == today) Sunny.Butter else Sunny.Cream)
                        .clearAndSetSemantics { },
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        plannerWeekday.format(date).replaceFirstChar { it.uppercase() },
                        fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.SemiBold, color = Sunny.InkTitle,
                    )
                    Text(date.dayOfMonth.toString(), fontSize = 16.sp, lineHeight = 18.sp, fontWeight = FontWeight.Bold, color = Sunny.Ink)
                }
                plannerMeals.forEach { meal ->
                    val moment = PlannerMoment(date, meal)
                    val slot = byMoment[moment]
                    DisposableEffect(drag, moment) {
                        onDispose { drag.bounds.remove(moment) }
                    }
                    val touch = if (slot != null && canDrag(date, slot)) Modifier
                        .semantics {
                            customActions = listOf(CustomAccessibilityAction("Déplacer") { onStartMove(slot); true })
                        }
                        .pointerInput(slot.id, moment) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { point ->
                                    val rect = drag.bounds[moment] ?: return@detectDragGesturesAfterLongPress
                                    drag.source = slot
                                    drag.sourceMoment = moment
                                    drag.position = rect.topLeft + point
                                },
                                onDrag = { change, delta ->
                                    if (drag.sourceMoment == moment) {
                                        change.consume()
                                        drag.position += delta
                                        drag.updateTarget(eligible.value)
                                    }
                                },
                                onDragEnd = {
                                    val target = drag.target
                                    drag.clear()
                                    if (target != null) drop.value(slot, target)
                                },
                                onDragCancel = drag::clear,
                            )
                        } else Modifier
                    PlannerCell(
                        date, meal, slot, compact, draft, readOnly(date),
                        active = date == selectedDate && meal == selectedMeal,
                        copyChange = moment in copyChanges, dragTarget = drag.target == moment,
                        enabled = enabled(date, meal, slot),
                        onClick = { onSelect(date, meal, slot) },
                        modifier = Modifier.weight(1f).fillMaxHeight()
                            .onGloballyPositioned { drag.bounds[moment] = it.boundsInRoot() }.then(touch),
                    )
                }
            }
        }
    }
}

@Composable
private fun PlannerCell(
    date: LocalDate,
    meal: String,
    slot: MenuSlot?,
    compact: Boolean,
    draft: Boolean,
    past: Boolean,
    active: Boolean,
    copyChange: Boolean,
    dragTarget: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val title = mealTitle(slot, past)
    val noRecipe = slot != null && slot.title == null && slot.recipe == null
    val highlight = when {
        active || dragTarget -> Sunny.Ink
        copyChange -> DishColors.veggie.second
        else -> Sunny.Track
    }
    val cellColor = when {
        active || dragTarget -> Sunny.ButterLight
        copyChange -> DishColors.veggie.first
        slot == null -> Sunny.Cream
        !enabled && !past -> Sunny.Track
        else -> Sunny.Card
    }
    val frame = if (slot == null && !active && !dragTarget) {
        Modifier.drawBehind {
            val stroke = 1.dp.toPx()
            val inset = stroke / 2
            val radius = 12.dp.toPx()
            drawRoundRect(
                color = Sunny.Track, topLeft = Offset(inset, inset),
                size = Size(size.width - stroke, size.height - stroke),
                cornerRadius = CornerRadius(radius, radius),
                style = Stroke(stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()))),
            )
        }
    } else Modifier.border(if (active || dragTarget || copyChange) 2.dp else 1.dp, highlight, plannerShape)

    val label = buildString {
        append(mealMoment(date, meal))
        append(" · ")
        append(title)
        if (slot?.locked == true) append(" · Conservé")
        if (copyChange) append(" · À copier")
    }

    Row(
        modifier.heightIn(min = plannerCellHeight).clip(plannerShape).background(cellColor).then(frame)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label; selected = active }
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (!compact && slot != null && !noRecipe) {
            val emoji = when (slot.recipe?.dishType) {
                "meat" -> "🍗"
                "fish" -> "🐟"
                "vegetarian" -> "🥗"
                else -> "📝"
            }
            RecipeThumb(slot.recipe?.image, emoji, Modifier.width(25.dp).height(28.dp)
                .clip(RoundedCornerShape(6.dp)).clearAndSetSemantics { }, emojiSize = 14.sp)
        }
        Text(
            title,
            Modifier.weight(1f).clearAndSetSemantics { },
            fontSize = 12.sp, lineHeight = 14.sp,
            fontWeight = if (slot == null) FontWeight.Medium else FontWeight.SemiBold,
            color = if (noRecipe) Sunny.InkTitle else Sunny.Ink,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        if (active || dragTarget || copyChange) Icon(Icons.Filled.Check, null, Modifier.size(13.dp), tint = Sunny.Ink)
        else if (draft && slot?.locked == true) Icon(Icons.Filled.Lock, null, Modifier.size(11.dp), tint = Sunny.InkTitle)
    }
}

/** Events shared by the calendar, the recipe picker and the contextual controls. */
data class PlannerActions(
    val onWeek: (LocalDate) -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onCancelPlacement: () -> Unit = {},
    val onChooseRecipe: (RecipeSummary) -> Unit = {},
    val onPlan: (LocalDate, String, Boolean) -> Unit = { _, _, _ -> },
    val onSearchRecipes: (String) -> Unit = {},
    val onOpenRecipe: (MenuSlot, Boolean) -> Unit = { _, _ -> },
    val onGenerate: (LocalDate, Int, MealPlan) -> Unit = { _, _, _ -> },
    val onSetPeople: (Int) -> Unit = {},
    val onPublish: () -> Unit = {},
    val onDiscard: () -> Unit = {},
    val onRebase: () -> Unit = {},
    val onMove: (MenuSlot, LocalDate, String) -> Unit = { _, _, _ -> },
    val onUndoMove: () -> Unit = {},
    val onSwap: (MenuSlot) -> Unit = {},
    val onRemove: (MenuSlot) -> Unit = {},
    val onRename: (MenuSlot, String) -> Unit = { _, _ -> },
    val onMealPeople: (MenuSlot, Int) -> Unit = { _, _ -> },
    val onAddRandom: (LocalDate, String) -> Unit = { _, _ -> },
    val onAddManual: (LocalDate, String, String) -> Unit = { _, _, _ -> },
    val onCopyPreview: (LocalDate, LocalDate) -> Unit = { _, _ -> },
    val onCopyReplace: (Boolean) -> Unit = {},
    val onApplyCopy: () -> Unit = {},
    val onCloseCopy: () -> Unit = {},
    val onRecipes: () -> Unit = {},
    val onAccount: () -> Unit = {},
    val onNotifications: () -> Unit = {},
)

private enum class PlannerPanel { None, Picker, Move, Manual, People, Generate, Copy, Discard }
private data class PlannerMoment(val date: LocalDate, val meal: String)

@Stable
private class PlannerDrag {
    val bounds = mutableMapOf<PlannerMoment, Rect>()
    var viewport = Rect.Zero
    var position by mutableStateOf(Offset.Zero)
    var source by mutableStateOf<MenuSlot?>(null)
    var sourceMoment by mutableStateOf<PlannerMoment?>(null)
    var target by mutableStateOf<PlannerMoment?>(null)

    fun updateTarget(eligible: (PlannerMoment) -> Boolean) {
        target = if (viewport.contains(position)) {
            bounds.entries.firstOrNull { (moment, frame) ->
                moment != sourceMoment && frame.contains(position) && eligible(moment)
            }?.key
        } else null
    }

    fun clear() {
        source = null
        sourceMoment = null
        target = null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlannerScreen(
    user: User,
    state: MenuState,
    week: PlannerWeekState,
    placement: PlacementRecipe?,
    recipes: RecipesState,
    copy: WeekCopyState,
    unreadNotifications: Int,
    actions: PlannerActions,
) {
    val today = state.menu?.today ?: week.menu?.today ?: LocalDate.now()
    val currentMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val start = week.start ?: currentMonday
    val history = start < currentMonday
    val proposal = state.proposal
    val inDraft = proposal != null
    val info = state.menu?.info ?: week.menu?.info
    val busy = state.working != null || state.generating || copy.loading
    val weekData = week.menu
    val hasCalendarData = weekData != null || start == currentMonday && state.menu != null
    val publishing = state.working == "confirm"
    val writing = user.canWrite && !history
    val shared = weekData?.slots ?: state.menu?.slots.orEmpty()
    val visibleSlots = remember(shared, proposal) {
        if (proposal == null) shared else shared.filter { it.date < proposal.from || it.date > proposal.to } + proposal.slots
    }
    val indexed = remember(visibleSlots) { visibleSlots.associateBy { PlannerMoment(it.date, it.meal) } }
    val copyPlan = remember(copy.preview, copy.replaceOccupied) { copy.preview?.plan(copy.replaceOccupied) }
    val copiedProposal = copyPlan?.proposal
    val boardSlots = remember(visibleSlots, copiedProposal) {
        if (copiedProposal == null) visibleSlots else {
            visibleSlots.filter { it.date !in copiedProposal.from..copiedProposal.to } + copiedProposal.slots
        }
    }
    val copyChanges = remember(indexed, copiedProposal) {
        copiedProposal?.slots.orEmpty().mapNotNull { slot ->
            val moment = PlannerMoment(slot.date, slot.meal)
            moment.takeIf { indexed[moment]?.id != slot.id }
        }.toSet()
    }

    var panel by rememberSaveable { mutableStateOf(PlannerPanel.None) }
    var dayEpoch by rememberSaveable { mutableStateOf<Long?>(null) }
    var mealChoice by rememberSaveable { mutableStateOf<String?>(null) }
    var sourceId by rememberSaveable { mutableStateOf<String?>(null) }
    var moveSnapshot by remember { mutableStateOf<MenuSlot?>(null) }
    var applyingCopy by rememberSaveable { mutableStateOf(false) }
    var freeTitle by rememberSaveable { mutableStateOf("") }
    var people by rememberSaveable { mutableIntStateOf(1) }
    var fromEpoch by rememberSaveable(today) { mutableLongStateOf(today.plusDays(1).toEpochDay()) }
    var duration by rememberSaveable(info?.horizonDays) { mutableIntStateOf((info?.horizonDays ?: 7).coerceIn(1, 21)) }
    var plan by rememberSaveable { mutableStateOf(MealPlan.LUNCH_DINNER) }
    var copySourceEpoch by rememberSaveable { mutableStateOf<Long?>(null) }
    var pickerChoice by rememberSaveable { mutableStateOf(false) }

    val selected = dayEpoch?.let(LocalDate::ofEpochDay)?.let { date -> mealChoice?.let { PlannerMoment(date, it) } }
    val selectedSlot = selected?.let(indexed::get)
    val source = sourceId?.let { id ->
        visibleSlots.firstOrNull { it.id == id }
            ?: state.menu?.slots?.firstOrNull { it.id == id }
            ?: moveSnapshot?.takeIf { it.id == id }
    }
    val activeCopy = panel == PlannerPanel.Copy || copy.from != null || copy.preview != null
    val selecting = placement != null || panel == PlannerPanel.Move
    val scroll = rememberScrollState()
    val drag = remember { PlannerDrag() }
    val dragEdge = with(LocalDensity.current) { 56.dp.toPx() }
    val dragStep = with(LocalDensity.current) { 14.dp.toPx() }

    fun select(date: LocalDate?, meal: String? = null) {
        dayEpoch = date?.toEpochDay()
        mealChoice = meal
    }
    fun withinDraft(date: LocalDate) = proposal == null || date in proposal.from..proposal.to
    fun editable(date: LocalDate) = writing && date >= today && withinDraft(date) && !state.proposalConflict
    fun canPlan(date: LocalDate) = editable(date) && (inDraft || date <= today.plusDays(20))
    fun visit(date: LocalDate) {
        if (date == start) return
        select(null)
        if (panel != PlannerPanel.Move && panel != PlannerPanel.Generate) panel = PlannerPanel.None
        actions.onWeek(date)
    }
    fun closePanel() {
        if (activeCopy) actions.onCloseCopy()
        panel = PlannerPanel.None
        sourceId = null
        moveSnapshot = null
    }
    fun cancelPlacement() {
        actions.onCancelPlacement()
        select(null)
        closePanel()
    }
    fun openPicker() {
        panel = PlannerPanel.Picker
        actions.onSearchRecipes("")
    }
    fun startMove(slot: MenuSlot) {
        sourceId = slot.id
        moveSnapshot = slot
        select(null)
        panel = PlannerPanel.Move
    }

    LaunchedEffect(copy.from, copy.loading, copy.error) {
        if (applyingCopy && !copy.loading) {
            if (copy.from == null) panel = PlannerPanel.None
            applyingCopy = false
        }
    }
    val dropRule by rememberUpdatedState<(PlannerMoment) -> Boolean> { point ->
        editable(point.date) && (!inDraft || indexed[point] != null)
    }
    LaunchedEffect(drag.source) {
        while (drag.source != null) {
            val distance = when {
                drag.position.y < drag.viewport.top + dragEdge -> -dragStep
                drag.position.y > drag.viewport.bottom - dragEdge -> dragStep
                else -> 0f
            }
            if (distance != 0f) {
                scroll.scrollBy(distance)
                drag.updateTarget(dropRule)
            }
            delay(16)
        }
    }
    LaunchedEffect(placement?.id) {
        if (placement != null) {
            if (!pickerChoice) select(null)
            pickerChoice = false
            panel = PlannerPanel.None
        }
    }
    LaunchedEffect(proposal != null) {
        if (panel == PlannerPanel.Generate && proposal != null) {
            panel = PlannerPanel.None
            select(null)
        }
    }

    val canBack = selected != null || panel != PlannerPanel.None || placement != null || inDraft || history || start != currentMonday
    BackHandler(canBack) {
        if (!busy) when {
            placement != null -> cancelPlacement()
            panel != PlannerPanel.None -> closePanel()
            selected != null -> select(null)
            inDraft -> panel = PlannerPanel.Discard
            else -> visit(currentMonday)
        }
    }

    val title = when {
        placement != null || panel == PlannerPanel.Move -> "Planifier"
        panel == PlannerPanel.Generate -> "Composer"
        history -> "Repas passés"
        inDraft -> "Proposition"
        else -> "Menus"
    }
    val subtitle = when {
        history -> "Historique · lecture seule"
        inDraft -> "Brouillon privé · " + periodLabel(proposal!!.from, proposal.to)
        placement != null || panel == PlannerPanel.Move -> null
        else -> "Menu partagé du foyer"
    }

    val canChangeWeek = !busy && !activeCopy
    val minWeek = when {
        selecting && inDraft -> proposal!!.from.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        selecting -> currentMonday
        else -> null
    }
    val maxWeek = when {
        selecting && inDraft -> proposal!!.to.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        placement != null -> today.plusDays(20).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        else -> null
    }
    val showNav = !inDraft && !selecting && panel != PlannerPanel.Generate && !activeCopy
    val canChangeShared = weekData != null && !week.loading && week.error == null
    val selectionWeekReady = inDraft || canChangeShared
    val moveReady = source != null && selected != null && selectionWeekReady
    val planReady = placement != null && selected != null && selectionWeekReady
    val generationStart = LocalDate.ofEpochDay(fromEpoch)
    val generationCount = duration * if (plan == MealPlan.LUNCH_DINNER) 2 else 1
    val headlineAction: (() -> Unit)? = when {
        placement != null -> ::cancelPlacement
        panel != PlannerPanel.None -> ::closePanel
        inDraft -> { { panel = PlannerPanel.Discard } }
        else -> null
    }
    val headlineLabel = when {
        placement != null || (panel != PlannerPanel.None && panel != PlannerPanel.Discard) -> "Annuler"
        inDraft -> "Quitter"
        else -> null
    }

    Column(Modifier.fillMaxSize().background(Sunny.Cream).statusBarsPadding().imePadding()) {
        PlannerHeader(
            title, subtitle, headlineLabel, headlineAction,
            enabled = !busy,
            notifications = unreadNotifications.takeIf { user.canWrite && showNav },
            onNotifications = actions.onNotifications,
        )
        PullToRefreshBox(
            isRefreshing = state.refreshing || (week.loading && weekData != null),
            onRefresh = { if (!busy && !inDraft) actions.onRefresh() },
            modifier = Modifier.weight(1f).fillMaxWidth()
                .onGloballyPositioned { drag.viewport = it.boundsInRoot() },
        ) {
            Column(
                Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 10.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (selecting) {
                    val label = if (placement != null) "Repas à placer" else "Repas à déplacer"
                    PlannerNotice("$label · ${placement?.title ?: source?.let { mealTitle(it) }.orEmpty()}")
                }
                PlannerWeekNav(
                    start, history, canChangeWeek && (minWeek == null || start > minWeek),
                    canChangeWeek && (maxWeek == null || start < maxWeek),
                    onPrevious = { visit(start.minusWeeks(1)) }, onNext = { visit(start.plusWeeks(1)) },
                    onHistoryOrCurrent = if (!canChangeWeek || selecting) null
                        else { { visit(if (history) currentMonday else currentMonday.minusWeeks(1)) } },
                )
                if ((state.loading || week.loading) && !hasCalendarData) {
                    PlannerLoading()
                } else if (!hasCalendarData) {
                    PlannerCard("Repas indisponibles") {
                        PlannerSmall(week.error ?: state.error ?: "Impossible de charger le menu.")
                        PlannerAction("Réessayer", actions.onRefresh, enabled = !busy)
                    }
                } else {
                    PlannerWeekGrid(
                        start, today, boardSlots, selected?.date, selected?.meal,
                        compact = selecting, draft = inDraft, readOnly = { it < today || history },
                        enabled = { date, meal, slot ->
                            !busy && when {
                                placement != null -> selectionWeekReady && canPlan(date)
                                panel == PlannerPanel.Move -> selectionWeekReady && editable(date) &&
                                    (date != source?.date || meal != source?.meal) && (!inDraft || slot != null)
                                panel == PlannerPanel.Generate || activeCopy -> false
                                editable(date) && canChangeShared -> true
                                else -> slot != null
                            }
                        },
                        copyChanges = copyChanges, drag = drag,
                        canDrag = { date, _ -> !busy && selectionWeekReady && editable(date) &&
                            placement == null && panel == PlannerPanel.None && !activeCopy },
                        canDrop = { date, slot -> editable(date) && (!inDraft || slot != null) },
                        onStartMove = ::startMove,
                        onDrop = { slot, target ->
                            startMove(slot)
                            select(target.date, target.meal)
                        },
                        onSelect = { date, meal, slot ->
                            select(date, meal)
                            if (placement == null && panel != PlannerPanel.Move) {
                                panel = PlannerPanel.None
                                if (slot?.recipe != null && !editable(date) && !history) {
                                    actions.onOpenRecipe(slot, true)
                                }
                            }
                        },
                    )
                }
                val error = week.error ?: state.error
                if (error != null && hasCalendarData && !state.proposalConflict) {
                    PlannerNotice(if (week.error != null) "Dernier menu chargé · $error" else error, error = true)
                    if (!inDraft) PlannerAction("Actualiser le menu", actions.onRefresh, enabled = !busy)
                }
                if (state.undoMove != null && !inDraft) {
                    Row(
                        Modifier.fillMaxWidth().background(Sunny.ButterLight, plannerShape).padding(start = 12.dp, end = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Repas déplacé", color = Sunny.Ink, fontWeight = FontWeight.SemiBold)
                        PlannerAction("Annuler", actions.onUndoMove, enabled = !busy)
                    }
                }
                when {
                    panel == PlannerPanel.Discard -> PlannerCard("Quitter la proposition ?") {
                        PlannerSmall("Vos modifications du brouillon seront perdues.")
                        PlannerAction("Quitter sans publier", {
                            closePanel()
                            select(null)
                            actions.onDiscard()
                        }, primary = true, warning = true, enabled = !busy)
                        PlannerAction("Poursuivre la relecture", ::closePanel, enabled = !busy)
                    }
                    state.proposalConflict -> PlannerCard("Le menu partagé a changé") {
                        PlannerSmall("Recalculez la proposition à partir des derniers repas partagés.")
                        state.error?.let { PlannerSmall(it) }
                        PlannerAction("Recalculer", actions.onRebase, primary = true, enabled = !busy)
                    }
                    placement != null -> {
                        if (selected != null) PlannerConfirmation(selected, selectedSlot, placement.title, inDraft)
                        else PlannerCard("Choisir un créneau") {
                            PlannerSmall("Touchez le midi ou le soir du jour souhaité.")
                        }
                    }
                    panel == PlannerPanel.Move -> {
                        if (selected != null) PlannerConfirmation(selected, selectedSlot, null, inDraft, moving = true)
                        else PlannerCard("Déplacer le repas") {
                            PlannerSmall("Choisissez le créneau d’arrivée dans le calendrier.")
                        }
                    }
                    panel == PlannerPanel.Picker -> PlannerRecipePicker(recipes, selected, busy, actions.onSearchRecipes) {
                        pickerChoice = true
                        actions.onChooseRecipe(it)
                    }
                    panel == PlannerPanel.Generate -> PlannerGeneration(
                        generationStart, today, duration, plan, user.people, generationCount,
                        visibleSlots, state.menu?.slots.orEmpty(),
                        info, busy,
                        onStart = { fromEpoch = it.toEpochDay() }, onDuration = { duration = it },
                        onPlan = { plan = it }, onPeople = actions.onSetPeople,
                    )
                    activeCopy -> PlannerCopyPanel(
                        start, copySourceEpoch?.let(LocalDate::ofEpochDay) ?: start.minusWeeks(1),
                        copy, busy, actions.onCopyReplace,
                        onSource = { copySourceEpoch = it.toEpochDay() },
                    )
                    panel == PlannerPanel.Manual -> PlannerCard("Repas libre") {
                        selected?.let { PlannerSmall(mealMoment(it.date, it.meal)) }
                        OutlinedTextField(
                            freeTitle,
                            { if (it.codePointCount(0, it.length) <= 100) freeTitle = it },
                            label = { Text("Intitulé") }, placeholder = { Text("Ex. Repas chez des amis") },
                            enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth(),
                            shape = plannerShape,
                        )
                    }
                    panel == PlannerPanel.People -> PlannerCard("Nombre de personnes") {
                        selected?.let { PlannerSmall(mealMoment(it.date, it.meal)) }
                        selectedSlot?.let { Text(mealTitle(it), color = Sunny.Ink, fontWeight = FontWeight.SemiBold) }
                        PlannerStepper(people, 1, 20, "Une personne", { people = it }, !busy)
                    }
                    selected != null && selectedSlot != null -> PlannerMealPanel(
                        selected, selectedSlot, allowWrite = editable(selected.date) && selectionWeekReady,
                        historical = history || selected.date < today,
                        allowRecipeChoice = canPlan(selected.date),
                        allowMove = !inDraft || proposal!!.slots.size > 1,
                        enabled = !busy, actions = actions,
                        onPick = ::openPicker, onMove = { startMove(selectedSlot) },
                        onManual = {
                            freeTitle = selectedSlot.title.orEmpty()
                            panel = PlannerPanel.Manual
                        },
                        onPeople = {
                            people = (selectedSlot.people ?: user.people).coerceIn(1, 20)
                            panel = PlannerPanel.People
                        },
                        onRemove = {
                            select(null)
                            actions.onRemove(selectedSlot)
                        },
                    )
                    selected != null && editable(selected.date) && selectionWeekReady && canPlan(selected.date) -> PlannerCard("Créneau libre") {
                        PlannerSmall(mealMoment(selected.date, selected.meal))
                        PlannerAction("Choisir une recette", ::openPicker, enabled = !busy)
                        if (inDraft) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            PlannerAction("Autre idée", { actions.onAddRandom(selected.date, selected.meal) }, enabled = !busy)
                            PlannerAction("Repas libre", {
                                freeTitle = ""
                                panel = PlannerPanel.Manual
                            }, enabled = !busy)
                        }
                    }
                    history && weekData != null -> PlannerCard("Repas planifiés à cette date") {
                        PlannerSmall(if (visibleSlots.isEmpty()) "Aucun repas planifié cette semaine."
                            else "Sélectionnez un repas pour le consulter.")
                    }
                    writing && (weekData != null || inDraft) -> PlannerCard(if (inDraft) "Ajuster la proposition" else "Planifier un repas") {
                        PlannerAction("Choisir une recette", { select(null); openPicker() }, enabled = selectionWeekReady && !busy)
                        if (!inDraft) PlannerAction("Copier une semaine", {
                            panel = PlannerPanel.Copy
                            copySourceEpoch = start.minusWeeks(1).toEpochDay()
                            select(null)
                        }, enabled = canChangeShared && !busy)
                    }
                }
                Spacer(Modifier.height(2.dp))
            }
        }

        when {
            planReady -> PlannerFooter(
                if (selectedSlot != null) "Remplacer le repas" else "Planifier le repas",
                onClick = { actions.onPlan(selected!!.date, selected.meal, selectedSlot != null) },
                enabled = !busy && canPlan(selected!!.date),
                warning = selectedSlot != null,
            )
            moveReady -> PlannerFooter("Déplacer ici", {
                actions.onMove(source!!, selected!!.date, selected.meal)
                closePanel()
                select(null)
            }, enabled = !busy && editable(selected!!.date))
            panel == PlannerPanel.Generate -> PlannerFooter(
                if (state.generating) "Création en cours…" else "Créer la proposition",
                onClick = { actions.onGenerate(generationStart, generationCount, plan) },
                enabled = info?.enoughRecipes == true && !busy,
            )
            activeCopy && copy.preview != null -> PlannerFooter(
                "Appliquer à la semaine",
                onClick = { applyingCopy = true; actions.onApplyCopy() },
                enabled = !busy && copiedProposal != null,
            )
            activeCopy -> PlannerFooter("Vérifier la copie", {
                actions.onCopyPreview(copySourceEpoch?.let(LocalDate::ofEpochDay) ?: start.minusWeeks(1), start)
            }, enabled = !busy && canChangeShared)
            panel == PlannerPanel.Manual && selected != null -> PlannerFooter("Enregistrer le repas", {
                val title = freeTitle.trim()
                if (selectedSlot != null) actions.onRename(selectedSlot, title)
                else actions.onAddManual(selected.date, selected.meal, title)
                closePanel()
            }, enabled = !busy && editable(selected.date) && selectionWeekReady && freeTitle.isNotBlank())
            panel == PlannerPanel.People && selectedSlot != null -> PlannerFooter("Enregistrer les portions", {
                actions.onMealPeople(selectedSlot, people)
                closePanel()
            }, enabled = !busy && selectionWeekReady)
            inDraft && panel == PlannerPanel.None && placement == null && !state.proposalConflict -> PlannerFooter(
                if (publishing) "Publication…" else "Publier",
                onClick = actions.onPublish, enabled = !busy,
            )
            writing && !inDraft && panel == PlannerPanel.None && !activeCopy -> PlannerFooter(
                "Composer", onClick = { panel = PlannerPanel.Generate; select(null) },
                enabled = !busy && canChangeShared,
            )
        }
        if (showNav) PlannerBottomBar(Tab.MENUS, { visit(currentMonday) }, actions.onRecipes, actions.onAccount)
        else Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
    }
}

@Composable
private fun PlannerHeader(
    title: String,
    subtitle: String?,
    label: String?,
    onAction: (() -> Unit)?,
    enabled: Boolean,
    notifications: Int?,
    onNotifications: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, Modifier.semantics { heading() }, fontSize = 26.sp, lineHeight = 29.sp,
                fontWeight = FontWeight.Bold, color = Sunny.Ink)
            if (subtitle != null) Text(
                subtitle, fontSize = 12.sp, lineHeight = 16.sp, color = Sunny.InkTitle,
            )
        }
        if (notifications != null) {
            IconButton(onClick = onNotifications, enabled = enabled, modifier = Modifier.size(48.dp)) {
                if (notifications > 0) BadgedBox(
                    badge = { Badge(containerColor = Sunny.Butter, contentColor = Sunny.Ink) { Text(notifications.toString()) } },
                ) {
                    Icon(Icons.Filled.Notifications, "Notifications · $notifications non lues", tint = Sunny.Ink)
                } else Icon(Icons.Filled.Notifications, "Notifications", tint = Sunny.Ink)
            }
        }
        if (label != null && onAction != null) PlannerAction(label, onAction, enabled = enabled)
    }
}

@Composable
fun PlannerBottomBar(selected: Tab?, onMenu: () -> Unit, onRecipes: () -> Unit, onAccount: () -> Unit) {
    NavigationBar(containerColor = Sunny.Card) {
        val colors = NavigationBarItemDefaults.colors(
            selectedIconColor = Sunny.Ink,
            selectedTextColor = Sunny.Ink,
            indicatorColor = Sunny.Butter,
            unselectedIconColor = Sunny.InkTitle,
            unselectedTextColor = Sunny.InkTitle,
        )
        NavigationBarItem(
            selected = selected == Tab.MENUS, onClick = onMenu, colors = colors,
            icon = { Icon(Icons.Filled.DateRange, null) }, label = { Text("Menus") },
        )
        NavigationBarItem(
            selected = selected == Tab.RECIPES, onClick = onRecipes, colors = colors,
            icon = { Icon(Icons.Filled.Search, null) }, label = { Text("Recettes") },
        )
        NavigationBarItem(
            selected = selected == null, onClick = onAccount, colors = colors,
            icon = { Icon(Icons.Filled.Person, null) }, label = { Text("Compte") },
        )
    }
}

@Composable
private fun PlannerCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(Shapes.Control).background(Sunny.Card)
            .border(1.dp, Sunny.Track, Shapes.Control).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, Modifier.semantics { heading() }, fontSize = 17.sp, lineHeight = 21.sp,
            fontWeight = FontWeight.Bold, color = Sunny.Ink)
        content()
    }
}

@Composable
private fun PlannerSmall(text: String) {
    Text(text, fontSize = 12.sp, lineHeight = 16.sp, color = Sunny.InkTitle)
}

@Composable
private fun PlannerNotice(text: String, error: Boolean = false) {
    Text(
        text,
        Modifier.fillMaxWidth().clip(plannerShape)
            .background(if (error) Sunny.ErrorBg else Sunny.ButterLight)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        fontSize = 12.sp, lineHeight = 16.sp, color = Sunny.Ink, fontWeight = FontWeight.Medium,
    )
}

@Composable
private fun PlannerLoading() {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(Modifier.size(22.dp), color = Sunny.PeachDeep, strokeWidth = 3.dp)
        PlannerSmall("Chargement des repas…")
    }
}

@Composable
private fun PlannerAction(
    label: String,
    onClick: () -> Unit,
    primary: Boolean = false,
    warning: Boolean = false,
    enabled: Boolean = true,
) {
    val text: @Composable RowScope.() -> Unit = {
        Text(label, fontSize = 12.sp, lineHeight = 15.sp, fontWeight = FontWeight.Bold)
    }
    if (primary) {
        Button(
            onClick = onClick, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp),
            contentPadding = PaddingValues(horizontal = 14.dp), shape = plannerShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (warning) Sunny.PeachLight else Sunny.Butter,
                contentColor = Sunny.Ink,
            ),
            content = text,
        )
    } else {
        OutlinedButton(
            onClick = onClick, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp),
            contentPadding = PaddingValues(horizontal = 10.dp), shape = plannerShape,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Sunny.Ink),
            border = androidx.compose.foundation.BorderStroke(1.dp, Sunny.Track),
            content = text,
        )
    }
}

@Composable
private fun PlannerFooter(label: String, onClick: () -> Unit, enabled: Boolean, warning: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().background(Sunny.Cream)
            .padding(start = 10.dp, end = 10.dp, top = 3.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        PlannerAction(label, onClick, primary = true, warning = warning, enabled = enabled)
    }
}

@Composable
private fun PlannerConfirmation(
    moment: PlannerMoment,
    current: MenuSlot?,
    newTitle: String?,
    draft: Boolean,
    moving: Boolean = false,
) {
    PlannerCard(when {
        moving -> "Confirmer le déplacement"
        current != null -> "Confirmer le remplacement"
        else -> "Confirmer le repas"
    }) {
        PlannerSmall(mealMoment(moment.date, moment.meal))
        if (newTitle != null) Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            PlannerMealChange("Actuellement", mealTitle(current), Modifier.weight(1f), Sunny.Cream)
            PlannerMealChange("Après validation", newTitle, Modifier.weight(1f), Sunny.ButterLight)
        } else if (current == null) {
            PlannerSmall("Le créneau est libre.")
        } else {
            PlannerSmall("« ${mealTitle(current)} » est prévu ici. Les repas entre les deux créneaux seront décalés.")
        }
        PlannerSmall(if (draft) "Modification du brouillon." else "Modification du menu partagé.")
    }
}

@Composable
private fun PlannerMealChange(label: String, title: String, modifier: Modifier, color: androidx.compose.ui.graphics.Color) {
    Column(modifier.background(color, plannerShape).padding(8.dp)) {
        PlannerSmall(label)
        Text(title, fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold, color = Sunny.Ink)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlannerMealPanel(
    moment: PlannerMoment,
    slot: MenuSlot,
    allowWrite: Boolean,
    historical: Boolean,
    allowRecipeChoice: Boolean,
    allowMove: Boolean,
    enabled: Boolean,
    actions: PlannerActions,
    onPick: () -> Unit,
    onMove: () -> Unit,
    onManual: () -> Unit,
    onPeople: () -> Unit,
    onRemove: () -> Unit,
) {
    PlannerCard(mealMoment(moment.date, moment.meal)) {
        Text(mealTitle(slot), fontSize = 16.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold, color = Sunny.Ink)
        if (slot.manual) PlannerSmall("Repas libre")
        else if (slot.recipe == null) PlannerSmall("Aucune recette n’est associée à ce repas.")
        if (slot.people != null) PlannerSmall(if (slot.people == 1) "1 personne" else "${slot.people} personnes")
        if (slot.locked) PlannerSmall("Conservé lors de la génération.")
        if (slot.recipe != null || allowWrite) FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (slot.recipe != null) PlannerAction("Voir la recette", {
                actions.onOpenRecipe(slot, historical || !allowWrite)
            }, enabled = enabled)
            if (allowWrite) {
                if (allowRecipeChoice) PlannerAction("Remplacer", onPick, enabled = enabled)
                PlannerAction("Déplacer", onMove, enabled = enabled && allowMove)
                PlannerAction("Autre idée", { actions.onSwap(slot) }, enabled = enabled)
                PlannerAction(if (slot.manual) "Modifier l’intitulé" else "Repas libre", onManual, enabled = enabled)
                PlannerAction("Portions", onPeople, enabled = enabled)
                PlannerAction("Retirer", onRemove, enabled = enabled)
            }
        }
    }
}

@Composable
private fun PlannerRecipePicker(
    state: RecipesState,
    moment: PlannerMoment?,
    busy: Boolean,
    onSearch: (String) -> Unit,
    onChoose: (RecipeSummary) -> Unit,
) {
    PlannerCard("Choisir une recette") {
        moment?.let { PlannerSmall(mealMoment(it.date, it.meal)) }
        OutlinedTextField(
            state.query, onSearch, label = { Text("Rechercher une recette") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            leadingIcon = { Icon(Icons.Filled.Search, null) }, shape = plannerShape,
        )
        val recipes = state.recipes.orEmpty()
        val error = state.error
        when {
            state.loading && recipes.isEmpty() -> PlannerLoading()
            error != null -> {
                PlannerNotice(error, error = true)
                PlannerAction("Réessayer", { onSearch(state.query) }, enabled = !busy)
            }
            recipes.isEmpty() -> PlannerSmall(if (state.query.isBlank()) "Le carnet est vide." else "Aucune recette trouvée.")
        }
        if (recipes.isNotEmpty()) LazyColumn(
            Modifier.fillMaxWidth().heightIn(max = 240.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            items(recipes, key = RecipeSummary::id) { recipe ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(plannerShape)
                        .border(1.dp, Sunny.Track, plannerShape)
                        .clickable(enabled = !busy, role = Role.Button) { onChoose(recipe) }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RecipeThumb(recipe.image, "🍽️", Modifier.size(31.dp).clip(RoundedCornerShape(6.dp))
                        .clearAndSetSemantics { }, emojiSize = 15.sp)
                    Text(recipe.title, Modifier.weight(1f), fontSize = 13.sp, lineHeight = 16.sp,
                        fontWeight = FontWeight.SemiBold, color = Sunny.Ink)
                    recipe.minutes?.let { PlannerSmall("$it min") }
                }
            }
        }
    }
}

@Composable
private fun <T> PlannerSelect(
    title: String,
    selected: T,
    options: List<T>,
    label: (T) -> String,
    enabled: Boolean,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var show by remember { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        PlannerSmall(title)
        OutlinedButton(
            onClick = { show = true }, enabled = enabled,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            shape = plannerShape, contentPadding = PaddingValues(horizontal = 8.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Sunny.Ink),
            border = androidx.compose.foundation.BorderStroke(1.dp, Sunny.Track),
        ) {
            Text(label(selected), Modifier.weight(1f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Icon(Icons.Filled.KeyboardArrowDown, null, Modifier.size(16.dp))
        }
        DropdownMenu(expanded = show, onDismissRequest = { show = false }) {
            options.forEach { item ->
                DropdownMenuItem(
                    text = { Text(label(item), color = Sunny.Ink) },
                    onClick = { onSelect(item); show = false },
                )
            }
        }
    }
}

@Composable
private fun PlannerStepper(value: Int, min: Int, max: Int, unit: String, onValue: (Int) -> Unit, enabled: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        IconButton(
            onClick = { onValue(value - 1) }, enabled = enabled && value > min, modifier = Modifier.size(48.dp)
                .semantics { contentDescription = "$unit en moins" },
        ) { Text("−", color = Sunny.Ink, fontSize = 21.sp) }
        Text(value.toString(), fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Sunny.Ink)
        IconButton(
            onClick = { onValue(value + 1) }, enabled = enabled && value < max, modifier = Modifier.size(48.dp)
                .semantics { contentDescription = "$unit en plus" },
        ) { Text("+", color = Sunny.Ink, fontSize = 21.sp) }
    }
}

@Composable
private fun PlannerGeneration(
    start: LocalDate,
    today: LocalDate,
    duration: Int,
    plan: MealPlan,
    people: Int,
    meals: Int,
    visible: List<MenuSlot>,
    all: List<MenuSlot>,
    info: GenerationInfo?,
    busy: Boolean,
    onStart: (LocalDate) -> Unit,
    onDuration: (Int) -> Unit,
    onPlan: (MealPlan) -> Unit,
    onPeople: (Int) -> Unit,
) {
    val end = start.plusDays(duration - 1L)
    val (replaced, kept) = periodImpact((visible + all).distinctBy { it.date to it.meal }, start, end)
    PlannerCard("Période et nombre de repas") {
        if (info != null && !info.enoughRecipes) PlannerNotice(
            "Il faut au moins ${info.minRecipes} recettes pour composer un menu. Le carnet en contient ${info.recipeCount}.",
            error = true,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PlannerSelect(
                "À partir du", start, List(14) { today.plusDays(it.toLong()) },
                plannerMonthDay::format, !busy, onStart, Modifier.weight(1f),
            )
            PlannerSelect("Durée en jours", duration, (1..21).toList(), Int::toString, !busy, onDuration, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PlannerSelect(
                "Créneaux", plan, listOf(MealPlan.LUNCH_DINNER, MealPlan.LUNCH, MealPlan.DINNER),
                { when (it) { MealPlan.LUNCH -> "Midi"; MealPlan.DINNER -> "Soir"; else -> "Midi et soir" } },
                !busy, onPlan, Modifier.weight(1f),
            )
            PlannerSelect(
                "Personnes", people, (1..20).toList(), Int::toString,
                !busy, onPeople, Modifier.weight(1f),
            )
        }
        PlannerSmall("${periodLabel(start, end)} · $meals repas · $kept à conserver, $replaced à revoir.")
    }
}

@Composable
private fun PlannerCopyPanel(
    destination: LocalDate,
    source: LocalDate,
    copy: WeekCopyState,
    busy: Boolean,
    onReplace: (Boolean) -> Unit,
    onSource: (LocalDate) -> Unit,
) {
    val preview = copy.preview
    PlannerCard(if (preview == null) "Copier une semaine" else "Vérifier la copie") {
        if (preview == null) {
            PlannerSmall("Semaine source")
            PlannerWeekNav(
                source, pastWeek = false, previous = !busy,
                next = !busy && source.plusWeeks(1) < destination,
                onPrevious = { onSource(source.minusWeeks(1)) },
                onNext = { onSource(source.plusWeeks(1)) },
                onHistoryOrCurrent = null,
            )
        } else {
            PlannerSmall("Source · ${periodLabel(source, source.plusDays(6))}")
            PlannerNotice("Aperçu · Les cases colorées indiquent les repas à copier.")
        }
        PlannerSmall("Destination · ${periodLabel(destination, destination.plusDays(6))}")
        copy.error?.let { PlannerNotice(it, error = true) }
        if (copy.loading) PlannerLoading()
        if (preview != null) {
            val plan = preview.plan(copy.replaceOccupied)
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(plannerShape).clickable(enabled = !busy) {
                    onReplace(!copy.replaceOccupied)
                },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Remplacer les repas déjà prévus", Modifier.weight(1f), color = Sunny.Ink, fontSize = 13.sp)
                Switch(checked = copy.replaceOccupied, onCheckedChange = null, enabled = !busy)
            }
            PlannerSmall("${plan.added} à ajouter · ${plan.replaced} à remplacer")
            if (plan.skippedOccupied > 0) PlannerSmall("${plan.skippedOccupied} occupés seront conservés.")
            if (plan.skippedUnavailable > 0) PlannerSmall("${plan.skippedUnavailable} repas ne peuvent pas être copiés.")
            if (plan.skippedPast > 0) PlannerSmall("${plan.skippedPast} créneaux passés ne seront pas modifiés.")
        }
    }
}
