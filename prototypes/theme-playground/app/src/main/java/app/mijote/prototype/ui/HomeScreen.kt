package app.mijote.prototype.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
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
        LazyColumn(
            Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()),
            // Content scrolls behind the floating bottom bar; the bottom padding keeps the last item reachable.
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = padding.calculateBottomPadding() + 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text("CETTE SEMAINE", fontSize = 26.sp, letterSpacing = 4.sp, fontWeight = FontWeight.Light, color = MaterialTheme.colorScheme.onBackground)
                Text("2 → 8 novembre · 11 repas", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                Spacer(Modifier.height(6.dp))
            }
            sampleDays.forEach { day ->
                item {
                    Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.Bottom) {
                        Text(day.name, fontWeight = FontWeight.Medium, fontSize = 17.sp, color = MaterialTheme.colorScheme.onBackground)
                        Spacer(Modifier.width(8.dp))
                        Text(day.date, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                day.meals.forEach { meal -> item { MealCard(meal) } }
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
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            // Placeholder for the recipe thumbnail.
            Box(Modifier.size(56.dp).background(Brush.linearGradient(listOf(tagBg, tagFg.copy(alpha = 0.5f))), RoundedCornerShape(12.dp)))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(meal.slot.uppercase(), fontSize = 11.sp, letterSpacing = 1.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(meal.title, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(meal.dish.label, fontSize = 12.sp, color = tagFg,
                        modifier = Modifier.background(tagBg, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 2.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(meal.time, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            when {
                meal.locked -> Icon(Icons.Filled.Lock, "Verrouillé", tint = MaterialTheme.colorScheme.primary)
                meal.favorite -> Icon(Icons.Filled.Star, "Favori", tint = Calm.Sand)
                else -> Spacer(Modifier.size(0.dp).background(Color.Transparent))
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
