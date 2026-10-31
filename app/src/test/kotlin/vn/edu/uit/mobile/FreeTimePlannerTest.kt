package vn.edu.uit.mobile

import kotlinx.datetime.LocalDate
import org.junit.Assert.*
import org.junit.Test
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.model.*
import vn.edu.uit.mobile.schedule.*

class FreeTimePlannerTest {
    private val day = LocalDate(2026, 10, 26)

    @Test fun personalBlocksAreExplicitDraftsThatClearWhenThePlannerCloses() {
        val planner = FreeTimePlanner()
        planner.open(day)
        assertTrue(planner.uiState.value.personal.isEmpty())
        assertNull(planner.add("Lunch and travel", "12:00", "13:00"))
        val block = planner.uiState.value.personal.single()
        assertEquals("Lunch and travel", block.title)
        assertTrue(block.end > block.start)
        planner.remove(block.id)
        assertTrue(planner.uiState.value.personal.isEmpty())
        planner.add("Draft", "12:00", "13:00")
        planner.close()
        assertTrue(planner.uiState.value.personal.isEmpty())
    }

    @Test fun accountChangesClearDraftsWhileOrdinaryRefreshesPreserveThem() {
        val planner = FreeTimePlanner()
        planner.onAccounts(setOf("one", "calendar-only"))
        planner.open(day)
        planner.add("Draft", "12:00", "13:00")
        planner.onAccounts(setOf("one", "calendar-only"))
        assertEquals(1, planner.uiState.value.personal.size)
        planner.onAccounts(setOf("one"))
        assertTrue(planner.uiState.value.personal.isEmpty())
        assertNotNull(planner.uiState.value.message)
    }

    @Test fun invalidTimesAndTheBlockLimitReturnErrorsWithoutAddingDrafts() {
        val planner = FreeTimePlanner()
        planner.open(day)
        assertNotNull(planner.add("Overnight", "23:00", "01:00"))
        assertNotNull(planner.add("Bad", "24:00", "25:00"))
        assertNotNull(planner.add("", "12:00", "13:00"))
        repeat(10) { assertNull(planner.add("Draft $it", "12:00", "13:00")) }
        assertNotNull(planner.add("Limit", "12:00", "13:00"))
        assertEquals(10, planner.uiState.value.personal.size)
    }

    @Test fun aStaleFormCannotAddPrivateTextAfterItsAccountSessionWasReset() {
        val planner = FreeTimePlanner()
        planner.onAccounts(setOf("one", "calendar-only"))
        planner.open(day)
        val formSession = planner.uiState.value.session
        planner.onAccounts(setOf("one"))
        assertNotNull(planner.addForSession(formSession, "Private stale draft", "12:00", "13:00"))
        assertTrue(planner.uiState.value.personal.isEmpty())
    }

    @Test fun typedSettingsValidateBeforeTheyCanProduceCandidateGaps() {
        for (state in listOf(FreeTimeState(date = "invalid"), FreeTimeState(date = "2026-02-30"), FreeTimeState(start = "25:00"), FreeTimeState(minimum = "abc"))) {
            assertTrue(runCatching { state.options() }.isFailure)
        }
        val options = FreeTimeState(date = "$day", start = "07:00", end = "20:00", minimum = "30", buffer = "10").options()
        assertEquals(day, options.date)
        assertEquals(30, options.minimumMinutes)
        assertEquals(10, options.bufferMinutes)
    }
}
