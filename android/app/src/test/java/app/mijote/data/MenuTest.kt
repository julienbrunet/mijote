package app.mijote.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class MenuTest {
    private val weekdaysDinner = listOf("dinner", "dinner", "dinner", "dinner", "dinner", "lunch_dinner", "none")

    @Test
    fun parsesMenu() {
        val m = parseMenu(JSONObject("""{"today":"2026-11-02","generation":{"recipeCount":12,"minRecipes":10,"horizonDays":7,
            "defaultMeals":["dinner","dinner","dinner","dinner","dinner","lunch_dinner","none"]},
            "slots":[{"id":"s1","date":"2026-11-02","meal":"dinner","locked":true,
              "recipe":{"id":"r1","title":"Soupe","dishType":"vegetarian","prepMinutes":10,"totalMinutes":null}},
              {"id":"s2","date":"2026-11-03","meal":"dinner","locked":false,"recipe":null}]}"""))
        assertEquals(LocalDate.of(2026, 11, 2), m.today)
        assertEquals(10, m.slots[0].recipe!!.minutes)
        assertNull(m.slots[1].recipe)
        assertEquals(true, m.info.enoughRecipes)
        assertEquals("none", m.info.defaultMeals[6])
    }

    @Test
    fun periodFollowsThePlan() {
        val monday = LocalDate.of(2026, 11, 2)
        assertEquals(7, defaultMealCount(weekdaysDinner, monday, 7))
        assertEquals(LocalDate.of(2026, 11, 6), periodEnd(MealPlan.DEFAULT, weekdaysDinner, monday, 5))
        assertEquals(LocalDate.of(2026, 11, 9), periodEnd(MealPlan.DEFAULT, weekdaysDinner, monday, 8)) // Sunday skipped
        assertEquals(LocalDate.of(2026, 11, 3), periodEnd(MealPlan.LUNCH_DINNER, weekdaysDinner, monday, 3))
        assertNull(periodEnd(MealPlan.DEFAULT, List(7) { "none" }, monday, 1))
        assertEquals(monday, nextMonday(LocalDate.of(2026, 10, 31)))
        assertEquals(LocalDate.of(2026, 11, 9), nextMonday(monday))
    }

    @Test
    fun lunchOnlyPlanHasOneMealPerDay() {
        val d = LocalDate.of(2026, 11, 2)
        assertEquals(listOf("lunch"), mealsOn(MealPlan.LUNCH, emptyList(), d))
        assertEquals(LocalDate.of(2026, 11, 4), periodEnd(MealPlan.LUNCH, emptyList(), d, 3))
    }

    @Test
    fun recipeIngredientsAreMultipliedByPeople() {
        assertEquals("600 g farine, tamisée", IngredientLine(150.0, "g", "farine", "tamisée").text(4))
        assertEquals("1,5 c. à soupe pesto", IngredientLine(0.5, "tbsp", "pesto", null).text(3))
        assertEquals("pincée sel", IngredientLine(null, "pinch", "sel", null).text(2))
    }

    @Test
    fun periodImpactCountsReplacedAndKeptMeals() {
        val d = LocalDate.of(2026, 11, 3)
        val slots = listOf(
            MenuSlot("a", d, "dinner", null, locked = false),
            MenuSlot("b", d.plusDays(1), "dinner", null, locked = true),
            MenuSlot("c", d.plusDays(2), "lunch", null, locked = true, title = "Pizza"),
            MenuSlot("d", d.plusDays(9), "dinner", null, locked = false),
        )
        assertEquals(1 to 2, periodImpact(slots, d, d.plusDays(6)))
        assertEquals(0 to 0, periodImpact(slots, d.minusDays(5), d.minusDays(1)))
    }
}
