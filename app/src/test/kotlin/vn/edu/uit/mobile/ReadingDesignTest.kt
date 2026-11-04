package vn.edu.uit.mobile

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
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
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.*
import vn.edu.uit.mobile.courses.*
import vn.edu.uit.mobile.designsystem.*
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.feature.courses.R as CourseStrings

/** Production UI/VM; the synthetic PDF pages are prerendered because host SDK 35 PdfRenderer is unsupported. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalRoborazziApi::class, ExperimentalCoroutinesApi::class)
class ReadingDesignTest {
    @get:Rule val compose = createComposeRule()

    @get:Rule val folder = TemporaryFolder()
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private lateinit var h: ReadingTestHarness
    private val vm get() = h.vm

    @Before fun prepare() {
        Dispatchers.setMain(dispatcher)
        h = ReadingTestHarness(folder.root, dispatcher)
        store.put("reading", vm)
    }

    @After fun restore() {
        vm.preempt()
        store.clear()
        dispatcher.scheduler.runCurrent()
        Dispatchers.resetMain()
        RuntimeEnvironment.setFontScale(1f)
    }
    private fun pump() {
        compose.runOnIdle { dispatcher.scheduler.runCurrent() }
        compose.waitForIdle()
    }
    private fun open() {
        vm.library()
        dispatcher.scheduler.runCurrent()
        compose.setContent { Frame { CourseReadingScreen(vm) } }
        pump()
    }
    private fun click(text: String) {
        compose.onNodeWithTag("reading-content").performScrollToNode(hasText(text))
        compose.onAllNodesWithText(text, substring = false).onFirst().performScrollTo().performClick()
        pump()
    }
    private fun dialogClick(text: String) {
        compose.onNode(hasText(text) and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        pump()
    }
    private fun desc(text: String) {
        compose.onNodeWithTag("reading-content").performScrollToNode(hasContentDescription(text))
        compose.onNodeWithContentDescription(text).performClick()
        pump()
    }
    private fun addNote(text: String) {
        click("Add note")
        compose.onNodeWithTag("reading-page").performTouchInput { click(Offset(width * .64f, height * .36f)) }
        pump()
        compose.onNodeWithTag("reading-note-editor").performTextInput(text)
        pump()
        dialogClick("Save note")
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

    @Test fun fullPhoneFlowReadsBookmarksPinsEditsExportsAndRestoresTheDocument() {
        open()
        capture("reading-library-390-phone")
        click("Read in app")
        capture("reading-page-390-phone")
        desc("Next page")
        desc("Bookmark this page")
        click("Bookmarks")
        capture("reading-bookmarks-390-phone")
        click("Page 2")
        click("Add note")
        compose.onNodeWithTag("reading-page").performTouchInput { click(Offset(width * .64f, height * .36f)) }
        pump()
        compose.onNodeWithTag("reading-note-editor").performTextInput("Why does neighbor ordering change the DFS traversal?")
        pump()
        capture("reading-note-editor-390-phone")
        dialogClick("Save note")
        capture("reading-pinned-page-390-phone")
        click("Notes")
        capture("reading-notes-390-phone")
        click("Edit note")
        compose.onNodeWithTag("reading-note-editor").performTextReplacement("Compare recursive DFS with an explicit stack.")
        pump()
        dialogClick("Save note")
        assertEquals("Compare recursive DFS with an explicit stack.", vm.uiState.value.record!!.notes.single().text)
        click("Export reading notes")
        capture("reading-export-preview-390-phone")
        assertTrue(vm.pendingExport!!.second.contains("Compare recursive DFS"))
        dialogClick("Cancel")
        click("Reading library")
        capture("reading-saved-library-390-phone")
        click("Read in app")
        assertEquals(2, vm.uiState.value.position.page)
        assertEquals(setOf(2), vm.uiState.value.record!!.bookmarks)
        capture("reading-restored-page-390-phone")
        click("Notes")
        click("Delete note")
        capture("reading-delete-note-390-phone")
        dialogClick("Delete")
        assertTrue(vm.uiState.value.record!!.notes.isEmpty())
        assertTrue(h.universityCalls.all { it == "getSnapshot" })
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-mdpi")
    fun compactPhoneRetainsFailedDraftAndRetriesWithoutDuplicateAnnotation() {
        open()
        click("Read in app")
        click("Add note")
        compose.onNodeWithTag("reading-page").performTouchInput { click(center) }
        pump()
        compose.onNodeWithTag("reading-note-editor").performTextInput("Keep this note when saving fails.")
        pump()
        h.committedFailure = true
        dialogClick("Save note")
        capture("reading-note-failed-save-360-phone")
        assertEquals("Keep this note when saving fails.", vm.uiState.value.draft!!.text)
        h.committedFailure = false
        dialogClick("Retry")
        assertEquals(1, vm.uiState.value.record!!.notes.size)
        assertNull(vm.uiState.value.draft)
        compose.runOnIdle { vm.viewport(ReadingPosition(1, 2f, .2f, .4f)) }
        pump()
        desc("Next page")
        assertEquals(2, vm.uiState.value.position.page)
        capture("reading-page-360-phone")
    }

    @Test fun changedAndMissingPdfVersionsRetainOldNotesWithoutReattachingThem() {
        open()
        click("Read in app")
        addNote("This belongs to the first document version.")
        val old = vm.uiState.value.record!!
        compose.runOnIdle {
            h.updateBytes(ReadingFixtures.pdf.toString(Charsets.US_ASCII).replace("Breadth-first search", "Breadth-First search").toByteArray(Charsets.US_ASCII))
            vm.open(h.course, h.file)
        }
        pump()
        capture("reading-version-changed-390-phone")
        assertTrue(vm.uiState.value.record!!.notes.isEmpty())
        compose.runOnIdle {
            h.pdfFile.delete()
            vm.library()
        }
        pump()
        click("Review saved notes")
        capture("reading-archived-notes-390-phone")
        assertTrue(vm.uiState.value.archived)
        assertEquals(old.identity.version, vm.uiState.value.record!!.identity.version)
        click("Export reading notes")
        dialogClick("Cancel")
        click("Delete local reading record")
        capture("reading-delete-record-390-phone")
        dialogClick("Delete")
        assertNull(vm.uiState.value.record)
    }

    @Test
    @Config(qualifiers = "vi-w412dp-h915dp-mdpi")
    fun vietnameseLargeTextKeepsNavigationNotesAndExportActionsReachable() {
        RuntimeEnvironment.setFontScale(1.3f)
        open()
        click(text(CourseStrings.string.rd_open))
        capture("reading-page-412-vietnamese-phone")
        desc(text(CourseStrings.string.rd_next))
        desc(text(CourseStrings.string.rd_add_bookmark))
        click(text(CourseStrings.string.rd_add_note))
        compose.onNodeWithTag("reading-page").performTouchInput { click(center) }
        pump()
        compose.onNodeWithTag("reading-note-editor").performTextInput("Synthetic note for the selected page.")
        pump()
        capture("reading-note-editor-412-vietnamese-phone")
        dialogClick(text(CourseStrings.string.rd_save_note))
        click(text(CourseStrings.string.rd_notes))
        capture("reading-notes-412-vietnamese-phone")
        click(text(CourseStrings.string.rd_export))
        capture("reading-export-412-vietnamese-phone")
        compose.onNodeWithText(text(CourseStrings.string.rd_export_file)).assertIsDisplayed()
        dialogClick(text(CourseStrings.string.rd_cancel))
    }

    @Test fun fitWidthPinchPanAndFitPageKeepReadingControlsUsable() {
        open()
        click("Read in app")
        compose.onNodeWithTag("reading-page").performScrollTo().performTouchInput {
            pinch(
                start0 = center - Offset(width * .25f, 0f),
                end0 = center - Offset(width * .4f, 0f),
                start1 = center + Offset(width * .25f, 0f),
                end1 = center + Offset(width * .4f, 0f)
            )
        }
        pump()
        assertTrue("Pinch must also start at fit-page scale", vm.uiState.value.position.zoom > 1.2f)
        click("Fit page")
        click("Fit width")
        assertTrue(vm.uiState.value.position.zoom > 1f)
        assertTrue(vm.uiState.value.position.panY >= 0f)
        capture("reading-fit-width-390-phone")
        val before = vm.uiState.value.position.zoom
        compose.onNodeWithTag("reading-page").performTouchInput {
            pinch(
                start0 = center - Offset(40f, 0f),
                end0 = center - Offset(80f, 0f),
                start1 = center + Offset(40f, 0f),
                end1 = center + Offset(80f, 0f)
            )
        }
        pump()
        assertTrue(vm.uiState.value.position.zoom > before * 1.2f)
        compose.onNodeWithTag("reading-page").performTouchInput { swipe(center, center + Offset(40f, 40f)) }
        pump()
        assertTrue(vm.uiState.value.position.panX != 0f || vm.uiState.value.position.panY != 0f)
        capture("reading-zoomed-page-390-phone")
        click("Fit page")
        assertEquals(1f, vm.uiState.value.position.zoom)
        compose.onNodeWithTag("reading-page").performScrollTo().performTouchInput { swipeUp() }
        pump()
        compose.onNodeWithText("Add note").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-mdpi")
    fun missingOrUnsupportedPdfKeepsLibraryAndBackReachable() {
        open()
        h.pdfFile.delete()
        click("Read in app")
        capture("reading-missing-file-360-phone")
        assertEquals(ReadingProblem.FILE, vm.uiState.value.problem)
        click("Reading library")
        compose.runOnIdle {
            h.updateBytes(ReadingFixtures.pdf)
            vm.refresh()
            h.failOpen = true
        }
        pump()
        click("Read in app")
        capture("reading-unsupported-pdf-360-phone")
        assertEquals(ReadingProblem.PDF, vm.uiState.value.problem)
        click("Back")
        assertNull(vm.uiState.value.candidate)
    }

    @Test fun pendingOpenCanBeCanceledAndUnreadableLocalDataDoesNotBlockReading() {
        open()
        h.pendingLoad = CompletableDeferred()
        click("Read in app")
        capture("reading-loading-390-phone")
        click("Back")
        h.pendingLoad!!.complete(Unit)
        pump()
        assertNull(vm.uiState.value.image)
        h.pendingLoad = null
        h.failRead = true
        click("Read in app")
        capture("reading-store-error-390-phone")
        assertNotNull(vm.uiState.value.image)
        compose.onNodeWithTag("reading-content").performScrollToNode(hasText("Add note"))
        compose.onNodeWithText("Add note").assertIsNotEnabled()
    }

    @Composable private fun Frame(content: @Composable () -> Unit) {
        UitTheme(dark = true, dynamic = false) { WorkspaceFrame(2, {}, {}, "Synthetic student", true, false, 1793586000) { content() } }
    }
}
