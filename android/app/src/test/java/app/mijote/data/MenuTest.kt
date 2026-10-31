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
}
