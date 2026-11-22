package app.mijote.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.UUID

class WeekCopyTest {
    private val from = LocalDate.of(2026, 10, 26)
    private val to = LocalDate.of(2026, 11, 2)
    private val info = GenerationInfo(10, 5, 7, emptyList())
    private val recipe = MenuRecipe("active", "Gratin", "vegetarian", 30)

    @Test
    fun fillingFreeSlotsPreservesVersionsAndContentsOfEveryOtherSlotInTheWriteRange() {
        val source = listOf(
            MenuSlot("past", from, "lunch", recipe, false, version = 20),
            MenuSlot("occupied", from.plusDays(2), "lunch", recipe, false, people = 4),
            MenuSlot("manual", from.plusDays(2), "dinner", null, true, title = "Pizza", people = 6),
            MenuSlot("empty", from.plusDays(3), "lunch", null, false),
            MenuSlot("archived", from.plusDays(3), "dinner", recipe.copy(id = "archived"), true),
            MenuSlot("copied", from.plusDays(4), "lunch", recipe, false, people = null, version = 12),
        )
        val savedLunch = MenuSlot("saved", to.plusDays(2), "lunch", null, true, title = "Soupe", people = 2, version = 5)
        val savedDinner = MenuSlot("kept", to.plusDays(3), "dinner", recipe, true, people = 3, version = 9)
        val outside = MenuSlot("outside", to.plusDays(6), "dinner", recipe, true, version = 14)
        val copy = WeekCopy(from, to, source, Menu(to.plusDays(2), listOf(outside, savedLunch, savedDinner), info), setOf("archived"))

        val plan = copy.plan()
        val proposal = requireNotNull(plan.proposal)
        assertEquals(listOf(2, 0, 1, 2, 1), listOf(plan.added, plan.replaced, plan.skippedOccupied, plan.skippedUnavailable, plan.skippedPast))
        assertEquals(to.plusDays(2), proposal.from)
        assertEquals(to.plusDays(4), proposal.to)
        assertEquals(listOf(SlotVersion("saved", 5), SlotVersion("kept", 9)), proposal.base)
        assertEquals(listOf(savedLunch, savedDinner), proposal.slots.filter { it.version > 0 })
        assertEquals("Pizza", proposal.slots[1].title)
        assertEquals(6, proposal.slots[1].people)
        assertTrue(proposal.slots[1].locked)
        assertNull(proposal.slots.last().people)
        for (slot in proposal.slots.filter { it.version == 0 }) {
            assertFalse(source.any { it.id == slot.id })
            assertEquals(slot.id, UUID.fromString(slot.id).toString())
        }

        val replacement = copy.plan(replaceOccupied = true)
        assertEquals(1, replacement.replaced)
        assertEquals(proposal.base, replacement.proposal?.base)
        assertEquals(proposal.slots.last(), replacement.proposal?.slots?.last())
        assertEquals("Soupe", savedLunch.title)
        assertEquals(12, source.last().version)
    }

    @Test
    fun emptySourceAndIdenticalDestinationNeverProduceWrites() {
        val saved = MenuSlot("saved", to, "dinner", recipe, false, version = 4)
        val destination = Menu(to, listOf(saved), info)
        assertNull(WeekCopy(from, to, emptyList(), destination, emptySet()).plan().proposal)

        val source = listOf(saved.copy(id = "source", date = from))
        val copy = WeekCopy(from, to, source, destination, emptySet())
        assertNull(copy.plan().proposal)
        assertNull(copy.plan(replaceOccupied = true).proposal)
    }

    @Test
    fun readsAnInclusiveRangeAndUsesTheExistingAtomicConfirmationRoute() {
        assertEquals(LocalDate.of(2025, 12, 29), LocalDate.of(2026, 1, 1).weekStart())
        val source = Menu(to, listOf(
            MenuSlot("past", from, "lunch", recipe.copy(id = "past"), true),
            MenuSlot("first", from.plusDays(1), "lunch", recipe, false, people = 4),
            MenuSlot("second", from.plusDays(1), "dinner", recipe, true, people = 3),
            MenuSlot("archived", from.plusDays(2), "lunch", recipe.copy(id = "archived"), true),
            MenuSlot("missing", from.plusDays(2), "dinner", recipe.copy(id = "missing"), true),
            MenuSlot("outside", from.plusDays(7), "lunch", recipe.copy(id = "outside"), true),
        ), info)
        val calls = mutableListOf<String>()
        val api = Api("https://srv", Http { method, url, body, bearer ->
            assertEquals("token", bearer)
            val path = url.removePrefix("https://srv")
            calls += "$method $path"
            when (path) {
                "/api/v1/menu" -> 200 to """{"today":"2026-11-03","slots":[],"generation":{}}"""
                "/api/v1/menu?from=2026-11-02&to=2026-11-08" ->
                    200 to """{"today":"2026-11-03","generation":{},"slots":[{"id":"occupied","date":"2026-11-03","meal":"lunch","version":16,"title":"Soupe"}]}"""
                "/api/v1/recipes/active" -> 200 to """{"id":"active","title":"Gratin","archivedAt":null}"""
                "/api/v1/recipes/archived" -> 200 to """{"id":"archived","title":"Ancien gratin","archivedAt":"2026-10-29T17:30:00Z"}"""
                "/api/v1/recipes/missing" -> 404 to """{"error":"not_found","message":"recipe not found"}"""
                "/api/v1/menu/confirm" -> {
                    val payload = JSONObject(body!!)
                    assertEquals("2026-11-03", payload.getString("from"))
                    assertEquals("2026-11-03", payload.getString("to"))
                    assertEquals(16, payload.getJSONArray("base").getJSONObject(0).getInt("version"))
                    assertEquals("Soupe", payload.getJSONArray("slots").getJSONObject(0).getString("title"))
                    assertEquals("active", payload.getJSONArray("slots").getJSONObject(1).getJSONObject("recipe").getString("id"))
                    409 to """{"error":"menu_changed","message":"The menu has changed"}"""
                }
                else -> error("Unexpected request: $method $path")
            }
        })

        assertEquals(to.plusDays(1), api.menu("token").today)
        val plan = api.weekCopy("token", source, from, to).plan()
        assertEquals(listOf(
            "GET /api/v1/menu",
            "GET /api/v1/menu?from=2026-11-02&to=2026-11-08",
            "GET /api/v1/recipes/active",
            "GET /api/v1/recipes/archived",
            "GET /api/v1/recipes/missing",
        ), calls)
        assertEquals(1, plan.added)
        assertEquals(1, plan.skippedPast)
        assertEquals(2, plan.skippedUnavailable)
        val failure = runCatching { api.confirmMenu("token", requireNotNull(plan.proposal)) }.exceptionOrNull()
        assertEquals("menu_changed", (failure as ApiException).code)
        assertEquals("POST /api/v1/menu/confirm", calls.last())
    }
}
