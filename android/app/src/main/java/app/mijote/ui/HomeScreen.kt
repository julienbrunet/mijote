package app.mijote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Add
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mijote.data.Menu
import app.mijote.data.MenuSlot
import app.mijote.data.User
import java.time.LocalDate

enum class Tab(val label: String) { MENUS("Menus"), RECIPES("Recettes"), GENERATE("Idées de repas") }

enum class DishType(val label: String, val colors: Pair<Color, Color>) {
    MEAT("Viande", DishColors.meat), FISH("Poisson", DishColors.fish), VEGETARIAN("Végé", DishColors.veggie)
}

/** A planned meal as shown on the home screen. [picture] stands in for the recipe image. */
data class MealUi(
    val slot: String, val title: String, val dish: DishType?, val prepMinutes: Int?, val picture: String, val locked: Boolean,
    val id: String = "", val recipeId: String? = null, val image: String? = null,
    /** A manual meal: a free text ("Commander une pizza"), not a recipe. */
    val manual: Boolean = false,
    /** Covers of the meal (its own number, or the user's preference for older meals). */
    val people: Int? = null,
)

/** One day of the menu. [missing] lists the meals ("lunch", "dinner") that can still be added (review screen). */
data class DayUi(
    val label: String, val date: String, val meals: List<MealUi>,
    val day: LocalDate? = null, val missing: List<String> = emptyList(),
    val isToday: Boolean = false,
)

/** Signed-in shell on the Butter background: white header, the days as a list, corner tabs. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    user: User,
    onLogout: () -> Unit,
    period: String? = null,
    days: List<DayUi> = emptyList(),
    generatedCount: Int = 0,
    onNewWeek: (() -> Unit)? = null,
    onPreferences: () -> Unit = {},
    working: String? = null,
    error: String? = null,
    onOpenMeal: (MealUi) -> Unit = {},
    onSwapMeal: (MealUi) -> Unit = {},
    onRemoveMeal: (MealUi) -> Unit = {},
    onRenameMeal: (MealUi, String) -> Unit = { _, _ -> },
    tab: Tab = Tab.MENUS,
    onTab: (Tab) -> Unit = {},
    refreshing: Boolean = false,
    /** Adds a recipe: a round "+" left of the navigation on the Recipes tab (members only). */
    onAddRecipe: (() -> Unit)? = null,
    /** Opens the recipe filters: a round funnel right of the navigation on the Recipes tab. */
    onFilterRecipes: (() -> Unit)? = null,
    filterCount: Int = 0,
    onRefresh: () -> Unit = {},
    onNotifications: (() -> Unit)? = null,
    unreadNotifications: Int = 0,
    recipes: @Composable () -> Unit = {},
    generate: @Composable () -> Unit = {},
) {
    var editing by remember { mutableStateOf<MealUi?>(null) }
    val canEdit = user.role != "readonly"
    val availableTabs = if (user.role == "readonly") listOf(Tab.MENUS, Tab.RECIPES) else Tab.entries
    MealActionsDialog(editing, onDismiss = { editing = null }, onOpen = onOpenMeal, onSwap = onSwapMeal, onRemove = onRemoveMeal,
        onRename = onRenameMeal, canEdit = canEdit)
    if ((tab == Tab.GENERATE && canEdit) || tab == Tab.RECIPES) {
        Box(Modifier.fillMaxSize()) {
            if (tab == Tab.RECIPES) recipes() else generate()
            BottomBar(tab, availableTabs, Modifier.align(Alignment.BottomEnd), onTab,
                onAdd = onAddRecipe.takeIf { tab == Tab.RECIPES && canEdit },
                onFilter = onFilterRecipes.takeIf { tab == Tab.RECIPES }, filterCount = filterCount)
        }
        return
    }
    // Pull down to fetch what other members changed (no real-time, FR-SHR-02).
    PullToRefreshBox(refreshing, onRefresh, Modifier.fillMaxSize().background(Sunny.Butter)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            HomeHeader(tab.label.takeIf { tab != Tab.MENUS }, period.takeIf { tab == Tab.MENUS }, onPreferences)
            if (tab == Tab.MENUS && days.isNotEmpty()) {
                Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 120.dp)) {
                    ErrorText(error)
                    days.filter { it.isToday }.forEachIndexed { i, day ->
                        DaySection(day, first = i == 0, working = working, onOpen = onOpenMeal, onEdit = { editing = it }, canEdit = canEdit)
                    }
                    val otherDays = days.filterNot { it.isToday }
                    if (otherDays.any { it.meals.isNotEmpty() }) {
                        CompactMealList(otherDays, working = working, onOpen = onOpenMeal, onEdit = { editing = it }, canEdit = canEdit)
                    }
                }
            } else {
                Text(
                    if (canEdit) "Aucun repas prévu : ouvrez « Idées de repas » pour créer le menu, ou planifiez une recette depuis « Recettes »."
                    else "Aucun repas prévu pour le moment.",
                    color = Sunny.InkTitle, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 120.dp, start = 24.dp, end = 24.dp),
                )
            }
        }
        BottomBar(tab, availableTabs, Modifier.align(Alignment.BottomEnd), onTab,
            onNotifications = onNotifications.takeIf { canEdit }, unreadNotifications = unreadNotifications)
    }
}

/**
 * White header shared by the main tabs: logo tile and wordmark on the Menus tab, a plain [title]
 * elsewhere, [subtitle] below, then optional [extra] content (search field…). The user button is a
 * plain Butter circle at the top right.
 */
@Composable
fun HomeHeader(
    title: String?, subtitle: String?, onPreferences: () -> Unit,
    extra: @Composable ColumnScope.() -> Unit = {},
) {
    Box(
        Modifier.fillMaxWidth().zIndexShadow()
            .background(Sunny.Card, RoundedCornerShape(bottomStart = 40.dp, bottomEnd = 40.dp)),
    ) {
        // Profile button: a plain Butter circle on the white header.
        Box(
            Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 20.dp, end = 20.dp).size(48.dp)
                .background(Sunny.Butter, CircleShape).clip(CircleShape),
            contentAlignment = Alignment.Center,
        ) { UserButton(onPreferences) }
        Column(Modifier.statusBarsPadding().padding(start = 22.dp, end = 22.dp, top = 22.dp, bottom = 26.dp)) {
            Column(Modifier.padding(end = 80.dp)) {
                if (title != null) ScreenTitle(title, subtitle, size = 32)
                else {
                    BrandTitle(logoSize = 48.dp, textSize = 44)
                    subtitle?.let { Text(it, fontSize = 15.sp, color = Sunny.InkTitle, modifier = Modifier.padding(start = 2.dp, top = 12.dp)) }
                }
            }
            extra()
        }
    }
}

private fun Modifier.zIndexShadow() =
    shadow(6.dp, RoundedCornerShape(bottomStart = 40.dp, bottomEnd = 40.dp), ambientColor = Sunny.PeachDeep, spotColor = Sunny.PeachDeep)

/** One day: its title and date on the Butter background, then the meal cards. */
@Composable
fun DaySection(
    day: DayUi, first: Boolean = false, working: String? = null,
    onOpen: (MealUi) -> Unit, onEdit: (MealUi) -> Unit, canEdit: Boolean = true, onPeople: ((MealUi) -> Unit)? = null, extra: @Composable () -> Unit = {},
) {
    Column {
        Row(Modifier.fillMaxWidth().padding(top = if (first) 18.dp else 34.dp, bottom = 14.dp, start = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(day.label, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Sunny.Ink, modifier = Modifier.weight(1f))
            Text(day.date.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp,
                color = Sunny.Ink.copy(alpha = 0.6f))
        }
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            day.meals.forEach { m ->
                MealCard(m, Sunny.Peach, busy = working == m.id, onOpen = { onOpen(m) }, onEdit = { onEdit(m) }, canEdit = canEdit,
                    onPeople = onPeople?.let { { it(m) } })
            }
            extra()
        }
    }
}

/** A flat list of compact meals: the date belongs to each row, with no day heading. */
@Composable
private fun CompactMealList(
    days: List<DayUi>, working: String?,
    onOpen: (MealUi) -> Unit, onEdit: (MealUi) -> Unit, canEdit: Boolean,
) {
    Column(Modifier.padding(top = 18.dp).fillMaxWidth()
        .shadow(2.dp, Shapes.Card).background(Sunny.Card, Shapes.Card).clip(Shapes.Card)) {
        days.flatMap { day -> day.meals.map { day to it } }.forEachIndexed { index, (day, meal) ->
            if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = Sunny.Butter, thickness = 1.dp)
            Row(
                Modifier.fillMaxWidth().heightIn(min = 64.dp)
                    .clickable(enabled = meal.recipeId != null || (meal.manual && canEdit),
                        onClick = { if (meal.manual) onEdit(meal) else onOpen(meal) })
                    .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val dateParts = day.day?.let {
                    it.dayOfMonth.toString() to it.format(java.time.format.DateTimeFormatter.ofPattern("MMM", java.util.Locale.FRENCH)).trimEnd('.')
                } ?: (day.date.substringBefore(' ') to day.date.substringAfter(' ').trimEnd('.'))
                Column(
                    Modifier.padding(end = 12.dp).width(46.dp).heightIn(min = 48.dp)
                        .background(Sunny.ButterLight, RoundedCornerShape(12.dp)).padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(dateParts.first, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Sunny.Ink, lineHeight = 22.sp)
                    Text(dateParts.second, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = Sunny.Ink.copy(alpha = 0.65f))
                }
                Column(Modifier.weight(1f).padding(end = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${day.label} · ${meal.slot}", fontSize = 12.sp, fontWeight = FontWeight.Medium,
                            color = Sunny.Ink.copy(alpha = 0.65f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false))
                        if (meal.locked && !meal.manual) Icon(Icons.Filled.Lock,
                            contentDescription = "Conservé si le menu est généré à nouveau", tint = Sunny.InkSoft,
                            modifier = Modifier.padding(start = 5.dp).size(11.dp))
                    }
                    Text(meal.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Sunny.Ink,
                        maxLines = 3, overflow = TextOverflow.Ellipsis, lineHeight = 20.sp,
                        modifier = Modifier.padding(top = 4.dp))
                }
                if (canEdit) IconButton(onClick = { onEdit(meal) }, modifier = Modifier.size(48.dp)) {
                    Box(Modifier.size(32.dp).background(Sunny.Peach, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                        if (working == meal.id) CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                        else Icon(Icons.Filled.Edit, contentDescription = "Modifier : ${meal.title}", tint = Color.White,
                            modifier = Modifier.size(17.dp))
                    }
                }
            }
        }
    }
}

/** A planned meal: tap the card for the recipe, the corner pencil to change it. */
@Composable
fun MealCard(
    meal: MealUi, accent: Color, busy: Boolean, onOpen: () -> Unit, onEdit: () -> Unit, canEdit: Boolean = true,
    onPeople: (() -> Unit)? = null,
) {
    Box(
        Modifier.fillMaxWidth().height(104.dp)
            .shadow(4.dp, Shapes.Card, ambientColor = Color.Black, spotColor = Color.Black)
            .background(Sunny.Card, Shapes.Card).clip(Shapes.Card)
            .clickable(enabled = meal.recipeId != null || (meal.manual && canEdit), onClick = if (meal.manual) onEdit else onOpen),
    ) {
        Row(Modifier.fillMaxSize().padding(start = 12.dp, end = if (canEdit) 52.dp else 16.dp), verticalAlignment = Alignment.CenterVertically) {
            RecipeThumb(meal.image, meal.picture, Modifier.size(76.dp).clip(RoundedCornerShape(16.dp)), emojiSize = 40.sp)
            Column(Modifier.padding(start = 12.dp)) {
                Text(meal.slot.uppercase(), fontSize = 10.sp, letterSpacing = 1.sp, fontWeight = FontWeight.SemiBold, color = Sunny.InkSoft)
                Text(meal.title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Sunny.Ink, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, lineHeight = 19.sp, modifier = Modifier.padding(vertical = 3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    meal.dish?.let { DishTag(it.label, it.colors) }
                    meal.prepMinutes?.let {
                        Text("$it min", fontSize = 12.sp, color = Sunny.InkSoft, modifier = Modifier.padding(start = 8.dp))
                    }
                    // Covers of a recipe meal: a discreet pill, tappable where it can be changed (review screen).
                    if (meal.recipeId != null) meal.people?.let { n ->
                        Text("$n pers.", fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                            color = if (onPeople != null) Sunny.PeachDeep else Sunny.InkSoft,
                            modifier = Modifier.padding(start = 8.dp).clip(RoundedCornerShape(8.dp))
                                .background(if (onPeople != null) Sunny.PeachLight else Color.Transparent)
                                .then(if (onPeople != null) Modifier.clickable(onClickLabel = "Nombre de personnes", onClick = onPeople) else Modifier)
                                .padding(horizontal = if (onPeople != null) 8.dp else 0.dp, vertical = 3.dp))
                    }
                }
            }
        }
        // Corner action glued to the card's bottom-right corner, as the basket buttons of the reference.
        if (canEdit) {
            Box(
                Modifier.align(Alignment.BottomEnd).size(46.dp)
                    .background(accent, RoundedCornerShape(topStart = 16.dp, bottomEnd = 22.dp))
                    .clickable(onClick = onEdit),
                contentAlignment = Alignment.Center,
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                else Icon(Icons.Filled.Edit, contentDescription = "Modifier", tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }
        if (meal.locked && !meal.manual) Icon(Icons.Filled.Lock, contentDescription = "Conservé si le menu est généré à nouveau", tint = Sunny.InkSoft,
            modifier = Modifier.align(Alignment.TopEnd).padding(10.dp).size(14.dp))
    }
}

/**
 * What can be done to a meal: see the recipe, take another one, turn it into a manual meal (or
 * rename a manual meal), remove it.
 */
@Composable
fun MealActionsDialog(
    meal: MealUi?, onDismiss: () -> Unit,
    onOpen: (MealUi) -> Unit, onSwap: (MealUi) -> Unit, onRemove: (MealUi) -> Unit,
    onRename: (MealUi, String) -> Unit = { _, _ -> },
    canEdit: Boolean = true,
) {
    var naming by remember { mutableStateOf<MealUi?>(null) }
    naming?.let { m ->
        MealTitleDialog(if (m.manual) m.title else "", onDismiss = { naming = null }) { onRename(m, it); naming = null }
    }
    if (meal == null) return
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Sunny.Card,
        title = { Text(meal.title, fontWeight = FontWeight.Bold, color = Sunny.Ink, fontSize = 18.sp) },
        text = {
            Column {
                if (meal.recipeId != null) DialogAction("Voir la recette") { onDismiss(); onOpen(meal) }
                if (canEdit) {
                    if (meal.manual) DialogAction("Modifier l'intitulé") { onDismiss(); naming = meal }
                    DialogAction(if (meal.manual) "Remplacer par une recette au hasard" else "Changer de recette") { onDismiss(); onSwap(meal) }
                    if (!meal.manual) DialogAction("Remplacer par un repas libre") { onDismiss(); naming = meal }
                    DialogAction("Retirer ce repas", danger = true) { onDismiss(); onRemove(meal) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fermer", color = Sunny.Slate) } },
    )
}

@Composable
private fun DialogAction(text: String, enabled: Boolean = true, danger: Boolean = false, onClick: () -> Unit) {
    Text(text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
        color = when { !enabled -> Sunny.InkSoft; danger -> Sunny.Error; else -> Sunny.Ink },
        modifier = Modifier.fillMaxWidth().clip(Shapes.Control).clickable(enabled = enabled, onClick = onClick).padding(vertical = 14.dp, horizontal = 4.dp))
}

@Composable
private fun UserButton(onPreferences: () -> Unit) {
    IconButton(onClick = onPreferences, modifier = Modifier.size(48.dp)) {
        Icon(Icons.Filled.Person, contentDescription = "Préférences", tint = Sunny.Ink)
    }
}

/**
 * Floating pill navigation bar centred at the bottom of the screen (variant B).
 * A single PeachDeep pill holds the icons only (no labels); the active one is highlighted
 * with a white semi-transparent circle. Labels stay as content descriptions.
 */
@Composable
internal fun BottomBar(
    selected: Tab, tabs: List<Tab> = Tab.entries, modifier: Modifier = Modifier, onSelect: (Tab) -> Unit,
    onAdd: (() -> Unit)? = null,
    onFilter: (() -> Unit)? = null,
    filterCount: Int = 0,
    onNotifications: (() -> Unit)? = null,
    unreadNotifications: Int = 0,
) {
    val icons = mapOf(Tab.MENUS to Icons.Filled.Home, Tab.RECIPES to Icons.Filled.Search,
        Tab.GENERATE to ImageVector.vectorResource(app.mijote.R.drawable.ic_meal_idea))
    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Box(modifier.fillMaxWidth().padding(bottom = 20.dp + navInset)) {
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                // Dark shadow so that the bar stands out from the list scrolling under it.
                .shadow(20.dp, RoundedCornerShape(40.dp), ambientColor = Color.Black, spotColor = Color.Black)
                .background(Sunny.PeachDeep, RoundedCornerShape(40.dp))
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEach { tab ->
                val on = tab == selected
                Row(
                    Modifier
                        .clip(RoundedCornerShape(32.dp))
                        .background(if (on) Color.White.copy(alpha = 0.22f) else Color.Transparent)
                        .clickable { onSelect(tab) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        icons.getValue(tab), contentDescription = tab.label,
                        tint = Color.White,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }
        // Round buttons on each side of the bar, as high as it, with icons the size of the tab icons:
        // "+" on the left, the filters (with the number of active ones) on the right.
        if (onNotifications != null) Box(Modifier.align(Alignment.CenterStart).padding(start = 20.dp)) {
            RoundBarButton("Notifications : $unreadNotifications non lues", Modifier, onNotifications) {
                Icon(Icons.Filled.Notifications, contentDescription = "Notifications : $unreadNotifications non lues", tint = Sunny.PeachDeep, modifier = Modifier.size(24.dp))
            }
            if (unreadNotifications > 0) Box(Modifier.align(Alignment.TopEnd).heightIn(min = 22.dp).widthIn(min = 22.dp)
                .background(Sunny.PeachDeep, CircleShape).border(2.dp, Sunny.Card, CircleShape).padding(horizontal = 4.dp), contentAlignment = Alignment.Center) {
                Text(if (unreadNotifications > 99) "99+" else "$unreadNotifications", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
        }
        if (onAdd != null) RoundBarButton("Nouvelle recette", Modifier.align(Alignment.CenterStart).padding(start = 20.dp), onAdd) {
            Icon(Icons.Filled.Add, contentDescription = "Nouvelle recette", tint = Sunny.PeachDeep, modifier = Modifier.size(24.dp))
        }
        if (onFilter != null) Box(Modifier.align(Alignment.CenterEnd).padding(end = 20.dp)) {
            RoundBarButton("Filtres", Modifier, onFilter) {
                Icon(ImageVector.vectorResource(app.mijote.R.drawable.ic_filter), contentDescription = "Filtres",
                    tint = Sunny.PeachDeep, modifier = Modifier.size(24.dp))
            }
            if (filterCount > 0) Box(
                Modifier.align(Alignment.TopEnd).size(22.dp).background(Sunny.PeachDeep, CircleShape).border(2.dp, Sunny.Card, CircleShape),
                contentAlignment = Alignment.Center,
            ) { Text("$filterCount", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White) }
        }
    }
}

/** Groups the planned meals by day for the home screen. */
fun menuDays(menu: Menu, people: Int = 1): List<DayUi> = menu.slots.groupBy { it.date }.toSortedMap().map { (date, slots) ->
    DayUi(dayLabel(date, menu.today), shortDate(date), slots.sortedBy { it.meal != "lunch" }.map { mealUi(it, people) }, day = date, isToday = date == menu.today)
}

/** Every day from [from] to [to], also the empty ones, with the meals that can still be added. */
fun reviewDays(menu: Menu, from: LocalDate, to: LocalDate, people: Int = 1): List<DayUi> {
    val byDate = menu.slots.groupBy { it.date }
    return generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.map { d ->
        val slots = byDate[d].orEmpty().sortedBy { it.meal != "lunch" }
        DayUi(dayLabel(d, menu.today), shortDate(d), slots.map { mealUi(it, people) }, day = d,
            missing = listOf("lunch", "dinner").filter { m -> slots.none { it.meal == m } })
    }.toList()
}

/** "2 → 8 novembre · 11 repas", null without planned meals. */
fun menuPeriod(menu: Menu): String? {
    val dates = menu.slots.map { it.date }
    if (dates.isEmpty()) return null
    return "${periodLabel(dates.min(), dates.max())} · ${menu.slots.size} repas"
}

private fun mealUi(s: MenuSlot, defaultPeople: Int): MealUi {
    val dish = when (s.recipe?.dishType) {
        "meat" -> DishType.MEAT
        "fish" -> DishType.FISH
        "vegetarian" -> DishType.VEGETARIAN
        else -> null
    }
    val picture = when {
        s.manual -> "📝"
        else -> when (dish) { DishType.MEAT -> "🍗"; DishType.FISH -> "🐟"; DishType.VEGETARIAN -> "🥗"; null -> "🍽️" }
    }
    return MealUi(if (s.meal == "lunch") "Midi" else "Soir", s.title ?: s.recipe?.title ?: "Aucune recette disponible",
        dish, s.recipe?.minutes, picture, s.locked, id = s.id, recipeId = s.recipe?.id, image = s.recipe?.image, manual = s.manual,
        people = s.people ?: defaultPeople)
}

@Composable
private fun RoundBarButton(label: String, modifier: Modifier, onClick: () -> Unit, icon: @Composable () -> Unit) {
    Box(
        modifier.size(60.dp)
            .shadow(20.dp, CircleShape, ambientColor = Color.Black, spotColor = Color.Black)
            .background(Sunny.Card, CircleShape).border(3.dp, Sunny.PeachDeep, CircleShape)
            .clip(CircleShape).clickable(onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { icon() }
}
