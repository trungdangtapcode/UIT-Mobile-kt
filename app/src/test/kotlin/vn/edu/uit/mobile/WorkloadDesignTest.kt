package vn.edu.uit.mobile

import android.app.Application
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import java.io.File
import kotlinx.datetime.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.*
import vn.edu.uit.mobile.common.*
import vn.edu.uit.mobile.designsystem.*
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.feature.schedule.R as ScheduleStrings
import vn.edu.uit.mobile.model.*
import vn.edu.uit.mobile.schedule.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalRoborazziApi::class)
class WorkloadDesignTest {
    @get:Rule val compose = createComposeRule()
    private val day = LocalDate(2026, 10, 26)
    private val now = dateAt(day, LocalTime(8, 0))
    private val study = Account("study", "https://courses.uit.edu.vn", 1, "Study account", PortalType.MOODLE)
    private val calendar = Account("calendar", "https://portal.uit.edu.vn", 1, "Teaching calendar", PortalType.UIT_PORTAL)
    private val policy = WorkloadPolicy(days = (1..7).map { StudyDayBudget(it, 8 * 60, 18 * 60, 240) }, travelBuffer = 0, deadlineLead = 0, calendarAccountId = calendar.id)
    private val assignment = Assignment(1, 1, 101, "Database project", "", now + 4 * 3600, accountId = study.id)
    private val ref = WorkloadRef(study.id, WorkloadKind.ASSIGNMENT, "assignment:1")
    private val sources = listOf(
        WorkloadAccount(study, PortalSnapshot(assignments = listOf(assignment, assignment.copy(id = 2, cmid = 102, name = "Software design report", due = now + 25 * 3600)), events = listOf(CalendarEvent("lab", 1, study.id, null, "Lab", now, now + 2 * 3600)), updatedAt = now), policy, listOf(WorkloadEstimate(ref, assignment.name, 180)), configured = true),
        WorkloadAccount(calendar, PortalSnapshot(updatedAt = now))
    )
    private val result get() = WorkloadEngine().analyze(study.id, sources, policy, now)
    private val state get() = WorkloadState(owners = listOf(study, calendar), data = sources, selectedId = study.id, result = result, loading = false)

    @After fun restore() {
        RuntimeEnvironment.setFontScale(1f)
    }

    @Test fun knownShortfallAndUnknownEstimatesRemainDistinctAndExplainContributors() {
        compose.setContent { Frame { WorkloadContent(state) } }
        compose.onNodeWithText("Known study time · 180 min").assertExists()
        compose.onNodeWithText("Upcoming items without an estimate · 1").assertExists()
        compose.onNodeWithText("Known demand exceeds recorded capacity").assertExists()
        capture("workload-overview-390-phone")
        compose.onNodeWithText("How this is calculated").performScrollTo().performClick()
        compose.onNodeWithText("Cumulative known demand · 180 min").assertExists()
        compose.onNodeWithText("Recorded capacity · 120 min").assertExists()
        compose.onNode(hasText("Database project") and hasAnyAncestor(hasTestTag("workload-dialog"))).assertExists()
        capture("workload-explanation-390-phone")
        compose.onNodeWithText("Close", substring = false).performClick()
    }

    @Test fun failedEstimateSaveRetainsItsDraftAndClearingBothFieldsIsExplicit() {
        var current by mutableStateOf(state)
        var saved = false
        var attempts = 0
        compose.setContent {
            Frame {
                WorkloadContent(current, onEstimate = { reference, minutes, duration, done ->
                    assertEquals(ref, reference)
                    assertEquals("", duration)
                    attempts++
                    if (attempts == 1) {
                        assertEquals("90", minutes)
                        current = current.copy(problem = WorkloadProblem.SAVE)
                    } else {
                        assertEquals("", minutes)
                        saved = true
                        done()
                    }
                })
            }
        }
        compose.onNodeWithTag("workload-content").performScrollToNode(hasText("Database project"))
        compose.onNodeWithText("Edit estimate").performScrollTo().performClick()
        compose.onNodeWithText("Remaining study minutes · 0–60000").performTextReplacement("90")
        compose.onNodeWithText("Save on device").performClick()
        compose.onNode(hasText("Changes could not be saved. Your draft remains open.") and hasAnyAncestor(hasTestTag("workload-dialog"))).assertExists()
        compose.onNodeWithText("Remaining study minutes · 0–60000").performTextReplacement("")
        compose.onNodeWithText("Save on device").performClick()
        assertTrue(saved)
        compose.onNodeWithText("Save on device").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-mdpi")
    fun compactPhoneConfigurationChangesHorizonAndWeekdayBudgetWithoutSavingImplicitly() {
        var changed: WorkloadPolicy? = null
        compose.setContent {
            Frame {
                WorkloadContent(state, onPolicy = { value, done ->
                    changed = value
                    done()
                })
            }
        }
        compose.onNodeWithText("Configure budget").performClick()
        compose.onNodeWithText("14 days").performClick()
        compose.onNodeWithText("Daily study budget · minutes").performScrollTo().performTextReplacement("180")
        assertNull(changed)
        capture("workload-settings-360-phone")
        compose.onNodeWithText("Save on device").performClick()
        assertEquals(14, changed!!.horizonDays)
        assertEquals(180, changed!!.days.single { it.isoDay == 1 }.minutes)
        assertEquals(240, changed!!.days.single { it.isoDay == 2 }.minutes)
    }

    @Test
    @Config(qualifiers = "vi-w412dp-h915dp-mdpi")
    fun vietnameseLargeTextKeepsTasksAndEstimateActionsReachable() {
        RuntimeEnvironment.setFontScale(1.3f)
        val label = RuntimeEnvironment.getApplication().getString(ScheduleStrings.string.wl_edit_estimate)
        compose.setContent { Frame { WorkloadContent(state) } }
        compose.onNodeWithTag("workload-content").performScrollToNode(hasText("Database project"))
        compose.onNodeWithText(label).performScrollTo().assertIsDisplayed()
        capture("workload-tasks-412-vietnamese-phone")
    }

    @Test fun missingSourceDoesNotImplyCompletedWorkAndOrphanRemovalIsDeliberate() {
        val estimate = WorkloadEstimate(ref, "Unavailable assignment", 120)
        val missing = sources.map { if (it.account.id == study.id) it.copy(snapshot = PortalSnapshot(updatedAt = now), estimates = listOf(estimate)) else it }
        val projection = WorkloadEngine().analyze(study.id, missing, policy, now)
        var removed: WorkloadRef? = null
        compose.setContent { Frame { WorkloadContent(state.copy(data = missing, result = projection), onRemove = { removed = it }) } }
        compose.onNodeWithText("Missing cached records do not mean coursework is complete.").assertExists()
        compose.onNodeWithTag("workload-content").performScrollToNode(hasText("Unavailable assignment"))
        assertNull(removed)
        compose.onNodeWithText("Remove saved estimate").performScrollTo().performClick()
        assertEquals(ref, removed)
    }

    @Test fun scheduleExposesWorkloadEntryAndBackDoesNotSaveAnything() {
        var showing by mutableStateOf(false)
        compose.setContent { Frame { if (showing) WorkloadContent(state, onBack = { showing = false }) else ScheduleContent(ScheduleState(anchor = day), onWorkload = { showing = true }) } }
        compose.onNodeWithText("Workload preview").performScrollTo().performClick()
        compose.onNodeWithText("Upcoming workload").assertExists()
        compose.onAllNodesWithText("Schedule", substring = false).onFirst().performClick()
        compose.onNodeWithText("Workload preview").assertExists()
    }

    @Composable private fun Frame(content: @Composable () -> Unit) {
        UitTheme(dark = true, dynamic = false) { WorkspaceFrame(1, {}, {}, "Synthetic", true, false, now) { content() } }
    }
    private fun capture(name: String) {
        if (java.lang.Boolean.getBoolean("roborazzi.test.record")) {
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(64)
            compose.waitForIdle()
            captureScreenRoboImage(File(System.getProperty("uit.preview.dir"), "$name.png").absolutePath)
        }
    }
}
