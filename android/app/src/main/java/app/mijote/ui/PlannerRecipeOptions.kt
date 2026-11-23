package app.mijote.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mijote.RandomMealRecipesState
import app.mijote.RecipesState
import app.mijote.data.QUICK_MINUTES
import app.mijote.data.RecipeSummary
import kotlinx.coroutines.launch

private val recipeOptionSeasons = listOf("spring" to "Printemps", "summer" to "Été", "autumn" to "Automne", "winter" to "Hiver")
private val recipeOptionTypes = listOf("vegetarian" to "Végé", "fish" to "Poisson", "meat" to "Viande")

@Composable
internal fun PlannerRecipePickerScreen(
    state: RecipesState, moment: PlannerMoment, busy: Boolean,
    onSearch: (String) -> Unit, onChoose: (RecipeSummary) -> Unit, onBack: () -> Unit,
) {
    var types by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var seasons by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    val catalog = state.recipes.orEmpty()
    val results = remember(catalog, types, seasons) {
        catalog.filter { (types.isEmpty() || it.dishType in types) &&
            (seasons.isEmpty() || it.seasons.isEmpty() || it.seasons.any(seasons::contains)) }
    }
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().background(Sunny.Cream).safeDrawingPadding()) {
        RecipeOptionsHeader("Choisir une recette", moment, onBack)
        Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedTextField(
                state.query, onSearch, singleLine = true, label = { Text("Rechercher une recette") },
                leadingIcon = { Icon(Icons.Filled.Search, null) }, shape = Shapes.Control, modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                recipeOptionTypes.forEach { (value, label) ->
                    RecipeOptionChip(label, value in types) { types = toggled(types, value) }
                }
                recipeOptionSeasons.forEach { (value, label) ->
                    RecipeOptionChip(label, value in seasons) { seasons = toggled(seasons, value) }
                }
            }
        }
        if (state.error != null) RecipeOptionsStatus(state.error, { onSearch(state.query) })
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 12.dp), color = Sunny.PeachDeep)
        if (!state.loading && state.error == null && results.isEmpty()) {
            RecipeOptionsStatus(
                if (types.isNotEmpty() || seasons.isNotEmpty()) "Aucune recette ne correspond aux filtres."
                else if (state.query.isNotBlank()) "Aucune recette trouvée." else "Le carnet est vide.",
                if (types.isNotEmpty() || seasons.isNotEmpty()) {
                    {
                        types = emptyList()
                        seasons = emptyList()
                    }
                } else null,
                "Réinitialiser les filtres",
            )
        } else {
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                items(results, key = RecipeSummary::id) { recipe ->
                    Row(
                        Modifier.fillMaxWidth().clip(Shapes.Control).background(Sunny.Card)
                            .border(1.dp, Sunny.Track, Shapes.Control)
                            .clickable(enabled = !busy && !state.loading, role = Role.Button) { onChoose(recipe) }.padding(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RecipeThumb(recipe.image, dishEmoji(recipe.dishType), Modifier.size(61.dp).clip(RoundedCornerShape(11.dp)), 29.sp)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(recipe.title, color = Sunny.Ink, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                                lineHeight = 19.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            RecipeOptionMetadata(recipe)
                        }
                    }
                }
            }
        }
    }
}

private data class RecipeIdea(val id: String, val label: String, val matches: (RecipeSummary) -> Boolean)

@Composable
internal fun PlannerRandomRecipesScreen(
    state: RandomMealRecipesState, moment: PlannerMoment,
    onRetry: () -> Unit, onChoose: (RecipeSummary) -> Unit, onBack: () -> Unit,
) {
    var selected by rememberSaveable(moment.date.toEpochDay(), moment.meal) { mutableStateOf<List<String>>(emptyList()) }
    val ideas = remember(state.recipes) {
        val tags = state.recipes.flatMap(RecipeSummary::tags).distinctBy { it.lowercase() }
            .groupBy { it.lowercase() }.values.map { it.first() }
            .sortedWith(compareByDescending<String> { tag ->
                state.recipes.count { recipe -> recipe.tags.any { it.equals(tag, ignoreCase = true) } }
            }.thenBy { it.lowercase() })
        buildList {
            add(RecipeIdea("vege", "Végé") { it.dishType == "vegetarian" })
            add(RecipeIdea("quick", "30 min ou moins") { it.minutes?.let { min -> min <= QUICK_MINUTES } == true })
            recipeOptionSeasons.forEach { (code, label) -> add(RecipeIdea(code, label) { code in it.seasons }) }
            listOf("salade" to "Salades", "soupe" to "Soupes").forEach { (word, label) ->
                val idea = RecipeIdea(word, label) { recipe ->
                    word in recipe.title.lowercase() || recipe.tags.any { word in it.lowercase() }
                }
                if (state.recipes.any(idea.matches)) add(idea)
            }
            tags.filterNot { it.lowercase() in setOf("salade", "salades", "soupe", "soupes", "végé", "rapide") }
                .take(8).forEach { tag ->
                    add(RecipeIdea("tag:$tag", tag) { recipe -> recipe.tags.any { it.equals(tag, ignoreCase = true) } })
                }
        }
    }
    val cards = remember(state.recipes, selected, ideas) {
        val filters = ideas.filter { it.id in selected }
        state.recipes.filter { recipe -> filters.all { it.matches(recipe) } }
    }
    val filterScroll = rememberScrollState()
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().background(Sunny.Cream).safeDrawingPadding()) {
        RecipeOptionsHeader("Recette au hasard", moment, onBack)
        when {
            state.loading -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 12.dp))
            state.error != null -> RecipeOptionsStatus(state.error, onRetry)
            state.recipes.isEmpty() -> RecipeOptionsStatus("Aucune recette disponible pour ce créneau.", onRetry)
            else -> key(selected, cards) {
                val pager = rememberPagerState { cards.size }
                val scope = rememberCoroutineScope()
                val page = pager.currentPage.coerceIn(0, cards.lastIndex.coerceAtLeast(0))
                val current = cards.getOrNull(page)
                if (current != null) HorizontalPager(
                    state = pager,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 5.dp), pageSpacing = 10.dp,
                ) { index ->
                    val recipe = cards[index]
                    RandomRecipeCard(recipe, state.recentDays[recipe.id])
                }
                else Box(Modifier.weight(1f).fillMaxWidth()) {
                    RecipeOptionsStatus("Aucune recette ne correspond à ces envies.", { selected = emptyList() }, "Effacer les envies")
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    IconButton(
                        onClick = { scope.launch { pager.animateScrollToPage(page - 1) } }, enabled = page > 0,
                    ) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Suggestion précédente") }
                    if (current != null) Text("${page + 1} / ${cards.size}", color = Sunny.InkTitle, fontSize = 13.sp)
                    IconButton(
                        onClick = { scope.launch { pager.animateScrollToPage(page + 1) } }, enabled = page < cards.lastIndex,
                    ) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Suggestion suivante") }
                }
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)) {
                    AgendaSectionTitle("Filtrer les suggestions")
                    Row(Modifier.horizontalScroll(filterScroll), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ideas.forEach { idea ->
                            RecipeOptionChip(idea.label, idea.id in selected) {
                                selected = toggled(selected, idea.id)
                            }
                        }
                    }
                    if (current != null && page == cards.lastIndex) Text(
                        if (selected.isEmpty()) "Dernière suggestion." else "Plus de suggestion pour ces envies.",
                        color = Sunny.InkTitle, fontSize = 12.sp,
                    )
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    if (current != null) AgendaAction("Choisir cette recette", { onChoose(current) }, primary = true)
                }
            }
        }
    }
}

@Composable
private fun RandomRecipeCard(recipe: RecipeSummary, days: Int?) {
    Column(
        Modifier.fillMaxSize().clip(Shapes.Card).background(Sunny.Card).border(1.dp, Sunny.Track, Shapes.Card),
    ) {
        RecipeThumb(recipe.image, dishEmoji(recipe.dishType), Modifier.fillMaxWidth().weight(1f), 56.sp)
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(recipe.title, color = Sunny.Ink, fontSize = 19.sp, fontWeight = FontWeight.Bold,
                lineHeight = 23.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            RecipeOptionMetadata(recipe)
            if (days != null) {
                val notice = when (days) {
                    0 -> "Recette déjà planifiée ce jour-là"
                    1 -> "Recette déjà planifiée il y a 1 jour"
                    else -> "Recette déjà planifiée il y a $days jours"
                }
                Text(notice, color = Sunny.Ink, fontSize = 12.sp, lineHeight = 15.sp,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Sunny.ButterLight)
                        .padding(horizontal = 7.dp, vertical = 4.dp))
            }
        }
    }
}

@Composable
private fun RecipeOptionsHeader(title: String, moment: PlannerMoment, onBack: () -> Unit) {
    Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        AgendaAction("Retour", onBack, icon = Icons.AutoMirrored.Filled.ArrowBack)
    }
    AgendaHeader(title, mealMoment(moment.date, moment.meal).replaceFirstChar { it.uppercase() })
}

@Composable
private fun RecipeOptionChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected, onClick = onClick, label = { Text(label) },
        modifier = Modifier.heightIn(min = 44.dp),
        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Sunny.Butter, selectedLabelColor = Sunny.Ink),
    )
}

@Composable
private fun RecipeOptionMetadata(recipe: RecipeSummary) {
    val type = recipeOptionTypes.firstOrNull { it.first == recipe.dishType }?.second
    val meta = listOfNotNull(recipe.minutes?.let { "$it min" }, type) + recipe.tags.take(4)
    if (meta.isNotEmpty()) Text(meta.joinToString(" · "), color = Sunny.InkTitle, fontSize = 12.sp,
        lineHeight = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun RecipeOptionsStatus(text: String, retry: (() -> Unit)? = null, action: String = "Réessayer") {
    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text, color = Sunny.InkTitle)
        if (retry != null) AgendaAction(action, retry)
    }
}

private fun toggled(values: List<String>, value: String) =
    if (value in values) values - value else values + value
