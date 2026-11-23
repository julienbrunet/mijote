package app.mijote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mijote.R
import app.mijote.data.MenuSlot
import app.mijote.data.RecipeSummary

/** Contents of a meal sheet. All edits remain in the caller's draft until it saves. */
@Composable
internal fun PlannerMealEditor(
    moment: PlannerMoment,
    original: MenuSlot?,
    recipe: RecipeSummary?,
    people: Int,
    manualInput: String,
    manual: Boolean,
    removed: Boolean,
    canPlanRecipe: Boolean,
    canEdit: Boolean,
    busy: Boolean,
    dirty: Boolean,
    error: String?,
    scrollState: ScrollState = rememberScrollState(),
    onPeople: (Int) -> Unit,
    onManualInput: (String) -> Unit,
    onValidateManual: () -> Unit,
    onPick: () -> Unit,
    onOpen: (() -> Unit)?,
    onRemove: () -> Unit,
    onSave: () -> Unit,
) {
    val enabled = canEdit && !busy
    val hasMeal = !removed && (recipe != null || manual || original != null)
    val heading = when {
        !canEdit -> "Repas"
        original == null -> "Ajouter un repas"
        else -> "Modifier le repas"
    }
    Column(Modifier.fillMaxSize().background(Sunny.Cream).imePadding()) {
        AgendaHeader(heading, mealMoment(moment.date, moment.meal).replaceFirstChar { it.uppercase() })
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(scrollState)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AgendaCard {
                when {
                    removed -> AgendaSectionTitle("Repas à retirer")
                    manual -> {
                        AgendaSectionTitle("Repas libre")
                        if (!canEdit) original?.title?.let { Text(it, fontSize = 15.sp, color = Sunny.Ink) }
                    }
                    recipe != null -> MealEditorRecipe(recipe, onOpen, busy)
                    else -> Text(
                        if (original == null) "Créneau libre" else "Aucune recette associée",
                        fontSize = 15.sp, lineHeight = 20.sp, color = Sunny.InkTitle,
                    )
                }
                if (hasMeal) {
                    HorizontalDivider(color = Sunny.Track)
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text("Nombre de personnes", Modifier.weight(1f),
                            fontSize = 14.sp, lineHeight = 18.sp, color = Sunny.Ink)
                        if (canEdit) PlannerStepper(people, 1, 20, "Une personne", onPeople, enabled)
                        else Text(people.toString(), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Sunny.Ink)
                    }
                }
                if (canEdit && canPlanRecipe) {
                    AgendaAction(
                        if (hasMeal) "Planifier une autre recette" else "Choisir une recette",
                        onPick,
                        enabled = enabled,
                    )
                }
            }
            if (canEdit) {
                MealEditorManual(
                    manualInput, manual, hasMeal, enabled,
                    onManualInput = onManualInput, onValidate = onValidateManual,
                )
                if (original != null && !removed) {
                    AgendaAction(
                        "Retirer ce repas", onRemove, primary = true, warning = true, enabled = enabled,
                        icon = Icons.Filled.Delete,
                    )
                }
            }
        }
        if (canEdit) {
            HorizontalDivider(color = Sunny.Track)
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                if (error != null) {
                    Text(
                        error,
                        Modifier.fillMaxWidth().clip(Shapes.Control).background(Sunny.ErrorBg)
                            .semantics { liveRegion = LiveRegionMode.Polite }.padding(10.dp),
                        fontSize = 13.sp, lineHeight = 17.sp, color = Sunny.Ink,
                    )
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = if (error == null) 0.dp else 6.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    AgendaAction(
                        if (busy) "Enregistrement…" else "Enregistrer",
                        onSave, primary = true, enabled = enabled && dirty,
                    )
                }
            }
        }
    }
}

@Composable
private fun MealEditorRecipe(recipe: RecipeSummary, onOpen: (() -> Unit)?, busy: Boolean) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        RecipeThumb(recipe.image, dishEmoji(recipe.dishType), Modifier.size(64.dp).clip(Shapes.Control), emojiSize = 32.sp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(recipe.title, fontSize = 17.sp, lineHeight = 21.sp, fontWeight = FontWeight.Bold, color = Sunny.Ink)
            recipe.minutes?.let { Text("$it min", fontSize = 12.sp, lineHeight = 16.sp, color = Sunny.InkTitle) }
        }
        if (onOpen != null) {
            IconButton(onClick = onOpen, enabled = !busy, modifier = Modifier.size(48.dp)) {
                Icon(painterResource(R.drawable.ic_visibility), contentDescription = "Voir la recette", tint = Sunny.Ink)
            }
        }
    }
}

@Composable
private fun MealEditorManual(
    input: String,
    manual: Boolean,
    hasMeal: Boolean,
    enabled: Boolean,
    onManualInput: (String) -> Unit,
    onValidate: () -> Unit,
) {
    val trimmed = input.trim()
    val valid = trimmed.codePointCount(0, trimmed.length) in 1..100
    val focus = LocalFocusManager.current
    val validate = {
        onValidate()
        focus.clearFocus()
    }
    AgendaCard {
        AgendaSectionTitle(when {
            manual -> "Modifier le repas libre"
            hasMeal -> "Remplacer par un repas libre"
            else -> "Ajouter un repas libre"
        })
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = onManualInput,
                modifier = Modifier.weight(1f),
                label = { Text("Intitulé du repas") },
                enabled = enabled,
                singleLine = true,
                shape = Shapes.Control,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Sunny.InkTitle,
                    unfocusedBorderColor = Sunny.Slate,
                    focusedLabelColor = Sunny.Ink,
                    unfocusedLabelColor = Sunny.InkTitle,
                    cursorColor = Sunny.Ink,
                ),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { if (enabled && valid) validate() }),
            )
            AgendaAction("Valider", validate, enabled = enabled && valid)
        }
        if (trimmed.isNotEmpty() && !valid) Text("100 caractères maximum.", fontSize = 12.sp, color = Sunny.InkTitle)
    }
}
