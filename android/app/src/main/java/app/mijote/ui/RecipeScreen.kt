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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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

private fun dishType(code: String) = when (code) {
    "meat" -> DishType.MEAT
    "fish" -> DishType.FISH
    "vegetarian" -> DishType.VEGETARIAN
    else -> null
}

/** Recipe detail: picture, times, ingredients for the user's number of people, steps. */
@Composable
fun RecipeScreen(state: RecipeState, people: Int, onBack: () -> Unit) {
    val r = state.recipe
    Column(Modifier.fillMaxSize().background(Sunny.Butter).verticalScroll(rememberScrollState())) {
        Box(Modifier.fillMaxWidth().height(260.dp).clip(RoundedCornerShape(bottomStart = 40.dp, bottomEnd = 40.dp))) {
            RecipeThumb(r?.image, dishEmoji(r?.dishType), Modifier.fillMaxSize(), emojiSize = 90.sp)
            Box(
                Modifier.statusBarsPadding().padding(16.dp).size(44.dp).background(Color.White.copy(alpha = 0.9f), CircleShape)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour", tint = Sunny.Ink) }
        }
        Column(Modifier.padding(horizontal = 22.dp).padding(top = 22.dp, bottom = 48.dp).navigationBarsPadding()) {
            Text(state.title, fontSize = 28.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold, color = Sunny.InkTitle)
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

private fun dishEmoji(type: String?) = when (type) { "meat" -> "🍗"; "fish" -> "🐟"; "vegetarian" -> "🥗"; else -> "🍽️" }

@Composable
private fun Content(r: RecipeDetail, people: Int) {
    Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        dishType(r.dishType)?.let { DishTag(it.label, it.colors) }
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
private fun Section(title: String, hint: String?) {
    Row(Modifier.fillMaxWidth().padding(top = 28.dp, bottom = 12.dp, start = 4.dp), verticalAlignment = Alignment.Bottom) {
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Sunny.Ink, modifier = Modifier.weight(1f))
        hint?.let { Text(it, fontSize = 13.sp, color = Sunny.InkTitle) }
    }
}
