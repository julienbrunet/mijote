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
    val manualTitle: String? = null,
    val chosenRecipe: MenuRecipe? = null,
    val removed: Boolean = false,
    val requestId: String? = null,
    val error: String? = null,
) {
    val recipe get() = if (removed || manualTitle != null) null else chosenRecipe ?: original?.recipe
    val title get() = if (removed || chosenRecipe != null) null else manualTitle ?: original?.title
    val contentChanged get() = recipe?.id != original?.recipe?.id || title != original?.title
    val dirty get() = when {
        removed -> original != null
        original == null && recipe == null && title == null -> false
        else -> contentChanged || people != initialPeople
    }

    fun replacement(moment: PlannerMoment): MenuSlot? = if (removed) null else MenuSlot(
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
                    "manualInput" to draft.manualInput, "manualTitle" to draft.manualTitle,
                    "chosenRecipe" to draft.chosenRecipe?.savedState(), "removed" to draft.removed,
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
                    original, state.getInt("initialPeople"), state.getInt("people"),
                    state.getString("manualInput").orEmpty(), state.getString("manualTitle"),
                    state.getBundle("chosenRecipe")?.savedRecipe(), state.getBoolean("removed"),
                    state.getString("requestId"), state.getString("error"),
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
