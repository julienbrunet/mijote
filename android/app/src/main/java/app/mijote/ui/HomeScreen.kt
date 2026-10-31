package app.mijote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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

enum class Tab(val label: String) { MENUS("Menus"), RECIPES("Recettes"), GENERATE("Générer") }

enum class DishType(val label: String, val colors: Pair<Color, Color>) {
    MEAT("Viande", DishColors.meat), FISH("Poisson", DishColors.fish), VEGETARIAN("Végé", DishColors.veggie)
}

/** A planned meal as shown on the home screen. [picture] stands in for the recipe image. */
data class MealUi(val slot: String, val title: String, val dish: DishType?, val prepMinutes: Int?, val picture: String, val locked: Boolean)

data class DayUi(val label: String, val date: String, val meals: List<MealUi>)

/** Sheet color, header text color and corner action color, cycled over the days. */
private data class SheetStyle(val sheet: Color, val text: Color, val action: Color)

private val sheetStyles = listOf(
    SheetStyle(Sunny.Butter, Sunny.Ink, Sunny.Peach),
    SheetStyle(Sunny.Peach, Sunny.Ink, Sunny.Butter),
    SheetStyle(Sunny.Slate, Color.White, Sunny.Butter),
)

/** Signed-in shell: white header with the user corner block, stacked day sheets, floating bottom bar. */
@Composable
fun HomeScreen(
    user: User,
    onLogout: () -> Unit,
    period: String? = null,
    days: List<DayUi> = emptyList(),
    generatedCount: Int = 0,
    onNewWeek: (() -> Unit)? = null,
    onPeople: (Int) -> Unit = {},
    generate: @Composable () -> Unit = {},
) {
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
    Box(Modifier.fillMaxSize().background(Sunny.Cream)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Header(user, onLogout, if (tab == Tab.MENUS) "Ça mijote" else tab.label, period.takeIf { tab == Tab.MENUS },
                onNewWeek = { onNewWeek?.invoke(); tab = Tab.GENERATE }, onPeople = onPeople)
            if (tab == Tab.MENUS && days.isNotEmpty()) {
                days.forEachIndexed { i, day ->
                    DaySheet(day, sheetStyles[i % sheetStyles.size], first = i == 0, last = i == days.lastIndex)
                }
            } else {
                Text(
                    when (tab) {
                        Tab.MENUS -> "Aucun repas prévu : touchez « Générer » pour créer le menu."
                        Tab.RECIPES -> "Les recettes arriveront ici."
                        Tab.GENERATE -> "La génération de menus arrivera ici."
                    },
                    color = Sunny.InkSoft, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 160.dp),
                )
            }
        }
        BottomBar(tab, Modifier.align(Alignment.BottomEnd)) { tab = it }
    }
}

@Composable
private fun Header(user: User, onLogout: () -> Unit, title: String, period: String?, onNewWeek: () -> Unit, onPeople: (Int) -> Unit) {
    Box(
        Modifier.fillMaxWidth().zIndexShadow()
            .background(Sunny.Card, RoundedCornerShape(bottomStart = 40.dp, bottomEnd = 40.dp)),
    ) {
        CornerBlock(Sunny.Butter, size = 96.dp, modifier = Modifier.align(Alignment.TopEnd)) {
            Box(Modifier.statusBarsPadding()) { UserButton(user, onLogout, onPeople) }
        }
        Column(Modifier.statusBarsPadding().padding(start = 24.dp, end = 24.dp, top = 40.dp, bottom = 28.dp)) {
            ScreenTitle(title, period, size = 32)
            if (title == "Ça mijote") {
                Spacer(Modifier.height(22.dp))
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

/** One day: a colored sheet with a handle, its title and date, and the meal cards. Sheets overlap. */
@Composable
private fun DaySheet(day: DayUi, style: SheetStyle, first: Boolean, last: Boolean) {
    Column(
        Modifier.fillMaxWidth()
            .padding(top = if (first) 20.dp else 0.dp)
            .offset(y = if (first) 0.dp else (-36).dp)
            .shadow(12.dp, Shapes.Sheet, ambientColor = Sunny.PeachDeep, spotColor = Sunny.PeachDeep)
            .background(style.sheet, Shapes.Sheet)
            .padding(horizontal = 20.dp)
            .padding(bottom = if (last) 140.dp else 64.dp),
    ) {
        SheetHandle()
        Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 16.dp, start = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(day.label, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = style.text, modifier = Modifier.weight(1f))
            Text(day.date.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp,
                color = style.text.copy(alpha = 0.7f))
        }
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            day.meals.forEach { MealCard(it, style.action) }
        }
    }
}

@Composable
private fun MealCard(meal: MealUi, accent: Color) {
    Box(
        Modifier.fillMaxWidth().height(104.dp)
            .shadow(4.dp, Shapes.Card, ambientColor = Color.Black, spotColor = Color.Black)
            .background(Sunny.Card, Shapes.Card),
    ) {
        Row(Modifier.fillMaxSize().padding(start = 12.dp, end = 52.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(68.dp), contentAlignment = Alignment.Center) { Text(meal.picture, fontSize = 44.sp) }
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
                .background(accent, RoundedCornerShape(topStart = 16.dp, bottomEnd = 22.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (meal.locked) Icons.Filled.Lock else Icons.Filled.Refresh,
                contentDescription = if (meal.locked) "Verrouillé" else "Changer", tint = Color.White, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun UserButton(user: User, onLogout: () -> Unit, onPeople: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Filled.Person, contentDescription = "Utilisateur", tint = Sunny.Ink)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = Sunny.Card) {
            Text(user.displayName, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            // Preference: number of people the menus are cooked for.
            Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Personnes", color = Sunny.Ink, modifier = Modifier.padding(start = 8.dp, end = 4.dp))
                TextButton(onClick = { onPeople(user.people - 1) }, enabled = user.people > 1) { Text("−", fontSize = 20.sp) }
                Text("${user.people}", fontWeight = FontWeight.Bold, color = Sunny.Ink)
                TextButton(onClick = { onPeople(user.people + 1) }, enabled = user.people < 20) { Text("+", fontSize = 20.sp) }
            }
            HorizontalDivider(color = Sunny.Track)
            DropdownMenuItem(text = { Text("Se déconnecter") }, onClick = { open = false; onLogout() })
        }
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
    val radius = 36.dp
    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val tabHeight = 76.dp + navInset
    fun width(t: Tab) = if (t == selected) 176.dp else 112.dp
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
                    tint = if (on) Color.White else Color.White.copy(alpha = 0.8f), modifier = Modifier.size(26.dp))
                if (on) Text(t.label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                    modifier = Modifier.padding(start = 10.dp))
            }
        }
    }
}

/** Groups the planned meals by day for the home screen. */
fun menuDays(menu: Menu): List<DayUi> = menu.slots.groupBy { it.date }.toSortedMap().map { (date, slots) ->
    DayUi(dayLabel(date, menu.today), shortDate(date), slots.sortedBy { it.meal != "lunch" }.map(::mealUi))
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
        dish, s.recipe?.minutes, picture, s.locked)
}
