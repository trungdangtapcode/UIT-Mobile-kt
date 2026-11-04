package vn.edu.uit.mobile

import android.app.Application
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import vn.edu.uit.mobile.courses.*
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.model.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class CodeTutorViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private lateinit var h: CodeTutorTestHarness
    private val vm get() = h.vm

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        h = CodeTutorTestHarness(dispatcher)
        store.put("tutor", vm)
    }

    @After fun cleanup() {
        vm.close()
        store.clear()
        dispatcher.scheduler.runCurrent()
        Dispatchers.resetMain()
    }
    private fun checkModel(block: suspend TestScope.() -> Unit) = runTest(dispatcher) {
        try {
            block()
        } finally {
            vm.close()
            runCurrent()
        }
    }
    private fun TestScope.open(course: Boolean = true) {
        runCurrent()
        vm.open(if (course) h.course else null)
        runCurrent()
        vm.code(TutorFixtures.code)
        runCurrent()
    }
    private fun TestScope.analyzed() {
        open()
        vm.analyze()
        runCurrent()
        assertNotNull(vm.uiState.value.result)
    }

    @Test fun explicitRangeAndCheckedContextOnlyAreSentWithoutUniversityTraffic() = checkModel {
        open()
        vm.code("# private prefix\n" + TutorFixtures.code + "# private suffix")
        vm.range("2", "3")
        vm.select(TutorFixtures.context.id)
        assertTrue(h.requests.isEmpty())
        vm.analyze()
        runCurrent()
        assertEquals(TutorFixtures.code.trimEnd(), h.requests.single().snippet.code)
        assertEquals(2, h.requests.single().snippet.firstLine)
        assertEquals(listOf(TutorFixtures.context), h.requests.single().sources)
        assertNotNull(vm.uiState.value.result)
        assertTrue(h.universityCalls.all { it == "getSnapshot" })
    }

    @Test fun standaloneCodeDoesNotRequireAnOwnerAndDefaultsToNoContext() = checkModel {
        h.ownerFlow.value = emptyList()
        open(false)
        vm.analyze()
        runCurrent()
        assertNotNull(vm.uiState.value.result)
        assertTrue(h.requests.single().sources.isEmpty())
        assertNull(vm.uiState.value.course)
    }

    @Test fun invalidRangeOversizedPasteAndMissingConnectionNeverSend() = checkModel {
        open()
        vm.range("0", "99")
        vm.analyze()
        runCurrent()
        assertTrue(h.requests.isEmpty())
        assertEquals(TutorProblem.INPUT, vm.uiState.value.problem)
        vm.code("x".repeat(48_001))
        assertEquals(TutorFixtures.code, vm.uiState.value.code)
        h.configured = false
        vm.reload()
        runCurrent()
        vm.range("1", "3")
        vm.analyze()
        runCurrent()
        assertTrue(h.requests.isEmpty())
    }

    @Test fun hintsRevealLocallyAndProposedEditsNeverReplaceTheEditor() = checkModel {
        open()
        vm.tab(TutorTab.HINTS)
        vm.analyze()
        runCurrent()
        assertEquals(TutorTab.HINTS, vm.uiState.value.tab)
        assertEquals(0, vm.uiState.value.revealedHints)
        repeat(5) { vm.reveal() }
        assertEquals(3, vm.uiState.value.revealedHints)
        assertEquals(1, h.requests.size)
        assertEquals(TutorFixtures.code, vm.uiState.value.code)
        vm.edit()
        assertNull(vm.uiState.value.result)
        assertEquals(0, vm.uiState.value.revealedHints)
        assertEquals(TutorFixtures.code, vm.uiState.value.code)
    }

    @Test fun importingACellRetainsItsVersionAndOnlyCopiesExplicitly() = checkModel {
        open()
        assertNull(vm.uiState.value.imported)
        vm.use(h.imported.copy(id = "not-in-catalog"))
        assertNull(vm.uiState.value.imported)
        vm.use(h.imported)
        assertEquals(h.imported, vm.uiState.value.imported)
        assertEquals(TutorFixtures.code, vm.uiState.value.code)
        vm.analyze()
        runCurrent()
        vm.question("Another question")
        assertNull(vm.uiState.value.result)
        assertNull(vm.uiState.value.submitted)
    }

    @Test fun canceledLateAndFailedRepliesRetainCodeWithoutPublishingAnAnalysis() = checkModel {
        open()
        h.replyPending = CompletableDeferred()
        vm.analyze()
        runCurrent()
        assertTrue(vm.uiState.value.busy)
        vm.cancel()
        h.replyPending!!.complete(TutorFixtures.result(h.requests.single()))
        runCurrent()
        assertNull(vm.uiState.value.result)
        assertEquals(TutorProblem.CANCELED, vm.uiState.value.problem)
        h.replyPending = null
        h.failGenerate = true
        vm.analyze()
        runCurrent()
        assertEquals(TutorProblem.GENERATE, vm.uiState.value.problem)
        assertEquals(TutorFixtures.code, vm.uiState.value.code)
    }

    @Test fun metadataChangingDuringLoadCannotBeAcceptedAsCurrent() = checkModel {
        runCurrent()
        h.contextPending = CompletableDeferred()
        vm.open(h.course)
        runCurrent()
        h.snapshot.value = h.snapshot.value.copy(assignments = listOf(Assignment(1, 1, 1, "New brief", "Changed instructions", 0, accountId = "owner")))
        runCurrent()
        h.contextPending!!.complete(listOf(TutorFixtures.context))
        runCurrent()
        assertTrue(vm.uiState.value.sourceChanged)
        assertTrue(vm.uiState.value.sources.isEmpty())
        vm.code(TutorFixtures.code)
        vm.analyze()
        runCurrent()
        assertTrue(h.requests.isEmpty())
        h.contextPending = null
        vm.reload()
        runCurrent()
        assertFalse(vm.uiState.value.sourceChanged)
        assertTrue(vm.uiState.value.sources.any { it.id == "assignment:1" })
    }

    @Test fun assignmentFileChangesInvalidateAnalysisButRetainTheEditorCopy() = checkModel {
        analyzed()
        h.snapshot.value = h.snapshot.value.copy(assignments = listOf(Assignment(1, 1, 1, "Brief", "", 0, accountId = "owner", files = listOf(CourseFile("changed.py", "https://example.invalid/changed.py", 20)))))
        runCurrent()
        assertNull(vm.uiState.value.result)
        assertTrue(vm.uiState.value.sourceChanged)
        assertEquals(TutorFixtures.code, vm.uiState.value.code)
        vm.analyze()
        runCurrent()
        assertEquals(1, h.requests.size)
        vm.reload()
        runCurrent()
        assertFalse(vm.uiState.value.sourceChanged)
    }

    @Test fun unrelatedSnapshotTimestampDoesNotInvalidateCode() = checkModel {
        analyzed()
        val result = vm.uiState.value.result
        h.snapshot.value = h.snapshot.value.copy(updatedAt = 100)
        runCurrent()
        assertEquals(result, vm.uiState.value.result)
    }

    @Test fun ownerRemovalClearsCodeAndCancelsAnInFlightReply() = checkModel {
        open()
        h.replyPending = CompletableDeferred()
        vm.analyze()
        runCurrent()
        h.ownerFlow.value = emptyList()
        runCurrent()
        h.replyPending!!.complete(TutorFixtures.result(h.requests.single()))
        runCurrent()
        assertEquals("", vm.uiState.value.code)
        assertNull(vm.uiState.value.course)
        assertNull(vm.uiState.value.result)
        assertTrue(vm.uiState.value.sources.isEmpty())
        assertEquals(TutorProblem.REMOVED, vm.uiState.value.problem)
    }

    @Test fun unknownContextResultHasNoEditAndCloseClearsWorkspace() = checkModel {
        open()
        h.unknown = true
        vm.analyze()
        runCurrent()
        assertEquals("insufficient_context", vm.uiState.value.result!!.status)
        assertTrue(vm.uiState.value.result!!.tests.isEmpty())
        vm.close()
        assertEquals("", vm.uiState.value.code)
        assertFalse(vm.uiState.value.open)
    }

    @Test fun failedConfigurationKeepsTheDialogPendingAndTheCodeIntact() = checkModel {
        open()
        h.failConfigure = true
        var done = false
        vm.configure(StudyConnectionMode.DIRECT, "", "synthetic-key", "synthetic-model") { done = true }
        runCurrent()
        assertFalse(done)
        assertEquals(TutorProblem.CONNECTION, vm.uiState.value.problem)
        assertEquals(TutorFixtures.code, vm.uiState.value.code)
    }
}
