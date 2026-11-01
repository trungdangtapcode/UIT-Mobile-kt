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

internal object RevisionPreview {
    val day = LocalDate(2026, 10, 26)
    val now = dateAt(day, LocalTime(10, 0))
    val owner = Account("teaching", "https://portal.uit.edu.vn", 1, "Teaching account", PortalType.UIT_PORTAL)
    val policy = WorkloadPolicy(days = (1..7).map { StudyDayBudget(it, 8 * 60, 22 * 60, 180) }, travelBuffer = 15, deadlineLead = 60)
    private val snapshot = PortalSnapshot(exams = listOf(DaaExamSlot("IT004", "Database Systems", 0, "2026-10-28", "", startTime = "13:00"), DaaExamSlot("IT005", "Computer Networks", 0, "2026-10-30", "", startTime = "09:00")), updatedAt = now)
    private val initial = WorkloadAccount(owner, snapshot, policy, configured = true)
    val exams = WorkloadSourceIndex().items(initial)
    val source = initial.copy(estimates = exams.map { WorkloadEstimate(it.ref, it.title, examMinutes = 90) })
    private val exam = exams.first()
    val plan = RevisionPlan(exam.ref, exam.title, exam.due, listOf(StudyTopic("transactions", "Transactions and isolation", 150), StudyTopic("indexing", "Query optimization", 90)), listOf(RevisionSession("completed", "transactions", dateAt(day, LocalTime(8, 0)), dateAt(day, LocalTime(8, 30)), 30, RevisionStatus.COMPLETED), RevisionSession("locked", "transactions", dateAt(day, LocalTime(14, 0)), dateAt(day, LocalTime(15, 0)), 60, locked = true)))
    val workspace = RevisionWorkspace(listOf(plan, RevisionPlan(exams.last().ref, exams.last().title, exams.last().due, listOf(StudyTopic("routing", "Routing and subnetting", 120)))))
    private val blocks = listOf(PersonalTimeBlock("lab", "Lab", dateAt(day, LocalTime(11, 0)), dateAt(day, LocalTime(13, 0))))
    val result = WorkloadEngine().analyze(owner.id, listOf(source), policy, now, blocks)
    val state = RevisionState(WorkloadState(listOf(owner), listOf(source), owner.id, result, loading = false), workspace, selectedExam = exam.ref.id, now = now)
    val draft = RevisionPlanner().generate(workspace, result, policy, exams, now)
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalRoborazziApi::class)
class RevisionDesignTest {
    @get:Rule val compose = createComposeRule()

    @After fun restore() {
        RuntimeEnvironment.setFontScale(1f)
    }

    @Test fun overviewKeepsExplicitDraftCreationSeparateFromSaving() {
        var generated = false
        compose.setContent { Frame { RevisionContent(RevisionPreview.state, onGenerate = { generated = true }) } }
        compose.onNodeWithText("30 of 360 effort min completed").assertExists()
        compose.onNodeWithText("270 effort min without a current reservation").assertExists()
        compose.onNodeWithText("Save draft on device").assertDoesNotExist()
        capture("revision-overview-390-phone")
        compose.onNodeWithText("Generate / replan all exams").performScrollTo().performClick()
        assertTrue(generated)
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-mdpi")
    fun compactDraftShowsJointlyCheckedSessionsAndExplicitSave() {
        var saved = false
        val state = RevisionPreview.state.copy(draft = RevisionPreview.draft)
        compose.setContent { Frame { RevisionContent(state, onSaveDraft = { saved = true }) } }
        compose.onNodeWithText("Draft · not saved").assertExists()
        compose.onNodeWithText("Save draft on device").performScrollTo().performClick()
        assertTrue(saved)
        compose.onNodeWithTag("revision-content").performScrollToNode(hasTestTag("revision-day-2026-10-26"))
        compose.onNodeWithTag("revision-day-2026-10-26").performScrollTo()
        capture("revision-draft-sessions-360-phone")
    }

    @Test
    @Config(qualifiers = "vi-w412dp-h915dp-mdpi")
    fun vietnameseLargeTextRulesKeepAllFieldsAndSaveReachable() {
        RuntimeEnvironment.setFontScale(1.3f)
        var changed = false
        val app = RuntimeEnvironment.getApplication()
        compose.setContent {
            Frame {
                RevisionContent(RevisionPreview.state, onOptions = { maximum, rest, delay, done ->
                    assertEquals("60", maximum)
                    assertEquals("15", rest)
                    assertEquals("20", delay)
                    changed = true
                    done()
                })
            }
        }
        compose.onNodeWithText(app.getString(ScheduleStrings.string.rp_rules)).performClick()
        compose.onNodeWithText(app.getString(ScheduleStrings.string.rp_maximum)).performTextReplacement("60")
        compose.onNodeWithText(app.getString(ScheduleStrings.string.rp_break)).performTextReplacement("15")
        compose.onNodeWithText(app.getString(ScheduleStrings.string.rp_delay)).performScrollTo().performTextReplacement("20")
        capture("revision-rules-412-vietnamese-phone")
        compose.onNodeWithText(app.getString(ScheduleStrings.string.wl_save)).performClick()
        assertTrue(changed)
    }

    @Test fun failedTopicSaveKeepsTheEnteredTitleAndEffortUntilSuccessfulRetry() {
        var state by mutableStateOf(RevisionPreview.state)
        var attempts = 0
        compose.setContent {
            Frame {
                RevisionContent(state, onTopic = { _, _, title, minutes, done ->
                    assertEquals("Recovery", title)
                    assertEquals("60", minutes)
                    attempts++
                    if (attempts == 1) state = state.copy(message = RevisionMessage.SAVE) else done()
                })
            }
        }
        compose.onNodeWithTag("revision-content").performScrollToNode(hasText("Add revision topic"))
        compose.onNodeWithText("Add revision topic").performClick()
        compose.onNodeWithText("Topic title").performTextInput("Recovery")
        compose.onNodeWithText("Total topic effort · 1–60000 min").performTextInput("60")
        compose.onNodeWithText("Save on device").performClick()
        compose.onNode(hasText("Changes were not saved. Your draft stays open; reload if another edit changed the workspace.") and hasAnyAncestor(hasTestTag("workload-dialog"))).assertExists()
        capture("revision-topic-editor-390-phone")
        compose.onNodeWithText("Save on device").performClick()
        assertEquals(2, attempts)
        compose.onNodeWithText("Topic title").assertDoesNotExist()
    }

    @Test fun sessionDetailsExposeLockAndExplicitMovementWithoutCreditingFutureCompletion() {
        var moved = false
        compose.setContent {
            Frame {
                RevisionContent(RevisionPreview.state, onMove = { ref, id, date, start, end, done ->
                    assertEquals(RevisionPreview.plan.exam, ref)
                    assertEquals("locked", id)
                    assertEquals("2026-10-26", date)
                    assertEquals("16:00", start)
                    assertEquals("17:00", end)
                    moved = true
                    done()
                })
            }
        }
        compose.onNodeWithTag("revision-content").performScrollToNode(hasTestTag("revision-edit-locked"))
        compose.onNodeWithTag("revision-edit-locked").performClick()
        compose.onNodeWithText("Unlock session").assertExists()
        compose.onNodeWithText("Mark completed").assertDoesNotExist()
        compose.onNodeWithText("Start · HH:mm").performTextReplacement("16:00")
        compose.onNodeWithText("End · HH:mm").performTextReplacement("17:00")
        compose.onNodeWithText("Save move and lock").performClick()
        assertTrue(moved)
    }

    @Test fun deletingAPlanRequiresASecondExplicitActionAndArchiveIsSeparate() {
        var deleted = false
        compose.setContent { Frame { RevisionContent(RevisionPreview.state, onRemovePlan = { deleted = true }) } }
        compose.onNodeWithTag("revision-content").performScrollToNode(hasText("Delete local plan"))
        compose.onNodeWithText("Delete local plan").performScrollTo().performClick()
        assertFalse(deleted)
        compose.onNode(hasText("Delete local plan") and hasClickAction() and hasAnyAncestor(hasTestTag("workload-dialog"))).performClick()
        assertTrue(deleted)
    }

    @Composable private fun Frame(content: @Composable () -> Unit) {
        UitTheme(dark = true, dynamic = false) { WorkspaceFrame(1, {}, {}, "Synthetic", true, false, RevisionPreview.now) { content() } }
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
