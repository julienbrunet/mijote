package app.mijote.data

import java.time.LocalDate
import java.util.UUID

fun LocalDate.weekStart(): LocalDate = minusDays((dayOfWeek.value - 1).toLong())

data class WeekCopyPlan(
    val proposal: MenuProposal?,
    val added: Int,
    val replaced: Int,
    val skippedOccupied: Int,
    val skippedUnavailable: Int,
    val skippedPast: Int,
)

/** An editable copy preview based on one versioned read of the destination. */
class WeekCopy internal constructor(
    val from: LocalDate,
    val to: LocalDate,
    sourceSlots: List<MenuSlot>,
    destination: Menu,
    private val unavailableRecipes: Set<String>,
) {
    private val today = destination.today
    private val copied = sourceSlots.map { slot ->
        val day = slot.date.toEpochDay() - from.toEpochDay()
        slot.copy(id = UUID.randomUUID().toString(), date = to.plusDays(day), version = 0)
    }
    private val occupied = destination.slots.associateBy { it.date to it.meal }

    fun plan(replaceOccupied: Boolean = false): WeekCopyPlan {
        val changes = mutableListOf<MenuSlot>()
        var added = 0
        var replaced = 0
        var skippedOccupied = 0
        var skippedUnavailable = 0
        var skippedPast = 0

        for (meal in copied) {
            val existing = occupied[meal.date to meal.meal]
            when {
                meal.date < today -> skippedPast++
                meal.recipe?.id in unavailableRecipes || (meal.recipe == null && !meal.manual) -> skippedUnavailable++
                existing != null && (!replaceOccupied || sameMeal(existing, meal)) -> skippedOccupied++
                else -> {
                    if (existing == null) added++ else replaced++
                    changes += meal
                }
            }
        }

        val proposal = if (changes.isEmpty()) null else {
            val start = changes.minOf { it.date }
            val end = changes.maxOf { it.date }
            val baseline = occupied.values.filter { it.date in start..end }
            val finalSlots = baseline.associateBy { it.date to it.meal }.toMutableMap()
            changes.forEach { finalSlots[it.date to it.meal] = it }
            MenuProposal(
                start, end,
                finalSlots.values.sortedWith(compareBy<MenuSlot> { it.date }.thenBy { it.meal != "lunch" }),
                baseline.map { SlotVersion(it.id, it.version) },
            )
        }
        return WeekCopyPlan(proposal, added, replaced, skippedOccupied, skippedUnavailable, skippedPast)
    }

    private fun sameMeal(existing: MenuSlot, copy: MenuSlot): Boolean =
        existing.recipe?.id == copy.recipe?.id && existing.title == copy.title &&
            existing.locked == copy.locked && existing.people == copy.people
}
