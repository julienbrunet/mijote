package app.mijote.ui

import android.os.Bundle
import androidx.compose.runtime.saveable.Saver
import androidx.core.os.bundleOf
import app.mijote.data.MenuRecipe
import app.mijote.data.MenuSlot
import java.time.LocalDate
import java.util.UUID

/** Local changes stay in the sheet until the user saves the whole meal. */
internal data class PlannerMealDraft(
    val original: MenuSlot?,
    val initialPeople: Int,
    val people: Int = initialPeople,
    val manualInput: String = original?.title.orEmpty(),
    val chosenRecipe: MenuRecipe? = null,
    val requestId: String? = null,
    val error: String? = null,
) {
    val manualTitle get() = manualInput.trim().ifEmpty { null }
    val recipe get() = if (manualTitle != null) null else chosenRecipe ?: original?.recipe
    val title get() = manualTitle ?: original?.title?.takeIf { chosenRecipe == null }
    val contentChanged get() = recipe?.id != original?.recipe?.id || title != original?.title
    val canSave get() = (original != null || recipe != null || title != null) &&
        (contentChanged || people != initialPeople) &&
        title?.let { it.codePointCount(0, it.length) <= 100 } != false
    val saveLabel get() = when {
        title != null && title != original?.title -> "Enregistrer le repas libre"
        recipe != null && recipe?.id != original?.recipe?.id ->
            if (original == null) "Planifier la recette" else "Changer de recette"
        else -> "Enregistrer"
    }

    fun replacement(moment: PlannerMoment): MenuSlot = MenuSlot(
        id = original?.id ?: UUID.randomUUID().toString(),
        date = moment.date,
        meal = moment.meal,
        recipe = recipe,
        locked = contentChanged || original?.locked == true,
        title = title,
        people = people.takeIf { it != initialPeople || original?.people != null || contentChanged },
        version = original?.version ?: 0,
    )

    companion object {
        val saver = Saver<PlannerMealDraft?, Bundle>(
            save = { draft ->
                if (draft == null) null else bundleOf(
                    "original" to draft.original?.let { slot ->
                        bundleOf(
                            "id" to slot.id, "day" to slot.date.toEpochDay(), "meal" to slot.meal,
                            "locked" to slot.locked, "title" to slot.title, "people" to slot.people,
                            "version" to slot.version, "recipe" to slot.recipe?.savedState(),
                        )
                    },
                    "initialPeople" to draft.initialPeople, "people" to draft.people,
                    "manualInput" to draft.manualInput,
                    "chosenRecipe" to draft.chosenRecipe?.savedState(),
                    "requestId" to draft.requestId, "error" to draft.error,
                )
            },
            restore = { state ->
                val original = state.getBundle("original")?.let { slot ->
                    MenuSlot(
                        id = slot.getString("id").orEmpty(),
                        date = LocalDate.ofEpochDay(slot.getLong("day")),
                        meal = slot.getString("meal").orEmpty(),
                        recipe = slot.getBundle("recipe")?.savedRecipe(),
                        locked = slot.getBoolean("locked"),
                        title = slot.getString("title"),
                        people = slot.getInt("people").takeIf { it > 0 },
                        version = slot.getInt("version"),
                    )
                }
                PlannerMealDraft(
                    original = original, initialPeople = state.getInt("initialPeople"), people = state.getInt("people"),
                    manualInput = state.getString("manualInput").orEmpty(), chosenRecipe = state.getBundle("chosenRecipe")?.savedRecipe(),
                    requestId = state.getString("requestId"), error = state.getString("error"),
                )
            },
        )
    }
}

private fun MenuRecipe.savedState() = bundleOf(
    "id" to id, "title" to title, "dishType" to dishType, "minutes" to minutes, "image" to image,
)

private fun Bundle.savedRecipe() = MenuRecipe(
    getString("id").orEmpty(), getString("title").orEmpty(), getString("dishType").orEmpty(),
    getInt("minutes", -1).takeIf { it >= 0 }, getString("image"),
)
