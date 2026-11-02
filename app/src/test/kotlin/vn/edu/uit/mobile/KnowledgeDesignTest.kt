package vn.edu.uit.mobile

import android.app.Application
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
import vn.edu.uit.mobile.courses.*
import vn.edu.uit.mobile.designsystem.*
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.feature.courses.R as CourseStrings

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalRoborazziApi::class)
class KnowledgeDesignTest {
    @get:Rule val compose = createComposeRule()
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private lateinit var h: KnowledgeTestHarness

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        h = KnowledgeTestHarness(dispatcher)
        store.put("knowledge", h.vm)
    }

    @After fun cleanup() {
        store.clear()
        dispatcher.scheduler.runCurrent()
        Dispatchers.resetMain()
        RuntimeEnvironment.setFontScale(1f)
    }
    private fun pump() {
        compose.runOnIdle { dispatcher.scheduler.runCurrent() }
        compose.waitForIdle()
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
    private fun open() {
        dispatcher.scheduler.runCurrent()
        compose.setContent { UitTheme(dark = true, dynamic = false) { WorkspaceFrame(2, {}, {}, "Synthetic student", true, false, 1793586000) { KnowledgeScreen(h.vm) {} } } }
        h.vm.open()
        pump()
    }
    private fun tag(name: String) {
        compose.onNodeWithTag(name).performScrollTo().performClick()
        pump()
    }
    private fun click(value: String) {
        compose.onNodeWithTag("knowledge-content").performScrollToNode(hasText(value) and hasClickAction())
        compose.onNode(hasText(value) and hasClickAction()).performScrollTo().performClick()
        pump()
    }
    private fun generate(selectionCapture: String? = null) {
        click(KnowledgeFixtures.course.fullname)
        compose.onNodeWithContentDescription(KnowledgeFixtures.sources.first().title).performScrollTo().performClick()
        pump()
        compose.onNodeWithTag("knowledge-focus").performScrollTo().performTextInput(KnowledgeFixtures.graph.focus)
        pump()
        selectionCapture?.let(::capture)
        tag("knowledge-generate")
    }

    @Test fun phoneCreatesExploresEditsSavesAndReopensMapWithoutUniversityCalls() {
        open()
        capture("knowledge-library-390-phone")
        generate("knowledge-selection-390-phone")
        compose.onNodeWithTag("knowledge-selected").performScrollTo()
        capture("knowledge-map-390-phone")
        tag("knowledge-neighbor-bfs")
        capture("knowledge-bfs-neighborhood-390-phone")
        click(text(CourseStrings.string.km_evidence))
        capture("knowledge-evidence-390-phone")
        compose.onNode(hasText(text(CourseStrings.string.pp_close)) and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        pump()
        click(text(CourseStrings.string.km_edit_concept))
        compose.onNodeWithTag("knowledge-edit-label").performTextReplacement("Breadth-first search")
        pump()
        capture("knowledge-edit-390-phone")
        compose.onNodeWithTag("knowledge-apply").performClick()
        pump()
        tag("knowledge-save")
        assertFalse(h.vm.uiState.value.dirty)
        click(text(CourseStrings.string.st_back))
        assertEquals(KnowledgeStage.LIBRARY, h.vm.uiState.value.stage)
        capture("knowledge-saved-library-390-phone")
        click(text(CourseStrings.string.km_open))
        tag("knowledge-index-bfs")
        assertEquals("Breadth-first search", h.vm.uiState.value.record!!.graph.nodes.first { it.id == "bfs" }.label)
        capture("knowledge-restored-390-phone")
        assertEquals(1, h.generations)
        assertTrue(h.calls.all { it == "getSnapshot" })
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-mdpi")
    fun failedSaveRetainsDraftAndRetryDoesNotDuplicateIt() {
        open()
        generate()
        h.failWrite = true
        tag("knowledge-save")
        assertEquals(KnowledgeProblem.SAVE, h.vm.uiState.value.problem)
        capture("knowledge-failed-save-360-phone")
        h.failWrite = false
        tag("knowledge-save")
        assertFalse(h.vm.uiState.value.dirty)
        click(text(CourseStrings.string.km_delete))
        capture("knowledge-delete-360-phone")
        compose.onNode(hasText(text(CourseStrings.string.km_delete_confirm)) and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        pump()
        assertNull(h.vm.uiState.value.record)
    }

    @Test
    @Config(qualifiers = "vi-rVN-w412dp-h915dp-mdpi")
    fun vietnameseLargeTextKeepsMapAndRelationshipEditorActionsReachable() {
        RuntimeEnvironment.setFontScale(1.3f)
        open()
        generate()
        tag("knowledge-neighbor-bfs")
        capture("knowledge-map-412-vietnamese-phone")
        click("BFS → uses → FIFO queue")
        capture("knowledge-relation-412-vietnamese-phone")
        compose.onNodeWithTag("knowledge-apply").assertIsDisplayed()
        compose.onNode(hasText(text(CourseStrings.string.pp_inspect_source)) and hasClickAction()).performScrollTo().performClick()
        pump()
        capture("knowledge-evidence-412-vietnamese-phone")
        compose.onNode(hasText(text(CourseStrings.string.pp_close)) and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        pump()
    }
}
