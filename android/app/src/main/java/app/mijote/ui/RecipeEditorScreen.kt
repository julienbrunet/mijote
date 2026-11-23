package app.mijote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mijote.EditorState
import app.mijote.data.RecipeDraft
import app.mijote.data.parseIngredient
import app.mijote.data.text

private val editorControlShape = RoundedCornerShape(12.dp)
private val dishTypes = listOf("meat" to DishType.MEAT, "fish" to DishType.FISH, "vegetarian" to DishType.VEGETARIAN)
private val mealTypes = listOf("lunch" to "Midi", "dinner" to "Soir")
private val seasons = listOf("spring" to "Printemps", "summer" to "Été", "autumn" to "Automne", "winter" to "Hiver")

/** Creates or edits a recipe, with an ingredient preview normalized to one person. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RecipeEditorScreen(state: EditorState, onChange: (RecipeDraft) -> Unit, onSave: () -> Unit, onBack: () -> Unit) {
    val draft = state.draft
    val safe = WindowInsets.safeDrawing
    Column(Modifier.fillMaxSize().background(Sunny.Cream).imePadding()) {
        Row(
            Modifier.fillMaxWidth().windowInsetsPadding(safe.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            AgendaAction("Retour", onBack, enabled = !state.saving, icon = Icons.AutoMirrored.Filled.ArrowBack)
        }
        Column(
            Modifier.weight(1f).windowInsetsPadding(safe.only(WindowInsetsSides.Horizontal))
                .verticalScroll(rememberScrollState()).padding(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AgendaHeader(if (state.id == null) "Nouvelle recette" else "Modifier la recette")
            Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AgendaCard {
                    AgendaSectionTitle("Informations")
                    EditorField(draft.title, { onChange(draft.copy(title = it)) }, "Titre de la recette")
                    EditorLabel("Type de plat")
                    FlowRow(
                        Modifier.selectableGroup(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        dishTypes.forEach { (code, dishType) ->
                            EditorChoice(dishType.label, draft.dishType == code, singleChoice = true) {
                                onChange(draft.copy(dishType = code))
                            }
                        }
                    }
                    EditorLabel("Repas")
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        mealTypes.forEach { (code, label) ->
                            EditorChoice(label, code in draft.mealTypes) { selected ->
                                val updated = if (selected) draft.mealTypes + code else draft.mealTypes - code
                                onChange(draft.copy(mealTypes = updated))
                            }
                        }
                    }
                }
                AgendaCard {
                    AgendaSectionTitle("Temps en minutes")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        EditorField(
                            draft.prepMinutes, { onChange(draft.copy(prepMinutes = it.filter(Char::isDigit).take(5))) },
                            "Préparation", Modifier.weight(1f), number = true,
                        )
                        EditorField(
                            draft.cookMinutes, { onChange(draft.copy(cookMinutes = it.filter(Char::isDigit).take(5))) },
                            "Cuisson", Modifier.weight(1f), number = true,
                        )
                    }
                }
                AgendaCard {
                    AgendaSectionTitle("Ingrédients")
                    Text("Quantités pour une personne.", fontSize = 13.sp, lineHeight = 17.sp, color = Sunny.InkTitle)
                    EditorField(draft.ingredients, { onChange(draft.copy(ingredients = it)) }, "Un ingrédient par ligne",
                        multiline = true, example = "200 g de farine")
                    val parsed = remember(draft.ingredients) {
                        draft.ingredients.lineSequence().map(String::trim).filter(String::isNotEmpty)
                            .map(::parseIngredient).toList()
                    }
                    if (parsed.isNotEmpty()) {
                        HorizontalDivider(color = Sunny.Track)
                        EditorLabel("Aperçu des quantités")
                        parsed.forEach {
                            Text("•  ${it.text(1)}", fontSize = 13.sp, lineHeight = 17.sp, color = Sunny.Ink)
                        }
                    }
                }
                AgendaCard {
                    AgendaSectionTitle("Préparation")
                    EditorField(draft.steps, { onChange(draft.copy(steps = it)) }, "Une étape par ligne",
                        multiline = true, example = "Préchauffer le four à 180 °C")
                }
                AgendaCard {
                    AgendaSectionTitle("Notes")
                    EditorField(draft.notes, { onChange(draft.copy(notes = it)) }, "Notes de la recette",
                        multiline = true, example = "Moins de sel, cuisson plus longue…")
                }
                AgendaCard {
                    AgendaSectionTitle("Saisons et tags")
                    Text("Sans saison sélectionnée, la recette convient toute l’année.",
                        fontSize = 13.sp, lineHeight = 17.sp, color = Sunny.InkTitle)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        seasons.forEach { (code, label) ->
                            EditorChoice(label, code in draft.seasons) { selected ->
                                val updated = if (selected) draft.seasons + code else draft.seasons - code
                                onChange(draft.copy(seasons = updated))
                            }
                        }
                    }
                    EditorField(draft.tags, { onChange(draft.copy(tags = it)) }, "Tags, séparés par des virgules")
                }
            }
        }
        Column(Modifier.fillMaxWidth().windowInsetsPadding(safe.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))) {
            HorizontalDivider(color = Sunny.Track)
            if (state.error != null) {
                Box(Modifier.padding(horizontal = 12.dp).semantics { liveRegion = LiveRegionMode.Polite }) {
                    ErrorText(state.error)
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                val label = when {
                    state.saving -> "Enregistrement…"
                    state.id == null -> "Ajouter la recette"
                    else -> "Enregistrer"
                }
                AgendaAction(label, onSave, primary = true, enabled = !state.saving)
            }
        }
    }
}

@Composable
private fun EditorLabel(text: String) {
    Text(text, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, color = Sunny.Ink)
}

@Composable
private fun EditorChoice(label: String, selected: Boolean, singleChoice: Boolean = false, onChange: (Boolean) -> Unit) {
    val fill = if (selected) Sunny.Butter else Sunny.Card
    val choice = if (singleChoice) {
        Modifier.selectable(selected, role = Role.RadioButton) { onChange(true) }
    } else {
        Modifier.toggleable(selected, role = Role.Checkbox, onValueChange = onChange)
    }
    Row(
        Modifier.heightIn(min = 48.dp).clip(editorControlShape).background(fill)
            .border(1.dp, if (selected) Sunny.Butter else Sunny.Slate, editorControlShape)
            .then(choice).padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected) Icon(Icons.Filled.Check, contentDescription = null, tint = Sunny.Ink, modifier = Modifier.size(16.dp))
        Text(label, fontSize = 13.sp, lineHeight = 17.sp, fontWeight = FontWeight.SemiBold, color = Sunny.Ink)
    }
}

@Composable
private fun EditorField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    multiline: Boolean = false,
    number: Boolean = false,
    example: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = example?.let { { Text(it) } },
        singleLine = !multiline,
        minLines = if (multiline) 3 else 1,
        keyboardOptions = KeyboardOptions(
            capitalization = if (number) KeyboardCapitalization.None else KeyboardCapitalization.Sentences,
            keyboardType = if (number) KeyboardType.Number else KeyboardType.Text,
        ),
        shape = editorControlShape,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = Sunny.Card, unfocusedContainerColor = Sunny.Card,
            focusedTextColor = Sunny.Ink, unfocusedTextColor = Sunny.Ink,
            focusedLabelColor = Sunny.Ink, unfocusedLabelColor = Sunny.InkTitle,
            focusedBorderColor = Sunny.InkTitle, unfocusedBorderColor = Sunny.Slate, cursorColor = Sunny.Ink,
        ),
        modifier = modifier.fillMaxWidth(),
    )
}
