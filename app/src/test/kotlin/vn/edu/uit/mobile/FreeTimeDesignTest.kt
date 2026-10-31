package vn.edu.uit.mobile

import android.app.Application
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import kotlinx.datetime.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import vn.edu.uit.mobile.common.*
import vn.edu.uit.mobile.designsystem.*
import vn.edu.uit.mobile.model.*
import vn.edu.uit.mobile.schedule.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FreeTimeDesignTest {
    @get:Rule val compose = createComposeRule()

    @After fun restoreFontScale() {
        RuntimeEnvironment.setFontScale(1f)
    }
    private val day = LocalDate(2026, 10, 26)
    private val slot = DaaTimetableSlot("IT004.O21", "IT004", "Database Systems", 3, 1, 1, 3, "A101", "Synthetic lecturer", "$day", "2026-12-28")
    private val snapshot = PortalSnapshot(timetable = listOf(slot), events = listOf(CalendarEvent("synthetic:1", 1, "synthetic", null, "Lab preparation", dateAt(day, LocalTime(13, 0)), dateAt(day, LocalTime(14, 0)))), updatedAt = dateAt(day, LocalTime(6, 0)))

    @Test fun candidateIntervalsExposeTheirBufferedBoundariesWithoutCreatingAnEvent() {
        val planner = FreeTimePlanner().apply { open(day) }
        compose.setContent {
            val state by planner.uiState.collectAsState()
            Frame { Content(snapshot, state, planner) }
        }
        capture("free-time-390-phone")
        compose.onNodeWithTag("free-time-content").performScrollToNode(hasText("Candidate gaps · 2"))
        compose.onNodeWithTag("free-time-content").performScrollToNode(hasText("Inspect interval"))
        capture("free-time-results-390-phone")
        compose.onAllNodesWithText("Inspect interval").onFirst().performScrollTo().performClick()
        compose.onNodeWithText("Candidate interval").assertIsDisplayed()
        compose.onNodeWithText("After Database Systems · buffer ends 10:00").assertIsDisplayed()
        compose.onNodeWithText("Before Lab preparation · buffer begins 12:45").assertIsDisplayed()
        assertTrue(planner.uiState.value.personal.isEmpty())
        compose.onNodeWithText("Close interval").performClick()
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-mdpi")
    fun personalBlocksRequireValidExplicitAddsAndCanBeRemoved() {
        val planner = FreeTimePlanner().apply { open(day) }
        compose.setContent {
            val state by planner.uiState.collectAsState()
            Frame { Content(snapshot, state, planner) }
        }
        capture("free-time-360-phone")
        compose.onNodeWithText("Add personal block").performScrollTo().performClick()
        compose.onNodeWithText("Block title").performTextInput("Lunch and travel")
        compose.onNodeWithText("Block end · HH:mm").performTextReplacement("11:00")
        compose.onNodeWithText("Add draft block").performClick()
        compose.onNodeWithText("The personal block must end after its start on the same day.").assertExists()
        assertTrue(planner.uiState.value.personal.isEmpty())
        capture("free-time-personal-block-360-phone")
        compose.onNodeWithText("Block end · HH:mm").performTextReplacement("13:00")
        compose.onNodeWithText("Add draft block").performClick()
        assertEquals(1, planner.uiState.value.personal.size)
        compose.onNodeWithText("Remove personal block").performScrollTo().performClick()
        assertTrue(planner.uiState.value.personal.isEmpty())
    }

    @Test
    @Config(qualifiers = "w412dp-h915dp-mdpi")
    fun unknownExamEndIsExplainedOnLargeTextPhones() {
        RuntimeEnvironment.setFontScale(1.3f)
        val planner = FreeTimePlanner().apply { open(day) }
        val data = snapshot.copy(exams = listOf(DaaExamSlot(slot.classCode, "Database exam", 3, "$day", "A204")))
        compose.setContent {
            val state by planner.uiState.collectAsState()
            Frame { Content(data, state, planner) }
        }
        compose.onNodeWithTag("free-time-content").performScrollToNode(hasText("Candidate gaps · 1"))
        capture("free-time-412-large-text-phone")
        compose.onNodeWithText("Some commitments have no end time. They block the rest of their UIT day and your buffer; check the commitments below.").assertExists()
        compose.onNodeWithText("End time missing · reserved through the end of its UIT day").performScrollTo().assertIsDisplayed()
    }

    @Test fun invalidSettingsNeverShowCandidateIntervals() {
        val planner = FreeTimePlanner().apply { open(day) }
        compose.setContent {
            val state by planner.uiState.collectAsState()
            Frame { Content(snapshot, state, planner) }
        }
        compose.onNodeWithText("Minimum (min)").performScrollTo().performTextReplacement("0")
        compose.onNodeWithText("Choose a minimum duration of 10 to 720 minutes.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Candidate gaps ·", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Minimum (min)").performScrollTo().performTextReplacement("60")
        compose.onNodeWithText("Candidate gaps · 2").performScrollTo().assertIsDisplayed()
    }

    @Test fun missingSavedDataIsDifferentFromAnEmptySyncedDay() {
        compose.setContent { Frame { FreeTimeContent(PortalSnapshot(), FreeTimeState(date = "$day")) } }
        compose.onNodeWithText("No saved schedule is available. Sync your accounts or add personal blocks to plan from.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Candidate gaps ·", substring = true).assertDoesNotExist()
    }

    @Test fun changingAccountsDismissesTheOpenPersonalFormAndClearsItsText() {
        val planner = FreeTimePlanner().apply {
            onAccounts(setOf("one", "calendar-only"))
            open(day)
        }
        compose.setContent {
            val state by planner.uiState.collectAsState()
            Frame { Content(snapshot, state, planner) }
        }
        compose.onNodeWithText("Add personal block").performScrollTo().performClick()
        compose.onNodeWithText("Block title").performTextInput("Private planning draft")
        compose.runOnIdle { planner.onAccounts(setOf("one")) }
        compose.onNodeWithText("Personal time block", substring = false).assertDoesNotExist()
        compose.onNodeWithText("Add personal block").performScrollTo().performClick()
        compose.onNodeWithText("Block title").assertTextEquals("Block title", "")
        assertTrue(planner.uiState.value.personal.isEmpty())
    }

    @Test fun cachedVersionsWithDifferentEndsKeepDistinctCommitmentRows() {
        val planner = FreeTimePlanner().apply { open(day) }
        val data = snapshot.copy(events = snapshot.events + snapshot.events.single().copy(end = dateAt(day, LocalTime(15, 0)), name = "Lab preparation updated"))
        compose.setContent {
            val state by planner.uiState.collectAsState()
            Frame { Content(data, state, planner) }
        }
        compose.onNodeWithTag("free-time-content").performScrollToNode(hasText("Commitments used · 3"))
        compose.onNodeWithText("12:45–15:15 · Event").performScrollTo().assertIsDisplayed()
    }

    @Test fun theScheduleEntryPointOpensThePlanner() {
        var opened = false
        compose.setContent { Frame { ScheduleContent(ScheduleState(snapshot, day), onFreeTime = { opened = true }) } }
        compose.onNodeWithText("Find free time").performClick()
        assertTrue(opened)
    }

    @Composable private fun Content(data: PortalSnapshot, state: FreeTimeState, planner: FreeTimePlanner) {
        FreeTimeContent(data, state, planner::close, planner::date, planner::start, planner::end, planner::minimum, planner::buffer, { title, start, end -> planner.addForSession(state.session, title, start, end) }, planner::remove)
    }

    @Composable private fun Frame(content: @Composable () -> Unit) {
        UitTheme(dark = true, dynamic = false) { WorkspaceFrame(1, {}, {}, "Synthetic student", true, false, snapshot.updatedAt) { content() } }
    }
    private fun capture(name: String) {
        if (java.lang.Boolean.getBoolean("roborazzi.test.record")) compose.onRoot().captureRoboImage(File(System.getProperty("uit.preview.dir"), "$name.png").absolutePath)
    }
}
