package app.mijote.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mijote.RecipesState
import app.mijote.data.QUICK_MINUTES
import app.mijote.data.RecipeFilters
import app.mijote.data.RecipeSummary

private val recipeControlShape = RoundedCornerShape(12.dp)

/**
 * "Recettes" tab: search by title or tag, a filter sheet, removable active filters and the active
 * recipes. Each card can open and plan its recipe. The caller owns the tab's navigation and toolbar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipesTab(
    state: RecipesState,
    canWrite: Boolean,
    onQuery: (String) -> Unit,
    onOpen: (RecipeSummary) -> Unit,
    onPlan: (RecipeSummary) -> Unit,
    working: Boolean = false,
    onRefresh: () -> Unit = {},
    onPreferences: (() -> Unit)? = null,
    onFilters: (RecipeFilters) -> Unit = {},
    /** Whether the caller has opened the filter sheet. */
    sheetOpen: Boolean = false,
    onSheet: (Boolean) -> Unit = {},
    listState: LazyListState = rememberLazyListState(),
) {
    val list = state.shown
    val filters = state.filters
    if (sheetOpen) {
        ModalBottomSheet(onDismissRequest = { onSheet(false) }, containerColor = Sunny.Cream) {
            RecipeFiltersSheet(filters, tagsOf(state.recipes.orEmpty(), filters), state.recipes.orEmpty(),
                onChange = onFilters, onDone = { onSheet(false) })
        }
    }
    Box(Modifier.fillMaxSize().background(Sunny.Cream).statusBarsPadding()) {
        PullToRefreshBox(state.refreshing, onRefresh, Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 20.dp)) {
                item {
                    AgendaHeader("Recettes", list?.let { resultsLabel(it.size, state.query.isNotBlank(), filters.count) }) {
                        if (onPreferences != null) IconButton(onClick = onPreferences, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Filled.Person, contentDescription = "Compte", tint = Sunny.Ink)
                        }
                    }
                    Column(Modifier.padding(horizontal = 12.dp).padding(top = 6.dp)) {
                        SearchField(state.query, onQuery)
                        if (filters.count > 0) ActiveFilters(filters, onFilters)
                    }
                }
                item { Box(Modifier.padding(horizontal = 12.dp)) { ErrorText(state.error) } }
                when {
                    list == null -> item {
                        if (state.loading) Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp).heightIn(min = 48.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(Modifier.size(22.dp), color = Sunny.PeachDeep, strokeWidth = 3.dp)
                            Text("Chargement des recettes…", fontSize = 12.sp, color = Sunny.InkTitle)
                        }
                    }
                    list.isEmpty() -> item {
                        Surface(
                            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp),
                            color = Sunny.Card, shape = Shapes.Control,
                            border = BorderStroke(1.dp, Sunny.Track),
                        ) {
                            Text(when {
                                filters.count > 0 -> "Aucune recette ne correspond à ces filtres."
                                state.query.isNotBlank() -> "Aucune recette ne correspond à « ${state.query.trim()} »."
                                canWrite -> "Aucune recette pour le moment. Touchez « Ajouter une recette » pour créer la première."
                                else -> "Aucune recette pour le moment."
                            }, Modifier.padding(12.dp), fontSize = 13.sp, lineHeight = 17.sp, color = Sunny.InkTitle)
                        }
                    }
                    else -> items(list, key = { it.id }) { r ->
                        Box(Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp)) {
                            RecipeCard(r, canWrite, working, onOpen = { onOpen(r) }, onPlan = { onPlan(r) })
                        }
                    }
                }
            }
        }
    }
}

private fun resultsLabel(n: Int, searching: Boolean, filters: Int): String {
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

/** The active filters as pills; touching one removes it. */
@Composable
private fun ActiveFilters(f: RecipeFilters, onChange: (RecipeFilters) -> Unit) {
    fun label(list: List<Pair<String, String>>, code: String) = list.firstOrNull { it.first == code }?.second ?: code
    Row(Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (f.favorites) RemovablePill("Mes favoris") { onChange(f.copy(favorites = false)) }
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
        Modifier.heightIn(min = 48.dp).clip(recipeControlShape).background(Sunny.ButterLight)
            .clickable(onClickLabel = "Retirer le filtre $text", role = Role.Button, onClick = onRemove)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Sunny.Ink)
        Icon(Icons.Filled.Clear, contentDescription = null, tint = Sunny.Ink, modifier = Modifier.padding(start = 4.dp).size(16.dp))
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
    Column(
        Modifier.fillMaxWidth().background(Sunny.Cream).navigationBarsPadding()
            .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Filtres", Modifier.semantics { heading() }, fontSize = 26.sp, lineHeight = 29.sp,
            fontWeight = FontWeight.Bold, color = Sunny.Ink)
        Column(
            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val favorite = if (f.favorites) setOf("favorites") else emptySet()
            FilterGroup("Favoris", listOf("favorites" to "Mes favoris"), favorite) {
                onChange(f.copy(favorites = "favorites" in it))
            }
            FilterGroup("Type de plat", dishLabels, f.dishTypes) { onChange(f.copy(dishTypes = it)) }
            FilterGroup("Repas", mealLabels, f.meals) { onChange(f.copy(meals = it)) }
            FilterGroup("Durée", durationLabels, f.durations) { onChange(f.copy(durations = it)) }
            FilterGroup("Saison", seasonLabels, f.seasons) { onChange(f.copy(seasons = it)) }
            if (tags.isNotEmpty()) {
                FilterGroup("Tags", tags.map { it to it }, f.tags.map { it.lowercase() }.toSet()) {
                    onChange(f.copy(tags = it))
                }
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AgendaAction("Tout effacer", { onChange(RecipeFilters()) }, enabled = f.count > 0)
            AgendaAction(if (n == 1) "Voir 1 recette" else "Voir $n recettes", onDone, primary = true)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterGroup(label: String, options: List<Pair<String, String>>, selected: Set<String>, onChange: (Set<String>) -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(Shapes.Control).background(Sunny.Card)
            .border(1.dp, Sunny.Track, Shapes.Control).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(label, Modifier.semantics { heading() }, fontSize = 15.sp, lineHeight = 18.sp,
            fontWeight = FontWeight.Bold, color = Sunny.Ink)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { (code, text) ->
                val active = code in selected
                Row(
                    Modifier.heightIn(min = 48.dp).clip(recipeControlShape)
                        .background(if (active) Sunny.Butter else Sunny.Card)
                        .border(1.dp, if (active) Sunny.Butter else Sunny.Track, recipeControlShape)
                        .selectable(active, role = Role.Checkbox) {
                            onChange(if (active) selected - code else selected + code)
                        }.padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (active) Icon(Icons.Filled.Check, contentDescription = null, tint = Sunny.Ink, modifier = Modifier.size(16.dp))
                    Text(text, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold, color = Sunny.Ink)
                }
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit) {
    OutlinedTextField(
        value = query, onValueChange = onQuery, singleLine = true,
        label = { Text("Rechercher un titre, un tag…", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = Sunny.InkTitle) },
        trailingIcon = {
            if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.Clear, contentDescription = "Effacer la recherche", tint = Sunny.InkTitle)
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        shape = recipeControlShape,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = Sunny.Card, unfocusedContainerColor = Sunny.Card,
            focusedTextColor = Sunny.Ink, unfocusedTextColor = Sunny.Ink,
            focusedLabelColor = Sunny.Ink, unfocusedLabelColor = Sunny.InkTitle,
            focusedBorderColor = Sunny.InkTitle, unfocusedBorderColor = Sunny.Track, cursorColor = Sunny.Ink,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** A recipe of the list: tap the card to open it or the calendar beside its title to plan it. */
@Composable
private fun RecipeCard(r: RecipeSummary, canWrite: Boolean, working: Boolean, onOpen: () -> Unit, onPlan: () -> Unit) {
    val dish = dishTypeOf(r.dishType)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 72.dp).clip(Shapes.Control).background(Sunny.Card)
            .border(1.dp, Sunny.Track, Shapes.Control).clickable(role = Role.Button, onClick = onOpen)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RecipeThumb(r.image, dishEmoji(r.dishType), Modifier.size(42.dp).clip(recipeControlShape), emojiSize = 24.sp)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(r.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Sunny.Ink, maxLines = 3,
                    overflow = TextOverflow.Ellipsis, lineHeight = 18.sp, modifier = Modifier.weight(1f))
                if (canWrite) {
                    Box(
                        Modifier.size(48.dp).clip(recipeControlShape)
                            .background(if (working) Sunny.Track else Sunny.ButterLight)
                            .clickable(enabled = !working, role = Role.Button, onClick = onPlan),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.DateRange, contentDescription = "Planifier ${r.title}", tint = Sunny.Ink,
                            modifier = Modifier.size(20.dp))
                    }
                }
            }
            Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (r.favorite) Icon(Icons.Filled.Favorite, contentDescription = "Dans mes favoris", tint = Sunny.Error,
                    modifier = Modifier.size(14.dp))
                dish?.let { DishTag(it.label, it.colors) }
                r.minutes?.let { Text("$it min", fontSize = 12.sp, color = Sunny.InkTitle, maxLines = 1) }
                if (r.tags.isNotEmpty()) Text(r.tags.joinToString(" · "), fontSize = 12.sp, color = Sunny.InkTitle, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
            }
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
