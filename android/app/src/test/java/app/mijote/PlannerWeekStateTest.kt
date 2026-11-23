package app.mijote

import app.mijote.data.GenerationInfo
import app.mijote.data.Menu
import app.mijote.data.MenuSlot
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.time.LocalDate

class PlannerWeekStateTest {
    private val monday = LocalDate.of(2026, 11, 2)
    private val next = monday.plusWeeks(1)
    private val info = GenerationInfo(10, 5, 7, emptyList())
    private val current = Menu(monday, emptyList(), info)
    private val later = Menu(monday, listOf(MenuSlot("planned", next, "lunch", null, true, title = "Pizza", version = 7)), info)
    private val state = PlannerWeekState(start = monday, menu = current, weeks = mapOf(monday to current, next to later))

    @Test fun snapshotResolvesTheExactLoadedWeekEvenWhenItIsEmptyOrBeyondTheAnchor() {
        assertSame(current, state.snapshot(monday.plusDays(6)))
        assertSame(later, state.snapshot(next.plusDays(4)))
        assertNull(state.snapshot(monday.minusDays(1)))
        assertNull(state.snapshot(next.plusWeeks(1)))
    }

    @Test fun aPendingOrFailedRefreshDisablesOnlyThatWeeksSnapshot() {
        val refreshing = state.copy(loading = true, requestWeek = next)
        assertSame(current, refreshing.snapshot(monday))
        assertNull(refreshing.snapshot(next))

        val failed = refreshing.copy(loading = false, error = "Connection lost")
        assertSame(current, failed.snapshot(monday))
        assertNull(failed.snapshot(next))
    }

    @Test fun anEdgeFailureKeepsTheLoadedPagesAvailable() {
        val failed = state.copy(beforeError = "Connection lost", loadingAfter = true)
        assertSame(current, failed.snapshot(monday))
        assertSame(later, failed.snapshot(next))
        assertNull(failed.snapshot(monday.minusDays(1)))
    }
}
