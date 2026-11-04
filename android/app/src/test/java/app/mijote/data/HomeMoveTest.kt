package app.mijote.data

import app.mijote.ui.homeMoveDestinations
import app.mijote.ui.menuDays
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class HomeMoveTest {
    private val date = LocalDate.of(2026, 11, 4)
    private val info = GenerationInfo(10, 1, 7, List(7) { "lunch_dinner" })
    private val recipe = MenuRecipe("recipe", "Soupe", "vegetarian", 20)
    private val slots = listOf(
        MenuSlot("a", date, "lunch", recipe, false, people = 2, version = 3),
        MenuSlot("b", date, "dinner", null, true, title = "Pizza", people = 4, version = 7),
        MenuSlot("c", date.plusDays(1), "lunch", null, true, title = "Restaurant", people = 6, version = 2),
        MenuSlot("d", date.plusDays(2), "dinner", recipe, false, people = 1, version = 5),
    )
    private val menu = Menu(date, slots, info)

    @Test fun lunchToDinnerAndBackKeepDatesAndCarryAllMealContents() {
        val forward = menu.moveProposal("a", "b")!!
        assertEquals(listOf("lunch", "dinner"), forward.slots.map { it.meal })
        assertEquals(listOf("Pizza", null), forward.slots.map { it.title })
        assertEquals(listOf(4, 2), forward.slots.map { it.people })
        assertEquals(listOf(true, false), forward.slots.map { it.locked })
        assertEquals(recipe, forward.slots.last().recipe)
        val backward = menu.copy(slots = forward.slots + slots.drop(2)).moveProposal("b", "a")!!
        assertEquals(slots.take(2), backward.slots)
        assertEquals(slots, menu.slots)
    }

    @Test fun crossDayInsertShiftsMealsAndLimitsWritesToAffectedDays() {
        val move = menu.moveProposal("a", "c")!!
        assertEquals(date, move.from)
        assertEquals(date.plusDays(1), move.to)
        assertEquals(listOf("Pizza", "Restaurant", null), move.slots.map { it.title })
        assertEquals(listOf("a", "b", "c"), move.slots.map { it.id })
        assertEquals(listOf(SlotVersion("a", 3), SlotVersion("b", 7), SlotVersion("c", 2)), move.base)
        assertFalse(move.base.any { it.id == "d" })
        assertEquals(slots.take(3), Menu(date, move.slots, info).moveProposal("c", "a")!!.slots)
    }

    @Test fun movingToAnEmptyLunchOrDinnerDoesNotCreateAnEmptySavedMealOrShiftOtherMeals() {
        val destination = "${date.plusDays(1)}|dinner"
        val move = menu.moveProposal("a", destination)!!
        assertEquals(listOf("b", "c", destination), move.slots.map { it.id })
        assertEquals(slots[0].copy(id = destination, date = date.plusDays(1), meal = "dinner"), move.slots.last())
        assertEquals(listOf("a", "b", "c"), move.base.map { it.id })
        val backToLunch = menu.copy(slots = slots.drop(1)).moveProposal("b", "$date|lunch")!!
        assertEquals("lunch", backToLunch.slots.single().meal)
        assertEquals("Pizza", backToLunch.slots.single().title)
    }

    @Test fun invalidTargetsAndUnchangedPositionsDoNotProduceWrites() {
        assertNull(menu.moveProposal("a", "a"))
        assertNull(menu.moveProposal("missing", "b"))
        assertNull(menu.moveProposal("a", "bad-target"))
        assertNull(menu.moveProposal("a", "$date|breakfast"))
        assertNull(menu.moveProposal("a", "${date.minusDays(1)}|lunch"))
        assertNull(menu.moveProposal("a", "$date|dinner"))
    }

    @Test fun savedVersionsAreParsedAndSentToTheAtomicConfirmationEndpoint() {
        val slot = parseSlot(JSONObject("""{"id":"x","date":"2026-11-04","meal":"lunch","title":"Pizza","version":12}"""))
        assertEquals(12, slot.version)
        val source = Menu(date, listOf(slot), info)
        val proposal = source.moveProposal("x", "$date|dinner")!!
        var writes = 0
        val api = Api("https://srv", Http { method, url, body, _ ->
            assertEquals("POST", method)
            assertTrue(url.endsWith("/menu/confirm"))
            val json = JSONObject(body!!)
            assertEquals(12, json.getJSONArray("base").getJSONObject(0).getInt("version"))
            assertEquals("dinner", json.getJSONArray("slots").getJSONObject(0).getString("meal"))
            writes++
            200 to JSONObject().put("slots", json.getJSONArray("slots")).toString()
        })
        assertEquals(0, writes)
        api.confirmMenu("token", proposal)
        assertEquals(1, writes)
    }

    @Test fun destinationsIncludeBothSlotsAndEmptyDaysWithoutDuplicatingOccupiedMeals() {
        val days = homeMoveDestinations(menuDays(menu))
        assertEquals(3, days.size)
        assertEquals(List(3) { listOf("Midi", "Soir") }, days.map { day -> day.meals.map { it.slot } })
        assertEquals(2, days.flatMap { it.meals }.count { it.emptyDestination })
        val sparse = Menu(date, listOf(slots.first(), slots.last()), info)
        val sparseDays = homeMoveDestinations(menuDays(sparse))
        assertEquals(2, sparseDays[1].meals.count { it.emptyDestination })
        assertEquals(listOf("a", "d"), sparseDays.flatMap { it.meals }.filterNot { it.emptyDestination }.map { it.id })
    }
}
