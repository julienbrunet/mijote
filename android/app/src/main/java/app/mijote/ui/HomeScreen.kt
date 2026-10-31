package app.mijote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mijote.data.Menu
import app.mijote.data.MenuSlot
import app.mijote.data.User
import java.time.LocalDate

enum class Tab(val label: String) { MENUS("Menus"), RECIPES("Recettes"), GENERATE("Générer") }

enum class DishType(val label: String, val colors: Pair<Color, Color>) {
    MEAT("Viande", DishColors.meat), FISH("Poisson", DishColors.fish), VEGETARIAN("Végé", DishColors.veggie)
}

/** A planned meal as shown on the home screen. [picture] stands in for the recipe image. */
data class MealUi(
    val slot: String, val title: String, val dish: DishType?, val prepMinutes: Int?, val picture: String, val locked: Boolean,
    val id: String = "", val recipeId: String? = null, val image: String? = null,
)

/** One day of the menu. [missing] lists the meals ("lunch", "dinner") that can still be added (review screen). */
data class DayUi(
    val label: String, val date: String, val meals: List<MealUi>,
    val day: LocalDate? = null, val missing: List<String> = emptyList(),
)

/** Signed-in shell on the Butter background: white header, the days as a list, corner tabs. */
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
    generate: @Composable () -> Unit = {},
) {
    var editing by remember { mutableStateOf<MealUi?>(null) }
    MealActionsDialog(editing, onDismiss = { editing = null }, onOpen = onOpenMeal, onSwap = onSwapMeal, onRemove = onRemoveMeal)
    var tab by rememberSaveable { mutableStateOf(Tab.MENUS) }
    // A successful generation brings the user back to the menus.
    LaunchedEffect(generatedCount) { if (generatedCount > 0) tab = Tab.MENUS }
    if (tab == Tab.GENERATE) {
        Box(Modifier.fillMaxSize()) {
            generate()
            BottomBar(tab, Modifier.align(Alignment.BottomEnd)) { tab = it }
        }
        return
    }
    Box(Modifier.fillMaxSize().background(Sunny.Butter)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Header(user, onLogout, tab.label.takeIf { tab != Tab.MENUS }, period.takeIf { tab == Tab.MENUS },
                onNewWeek = { onNewWeek?.invoke(); tab = Tab.GENERATE }, onPreferences = onPreferences)
            if (tab == Tab.MENUS && days.isNotEmpty()) {
                Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 120.dp)) {
                    ErrorText(error)
                    // No separator between days: the day titles and a wider gap group the meals.
                    days.forEachIndexed { i, day ->
                        DaySection(day, first = i == 0, working = working, onOpen = onOpenMeal, onEdit = { editing = it })
                    }
                }
            } else {
                Text(
                    when (tab) {
                        Tab.MENUS -> "Aucun repas prévu : touchez « Générer » pour créer le menu."
                        Tab.RECIPES -> "Les recettes arriveront ici."
                        Tab.GENERATE -> "La génération de menus arrivera ici."
                    },
                    color = Sunny.InkTitle, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 160.dp),
                )
            }
        }
        BottomBar(tab, Modifier.align(Alignment.BottomEnd)) { tab = it }
    }
}

@Composable
/** Big header: logo and wordmark on the Menus tab, a plain title on the other tabs. */
private fun Header(user: User, onLogout: () -> Unit, title: String?, period: String?, onNewWeek: () -> Unit, onPreferences: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().zIndexShadow()
            .background(Sunny.Card, RoundedCornerShape(bottomStart = 40.dp, bottomEnd = 40.dp)),
    ) {
        CornerBlock(Sunny.Butter, size = 96.dp, modifier = Modifier.align(Alignment.TopEnd)) {
            Box(Modifier.statusBarsPadding()) { UserButton(onPreferences) }
        }
        Column(Modifier.statusBarsPadding().padding(start = 22.dp, end = 24.dp, top = 44.dp, bottom = 30.dp)) {
            if (title != null) ScreenTitle(title, period, size = 32)
            else {
                BrandTitle()
                period?.let { Text(it, fontSize = 15.sp, color = Sunny.InkTitle, modifier = Modifier.padding(start = 4.dp, top = 14.dp)) }
            }
            if (title == null) {
                Spacer(Modifier.height(24.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(
                        Modifier.weight(1f).height(64.dp).clip(Shapes.Control).background(Sunny.Peach)
                            .clickable(onClick = onNewWeek).padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(36.dp).background(Color.White.copy(alpha = 0.3f), CircleShape), contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.Refresh, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                        }
                        Text("Nouvelle\nsemaine", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                            lineHeight = 17.sp, modifier = Modifier.padding(start = 12.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Box(
                        Modifier.size(64.dp).clip(Shapes.Control).background(Sunny.Slate),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Filled.Search, contentDescription = "Rechercher", tint = Color.White) }
                }
            }
        }
    }
}

private fun Modifier.zIndexShadow() =
    shadow(6.dp, RoundedCornerShape(bottomStart = 40.dp, bottomEnd = 40.dp), ambientColor = Sunny.PeachDeep, spotColor = Sunny.PeachDeep)

/** One day: its title and date on the Butter background, then the meal cards. */
@Composable
fun DaySection(
    day: DayUi, first: Boolean = false, working: String? = null,
    onOpen: (MealUi) -> Unit, onEdit: (MealUi) -> Unit, extra: @Composable () -> Unit = {},
) {
    Column {
        Row(Modifier.fillMaxWidth().padding(top = if (first) 18.dp else 34.dp, bottom = 14.dp, start = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(day.label, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Sunny.Ink, modifier = Modifier.weight(1f))
            Text(day.date.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp,
                color = Sunny.Ink.copy(alpha = 0.6f))
        }
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            day.meals.forEach { MealCard(it, Sunny.Peach, busy = working == it.id, onOpen = { onOpen(it) }, onEdit = { onEdit(it) }) }
            extra()
        }
    }
}

/** A planned meal: tap the card for the recipe, the corner pencil to change it. */
@Composable
fun MealCard(meal: MealUi, accent: Color, busy: Boolean, onOpen: () -> Unit, onEdit: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(104.dp)
            .shadow(4.dp, Shapes.Card, ambientColor = Color.Black, spotColor = Color.Black)
            .background(Sunny.Card, Shapes.Card).clip(Shapes.Card)
            .clickable(enabled = meal.recipeId != null, onClick = onOpen),
    ) {
        Row(Modifier.fillMaxSize().padding(start = 12.dp, end = 52.dp), verticalAlignment = Alignment.CenterVertically) {
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
                }
            }
        }
        // Corner action glued to the card's bottom-right corner, as the basket buttons of the reference.
        Box(
            Modifier.align(Alignment.BottomEnd).size(46.dp)
                .background(accent, RoundedCornerShape(topStart = 16.dp, bottomEnd = 22.dp))
                .clickable(onClick = onEdit),
            contentAlignment = Alignment.Center,
        ) {
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
            else Icon(Icons.Filled.Edit, contentDescription = "Modifier", tint = Color.White, modifier = Modifier.size(20.dp))
        }
        if (meal.locked) Icon(Icons.Filled.Lock, contentDescription = "Verrouillé", tint = Sunny.InkSoft,
            modifier = Modifier.align(Alignment.TopEnd).padding(10.dp).size(14.dp))
    }
}

/** What can be done to a meal: see the recipe, take another one, remove it. */
@Composable
fun MealActionsDialog(
    meal: MealUi?, onDismiss: () -> Unit,
    onOpen: (MealUi) -> Unit, onSwap: (MealUi) -> Unit, onRemove: (MealUi) -> Unit,
) {
    if (meal == null) return
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Sunny.Card,
        title = { Text(meal.title, fontWeight = FontWeight.Bold, color = Sunny.Ink, fontSize = 18.sp) },
        text = {
            Column {
                if (meal.recipeId != null) DialogAction("Voir la recette") { onDismiss(); onOpen(meal) }
                DialogAction(if (meal.locked) "Verrouillé : impossible de changer" else "Changer de recette", enabled = !meal.locked) {
                    onDismiss(); onSwap(meal)
                }
                DialogAction("Retirer ce repas", enabled = !meal.locked, danger = true) { onDismiss(); onRemove(meal) }
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
 * Bottom navigation as stacked peach tabs anchored to the bottom-right corner of the screen.
 * Tabs are laid out from the right; each one overlaps the tab on its left with its rounded
 * top-left corner. The selected tab widens to show its label.
 */
@Composable
private fun BottomBar(selected: Tab, modifier: Modifier, onSelect: (Tab) -> Unit) {
    val icons = mapOf(Tab.MENUS to Icons.Filled.Home, Tab.RECIPES to Icons.Filled.Search, Tab.GENERATE to Icons.Filled.Refresh)
    // Shades from the leftmost tab to the rightmost one, lighter towards the screen edge.
    val shades = listOf(Sunny.PeachDeep, Color(0xFFF89A7D), Color(0xFFFCAF94))
    val radius = 28.dp
    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val tabHeight = 56.dp + navInset
    fun width(t: Tab) = if (t == selected) 164.dp else 96.dp
    val tabs = Tab.entries
    Box(modifier.fillMaxWidth().height(tabHeight)) {
        // Drawn left to right so each tab covers the one on its left.
        tabs.forEachIndexed { i, t ->
            val on = t == selected
            val rightmost = i == tabs.lastIndex
            // Distance from the screen's right edge: widths of the tabs on the right, minus their overlap.
            val end = tabs.drop(i + 1).fold(0.dp) { acc, r -> acc + width(r) - radius }
            val shape = RoundedCornerShape(topStart = radius)
            Row(
                Modifier.align(Alignment.BottomEnd).padding(end = end)
                    .width(width(t)).fillMaxHeight()
                    .shadow(10.dp, shape, ambientColor = Sunny.PeachDeep, spotColor = Sunny.PeachDeep)
                    .background(shades[i], shape).clip(shape)
                    .clickable { onSelect(t) }
                    .padding(bottom = navInset, end = if (rightmost) 0.dp else radius),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icons.getValue(t), contentDescription = t.label,
                    tint = if (on) Color.White else Color.White.copy(alpha = 0.8f), modifier = Modifier.size(24.dp))
                if (on) Text(t.label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                    modifier = Modifier.padding(start = 10.dp))
            }
        }
    }
}

/** Groups the planned meals by day for the home screen. */
fun menuDays(menu: Menu): List<DayUi> = menu.slots.groupBy { it.date }.toSortedMap().map { (date, slots) ->
    DayUi(dayLabel(date, menu.today), shortDate(date), slots.sortedBy { it.meal != "lunch" }.map(::mealUi), day = date)
}

/** Every day from [from] to [to], also the empty ones, with the meals that can still be added. */
fun reviewDays(menu: Menu, from: LocalDate, to: LocalDate): List<DayUi> {
    val byDate = menu.slots.groupBy { it.date }
    return generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.map { d ->
        val slots = byDate[d].orEmpty().sortedBy { it.meal != "lunch" }
        DayUi(dayLabel(d, menu.today), shortDate(d), slots.map(::mealUi), day = d,
            missing = listOf("lunch", "dinner").filter { m -> slots.none { it.meal == m } })
    }.toList()
}

/** "2 → 8 novembre · 11 repas", null without planned meals. */
fun menuPeriod(menu: Menu): String? {
    val dates = menu.slots.map { it.date }
    if (dates.isEmpty()) return null
    return "${periodLabel(dates.min(), dates.max())} · ${menu.slots.size} repas"
}

private fun mealUi(s: MenuSlot): MealUi {
    val dish = when (s.recipe?.dishType) {
        "meat" -> DishType.MEAT
        "fish" -> DishType.FISH
        "vegetarian" -> DishType.VEGETARIAN
        else -> null
    }
    val picture = when (dish) { DishType.MEAT -> "🍗"; DishType.FISH -> "🐟"; DishType.VEGETARIAN -> "🥗"; null -> "🍽️" }
    return MealUi(if (s.meal == "lunch") "Midi" else "Soir", s.recipe?.title ?: "Aucune recette disponible",
        dish, s.recipe?.minutes, picture, s.locked, id = s.id, recipeId = s.recipe?.id, image = s.recipe?.image)
}
