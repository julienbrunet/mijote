package app.mijote.prototype.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class Dish(val label: String) { MEAT("Viande"), FISH("Poisson"), VEGGIE("Végétarien") }

data class Meal(val slot: String, val title: String, val dish: Dish, val time: String, val locked: Boolean = false, val favorite: Boolean = false)
data class Day(val name: String, val date: String, val meals: List<Meal>)

// Hard-coded sample data: this prototype only exists to tune the theme.
val sampleDays = listOf(
    Day("Aujourd'hui", "lundi 2 nov.", listOf(
        Meal("Dîner", "Velouté de potimarron", Dish.VEGGIE, "25 min", favorite = true),
    )),
    Day("Demain", "mardi 3 nov.", listOf(
        Meal("Déjeuner", "Saumon citron-aneth", Dish.FISH, "20 min"),
        Meal("Dîner", "Curry de pois chiches", Dish.VEGGIE, "30 min"),
    )),
    Day("Mercredi", "4 nov.", listOf(
        Meal("Déjeuner", "Poulet rôti aux herbes", Dish.MEAT, "1 h 15", locked = true),
        Meal("Dîner", "Gratin de courgettes", Dish.VEGGIE, "40 min"),
    )),
    Day("Jeudi", "5 nov.", listOf(
        Meal("Dîner", "Cabillaud en papillote", Dish.FISH, "25 min"),
    )),
)

@Composable
fun HomeScreen() {
    var tab by remember { mutableIntStateOf(0) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = { BottomBar(tab) { tab = it } },
    ) { padding ->
        // Two meal cards per row, grouped by day; day headers span the full width.
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()),
            // Content scrolls behind the floating bottom bar; the bottom padding keeps the last item reachable.
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = padding.calculateBottomPadding() + 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("ÇA MIJOTE", fontSize = 26.sp, letterSpacing = 4.sp, fontWeight = FontWeight.Light, color = MaterialTheme.colorScheme.onBackground)
                        Text("2 → 8 novembre · 11 repas", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                    }
                    IconButton(
                        onClick = {},
                        modifier = Modifier.size(44.dp).background(Calm.Card, CircleShape),
                    ) { Icon(Icons.Filled.Person, contentDescription = "Utilisateur", tint = Calm.Sage) }
                }
            }
            sampleDays.forEach { day ->
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.Bottom) {
                        Text(day.name, fontWeight = FontWeight.Medium, fontSize = 17.sp, color = MaterialTheme.colorScheme.onBackground)
                        Spacer(Modifier.width(8.dp))
                        Text(day.date, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                items(day.meals) { meal -> MealCard(meal) }
            }
        }
    }
}

@Composable
private fun MealCard(meal: Meal) {
    val (tagBg, tagFg) = when (meal.dish) {
        Dish.MEAT -> DishColors.meat
        Dish.FISH -> DishColors.fish
        Dish.VEGGIE -> DishColors.veggie
    }
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Box {
            // Placeholder for the recipe thumbnail.
            Box(Modifier.fillMaxWidth().aspectRatio(1.4f).background(Brush.linearGradient(listOf(tagBg, tagFg.copy(alpha = 0.5f)))))
            val badge = when {
                meal.locked -> Icons.Filled.Lock to MaterialTheme.colorScheme.primary
                meal.favorite -> Icons.Filled.Star to Calm.Sand
                else -> null
            }
            badge?.let { (icon, tint) ->
                Box(
                    Modifier.align(Alignment.TopEnd).padding(8.dp).size(30.dp).background(Calm.Card.copy(alpha = 0.9f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp)) }
            }
        }
        Column(Modifier.padding(12.dp)) {
            Text(meal.slot.uppercase(), fontSize = 11.sp, letterSpacing = 1.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(meal.title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface,
                minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 19.sp)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(meal.dish.label, fontSize = 11.sp, color = tagFg,
                    modifier = Modifier.background(tagBg, RoundedCornerShape(8.dp)).padding(horizontal = 7.dp, vertical = 2.dp))
                Spacer(Modifier.width(6.dp))
                Text(meal.time, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}

@Composable
private fun BottomBar(selected: Int, onSelect: (Int) -> Unit) {
    val items = listOf("Menus" to Icons.Filled.Home, "Recettes" to Icons.Filled.Search, "Réglages" to Icons.Filled.Settings)
    Card(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Calm.Card),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            items.forEachIndexed { i, (label, icon) ->
                val color = if (i == selected) Calm.Sage else Calm.InkSoft
                Column(
                    Modifier.clickable { onSelect(i) }.padding(horizontal = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(icon, contentDescription = label, tint = color)
                    Text(label, fontSize = 12.sp, color = color, fontWeight = if (i == selected) FontWeight.Medium else FontWeight.Normal)
                    Spacer(Modifier.height(3.dp))
                    Box(Modifier.size(4.dp).background(if (i == selected) Calm.Sage else Color.Transparent, CircleShape))
                }
            }
        }
    }
}
