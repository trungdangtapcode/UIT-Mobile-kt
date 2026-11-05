package vn.edu.uit.mobile

import android.app.Application
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import vn.edu.uit.mobile.courses.*
import vn.edu.uit.mobile.domain.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ReadingViewModelTest {
    @get:Rule val folder = TemporaryFolder()
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private lateinit var h: ReadingTestHarness
    private val vm get() = h.vm

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        h = ReadingTestHarness(folder.root, dispatcher)
        store.put("reading", vm)
    }

    @After fun cleanup() {
        vm.preempt()
        store.clear()
        dispatcher.scheduler.runCurrent()
        Dispatchers.resetMain()
    }
    private fun checkModel(block: suspend TestScope.() -> Unit) = runTest(dispatcher) {
        try {
            runCurrent()
            block()
        } finally {
            vm.preempt()
            runCurrent()
        }
    }
    private fun TestScope.open() {
        vm.open(h.course, h.file)
        runCurrent()
        assertNotNull(vm.uiState.value.image)
        assertNotNull(vm.uiState.value.record)
    }

    @Test fun passageNavigationRejectsAReplacementBeforeRenderingAndRetryPreservesTheExpectedVersion() = checkModel {
        vm.openExpected(h.course, h.file, "b".repeat(64))
        runCurrent()
        assertEquals(ReadingProblem.FILE, vm.uiState.value.problem)
        assertEquals(0, h.opened)
        vm.retry()
        runCurrent()
        assertEquals(ReadingProblem.FILE, vm.uiState.value.problem)
        assertEquals(0, h.opened)
        vm.openExpected(h.course, h.file, ReadingFixtures.identity.version)
        runCurrent()
        assertNotNull(vm.uiState.value.image)
    }

    @Test fun libraryAndOpeningReadOnlyLocalDataNeverInvokeUniversityTransport() = checkModel {
        vm.library()
        runCurrent()
        assertEquals(listOf(h.file), vm.uiState.value.candidates.map { it.file })
        open()
        assertTrue(vm.uiState.value.record!!.revision > 0)
        assertTrue(h.universityCalls.all { it == "getSnapshot" })
        assertArrayEquals(ReadingFixtures.pdf, h.pdfFile.readBytes())
    }

    @Test fun pageAndViewportRestoreAfterCloseAndReopen() = checkModel {
        open()
        vm.page(2)
        runCurrent()
        vm.viewport(ReadingPosition(2, 2.5f, -.2f, .7f))
        runCurrent()
        assertTrue(vm.uiState.value.dirty)
        var left = false
        vm.leave { left = true }
        runCurrent()
        assertTrue(left)
        assertEquals(1, h.closed)
        open()
        assertEquals(ReadingPosition(2, 2.5f, -.2f, .7f), vm.uiState.value.position)
        assertEquals(2, h.rendered.last())
    }

    @Test fun debouncedViewportWritesKeepOnlyTheLatestPosition() = checkModel {
        open()
        val initial = vm.uiState.value.record!!.revision
        vm.viewport(ReadingPosition(1, 2f))
        vm.viewport(ReadingPosition(1, 3f, .4f, .5f))
        runCurrent()
        assertEquals(initial, vm.uiState.value.record!!.revision)
        advanceTimeBy(501)
        runCurrent()
        assertEquals(initial + 1, vm.uiState.value.record!!.revision)
        assertEquals(3f, vm.uiState.value.record!!.position.zoom)
    }

    @Test fun pageBookmarksAndPagePinnedNotesCanBeEditedAndDeletedLocally() = checkModel {
        open()
        vm.page(2)
        runCurrent()
        vm.bookmark()
        runCurrent()
        vm.editNote(x = .3f, y = .7f)
        vm.draftText("A local question.")
        vm.saveDraft()
        runCurrent()
        val note = vm.uiState.value.record!!.notes.single()
        assertEquals(2, note.page)
        assertEquals(.3f, note.x)
        vm.editNote(note)
        vm.draftText("An edited question.")
        vm.saveDraft()
        runCurrent()
        assertEquals(note.id, vm.uiState.value.record!!.notes.single().id)
        assertEquals("An edited question.", vm.uiState.value.record!!.notes.single().text)
        vm.removeNote(vm.uiState.value.record!!.notes.single()) {}
        runCurrent()
        assertTrue(vm.uiState.value.record!!.notes.isEmpty())
        assertEquals(setOf(2), vm.uiState.value.record!!.bookmarks)
        vm.bookmark()
        runCurrent()
        assertTrue(vm.uiState.value.record!!.bookmarks.isEmpty())
        assertArrayEquals(ReadingFixtures.pdf, h.pdfFile.readBytes())
    }

    @Test fun failedAndUncertainNoteWritesRetainDraftAndRetryExactlyOnce() = checkModel {
        open()
        vm.editNote()
        vm.draftText("Retain this draft.")
        h.committedFailure = true
        vm.saveDraft()
        runCurrent()
        assertEquals("Retain this draft.", vm.uiState.value.draft!!.text)
        assertTrue(vm.uiState.value.pendingSave)
        h.committedFailure = false
        vm.retry()
        runCurrent()
        assertNull(vm.uiState.value.draft)
        assertEquals(1, vm.uiState.value.record!!.notes.size)
        assertFalse(vm.uiState.value.pendingSave)
    }

    @Test fun failedPositionSavePreventsSilentLeaveUntilRetrySucceeds() = checkModel {
        open()
        h.failWrite = true
        vm.viewport(ReadingPosition(1, 2f))
        var left = false
        vm.leave { left = true }
        runCurrent()
        assertFalse(left)
        assertTrue(vm.uiState.value.open)
        assertTrue(vm.uiState.value.dirty)
        h.failWrite = false
        vm.leave { left = true }
        runCurrent()
        assertTrue(left)
        assertFalse(vm.uiState.value.open)
    }

    @Test fun accountRemovalCancelsLateOpenAndClearsDraftAndBitmap() = checkModel {
        h.pendingLoad = CompletableDeferred()
        vm.open(h.course, h.file)
        runCurrent()
        h.ownerFlow.value = emptyList()
        runCurrent()
        h.pendingLoad!!.complete(Unit)
        runCurrent()
        assertNull(vm.uiState.value.image)
        assertNull(vm.uiState.value.record)
        assertNull(vm.uiState.value.draft)
        assertEquals(ReadingProblem.REMOVED, vm.uiState.value.problem)
        assertEquals(0, h.opened)
    }

    @Test fun versionReplacementStartsCleanAndKeepsPriorNotesAvailableForReviewAndExport() = checkModel {
        open()
        vm.editNote()
        vm.draftText("Old version note")
        vm.saveDraft()
        runCurrent()
        val old = vm.uiState.value.record!!
        h.updateBytes(ReadingFixtures.pdf.toString(Charsets.US_ASCII).replace("Breadth-first search", "Breadth-First search").toByteArray(Charsets.US_ASCII))
        open()
        assertNotEquals(old.identity.version, vm.uiState.value.record!!.identity.version)
        assertEquals(1, vm.uiState.value.otherVersions)
        assertTrue(vm.uiState.value.record!!.notes.isEmpty())
        vm.review(old)
        runCurrent()
        assertTrue(vm.uiState.value.archived)
        var exported = ""
        vm.export { _, text -> exported = text }
        runCurrent()
        assertTrue(exported.contains("Old version note"))
        assertFalse(exported.contains("example.invalid"))
    }

    @Test fun deletedSourceStillAllowsSavedNotesExportAndRecordDeletion() = checkModel {
        open()
        vm.editNote()
        vm.draftText("Keep independently.")
        vm.saveDraft()
        runCurrent()
        val record = vm.uiState.value.record!!
        h.pdfFile.delete()
        vm.library()
        runCurrent()
        assertTrue(vm.uiState.value.candidates.isEmpty())
        vm.review(record)
        runCurrent()
        var encoded = ""
        vm.export { _, text -> encoded = text }
        runCurrent()
        assertTrue(encoded.contains("Keep independently."))
        vm.removeRecord {}
        runCurrent()
        assertTrue(h.repo.collections.first().flatMap { it.records }.isEmpty())
    }

    @Test fun corruptReadingStoreDisablesEditsButKeepsPdfReadableWithoutOverwritingData() = checkModel {
        h.failRead = true
        vm.open(h.course, h.file)
        runCurrent()
        assertNotNull(vm.uiState.value.image)
        assertNull(vm.uiState.value.record)
        assertEquals(ReadingProblem.STORE, vm.uiState.value.problem)
        vm.bookmark()
        vm.editNote()
        runCurrent()
        assertNull(vm.uiState.value.draft)
        assertTrue(h.values.isEmpty())
    }

    @Test fun unsupportedDocumentAndPageFailureRemainRecoverableWithoutLosingSavedNotes() = checkModel {
        h.failOpen = true
        vm.open(h.course, h.file)
        runCurrent()
        assertEquals(ReadingProblem.PDF, vm.uiState.value.problem)
        h.failOpen = false
        open()
        h.failPage = 2
        vm.page(2)
        runCurrent()
        assertEquals(ReadingProblem.PAGE, vm.uiState.value.problem)
        assertEquals(1, vm.uiState.value.position.page)
        assertNotNull(vm.uiState.value.image)
        h.failPage = null
        vm.page(2)
        runCurrent()
        assertEquals(2, vm.uiState.value.position.page)
    }

    @Test fun aNoteSaveResolvesAnInterruptedAutosaveBeforeBuildingTheNextRevision() = checkModel {
        open()
        h.afterWrite = {
            h.afterWrite = null
            vm.viewport(ReadingPosition(1, 3f))
        }
        vm.viewport(ReadingPosition(1, 2f))
        advanceTimeBy(501)
        runCurrent()
        vm.editNote()
        vm.draftText("Keep this note after the interrupted position write.")
        vm.saveDraft()
        runCurrent()
        assertNull(vm.uiState.value.draft)
        assertFalse(vm.uiState.value.pendingSave)
        assertEquals(1, vm.uiState.value.record!!.notes.size)
        assertEquals(3f, vm.uiState.value.record!!.position.zoom)
    }

    @Test fun fullReadingStorageStillAllowsReadOnlyPdfNavigationAndLeaving() = checkModel {
        repeat(50) { index ->
            val record = ReadingFixtures.initial.copy(identity = ReadingFixtures.identity.copy(documentId = index.toString(16).padStart(64, '0')))
            h.repo.save(record, record)
        }
        vm.open(h.course, h.file)
        runCurrent()
        assertNotNull(vm.uiState.value.image)
        assertNull(vm.uiState.value.record)
        assertEquals(ReadingProblem.LIMIT, vm.uiState.value.problem)
        vm.page(2)
        runCurrent()
        assertEquals(2, vm.uiState.value.position.page)
        assertEquals(ReadingProblem.LIMIT, vm.uiState.value.problem)
        var left = false
        vm.leave { left = true }
        runCurrent()
        assertTrue(left)
    }

    @Test fun exportWritesOnlyPreparedSnapshotAndReportsDestinationFailure() = checkModel {
        open()
        vm.export { _, _ -> }
        runCurrent()
        val written = StringBuilder()
        assertTrue(vm.exportTo { written.append(it) })
        assertTrue(written.contains("uit-course-reading"))
        assertNull(vm.pendingExport)
        vm.export { _, _ -> }
        runCurrent()
        assertFalse(vm.exportTo { error("Destination unavailable") })
        assertEquals(ReadingProblem.EXPORT, vm.uiState.value.problem)
        assertNotNull(vm.uiState.value.record)
    }

    @Test fun preparedExportCannotWriteAfterOwnerRemovalOrRecordChange() = checkModel {
        open()
        var captured: ReadingRecord? = null
        vm.export { record, _ -> captured = record }
        runCurrent()
        assertTrue(vm.canExport(captured!!))
        vm.bookmark()
        runCurrent()
        assertFalse(vm.canExport(captured!!))
        h.ownerFlow.value = emptyList()
        runCurrent()
        assertFalse(vm.canExport(captured!!))
        assertNull(vm.pendingExport)
    }
}
