package app.mijote

import app.mijote.data.MenuRecipe
import app.mijote.data.MenuSlot
import app.mijote.data.recentRecipeDays
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class RandomMealRecipesTest {
    @Test
    fun recentFallbackKeepsOnlyTheMostRecentOccurrenceInsideFiveDates() {
        val day = LocalDate.of(2026, 11, 23)
        fun meal(id: String, daysAgo: Long, time: String = "dinner") =
            MenuSlot("$id-$daysAgo", day.minusDays(daysAgo), time, MenuRecipe(id, id, "fish", null), false)

        val slots = listOf(
            meal("old", 5), meal("edge", 4), meal("repeat", 4), meal("repeat", 1, "lunch"),
            meal("same-day", 0), meal("future", -1),
        )
        assertEquals(mapOf("edge" to 4, "repeat" to 1, "same-day" to 0), recentRecipeDays(slots, day))
    }
}
