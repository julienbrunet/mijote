package app.mijote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mijote.RecipeState
import app.mijote.data.RecipeDetail
import app.mijote.data.text

/** Recipe detail with portions, preparation and actions for the signed-in user's role. */
@Composable
fun RecipeScreen(
    state: RecipeState,
    people: Int,
    onBack: () -> Unit,
    canWrite: Boolean = false,
    canArchive: Boolean = false,
    onPlan: () -> Unit = {},
    onEdit: () -> Unit = {},
    onArchive: () -> Unit = {},
    onFavorite: () -> Unit = {},
    onWish: (() -> Unit)? = null,
    /** Set when opened from a planned meal by a member: the number of people is saved for that meal. */
    onMealPeople: ((Int) -> Unit)? = null,
) {
    var confirmArchive by rememberSaveable(state.id) { mutableStateOf(false) }
    if (confirmArchive) AlertDialog(
        onDismissRequest = { confirmArchive = false },
        containerColor = Sunny.Cream,
        title = { RecipeSectionTitle("Archiver cette recette ?") },
        text = { Text("Elle disparaît de la liste et n’est plus proposée dans les nouveaux menus. " +
            "Elle reste dans les menus passés et peut être restaurée depuis l’administration web.", color = Sunny.Ink) },
        confirmButton = {
            AgendaAction("Archiver", {
                confirmArchive = false
                onArchive()
            }, primary = true, warning = true, enabled = !state.busy)
        },
        dismissButton = { AgendaAction("Annuler", { confirmArchive = false }) },
    )

    val recipe = state.recipe
    val wishAction = onWish?.takeIf { recipe != null && !recipe.archived }
    val contentInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
    val scrollState = key(state.id) { rememberScrollState() }
    Scaffold(
        containerColor = Sunny.Cream,
        contentWindowInsets = contentInsets,
        topBar = {
            Row(
                Modifier.fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top))
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AgendaAction("Retour", onBack, icon = Icons.AutoMirrored.Filled.ArrowBack)
                if (recipe != null) RecipeFavorite(recipe.favorite, onFavorite)
            }
        },
        bottomBar = {
            if (recipe != null && (canWrite || wishAction != null)) {
                Column(Modifier.fillMaxWidth().background(Sunny.Cream).windowInsetsPadding(contentInsets)) {
                    HorizontalDivider(color = Sunny.Track)
                    if (!canWrite && state.wishError != null) {
                        Box(Modifier.padding(horizontal = 12.dp)) { ErrorText(state.wishError) }
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        if (canWrite) {
                            AgendaAction("Ajouter au menu", onPlan, primary = true, enabled = !state.busy,
                                icon = Icons.Filled.DateRange)
                        } else if (wishAction != null) {
                            val label = when {
                                state.wishing -> "Envoi…"
                                state.wish?.requested == true -> "Envie envoyée"
                                else -> "J’ai envie de cette recette"
                            }
                            AgendaAction(label, wishAction, primary = true,
                                enabled = !state.wishing && state.wish?.requested != true && state.wish?.remaining != 0)
                        }
                    }
                }
            }
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
                .verticalScroll(scrollState).padding(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AgendaHeader(recipe?.title ?: state.title)
            Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (recipe == null) {
                    if (state.error != null) {
                        ErrorText(state.error)
                    } else {
                        Box(Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = Sunny.PeachDeep)
                        }
                    }
                } else {
                    RecipeThumb(
                        recipe.image, dishEmoji(recipe.dishType),
                        Modifier.fillMaxWidth().height(if (recipe.image == null) 140.dp else 196.dp).clip(Shapes.Control),
                        emojiSize = 54.sp,
                    )
                    RecipeOverview(recipe)
                    if (canWrite) {
                        ErrorText(state.error)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                            AgendaAction("Modifier", onEdit, enabled = !state.busy)
                            if (canArchive) AgendaAction("Archiver", { confirmArchive = true }, enabled = !state.busy)
                        }
                    }
                    if (wishAction != null) {
                        val wish = state.wish
                        val hint = when {
                            wish?.requested == true -> "Les personnes qui créent les menus ont été informées."
                            wish != null -> {
                                val suffix = if (wish.remaining > 1) "s" else ""
                                "${wish.remaining} demande$suffix restante$suffix cette semaine · maximum ${wish.weeklyLimit}."
                            }
                            else -> "Partagez votre envie avec les personnes qui créent les menus."
                        }
                        RecipeDetailCard {
                            Text(hint, color = Sunny.InkTitle, fontSize = 13.sp, lineHeight = 18.sp)
                        }
                    }
                    RecipeInstructions(recipe, state.slotPeople ?: people, onMealPeople)
                }
            }
        }
    }
}

@Composable
private fun RecipeFavorite(favorite: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    IconToggleButton(
        checked = favorite,
        onCheckedChange = { onClick() },
        modifier = Modifier.size(48.dp).clip(shape).background(if (favorite) Sunny.ButterLight else Sunny.Card)
            .border(1.dp, Sunny.Track, shape),
    ) {
        Icon(
            if (favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
            contentDescription = if (favorite) "Retirer de mes favoris" else "Ajouter à mes favoris",
            tint = if (favorite) Sunny.Error else Sunny.Ink,
        )
    }
}

@Composable
private fun RecipeOverview(recipe: RecipeDetail) {
    val dishType = dishTypeOf(recipe.dishType)
    val times = listOfNotNull(
        recipe.prepMinutes?.let { "Préparation" to it },
        recipe.cookMinutes?.let { "Cuisson" to it },
        recipe.totalMinutes?.let { "Total" to it },
    )
    if (dishType != null || times.isNotEmpty()) RecipeDetailCard {
        dishType?.let { DishTag(it.label, it.colors) }
        if (times.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                times.forEach { (label, minutes) ->
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("$minutes min", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Sunny.Ink,
                            textAlign = TextAlign.Center)
                        Text(label, fontSize = 12.sp, color = Sunny.InkTitle, textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}

@Composable
private fun RecipeInstructions(recipe: RecipeDetail, defaultPeople: Int, onMealPeople: ((Int) -> Unit)?) {
    var people by rememberSaveable(recipe.id) { mutableIntStateOf(defaultPeople) }
    fun setPortions(value: Int) {
        people = value
        onMealPeople?.invoke(value)
    }

    if (recipe.ingredients.isNotEmpty()) RecipeDetailCard {
        RecipeSectionTitle("Ingrédients")
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val portions = if (people > 1) "Pour $people personnes" else "Pour 1 personne"
            Text(
                portions + if (onMealPeople != null) " · pour ce repas" else "",
                Modifier.weight(1f), fontSize = 13.sp, lineHeight = 17.sp, color = Sunny.InkTitle,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                PeopleStep("−", "Une personne de moins", people > 1) { setPortions(people - 1) }
                Text("$people", Modifier.width(30.dp), fontSize = 16.sp, color = Sunny.Ink,
                    fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                PeopleStep("+", "Une personne de plus", people < 20) { setPortions(people + 1) }
            }
        }
        HorizontalDivider(color = Sunny.Track)
        recipe.ingredients.forEach {
            Text("•  ${it.text(people)}", fontSize = 15.sp, color = Sunny.Ink, lineHeight = 20.sp)
        }
    }
    if (recipe.steps.isNotEmpty()) RecipeDetailCard {
        RecipeSectionTitle("Préparation")
        recipe.steps.forEachIndexed { index, step ->
            if (index > 0) HorizontalDivider(color = Sunny.Track)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(26.dp).background(Sunny.ButterLight, CircleShape), contentAlignment = Alignment.Center) {
                    Text("${index + 1}", color = Sunny.Ink, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
                Text(step, Modifier.weight(1f).padding(top = 2.dp), fontSize = 15.sp, color = Sunny.Ink, lineHeight = 21.sp)
            }
        }
    }
    recipe.notes?.let { notes ->
        RecipeDetailCard {
            RecipeSectionTitle("Notes")
            Text(notes, fontSize = 15.sp, color = Sunny.Ink, lineHeight = 21.sp)
        }
    }
}

@Composable
private fun RecipeDetailCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(Shapes.Control).background(Sunny.Card)
            .border(1.dp, Sunny.Track, Shapes.Control).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
private fun RecipeSectionTitle(title: String) {
    Text(title, Modifier.semantics { heading() }, fontSize = 17.sp, lineHeight = 21.sp,
        fontWeight = FontWeight.Bold, color = Sunny.Ink)
}

@Composable
private fun PeopleStep(sign: String, label: String, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick, enabled = enabled, modifier = Modifier.size(48.dp).semantics { contentDescription = label }) {
        Box(
            Modifier.size(34.dp).background(if (enabled) Sunny.ButterLight else Sunny.Track, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(sign, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = if (enabled) Sunny.Ink else Sunny.InkTitle)
        }
    }
}
