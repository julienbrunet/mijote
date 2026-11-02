package app.mijote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mijote.EditorState
import app.mijote.data.RecipeDraft
import app.mijote.data.parseIngredient
import app.mijote.data.text

private val seasons = listOf("spring" to "Printemps", "summer" to "Été", "autumn" to "Automne", "winter" to "Hiver")

/**
 * Creates or edits a recipe by hand (FR-REC-06, FR-REC-11). Ingredients and steps are typed one
 * per line; ingredient lines are parsed on the device into quantity, unit and name (FR-REC-21),
 * with a preview of what will be saved. Quantities are for one person.
 */
@Composable
fun RecipeEditorScreen(state: EditorState, onChange: (RecipeDraft) -> Unit, onSave: () -> Unit, onBack: () -> Unit) {
    val d = state.draft
    Column(Modifier.fillMaxSize().background(Sunny.Butter).imePadding().verticalScroll(rememberScrollState())) {
        Box(
            Modifier.fillMaxWidth().background(Sunny.Card, RoundedCornerShape(bottomStart = 40.dp, bottomEnd = 40.dp)),
        ) {
            Column(Modifier.statusBarsPadding().padding(start = 12.dp, end = 22.dp, top = 8.dp, bottom = 22.dp)) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour", tint = Sunny.Ink)
                }
                Box(Modifier.padding(start = 10.dp, top = 8.dp)) {
                    ScreenTitle(if (state.id == null) "Nouvelle recette" else "Modifier la recette",
                        "Quantités pour une personne", size = 30)
                }
            }
        }
        Column(Modifier.padding(horizontal = 20.dp).padding(top = 16.dp, bottom = 32.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SunnyCard {
                Field(d.title, { onChange(d.copy(title = it)) }, "Titre", singleLine = true)
                Label("TYPE DE PLAT")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DishType.entries.forEach { t ->
                        val code = when (t) { DishType.MEAT -> "meat"; DishType.FISH -> "fish"; DishType.VEGETARIAN -> "vegetarian" }
                        Chip(t.label, d.dishType == code, Modifier.weight(1f)) { onChange(d.copy(dishType = code)) }
                    }
                }
                Label("REPAS")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("lunch" to "Midi", "dinner" to "Soir").forEach { (code, label) ->
                        Chip(label, code in d.mealTypes, Modifier.weight(1f)) {
                            onChange(d.copy(mealTypes = if (code in d.mealTypes) d.mealTypes - code else d.mealTypes + code))
                        }
                    }
                }
                Label("TEMPS (MINUTES)")
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.weight(1f)) { Field(d.prepMinutes, { onChange(d.copy(prepMinutes = it.filter(Char::isDigit).take(5))) }, "Préparation", singleLine = true, number = true) }
                    Box(Modifier.weight(1f)) { Field(d.cookMinutes, { onChange(d.copy(cookMinutes = it.filter(Char::isDigit).take(5))) }, "Cuisson", singleLine = true, number = true) }
                }
                Label("SAISONS (AUCUNE = TOUTE L'ANNÉE)")
                seasons.chunked(2).forEach { pair ->
                    Row(Modifier.padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        pair.forEach { (code, label) ->
                            Chip(label, code in d.seasons, Modifier.weight(1f)) {
                                onChange(d.copy(seasons = if (code in d.seasons) d.seasons - code else d.seasons + code))
                            }
                        }
                    }
                }
                Field(d.tags, { onChange(d.copy(tags = it)) }, "Tags, séparés par des virgules", singleLine = true)
            }
            SunnyCard {
                Label("INGRÉDIENTS, UN PAR LIGNE")
                Field(d.ingredients, { onChange(d.copy(ingredients = it)) }, "Par ex. 200 g de farine")
                val parsed = d.ingredients.lines().map { it.trim() }.filter { it.isNotEmpty() }.map(::parseIngredient)
                if (parsed.isNotEmpty()) {
                    Text("Compris comme :", fontSize = 12.sp, color = Sunny.InkSoft, modifier = Modifier.padding(top = 10.dp))
                    parsed.forEach { Text("•  ${it.text(1)}", fontSize = 13.sp, color = Sunny.InkTitle) }
                }
            }
            SunnyCard {
                Label("ÉTAPES, UNE PAR LIGNE")
                Field(d.steps, { onChange(d.copy(steps = it)) }, "Par ex. Préchauffer le four à 180 °C")
            }
            SunnyCard {
                Label("NOTES")
                Field(d.notes, { onChange(d.copy(notes = it)) }, "Moins de sel, cuisson plus longue…")
            }
            ErrorText(state.error)
            PrimaryButton(if (state.id == null) "Ajouter la recette" else "Enregistrer", state.saving, onSave)
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, fontSize = 11.sp, letterSpacing = 1.sp, fontWeight = FontWeight.SemiBold, color = Sunny.InkSoft,
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, label: String, singleLine: Boolean = false, number: Boolean = false) {
    TextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = singleLine,
        minLines = if (singleLine) 1 else 3,
        keyboardOptions = KeyboardOptions(
            capitalization = if (number) KeyboardCapitalization.None else KeyboardCapitalization.Sentences,
            keyboardType = if (number) KeyboardType.Number else KeyboardType.Text,
        ),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
            focusedIndicatorColor = Sunny.PeachDeep, unfocusedIndicatorColor = Sunny.Track,
            focusedLabelColor = Sunny.PeachDeep, unfocusedLabelColor = Sunny.InkSoft, cursorColor = Sunny.PeachDeep,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}
