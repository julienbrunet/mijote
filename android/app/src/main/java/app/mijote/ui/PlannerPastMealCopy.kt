package app.mijote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mijote.data.MenuSlot
import java.time.LocalDate

@Composable
internal fun PlannerPastMealCopy(
    source: MenuSlot,
    today: LocalDate,
    date: LocalDate,
    meal: String,
    occupied: Boolean,
    busy: Boolean,
    error: String?,
    onDate: (LocalDate) -> Unit,
    onMeal: (String) -> Unit,
    onConfirm: () -> Unit,
    onBack: () -> Unit,
) {
    val dates = List(20) { today.plusDays(it + 1L) }
    val title = source.title ?: source.recipe?.title
    val sourcePeople = source.people?.let { if (it == 1) "1 personne" else "$it personnes" }
    val notice = error ?: when {
        title.isNullOrBlank() -> "Ce repas n’a ni recette ni intitulé à copier."
        occupied -> "Un repas est déjà prévu sur ce créneau. Choisissez-en un autre."
        else -> null
    }
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AgendaCard {
            AgendaSectionTitle("Copier un repas passé")
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val recipe = source.recipe
                RecipeThumb(
                    recipe?.image, if (recipe == null && title != null) "📝" else dishEmoji(recipe?.dishType),
                    Modifier.size(40.dp).clip(Shapes.Control).clearAndSetSemantics {}, emojiSize = 21.sp,
                )
                Column(Modifier.weight(1f)) {
                    Text(title ?: "Repas sans recette", fontSize = 15.sp, lineHeight = 19.sp,
                        fontWeight = FontWeight.SemiBold, color = Sunny.Ink)
                    val sourceDate = mealMoment(source.date, source.meal).replaceFirstChar { it.uppercase() }
                    Text(listOfNotNull(sourceDate, sourcePeople).joinToString(" · "),
                        fontSize = 12.sp, lineHeight = 16.sp, color = Sunny.InkTitle)
                }
            }
            PlannerSelect(
                "Date de destination", date, dates,
                { "${dayLabel(it, today)} · ${periodLabel(it, it)}" },
                !busy, onDate, Modifier.fillMaxWidth(),
            )
            Row(
                Modifier.fillMaxWidth().selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf("lunch" to "Midi", "dinner" to "Soir").forEach { (value, label) ->
                    FilterChip(
                        selected = meal == value,
                        onClick = { onMeal(value) },
                        enabled = !busy,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        shape = Shapes.Control,
                        colors = FilterChipDefaults.filterChipColors(
                            labelColor = Sunny.Ink, selectedLabelColor = Sunny.Ink,
                            selectedContainerColor = Sunny.Butter,
                        ),
                        label = {
                            Text(label, Modifier.fillMaxWidth(), fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Center)
                        },
                    )
                }
            }
            if (notice != null) Text(
                notice, Modifier.fillMaxWidth().clip(Shapes.Control).background(Sunny.ErrorBg)
                    .semantics { liveRegion = LiveRegionMode.Polite }.padding(8.dp),
                fontSize = 12.sp, lineHeight = 16.sp, color = Sunny.Ink,
            )
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) {
            AgendaAction("Retour", onBack, enabled = !busy)
            AgendaAction(
                if (busy) "Copie en cours…" else "Copier ce repas",
                onConfirm, primary = true,
                enabled = !busy && !occupied && date in dates && !title.isNullOrBlank(),
            )
        }
    }
}
