package vn.edu.uit.mobile

import android.app.Application
import android.content.ClipboardManager
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.*
import vn.edu.uit.mobile.common.*
import vn.edu.uit.mobile.courses.*
import vn.edu.uit.mobile.designsystem.*
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.feature.courses.R as CourseStrings

/** Production Compose + ViewModel. Public evidence contains authored synthetic code only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalRoborazziApi::class, ExperimentalCoroutinesApi::class)
class CodeTutorDesignTest {
    @get:Rule val compose = createComposeRule()
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private lateinit var h: CodeTutorTestHarness
    private val vm get() = h.vm

    @Before fun prepare() {
        Dispatchers.setMain(dispatcher)
        h = CodeTutorTestHarness(dispatcher)
        store.put("tutor", vm)
    }

    @After fun restore() {
        vm.close()
        store.clear()
        dispatcher.scheduler.runCurrent()
        Dispatchers.resetMain()
        RuntimeEnvironment.setFontScale(1f)
    }
    private fun open() {
        vm.open(h.course)
        dispatcher.scheduler.runCurrent()
        compose.setContent { UitTheme(dark = true, dynamic = false) { WorkspaceFrame(2, {}, {}, "Synthetic student", true, false, 1793386800) { CodeTutorScreen(vm, {}) } } }
        pump()
    }
    private fun pump() {
        compose.runOnIdle { dispatcher.scheduler.runCurrent() }
        compose.waitForIdle()
    }
    private fun click(text: String) {
        compose.onNodeWithTag("tutor-content").performScrollToNode(hasText(text))
        compose.onAllNodesWithText(text, substring = false).onFirst().performScrollTo().performClick()
        pump()
    }
    private fun dialogClick(text: String) {
        compose.onNode(hasText(text) and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        pump()
    }
    private fun text(id: Int) = RuntimeEnvironment.getApplication().getString(id)
    private fun capture(name: String) {
        if (java.lang.Boolean.getBoolean("roborazzi.test.record")) {
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(64)
            compose.waitForIdle()
            captureScreenRoboImage(File(System.getProperty("uit.preview.dir"), "$name.png").absolutePath)
        }
    }

    @Test fun phoneFlowImportsPreviewsAnalyzesInspectsRevealsAndCopiesWithoutApplying() {
        open()
        capture("code-tutor-entry-390-phone")
        click("Choose saved code / notebook cell")
        capture("code-tutor-import-390-phone")
        compose.onNodeWithTag("tutor-import-${h.imported.id}").performClick()
        pump()
        compose.onNodeWithTag("tutor-content").performScrollToNode(hasText("First line"))
        capture("code-tutor-editor-390-phone")
        click("Preview selected lines")
        capture("code-tutor-selection-390-phone")
        dialogClick("Close")
        compose.onNodeWithTag("tutor-content").performScrollToNode(hasTestTag("tutor-source-${TutorFixtures.context.id}"))
        compose.onNodeWithTag("tutor-source-${TutorFixtures.context.id}").performClick()
        pump()
        assertTrue(h.requests.isEmpty())
        click("Send selection and analyze")
        assertNotNull(vm.uiState.value.result)
        capture("code-tutor-analysis-390-phone")
        click("Inspect lines 2–2")
        capture("code-tutor-line-evidence-390-phone")
        dialogClick("Copy selected code")
        assertEquals("    return values[0]", RuntimeEnvironment.getApplication().getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString())
        dialogClick("Close")
        click("Source · ${TutorFixtures.context.title}")
        capture("code-tutor-course-evidence-390-phone")
        dialogClick("Close")
        click("Preview suggested edit")
        capture("code-tutor-proposal-390-phone")
        dialogClick("Copy suggested code")
        assertEquals(vm.uiState.value.result!!.proposedCode, RuntimeEnvironment.getApplication().getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString())
        assertEquals(TutorFixtures.code, vm.uiState.value.code)
        dialogClick("Close")
        click("Hints")
        compose.onNodeWithText(TutorFixtures.result().summary).assertDoesNotExist()
        click("Reveal hint 1 / 3")
        compose.onNodeWithTag("tutor-content").performScrollToNode(hasText("Reveal hint 2 / 3"))
        capture("code-tutor-hint-one-390-phone")
        compose.onNodeWithText(TutorFixtures.result().hints[1].text).assertDoesNotExist()
        click("Reveal hint 2 / 3")
        click("Reveal hint 3 / 3")
        compose.onNodeWithTag("tutor-content").performScrollToNode(hasText("All three hints revealed"))
        capture("code-tutor-hints-complete-390-phone")
        assertEquals(1, h.requests.size)
        click("Test ideas")
        compose.onNodeWithTag("tutor-content").performScrollToNode(hasText("Empty list"))
        capture("code-tutor-tests-390-phone")
        click("Edit selection")
        assertNull(vm.uiState.value.result)
        assertEquals(TutorFixtures.code, vm.uiState.value.code)
        click("Back")
        capture("code-tutor-leave-390-phone")
        dialogClick("Keep working")
        assertEquals(TutorFixtures.code, vm.uiState.value.code)
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-mdpi")
    fun compactPhoneKeepsInvalidInputAndFailedAnalysisRecoverable() {
        open()
        compose.runOnIdle {
            vm.code(TutorFixtures.code)
            vm.range("2", "1")
        }
        pump()
        compose.onNodeWithTag("tutor-content").performScrollToNode(hasText("First line"))
        capture("code-tutor-invalid-range-360-phone")
        compose.onNodeWithTag("tutor-content").performScrollToNode(hasTestTag("tutor-analyze"))
        compose.onNodeWithTag("tutor-analyze").assertIsNotEnabled()
        compose.runOnIdle {
            vm.range("1", "3")
            h.failGenerate = true
        }
        pump()
        click("Send selection and analyze")
        capture("code-tutor-failure-360-phone")
        assertEquals(TutorFixtures.code, vm.uiState.value.code)
        assertNull(vm.uiState.value.result)
        compose.runOnIdle {
            h.failGenerate = false
            h.unknown = true
        }
        pump()
        click("Send selection and analyze")
        capture("code-tutor-missing-context-360-phone")
        compose.onNodeWithText("More context needed").assertExists()
        compose.onNodeWithText("Preview suggested edit").assertDoesNotExist()
    }

    @Test fun settingsFailureAndCanceledRequestRetainTheWorkspace() {
        h.configured = false
        open()
        compose.runOnIdle { vm.code(TutorFixtures.code) }
        pump()
        click(text(CourseStrings.string.st_configure))
        capture("code-tutor-settings-390-phone")
        dialogClick(text(CourseStrings.string.st_cancel))
        compose.runOnIdle {
            h.configured = true
            vm.reload()
        }
        pump()
        h.replyPending = CompletableDeferred()
        click("Send selection and analyze")
        capture("code-tutor-loading-390-phone")
        click("Cancel request")
        assertEquals(TutorProblem.CANCELED, vm.uiState.value.problem)
        assertEquals(TutorFixtures.code, vm.uiState.value.code)
        h.replyPending!!.complete(TutorFixtures.result(h.requests.single()))
        pump()
        assertNull(vm.uiState.value.result)
    }

    @Test
    @Config(qualifiers = "vi-w412dp-h915dp-mdpi")
    fun vietnameseLargeTextKeepsEvidenceAndProposalActionsReachable() {
        RuntimeEnvironment.setFontScale(1.3f)
        open()
        compose.runOnIdle { vm.code(TutorFixtures.code) }
        pump()
        click(text(CourseStrings.string.ct_analyze))
        capture("code-tutor-analysis-412-vietnamese-phone")
        click(RuntimeEnvironment.getApplication().getString(CourseStrings.string.ct_reference, 2, 2))
        capture("code-tutor-evidence-412-vietnamese-phone")
        dialogClick(text(CourseStrings.string.ct_close))
        click(text(CourseStrings.string.ct_proposal))
        capture("code-tutor-proposal-412-vietnamese-phone")
        compose.onNodeWithText(text(CourseStrings.string.ct_copy_proposal)).assertIsDisplayed()
        dialogClick(text(CourseStrings.string.ct_close))
        click(text(CourseStrings.string.ct_tests))
        compose.onNodeWithTag("tutor-content").performScrollToNode(hasText("Empty list"))
        capture("code-tutor-tests-412-vietnamese-phone")
        assertEquals(1, h.requests.size)
    }
}
