package app.mijote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mijote.RecipesState
import app.mijote.data.QUICK_MINUTES
import app.mijote.data.RecipeFilters
import app.mijote.data.RecipeSummary
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * "Recettes" tab: search by title or tag, a filter button (with the number of active filters)
 * opening a sheet, the active filters as removable pills, the active recipes (archived ones are left
 * out) and a round "+" above the navigation to add a recipe. Each card can plan its recipe on a day.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipesTab(
    state: RecipesState,
    canWrite: Boolean,
    onQuery: (String) -> Unit,
    onOpen: (RecipeSummary) -> Unit,
    onPlan: (RecipeSummary) -> Unit,
    onNew: () -> Unit,
    working: Boolean = false,
    onRefresh: () -> Unit = {},
    onPreferences: () -> Unit = {},
    onFilters: (RecipeFilters) -> Unit = {},
) {
    val list = state.shown
    val filters = state.filters
    var sheet by remember { mutableStateOf(false) }
    if (sheet) {
        ModalBottomSheet(onDismissRequest = { sheet = false }, containerColor = Sunny.Card) {
            RecipeFiltersSheet(filters, tagsOf(state.recipes.orEmpty(), filters), state.recipes.orEmpty(),
                onChange = onFilters, onDone = { sheet = false })
        }
    }
    Box(Modifier.fillMaxSize()) {
        PullToRefreshBox(state.refreshing, onRefresh, Modifier.fillMaxSize().background(Sunny.Butter)) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 180.dp)) {
                item {
                    // Same header as the other tabs, with the search, the filter button and the active filters.
                    HomeHeader("Recettes", list?.let { subtitle(it.size, state.query.isNotBlank(), filters.count) }, onPreferences) {
                        Row(Modifier.fillMaxWidth().padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) { SearchField(state.query, onQuery) }
                            FilterButton(filters.count) { sheet = true }
                        }
                        if (filters.count > 0) ActiveFilters(filters, onFilters)
                    }
                }
                item { Box(Modifier.padding(horizontal = 20.dp)) { ErrorText(state.error) } }
                when {
                    list == null -> item {
                        Box(Modifier.fillMaxWidth().padding(top = 60.dp), contentAlignment = Alignment.Center) {
                            if (state.loading) CircularProgressIndicator(color = Sunny.PeachDeep)
                        }
                    }
                    list.isEmpty() -> item {
                        Text(
                            when {
                                filters.count > 0 -> "Aucune recette ne correspond à ces filtres."
                                state.query.isNotBlank() -> "Aucune recette ne correspond à « ${state.query.trim()} »."
                                canWrite -> "Aucune recette pour le moment : ajoutez la première avec le bouton +."
                                else -> "Aucune recette pour le moment."
                            },
                            color = Sunny.InkTitle, textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(top = 60.dp, start = 24.dp, end = 24.dp),
                        )
                    }
                    else -> items(list, key = { it.id }) { r ->
                        Box(Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp)) {
                            RecipeCard(r, canWrite, working, onOpen = { onOpen(r) }, onPlan = { onPlan(r) })
                        }
                    }
                }
            }
        }
        if (canWrite) {
            // Round "+" above the navigation pill, within reach of the right thumb.
            Box(
                Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = 20.dp, bottom = 100.dp).size(60.dp)
                    .shadow(10.dp, CircleShape, ambientColor = Sunny.PeachDeep, spotColor = Sunny.PeachDeep)
                    .background(Sunny.PeachDeep, CircleShape).clip(CircleShape).clickable(onClickLabel = "Nouvelle recette", onClick = onNew),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Add, contentDescription = "Nouvelle recette", tint = Color.White, modifier = Modifier.size(30.dp)) }
        }
    }
}

private fun subtitle(n: Int, searching: Boolean, filters: Int): String {
    val what = if (searching || filters > 0) "résultat" else "recette"
    val base = "$n $what${if (n > 1) "s" else ""}"
    return if (filters > 0) "$base · $filters filtre${if (filters > 1) "s" else ""}" else base
}

private val dishLabels = listOf("meat" to "Viande", "fish" to "Poisson", "vegetarian" to "Végé")
private val mealLabels = listOf("lunch" to "Midi", "dinner" to "Soir")
private val durationLabels = listOf("quick" to "Rapide (≤ $QUICK_MINUTES min)", "long" to "Long")
private val seasonLabels = listOf("spring" to "Printemps", "summer" to "Été", "autumn" to "Automne", "winter" to "Hiver")

/** The tags of the recipes, most used first (at most 20), plus the selected ones. */
private fun tagsOf(recipes: List<RecipeSummary>, f: RecipeFilters): List<String> {
    val counts = recipes.flatMap { it.tags }.groupingBy { it.lowercase() }.eachCount()
    val top = counts.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).take(20).map { it.key }
    return (top + f.tags.map { it.lowercase() }).distinct()
}

@Composable
private fun FilterButton(count: Int, onClick: () -> Unit) {
    Box(
        Modifier.padding(start = 10.dp).size(56.dp).clip(Shapes.Control)
            .background(if (count > 0) Sunny.PeachDeep else Sunny.ButterLight).clickable(onClickLabel = "Filtres", onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Filtres", tint = if (count > 0) Color.White else Sunny.Ink)
        if (count > 0) Box(
            Modifier.align(Alignment.TopEnd).padding(6.dp).size(18.dp).background(Color.White, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Text("$count", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Sunny.PeachDeep) }
    }
}

/** The active filters as pills; touching one removes it. */
@Composable
private fun ActiveFilters(f: RecipeFilters, onChange: (RecipeFilters) -> Unit) {
    fun label(list: List<Pair<String, String>>, code: String) = list.firstOrNull { it.first == code }?.second ?: code
    Row(Modifier.padding(top = 12.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        f.dishTypes.forEach { c -> RemovablePill(label(dishLabels, c)) { onChange(f.copy(dishTypes = f.dishTypes - c)) } }
        f.meals.forEach { c -> RemovablePill(label(mealLabels, c)) { onChange(f.copy(meals = f.meals - c)) } }
        f.durations.forEach { c -> RemovablePill(if (c == "quick") "Rapide" else "Long") { onChange(f.copy(durations = f.durations - c)) } }
        f.seasons.forEach { c -> RemovablePill(label(seasonLabels, c)) { onChange(f.copy(seasons = f.seasons - c)) } }
        f.tags.forEach { c -> RemovablePill(c) { onChange(f.copy(tags = f.tags - c)) } }
    }
}

@Composable
private fun RemovablePill(text: String, onRemove: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(20.dp)).background(Sunny.PeachDeep).clickable(onClickLabel = "Retirer le filtre $text", onClick = onRemove)
            .padding(start = 14.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
        Icon(Icons.Filled.Clear, contentDescription = null, tint = Color.White, modifier = Modifier.padding(start = 4.dp).size(16.dp))
    }
}

/**
 * Content of the filter sheet: dish type, meal, duration, season and tags; filters apply at once,
 * the button shows how many recipes pass them and closes the sheet.
 */
@Composable
fun RecipeFiltersSheet(
    f: RecipeFilters, tags: List<String>, recipes: List<RecipeSummary>,
    onChange: (RecipeFilters) -> Unit, onDone: () -> Unit,
) {
    val n = recipes.count(f::matches)
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp).padding(bottom = 22.dp).navigationBarsPadding()) {
        Text("Filtres", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Sunny.InkTitle)
        Group("TYPE DE PLAT", dishLabels, f.dishTypes) { onChange(f.copy(dishTypes = it)) }
        Group("REPAS", mealLabels, f.meals) { onChange(f.copy(meals = it)) }
        Group("DURÉE", durationLabels, f.durations) { onChange(f.copy(durations = it)) }
        Group("SAISON", seasonLabels, f.seasons) { onChange(f.copy(seasons = it)) }
        if (tags.isNotEmpty()) Group("TAGS", tags.map { it to it }, f.tags.map { it.lowercase() }.toSet()) { onChange(f.copy(tags = it)) }
        Row(Modifier.padding(top = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onChange(RecipeFilters()) }, enabled = f.count > 0, modifier = Modifier.weight(1f)) {
                Text("Tout effacer", color = Sunny.Slate, fontWeight = FontWeight.SemiBold)
            }
            Button(
                onClick = onDone, shape = Shapes.Control,
                colors = ButtonDefaults.buttonColors(containerColor = Sunny.PeachDeep, contentColor = Color.White),
                modifier = Modifier.height(52.dp),
            ) { Text(if (n == 1) "Voir 1 recette" else "Voir $n recettes", fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun Group(label: String, options: List<Pair<String, String>>, selected: Set<String>, onChange: (Set<String>) -> Unit) {
    Text(label, fontSize = 11.sp, letterSpacing = 1.sp, fontWeight = FontWeight.SemiBold, color = Sunny.InkSoft,
        modifier = Modifier.padding(top = 18.dp, bottom = 8.dp))
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (code, text) ->
            Chip(text, code in selected) { onChange(if (code in selected) selected - code else selected + code) }
        }
    }
}

@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit) {
    TextField(
        value = query, onValueChange = onQuery, singleLine = true,
        placeholder = { Text("Rechercher…", maxLines = 1) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = Sunny.InkSoft) },
        trailingIcon = {
            if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) {
                Icon(Icons.Filled.Clear, contentDescription = "Effacer la recherche", tint = Sunny.InkSoft)
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        shape = Shapes.Control,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Sunny.ButterLight, unfocusedContainerColor = Sunny.ButterLight,
            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, cursorColor = Sunny.PeachDeep,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** A recipe of the list: tap to open it, the corner calendar to plan it on a day. */
@Composable
private fun RecipeCard(r: RecipeSummary, canWrite: Boolean, working: Boolean, onOpen: () -> Unit, onPlan: () -> Unit) {
    val dish = dishTypeOf(r.dishType)
    Box(
        Modifier.fillMaxWidth().height(96.dp)
            .shadow(4.dp, Shapes.Card, ambientColor = Color.Black, spotColor = Color.Black)
            .background(Sunny.Card, Shapes.Card).clip(Shapes.Card).clickable(onClick = onOpen),
    ) {
        Row(Modifier.fillMaxSize().padding(start = 12.dp, end = if (canWrite) 52.dp else 16.dp), verticalAlignment = Alignment.CenterVertically) {
            RecipeThumb(r.image, dishEmoji(r.dishType), Modifier.size(70.dp).clip(RoundedCornerShape(16.dp)), emojiSize = 36.sp)
            Column(Modifier.padding(start = 12.dp)) {
                Text(r.title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Sunny.Ink, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, lineHeight = 19.sp)
                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    dish?.let { DishTag(it.label, it.colors) }
                    r.minutes?.let { Text("$it min", fontSize = 12.sp, color = Sunny.InkSoft, modifier = Modifier.padding(start = 8.dp)) }
                    if (r.tags.isNotEmpty()) Text(r.tags.joinToString(" · "), fontSize = 12.sp, color = Sunny.InkSoft, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
        if (canWrite) {
            Box(
                Modifier.align(Alignment.BottomEnd).size(46.dp)
                    .background(Sunny.Peach, RoundedCornerShape(topStart = 16.dp, bottomEnd = 22.dp))
                    .clickable(enabled = !working, onClickLabel = "Planifier", onClick = onPlan),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.DateRange, contentDescription = "Planifier", tint = Color.White, modifier = Modifier.size(20.dp)) }
        }
    }
}

fun dishTypeOf(code: String?) = when (code) {
    "meat" -> DishType.MEAT
    "fish" -> DishType.FISH
    "vegetarian" -> DishType.VEGETARIAN
    else -> null
}

fun dishEmoji(type: String?) = when (type) { "meat" -> "🍗"; "fish" -> "🐟"; "vegetarian" -> "🥗"; else -> "🍽️" }
