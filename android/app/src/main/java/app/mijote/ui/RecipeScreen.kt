package app.mijote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mijote.RecipeState
import app.mijote.data.RecipeDetail
import app.mijote.data.text


/**
 * Recipe detail: picture, times, ingredients for the user's number of people, steps. Members can
 * plan it on a day and edit it; the admin can archive it.
 */
@Composable
fun RecipeScreen(
    state: RecipeState, people: Int, onBack: () -> Unit,
    canWrite: Boolean = false, canArchive: Boolean = false,
    onPlan: () -> Unit = {}, onEdit: () -> Unit = {}, onArchive: () -> Unit = {},
    onFavorite: () -> Unit = {},
) {
    var confirmArchive by remember { mutableStateOf(false) }
    if (confirmArchive) AlertDialog(
        onDismissRequest = { confirmArchive = false },
        containerColor = Sunny.Card,
        title = { Text("Archiver cette recette ?", fontWeight = FontWeight.Bold, color = Sunny.Ink, fontSize = 18.sp) },
        text = { Text("Elle disparaît de la liste et n'est plus proposée dans les menus, mais elle n'est pas supprimée : " +
            "les menus passés la gardent et elle peut être restaurée depuis l'administration web.", color = Sunny.Ink) },
        confirmButton = { TextButton(onClick = { confirmArchive = false; onArchive() }) { Text("Archiver", color = Sunny.Error, fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onClick = { confirmArchive = false }) { Text("Annuler", color = Sunny.Slate) } },
    )
    val r = state.recipe
    Column(Modifier.fillMaxSize().background(Sunny.Butter).verticalScroll(rememberScrollState())) {
        Box(Modifier.fillMaxWidth().height(260.dp).clip(RoundedCornerShape(bottomStart = 40.dp, bottomEnd = 40.dp))) {
            RecipeThumb(r?.image, dishEmoji(r?.dishType), Modifier.fillMaxSize(), emojiSize = 90.sp)
            Box(
                Modifier.statusBarsPadding().padding(16.dp).size(44.dp).background(Color.White.copy(alpha = 0.9f), CircleShape)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour", tint = Sunny.Ink) }
            // Personal favorite: every user, read-only ones included, has their own list.
            if (r != null) Box(
                Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(16.dp).size(44.dp)
                    .background(Color.White.copy(alpha = 0.9f), CircleShape).clip(CircleShape).clickable(onClick = onFavorite),
                contentAlignment = Alignment.Center,
            ) {
                Icon(if (r.favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    contentDescription = if (r.favorite) "Retirer de mes favoris" else "Ajouter à mes favoris", tint = Sunny.PeachDeep)
            }
        }
        Column(Modifier.padding(horizontal = 22.dp).padding(top = 22.dp, bottom = 48.dp).navigationBarsPadding()) {
            Text(r?.title ?: state.title, fontSize = 28.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold, color = Sunny.InkTitle)
            if (r != null && canWrite) {
                Button(
                    onClick = onPlan, enabled = !state.busy, shape = Shapes.Control,
                    colors = ButtonDefaults.buttonColors(containerColor = Sunny.PeachDeep, contentColor = Color.White),
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp).height(52.dp),
                ) {
                    Icon(Icons.Filled.DateRange, contentDescription = null, modifier = Modifier.size(20.dp))
                    Text("Ajouter au menu", fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.padding(start = 8.dp))
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallAction("Modifier", Icons.Filled.Edit, Sunny.Ink, Modifier.weight(1f), enabled = !state.busy, onClick = onEdit)
                    if (canArchive) SmallAction("Archiver", Icons.Filled.Delete, Sunny.Error, Modifier.weight(1f), enabled = !state.busy) {
                        confirmArchive = true
                    }
                }
                if (state.error != null) ErrorText(state.error)
            }
            when {
                r != null -> Content(r, people)
                state.error != null -> ErrorText(state.error)
                else -> Box(Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Sunny.PeachDeep)
                }
            }
        }
    }
}


@Composable
private fun Content(r: RecipeDetail, people: Int) {
    Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        dishTypeOf(r.dishType)?.let { DishTag(it.label, it.colors) }
    }
    val times = listOfNotNull(r.prepMinutes?.let { "Préparation" to it }, r.cookMinutes?.let { "Cuisson" to it },
        r.totalMinutes?.let { "Total" to it })
    if (times.isNotEmpty()) {
        Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            times.forEach { (label, min) ->
                Column(
                    Modifier.weight(1f).background(Sunny.Card, Shapes.Control).padding(vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("$min min", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Sunny.Ink)
                    Text(label, fontSize = 11.sp, color = Sunny.InkSoft)
                }
            }
        }
    }
    if (r.ingredients.isNotEmpty()) {
        Section("Ingrédients", if (people > 1) "pour $people personnes" else "pour 1 personne")
        Column(Modifier.fillMaxWidth().background(Sunny.Card, Shapes.Card).padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            r.ingredients.forEach { Text("•  ${it.text(people)}", fontSize = 15.sp, color = Sunny.Ink, lineHeight = 20.sp) }
        }
    }
    if (r.steps.isNotEmpty()) {
        Section("Préparation", null)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            r.steps.forEachIndexed { i, step ->
                Row(Modifier.fillMaxWidth().background(Sunny.Card, Shapes.Card).padding(16.dp)) {
                    Box(Modifier.size(28.dp).background(Sunny.Peach, CircleShape), contentAlignment = Alignment.Center) {
                        Text("${i + 1}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                    Text(step, fontSize = 15.sp, color = Sunny.Ink, lineHeight = 21.sp, modifier = Modifier.padding(start = 14.dp).weight(1f))
                }
            }
        }
    }
    r.notes?.let {
        Section("Notes", null)
        Text(it, fontSize = 15.sp, color = Sunny.Ink, lineHeight = 21.sp,
            modifier = Modifier.fillMaxWidth().background(Sunny.ButterLight, Shapes.Card).padding(16.dp))
    }
}

@Composable
private fun SmallAction(text: String, icon: ImageVector, color: Color, modifier: Modifier, enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier.height(46.dp).clip(Shapes.Control).background(Sunny.Card).clickable(enabled = enabled, onClick = onClick),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = color, modifier = Modifier.padding(start = 6.dp))
    }
}

@Composable
private fun Section(title: String, hint: String?) {
    Row(Modifier.fillMaxWidth().padding(top = 28.dp, bottom = 12.dp, start = 4.dp), verticalAlignment = Alignment.Bottom) {
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Sunny.Ink, modifier = Modifier.weight(1f))
        hint?.let { Text(it, fontSize = 13.sp, color = Sunny.InkTitle) }
    }
}
