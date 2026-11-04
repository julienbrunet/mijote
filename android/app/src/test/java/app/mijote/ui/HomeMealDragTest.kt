package app.mijote.ui

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.*
import org.junit.Test

class HomeMealDragTest {
    private val lunch = MealUi("Midi", "Soupe", null, null, "", false, id = "lunch")
    private val dinner = lunch.copy(id = "dinner", slot = "Soir", title = "Pizza")
    private val empty = lunch.copy(id = "tomorrow|lunch", title = "Déposer ici", emptyDestination = true)
    private fun drag() = HomeMealDrag().apply {
        viewport = Rect(0f, 0f, 400f, 500f)
        bounds[lunch.id] = Rect(0f, 0f, 400f, 100f)
        bounds[dinner.id] = Rect(0f, 100f, 400f, 200f)
        bounds[empty.id] = Rect(0f, 220f, 400f, 300f)
        source = lunch
    }

    @Test fun targetFollowsThePointerAcrossMealTypesAndEmptySlots() {
        val state = drag()
        val meals = listOf(lunch, dinner, empty)
        state.pointerY = 150f; state.updateTarget(meals)
        assertEquals(dinner, state.target)
        state.pointerY = 250f; state.updateTarget(meals)
        assertEquals(empty, state.target)
        state.pointerY = 50f; state.updateTarget(meals)
        assertNull(state.target)
    }

    @Test fun gapsAndTheFloatingNavigationCannotReceiveDrops() {
        val state = drag()
        val meals = listOf(lunch, dinner, empty)
        state.pointerY = 150f; state.updateTarget(meals)
        assertEquals(dinner, state.target)
        state.pointerY = 210f; state.updateTarget(meals)
        assertNull(state.target)
        state.bounds[dinner.id] = Rect(0f, 480f, 400f, 580f)
        state.pointerY = 520f; state.updateTarget(meals)
        assertNull(state.target)
    }

    @Test fun cancellingClearsBothSourceAndDestination() {
        val state = drag()
        state.pointerY = 150f; state.updateTarget(listOf(lunch, dinner))
        state.cancel()
        assertNull(state.source)
        assertNull(state.target)
    }
}
