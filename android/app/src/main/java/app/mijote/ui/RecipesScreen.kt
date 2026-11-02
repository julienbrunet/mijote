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
import app.mijote.data.RecipeSummary

/**
 * "Recettes" tab: search by title or tag, the active recipes (archived ones are left out), a
 * "Nouvelle recette" button and, on each card, a shortcut to plan the recipe on a day.
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
) {
    val list = state.recipes
    PullToRefreshBox(state.refreshing, onRefresh, Modifier.fillMaxSize().background(Sunny.Butter)) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 120.dp)) {
        item {
            // Same header as the other tabs, with the search field and the new recipe button below the title.
            HomeHeader("Recettes", list?.let {
                when {
                    state.query.isNotBlank() -> "${it.size} résultat${if (it.size > 1) "s" else ""}"
                    else -> "${it.size} recette${if (it.size > 1) "s" else ""}"
                }
            }, onPreferences) {
                    SearchField(state.query, onQuery)
                    if (canWrite) {
                        Button(
                            onClick = onNew, shape = Shapes.Control,
                            colors = ButtonDefaults.buttonColors(containerColor = Sunny.Peach, contentColor = Color.White),
                            modifier = Modifier.fillMaxWidth().padding(top = 16.dp).height(52.dp),
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(20.dp))
                            Text("Nouvelle recette", fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
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
                    if (state.query.isNotBlank()) "Aucune recette ne correspond à « ${state.query.trim()} »."
                    else if (canWrite) "Aucune recette pour le moment : ajoutez la première !" else "Aucune recette pour le moment.",
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
}

@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit) {
    TextField(
        value = query, onValueChange = onQuery, singleLine = true,
        placeholder = { Text("Rechercher un titre, un tag…") },
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
        modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
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
