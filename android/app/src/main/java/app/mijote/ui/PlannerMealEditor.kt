package app.mijote.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
    stagedRecipe: RecipeSummary?,
    stagedManual: String?,
    people: Int,
    manualInput: String,
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
    val originalTitle = original?.title
    val replacementRecipe = stagedRecipe?.takeUnless { it.id == recipe?.id }
    val replacementManual = stagedManual?.takeUnless { it == originalTitle }
    val trimmedInput = manualInput.trim()
    val pendingManualInput = trimmedInput != (stagedManual ?: originalTitle).orEmpty() &&
        (trimmedInput.isNotEmpty() || stagedRecipe == null && !removed)
    val showPeople = original != null || replacementRecipe != null || replacementManual != null
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
                AgendaSectionTitle(if (original == null) "Créneau libre" else "Repas prévu")
                if (recipe != null) {
                    MealEditorRecipe(recipe, onOpen, busy)
                } else if (originalTitle != null) {
                    Text(originalTitle, fontSize = 17.sp, lineHeight = 21.sp, fontWeight = FontWeight.Bold, color = Sunny.Ink)
                    Text("Repas libre", fontSize = 12.sp, color = Sunny.InkTitle)
                } else if (original != null) {
                    Text("Aucune recette associée", fontSize = 15.sp, lineHeight = 20.sp, color = Sunny.InkTitle)
                }
                if (showPeople) {
                    HorizontalDivider(color = Sunny.Track)
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text("Nombre de personnes", Modifier.weight(1f),
                            fontSize = 14.sp, lineHeight = 18.sp, color = Sunny.Ink)
                        if (canEdit) PlannerStepper(people, 1, 20, "Une personne", onPeople, enabled && !removed)
                        else Text(people.toString(), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Sunny.Ink)
                    }
                }
            }
            if (canEdit) {
                if (canPlanRecipe) {
                    MealEditorChangeCard(highlighted = replacementRecipe != null) {
                        AgendaSectionTitle(if (original == null) "Recette" else "Recette de remplacement")
                        if (replacementRecipe != null) {
                            Text("À enregistrer", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Sunny.Ink)
                            MealEditorRecipe(replacementRecipe, onOpen = null, busy = busy)
                        }
                        AgendaAction(
                            when {
                                replacementRecipe != null -> "Choisir une autre recette"
                                original != null -> "Planifier une autre recette"
                                else -> "Choisir une recette"
                            },
                            onPick,
                            enabled = enabled,
                        )
                    }
                }
                MealEditorManual(
                    input = manualInput,
                    label = when {
                        originalTitle != null -> "Modifier le repas libre"
                        original != null -> "Remplacer par un repas libre"
                        else -> "Ajouter un repas libre"
                    },
                    stagedTitle = replacementManual,
                    pendingInput = pendingManualInput,
                    enabled = enabled,
                    onManualInput = onManualInput, onValidate = onValidateManual,
                )
                if (original != null) {
                    if (removed) {
                        Text(
                            "Retrait du repas à enregistrer",
                            Modifier.semantics { liveRegion = LiveRegionMode.Polite }.padding(10.dp),
                            fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Sunny.Ink,
                        )
                    } else {
                        TextButton(
                            onClick = onRemove, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp),
                            colors = ButtonDefaults.textButtonColors(contentColor = Sunny.Ink),
                        ) {
                            Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(17.dp))
                            Text("Retirer ce repas", Modifier.padding(start = 6.dp), fontWeight = FontWeight.SemiBold)
                        }
                    }
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
private fun MealEditorChangeCard(highlighted: Boolean, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(Shapes.Control)
            .background(if (highlighted) Sunny.ButterLight else Sunny.Card)
            .border(1.dp, if (highlighted) Sunny.Butter else Sunny.Track, Shapes.Control)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
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
    label: String,
    stagedTitle: String?,
    pendingInput: Boolean,
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
    MealEditorChangeCard(highlighted = stagedTitle != null || pendingInput) {
        AgendaSectionTitle(label)
        if (stagedTitle != null) {
            Text("À enregistrer · $stagedTitle", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Sunny.Ink)
        }
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
        if (trimmed.isNotEmpty() && !valid) {
            Text("100 caractères maximum.", fontSize = 12.sp, color = Sunny.InkTitle)
        } else if (pendingInput) {
            Text("Validez l’intitulé avant d’enregistrer.", fontSize = 12.sp, color = Sunny.InkTitle)
        }
    }
}
