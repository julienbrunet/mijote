package app.mijote.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class MenuProposalTest {
    private val day = LocalDate.of(2026, 11, 3)
    private val recipe = MenuRecipe("r1", "Gratin", "vegetarian", 30)
    private val saved = listOf(
        MenuSlot("a", day, "lunch", recipe, false, people = 2),
        MenuSlot("b", day, "dinner", null, true, title = "Pizza", people = 4),
        MenuSlot("c", day.plusDays(1), "lunch", null, true, title = "Restaurant", people = 6),
    )
    private fun draft() = MenuProposal(day, day.plusDays(1), saved, listOf(SlotVersion("a", 3), SlotVersion("b", 7), SlotVersion("c", 2)))

    @Test
    fun movingInBothDirectionsPreservesPositionsAndMealContentsWithoutChangingSavedMenu() {
        val before = draft()
        val moved = before.move("a", "c")
        assertEquals(listOf("a", "b", "c"), moved.slots.map { it.id })
        assertEquals(listOf(day, day, day.plusDays(1)), moved.slots.map { it.date })
        assertEquals(listOf("lunch", "dinner", "lunch"), moved.slots.map { it.meal })
        assertEquals(listOf("Pizza", "Restaurant", null), moved.slots.map { it.title })
        assertEquals(listOf(4, 6, 2), moved.slots.map { it.people })
        assertEquals(listOf(true, true, false), moved.slots.map { it.locked })
        assertEquals(saved, moved.move("c", "a").slots)
        assertEquals(saved, before.slots)
        assertEquals(3, before.base.first().version)
    }

    @Test
    fun editingRemovingAndAddingOnlyChangesTheDraftAndKeepsItsOriginalBaseline() {
        val original = draft()
        val renamed = original.withSlot(saved.first().copy(recipe = null, title = "Repas libre", people = 8, locked = true))
        val edited = renamed.copy(slots = renamed.slots.filterNot { it.id == "b" })
            .withSlot(MenuSlot("local-id", day.plusDays(1), "dinner", recipe, false, people = 3))
        assertEquals(listOf("a", "c", "local-id"), edited.slots.map { it.id })
        assertEquals("Repas libre", edited.slots.first().title)
        assertEquals(8, edited.slots.first().people)
        assertEquals(original.base, edited.base)
        assertEquals(recipe, saved.first().recipe)
        assertEquals(2, saved.first().people)
        assertEquals("Pizza", saved[1].title)
        val json = edited.toJson()
        assertEquals("Repas libre", json.getJSONArray("slots").getJSONObject(0).getString("title"))
        assertEquals(7, json.getJSONArray("base").getJSONObject(1).getInt("version"))
    }

    @Test
    fun previewsUseChronologicalLunchThenDinnerOrder() {
        val json = JSONObject("""{"from":"2026-11-03","to":"2026-11-03","base":[],"slots":[
          {"id":"d","date":"2026-11-03","meal":"dinner","title":"Pizza"},
          {"id":"l","date":"2026-11-03","meal":"lunch","title":"Soupe"}]}""")
        assertEquals(listOf("l", "d"), MenuProposal.parse(json).slots.map { it.id })
    }

    @Test
    fun requestingAndEditingAProposalDoesNotUseAnySavedMenuWriteEndpoint() {
        val calls = mutableListOf<String>()
        var confirmed = false
        val api = Api("https://srv", Http { method, url, body, _ ->
            calls += "$method $url"
            when {
                url.endsWith("/menu/preview") -> {
                    val response = draft().toJson()
                    response.getJSONArray("slots").getJSONObject(0).getJSONObject("recipe")
                        .put("title", recipe.title).put("dishType", recipe.dishType).put("prepMinutes", recipe.minutes)
                    200 to response.toString()
                }
                url.endsWith("/menu/preview/recipe") -> 200 to """{"id":"preview","date":"2026-11-03","meal":"lunch","recipe":{"id":"r2","title":"Soupe","dishType":"vegetarian","prepMinutes":15}}"""
                url.endsWith("/menu/confirm") -> {
                    confirmed = true
                    val payload = JSONObject(body!!)
                    assertEquals("Local edit", payload.getJSONArray("slots").getJSONObject(0).getString("title"))
                    200 to JSONObject().put("slots", payload.getJSONArray("slots")).toString()
                }
                else -> fail("Unexpected saved-menu request: $method $url").let { 500 to "{}" }
            }
        })
        val proposal = api.previewMenu("token", day, 2, MealPlan.LUNCH_DINNER)
        assertFalse(confirmed)
        val replacement = api.previewRecipe("token", day, "lunch", proposal.slots, "r1")
        assertEquals("r2", replacement.id)
        val edited = proposal.withSlot(proposal.slots.first().copy(recipe = null, title = "Local edit"))
        assertFalse(confirmed)
        assertEquals(2, calls.size)
        api.confirmMenu("token", edited)
        assertTrue(confirmed)
        assertEquals(3, calls.size)
    }
}
