package app.mijote.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Menu
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mijote.MenuState
import app.mijote.PlacementRecipe
import app.mijote.PlannerWeekState
import app.mijote.RecipesState
import app.mijote.WeekCopyState
import app.mijote.data.GenerationInfo
import app.mijote.data.MealPlan
import app.mijote.data.Menu
import app.mijote.data.MenuSlot
import app.mijote.data.weekStart
import app.mijote.data.RecipeSummary
import app.mijote.data.User
import app.mijote.data.periodImpact
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val plannerMonthDay = DateTimeFormatter.ofPattern("d MMMM", Locale.FRENCH)
private val plannerShape = RoundedCornerShape(12.dp)

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

/** Events shared by the calendar, the recipe picker and the contextual controls. */
data class PlannerActions(
    val onWeek: (LocalDate) -> Unit = {},
    val onLoadBefore: (Boolean) -> Unit = {},
    val onLoadAfter: (Boolean) -> Unit = {},
    val onVisibleDay: (LocalDate) -> Unit = {},
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
internal data class PlannerMoment(val date: LocalDate, val meal: String)

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
    val proposal = state.proposal
    val inDraft = proposal != null
    val info = state.menu?.info ?: week.menu?.info
    val busy = state.working != null || state.generating || copy.loading
    val loaded = week.weeks.ifEmpty { week.menu?.let { mapOf((week.start ?: today.weekStart()) to it) }.orEmpty() }
    val fallback = state.menu?.takeIf { loaded.isEmpty() && week.start == today.weekStart() }
    val dates = remember(loaded.keys, fallback?.today) {
        val first = loaded.keys.minOrNull()
        val last = loaded.keys.maxOrNull()
        when {
            first != null && last != null -> (first.toEpochDay()..last.plusDays(6).toEpochDay()).map(LocalDate::ofEpochDay)
            fallback != null -> (fallback.today.toEpochDay()..fallback.today.weekStart().plusDays(6).toEpochDay()).map(LocalDate::ofEpochDay)
            else -> emptyList()
        }
    }
    val shared = remember(loaded, fallback) { loaded.values.flatMap(Menu::slots).ifEmpty { fallback?.slots.orEmpty() } }
    val visibleSlots = remember(shared, proposal) {
        if (proposal == null) shared else shared.filterNot { it.date in proposal.from..proposal.to } + proposal.slots
    }
    val indexed = remember(visibleSlots) { visibleSlots.associateBy { PlannerMoment(it.date, it.meal) } }
    val copyPlan = remember(copy.preview, copy.replaceOccupied) { copy.preview?.plan(copy.replaceOccupied) }
    val copied = copyPlan?.proposal
    val displayed = remember(visibleSlots, copied) {
        val result = if (copied == null) visibleSlots
            else visibleSlots.filterNot { it.date in copied.from..copied.to } + copied.slots
        result.associateBy { PlannerMoment(it.date, it.meal) }
    }
    val copyChanges = remember(indexed, copied) {
        copied?.slots.orEmpty().mapNotNull { slot ->
            val moment = PlannerMoment(slot.date, slot.meal)
            moment.takeIf { indexed[moment]?.id != slot.id }
        }.toSet()
    }

    var consumedJump by rememberSaveable { mutableIntStateOf(-1) }
    var visibleEpoch by rememberSaveable { mutableLongStateOf(today.toEpochDay()) }
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
    var copyTargetEpoch by rememberSaveable { mutableStateOf<Long?>(null) }
    var pickerChoice by rememberSaveable { mutableStateOf(false) }
    var hadPlacement by rememberSaveable { mutableStateOf(false) }

    val visibleDate = LocalDate.ofEpochDay(visibleEpoch)
    val selected = dayEpoch?.let(LocalDate::ofEpochDay)?.let { date -> mealChoice?.let { PlannerMoment(date, it) } }
    val selectedSlot = selected?.let(indexed::get)
    val source = moveSnapshot ?: sourceId?.let { id -> visibleSlots.firstOrNull { it.id == id } }
    val activeCopy = panel == PlannerPanel.Copy || copy.from != null
    val selecting = placement != null || panel == PlannerPanel.Move
    val isHome = !inDraft && !selecting
    val todayIndex = dates.indexOf(today)
    val list = rememberLazyListState(
        initialFirstVisibleItemIndex = if (isHome) todayIndex.coerceAtLeast(0) else 0,
    )
    var focusedHomeDate by remember { mutableStateOf<LocalDate?>(null) }
    var requestedHomeDate by remember(today) { mutableStateOf(false) }
    var homeMenuOpen by remember { mutableStateOf(false) }
    val todayVisible by remember(list, today) {
        derivedStateOf {
            val layout = list.layoutInfo
            layout.visibleItemsInfo.any {
                it.key == today.toEpochDay() &&
                    it.offset < layout.viewportEndOffset && it.offset + it.size > layout.viewportStartOffset
            }
        }
    }
    val showTodayButton = isHome && focusedHomeDate == today && dates.isNotEmpty() && !todayVisible
    val targetWeek = copyTargetEpoch?.let(LocalDate::ofEpochDay) ?: maxOf(visibleDate.weekStart(), today.weekStart())
    val sourceWeek = copySourceEpoch?.let(LocalDate::ofEpochDay) ?: targetWeek.minusWeeks(1)

    fun select(moment: PlannerMoment?) {
        dayEpoch = moment?.date?.toEpochDay()
        mealChoice = moment?.meal
    }
    fun loadedDay(date: LocalDate) = loaded.containsKey(date.weekStart()) || fallback != null && date >= fallback.today
    fun readOnly(date: LocalDate) = !user.canWrite || date < today ||
        (proposal != null && date !in proposal.from..proposal.to)
    fun editable(date: LocalDate) = !readOnly(date) && week.snapshot(date) != null && !state.proposalConflict
    fun canPlan(date: LocalDate) = editable(date) && (inDraft || date <= today.plusDays(20))
    fun canMove(moment: PlannerMoment) = editable(moment.date) &&
        (!inDraft || indexed[moment] != null) &&
        (source == null || kotlin.math.abs(moment.date.toEpochDay() - source.date.toEpochDay()) < 60) &&
        (source == null || source.date != moment.date || source.meal != moment.meal)
    fun closePanel() {
        if (activeCopy) actions.onCloseCopy()
        panel = PlannerPanel.None
        sourceId = null
        moveSnapshot = null
        select(null)
    }
    fun cancelPlacement() {
        actions.onCancelPlacement()
        closePanel()
    }
    fun dismissSheet() {
        if (!busy) {
            if (selecting) select(null) else closePanel()
        }
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
    fun startCopy() {
        val visibleWeek = visibleDate.weekStart()
        val destination = maxOf(visibleWeek, today.weekStart())
        copyTargetEpoch = destination.toEpochDay()
        copySourceEpoch = minOf(visibleWeek, destination.minusWeeks(1)).toEpochDay()
        select(null)
        panel = PlannerPanel.Copy
        if (visibleWeek < destination) actions.onWeek(destination)
    }
    fun goToToday() {
        if (todayIndex >= 0 && loadedDay(today)) list.requestScrollToItem(todayIndex)
        else actions.onWeek(today)
    }

    LaunchedEffect(isHome, today, dates, week.jumpSerial, week.start, week.loading) {
        if (isHome && focusedHomeDate != today) {
            if (todayIndex >= 0 && loadedDay(today)) {
                list.scrollToItem(todayIndex)
                visibleEpoch = today.toEpochDay()
                focusedHomeDate = today
                consumedJump = week.jumpSerial
            } else if (!requestedHomeDate && !week.loading && week.start?.let { it != today.weekStart() } == true) {
                requestedHomeDate = true
                actions.onWeek(today)
            }
            return@LaunchedEffect
        }
        if (dates.isEmpty() || consumedJump == week.jumpSerial) return@LaunchedEffect
        val anchor = week.start ?: today.weekStart()
        val destination = when {
            proposal != null && proposal.from.weekStart() == anchor -> proposal.from
            anchor == today.weekStart() -> today
            else -> anchor
        }
        val index = dates.indexOf(destination)
        if (index >= 0) {
            list.scrollToItem(index)
            visibleEpoch = destination.toEpochDay()
            consumedJump = week.jumpSerial
        }
    }
    LaunchedEffect(copy.from, copy.loading, copy.error) {
        if (applyingCopy && !copy.loading) {
            if (copy.from == null) closePanel()
            applyingCopy = false
        }
    }
    LaunchedEffect(placement?.id) {
        if (placement != null) {
            if (!pickerChoice) select(null)
            pickerChoice = false
            panel = PlannerPanel.None
            hadPlacement = true
        } else if (hadPlacement) {
            select(null)
            pickerChoice = false
            hadPlacement = false
        }
    }
    LaunchedEffect(proposal != null) {
        if (panel == PlannerPanel.Generate && proposal != null) closePanel()
    }

    val showSheet = when {
        panel == PlannerPanel.Discard -> true
        state.proposalConflict -> false
        selecting -> selected != null
        panel != PlannerPanel.None || activeCopy -> true
        selectedSlot != null -> true
        selected != null -> canPlan(selected.date)
        else -> false
    }
    val canBack = !showSheet && (selecting || inDraft || showTodayButton)
    BackHandler(canBack || busy) {
        if (!busy) when {
            placement != null -> cancelPlacement()
            panel == PlannerPanel.Move -> closePanel()
            inDraft -> panel = PlannerPanel.Discard
            else -> goToToday()
        }
    }

    val title = when {
        placement != null -> "Planifier"
        panel == PlannerPanel.Move -> "Déplacer un repas"
        inDraft -> "Proposition"
        else -> ""
    }
    val subtitle = when {
        inDraft -> "Brouillon privé · " + periodLabel(proposal!!.from, proposal.to)
        else -> null
    }
    val generationStart = LocalDate.ofEpochDay(fromEpoch)
    val generationCount = duration * if (plan == MealPlan.LUNCH_DINNER) 2 else 1
    val headerAction: (() -> Unit)? = when {
        placement != null -> ::cancelPlacement
        panel == PlannerPanel.Move -> ::closePanel
        inDraft -> { { panel = PlannerPanel.Discard } }
        else -> null
    }
    val error = week.error ?: state.error
    val mealBeingPlaced = placement?.title ?: source?.let { mealTitle(it) }

    Column(Modifier.fillMaxSize().background(Sunny.Cream).statusBarsPadding().imePadding()) {
        if (isHome) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp)) {
                BrandTitle(logoSize = 38.dp, textSize = 32)
            }
        } else {
            headerAction?.let { back ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                    AgendaAction("Retour", back, enabled = !busy, icon = Icons.AutoMirrored.Filled.ArrowBack)
                }
            }
            AgendaHeader(title, subtitle)
        }
        if (selecting && mealBeingPlaced != null) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                PlannerNotice(if (placement != null) "$mealBeingPlaced · Choisissez un midi ou un soir"
                    else "$mealBeingPlaced · Choisissez la nouvelle place")
            }
        }
        if (state.proposalConflict) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                PlannerNotice(error ?: "Le menu partagé a changé.", error = true)
                AgendaAction("Recalculer", actions.onRebase, primary = true, enabled = !busy)
            }
        } else if (error != null && dates.isNotEmpty()) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                PlannerNotice(if (week.error != null) "Dernier menu chargé · $error" else error, error = true)
                if (week.error != null) AgendaAction("Actualiser", actions.onRefresh, enabled = !busy)
            }
        }
        if (state.undoMove != null && !inDraft) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp).background(Sunny.ButterLight, plannerShape).padding(start = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Repas déplacé", color = Sunny.Ink, fontWeight = FontWeight.SemiBold)
                AgendaAction("Annuler", actions.onUndoMove, enabled = !busy)
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            PullToRefreshBox(
                isRefreshing = state.refreshing || (week.loading && dates.isNotEmpty()),
                onRefresh = { if (!busy && !inDraft) actions.onRefresh() },
                modifier = Modifier.fillMaxSize(),
            ) {
                if (dates.isNotEmpty()) {
                    PlannerTimeline(
                        modifier = Modifier.fillMaxSize(),
                        dates = dates,
                        today = today,
                        slots = displayed,
                        listState = list,
                        selected = selected,
                        copyChanges = copyChanges,
                        isReadOnly = ::readOnly,
                        isEnabled = { moment, slot ->
                            !busy && when {
                                placement != null -> canPlan(moment.date)
                                panel == PlannerPanel.Move -> canMove(moment)
                                activeCopy || state.proposalConflict -> false
                                else -> slot != null || canPlan(moment.date)
                            }
                        },
                        canDrag = { moment, _ -> !busy && !showSheet && !selecting && !activeCopy && editable(moment.date) },
                        canDrop = { moment -> !busy && !activeCopy && canMove(moment) },
                        onSelect = { moment, slot ->
                            if (isHome && slot?.recipe != null) actions.onOpenRecipe(slot, true)
                            else select(moment)
                        },
                        onKeyboardMove = ::startMove,
                        onDrop = { slot, moment -> startMove(slot); select(moment) },
                        onBoundary = { before, retry ->
                            if (before) actions.onLoadBefore(retry) else actions.onLoadAfter(retry)
                        },
                        onVisibleDay = { date ->
                            visibleEpoch = date.toEpochDay()
                            actions.onVisibleDay(date)
                        },
                        loadingBefore = week.loadingBefore,
                        loadingAfter = week.loadingAfter,
                        beforeError = week.beforeError,
                        afterError = week.afterError,
                        isDateLoaded = ::loadedDay,
                        boundaryEnabled = loaded.isNotEmpty() && !busy && !state.loading && !week.loading,
                        largeCards = isHome,
                        hasMealOptions = { moment, _ -> !activeCopy && editable(moment.date) },
                        onMealOptions = { moment, _ -> select(moment) },
                        bottomPadding = if (isHome) 80.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() else 18.dp,
                    )
                } else {
                    Column(Modifier.padding(12.dp)) {
                        if (state.loading || week.loading) PlannerLoading()
                        else PlannerCard("Repas indisponibles") {
                            PlannerSmall(error ?: "Impossible de charger le menu.")
                            AgendaAction("Réessayer", actions.onRefresh, enabled = !busy)
                        }
                    }
                }
            }
            if (isHome) {
                if (showTodayButton) {
                    PlannerTodayButton(
                        onClick = ::goToToday,
                        modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
                            .padding(horizontal = 80.dp, vertical = 16.dp),
                    )
                }
                PlannerHomeMenu(
                    expanded = homeMenuOpen, onExpanded = { homeMenuOpen = it },
                    canWrite = user.canWrite, canPlan = !busy && dates.isNotEmpty(),
                    unreadNotifications = unreadNotifications,
                    onRecipes = actions.onRecipes, onAccount = actions.onAccount, onNotifications = actions.onNotifications,
                    onGenerate = { select(null); panel = PlannerPanel.Generate }, onCopy = ::startCopy,
                    modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding()
                        .padding(end = 16.dp, bottom = 12.dp),
                )
            }
        }
        if (inDraft && !selecting && !state.proposalConflict) {
            PlannerFooter(if (state.working == "confirm") "Publication…" else "Publier", actions.onPublish, enabled = !busy)
        }
        if (!isHome) Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
    }

    if (showSheet) {
        val saving by rememberUpdatedState(busy)
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true,
            confirmValueChange = { it != SheetValue.Hidden || !saving })
        ModalBottomSheet(
            onDismissRequest = ::dismissSheet,
            sheetState = sheetState,
            containerColor = Sunny.Cream,
            contentColor = Sunny.Ink,
        ) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp).padding(bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                when {
                    panel == PlannerPanel.Discard -> PlannerCard("Quitter la proposition ?") {
                        PlannerSmall("Vos modifications du brouillon seront perdues.")
                        AgendaAction("Quitter sans publier", {
                            closePanel()
                            actions.onDiscard()
                        }, primary = true, warning = true, enabled = !busy)
                        AgendaAction("Poursuivre la relecture", ::closePanel, enabled = !busy)
                    }
                    placement != null && selected != null -> {
                        PlannerConfirmation(selected, selectedSlot, placement.title, inDraft)
                        PlannerFooter(if (selectedSlot != null) "Remplacer le repas" else "Planifier le repas",
                            { actions.onPlan(selected.date, selected.meal, selectedSlot != null) },
                            enabled = !busy && canPlan(selected.date), warning = selectedSlot != null)
                    }
                    panel == PlannerPanel.Move && selected != null && source != null -> {
                        PlannerConfirmation(selected, selectedSlot, null, inDraft, moving = true)
                        PlannerFooter("Déplacer ici", {
                            actions.onMove(source, selected.date, selected.meal)
                            closePanel()
                        }, enabled = !busy && canMove(selected))
                    }
                    panel == PlannerPanel.Picker -> PlannerRecipePicker(recipes, selected, busy, actions.onSearchRecipes) {
                        pickerChoice = true
                        actions.onChooseRecipe(it)
                    }
                    panel == PlannerPanel.Generate -> {
                        PlannerGeneration(generationStart, today, duration, plan, user.people, generationCount,
                            visibleSlots, state.menu?.slots.orEmpty(), info, busy,
                            onStart = { fromEpoch = it.toEpochDay() }, onDuration = { duration = it },
                            onPlan = { plan = it }, onPeople = actions.onSetPeople)
                        PlannerFooter(if (state.generating) "Création en cours…" else "Créer la proposition",
                            { actions.onGenerate(generationStart, generationCount, plan) }, enabled = info?.enoughRecipes == true && !busy)
                    }
                    activeCopy -> {
                        PlannerCopyPanel(targetWeek, sourceWeek, copy, busy, actions.onCopyReplace,
                            onSource = { copySourceEpoch = it.toEpochDay() })
                        if (copy.preview == null) {
                            PlannerFooter("Vérifier la copie", { actions.onCopyPreview(sourceWeek, targetWeek) }, enabled = !busy)
                        } else {
                            PlannerFooter("Appliquer à la semaine", { applyingCopy = true; actions.onApplyCopy() },
                                enabled = !busy && copied != null)
                        }
                    }
                    panel == PlannerPanel.Manual && selected != null -> {
                        PlannerCard("Repas libre") {
                            PlannerSmall(mealMoment(selected.date, selected.meal))
                            OutlinedTextField(freeTitle, { if (it.codePointCount(0, it.length) <= 100) freeTitle = it },
                                label = { Text("Intitulé") }, enabled = !busy, singleLine = true,
                                modifier = Modifier.fillMaxWidth(), shape = plannerShape)
                        }
                        PlannerFooter("Enregistrer le repas", {
                            if (selectedSlot != null) actions.onRename(selectedSlot, freeTitle.trim())
                            else actions.onAddManual(selected.date, selected.meal, freeTitle.trim())
                            closePanel()
                        }, enabled = !busy && editable(selected.date) && freeTitle.isNotBlank())
                    }
                    panel == PlannerPanel.People && selectedSlot != null -> {
                        PlannerCard("Nombre de personnes") {
                            Text(mealTitle(selectedSlot), color = Sunny.Ink, fontWeight = FontWeight.SemiBold)
                            PlannerStepper(people, 1, 20, "Une personne", { people = it }, !busy)
                        }
                        PlannerFooter("Enregistrer les portions", {
                            actions.onMealPeople(selectedSlot, people)
                            closePanel()
                        }, enabled = !busy && editable(selectedSlot.date))
                    }
                    selected != null && selectedSlot != null -> PlannerMealPanel(
                        selected, selectedSlot,
                        allowWrite = editable(selected.date),
                        allowRecipeChoice = canPlan(selected.date),
                        allowMove = !inDraft || proposal!!.slots.size > 1,
                        enabled = !busy, actions = actions,
                        onOpen = {
                            closePanel()
                            actions.onOpenRecipe(selectedSlot, isHome || readOnly(selected.date))
                        },
                        onPick = ::openPicker, onMove = { startMove(selectedSlot) },
                        onManual = { freeTitle = selectedSlot.title.orEmpty(); panel = PlannerPanel.Manual },
                        onPeople = { people = (selectedSlot.people ?: user.people).coerceIn(1, 20); panel = PlannerPanel.People },
                        onRemove = { closePanel(); actions.onRemove(selectedSlot) },
                    )
                    selected != null -> PlannerCard("Créneau libre") {
                        PlannerSmall(mealMoment(selected.date, selected.meal))
                        AgendaAction("Choisir une recette", ::openPicker, primary = true, enabled = !busy)
                        if (inDraft) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            AgendaAction("Autre idée", { actions.onAddRandom(selected.date, selected.meal); closePanel() }, enabled = !busy)
                            AgendaAction("Repas libre", { freeTitle = ""; panel = PlannerPanel.Manual }, enabled = !busy)
                        }
                    }
                }
                if (state.error != null && !state.proposalConflict) PlannerNotice(state.error, error = true)
            }
        }
    }
}

@Composable
private fun PlannerTodayButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick, modifier = modifier.heightIn(min = 48.dp), shape = Shapes.Control,
        color = Sunny.Card, contentColor = Sunny.Ink, shadowElevation = 5.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, Sunny.Track),
    ) {
        Row(
            Modifier.padding(horizontal = 13.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(Icons.Filled.DateRange, null, Modifier.size(18.dp))
            Text("Aujourd’hui", fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun PlannerHomeMenu(
    expanded: Boolean,
    onExpanded: (Boolean) -> Unit,
    canWrite: Boolean,
    canPlan: Boolean,
    unreadNotifications: Int,
    onRecipes: () -> Unit,
    onAccount: () -> Unit,
    onNotifications: () -> Unit,
    onGenerate: () -> Unit,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
) {
    fun choose(action: () -> Unit) {
        onExpanded(false)
        action()
    }

    Box(modifier) {
        FloatingActionButton(
            onClick = { onExpanded(true) },
            containerColor = Sunny.Butter, contentColor = Sunny.Ink, shape = Shapes.Control,
        ) {
            Icon(Icons.Filled.Menu, contentDescription = "Ouvrir le menu")
        }
        DropdownMenu(
            expanded = expanded, onDismissRequest = { onExpanded(false) }, containerColor = Sunny.Card,
            shape = Shapes.Control,
        ) {
            DropdownMenuItem(text = { Text("Recettes") }, onClick = { choose(onRecipes) },
                leadingIcon = { Icon(Icons.Filled.Search, null) })
            if (canWrite) {
                DropdownMenuItem(text = { Text("Composer un menu") }, onClick = { choose(onGenerate) },
                    leadingIcon = { Icon(Icons.Filled.Add, null) }, enabled = canPlan)
                DropdownMenuItem(text = { Text("Copier une semaine") }, onClick = { choose(onCopy) },
                    leadingIcon = { Icon(Icons.Filled.DateRange, null) }, enabled = canPlan)
                DropdownMenuItem(
                    text = { Text(if (unreadNotifications > 0) "Notifications ($unreadNotifications)" else "Notifications") },
                    onClick = { choose(onNotifications) },
                    leadingIcon = { Icon(Icons.Filled.Notifications, null) },
                )
                HorizontalDivider(color = Sunny.Track)
            }
            DropdownMenuItem(text = { Text("Compte") }, onClick = { choose(onAccount) },
                leadingIcon = { Icon(Icons.Filled.Person, null) })
        }
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
private fun PlannerFooter(label: String, onClick: () -> Unit, enabled: Boolean, warning: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().background(Sunny.Cream)
            .padding(start = 10.dp, end = 10.dp, top = 3.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        AgendaAction(label, onClick, primary = true, warning = warning, enabled = enabled)
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
    allowRecipeChoice: Boolean,
    allowMove: Boolean,
    enabled: Boolean,
    actions: PlannerActions,
    onOpen: () -> Unit,
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
        if (allowWrite) FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (allowRecipeChoice) AgendaAction("Remplacer", onPick, enabled = enabled)
            AgendaAction("Autre idée", { actions.onSwap(slot) }, enabled = enabled)
            AgendaAction(if (slot.manual) "Modifier l’intitulé" else "Repas libre", onManual, enabled = enabled)
            AgendaAction("Portions", onPeople, enabled = enabled)
            AgendaAction("Retirer", onRemove, enabled = enabled)
        }
        if (allowWrite && allowMove) PlannerSmall("Un appui long sur la carte permet aussi de la déplacer.")
        if (slot.recipe != null || allowWrite && allowMove) Row(
            Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
        ) {
            if (allowWrite && allowMove) AgendaAction("Déplacer", onMove, primary = slot.recipe == null, enabled = enabled)
            if (slot.recipe != null) AgendaAction("Voir la recette", onOpen, primary = true, enabled = enabled)
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
                AgendaAction("Réessayer", { onSearch(state.query) }, enabled = !busy)
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
