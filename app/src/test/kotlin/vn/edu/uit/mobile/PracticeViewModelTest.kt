package vn.edu.uit.mobile

import android.app.Application
import androidx.lifecycle.ViewModelStore
import java.lang.reflect.Proxy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import vn.edu.uit.mobile.courses.*
import vn.edu.uit.mobile.data.*
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.model.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class PracticeViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val course = Course(1, "owner", "IT004", "Database systems")
    private val owners = object : AccountRepository {
        override val accounts = MutableStateFlow(listOf(Account("owner", "https://example.invalid", 1, "Synthetic", PortalType.MOODLE), Account("other", "https://example.invalid", 2, "Synthetic other", PortalType.MOODLE)))
        override suspend fun remove(accountId: String) = error("University account writes forbidden")
        override suspend fun saveSessions(sessions: List<PortalSession>) = error("University account writes forbidden")
    }
    private val snapshot = MutableStateFlow(PortalSnapshot(courses = listOf(course)))
    private val universityCalls = mutableListOf<String>()
    private val portal = Proxy.newProxyInstance(PortalRepository::class.java.classLoader, arrayOf(PortalRepository::class.java)) { _, method, _ ->
        universityCalls += method.name
        when (method.name) {
            "getSnapshot" -> snapshot
            "getSyncState" -> MutableStateFlow(SyncState())
            else -> throw AssertionError("University operation blocked: ${method.name}")
        }
    } as PortalRepository
    private val storage = object : StudyStorage {
        val values = mutableMapOf<String, String>()
        var fail = false
        override fun read(key: String) = values[key]
        override fun write(key: String, value: String) {
            check(!fail)
            values[key] = value
        }
        override fun remove(key: String) {
            values.remove(key)
        }
        override fun removeAccount(accountId: String) = error("Account removal is not invoked by this test adapter")
    }
    private val local = LocalPracticeRepository(storage, owners, io = dispatcher) { 1793386800 }
    private var sourcePending: CompletableDeferred<List<StudySource>>? = null
    private val ai = object : StudyAiRepository {
        var calls = 0
        var fail = false
        var pending: CompletableDeferred<PracticeGeneration>? = null
        override suspend fun connection() = StudyConnection(configured = true, model = "synthetic-practice-model")
        override suspend fun configure(endpoint: String, token: String) = Unit
        override suspend fun configureDirect(apiKey: String, model: String) = Unit
        override suspend fun disconnect() = Unit
        override suspend fun generate(course: Course, mode: String, sources: List<StudySource>, question: String, language: String): StudyAnswer = error("Wrong generation mode")
        override suspend fun generatePractice(course: Course, sources: List<StudySource>, options: PracticeOptions): PracticeGeneration {
            calls++
            check(!fail)
            return pending?.await() ?: PracticeFixtures.generation
        }
        override suspend fun tasks(course: Course, assignmentId: Long) = emptyList<StudyTask>()
        override suspend fun saveTasks(course: Course, assignmentId: Long, tasks: List<StudyTask>) = error("Checklist writes forbidden")
    }
    private lateinit var vm: PracticeViewModel

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        vm = PracticeViewModel(ai, local, owners, portal, OfflineCourseSourceLoader { _, _, _ -> sourcePending?.await() ?: listOf(PracticeFixtures.source) }, io = dispatcher)
        store.put("practice", vm)
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
    private fun TestScope.openSelected() {
        runCurrent()
        vm.open(course, emptyList(), emptyList())
        runCurrent()
        vm.select(PracticeFixtures.source.id)
        vm.options(PracticeOptions(count = 2))
        runCurrent()
    }
    private fun TestScope.generated() {
        openSelected()
        vm.generate()
        runCurrent()
        assertNotNull(vm.uiState.value.draft)
    }
    private fun TestScope.saved(): PracticeRecord {
        generated()
        vm.save()
        runCurrent()
        return vm.uiState.value.records.single()
    }

    @Test fun onlyAnExplicitSendCallsAiAndPreviewDoesNotSaveOrTouchUniversity() = checkModel {
        openSelected()
        assertEquals(0, ai.calls)
        assertTrue(vm.uiState.value.records.isEmpty())
        vm.generate()
        runCurrent()
        assertEquals(1, ai.calls)
        assertEquals(PracticeStage.PREVIEW, vm.uiState.value.stage)
        assertTrue(vm.uiState.value.records.isEmpty())
        assertTrue(universityCalls.all { it in setOf("getSnapshot", "getSyncState") })
    }

    @Test fun metadataChangingDuringSourceLoadingCannotBecomeACurrentSelection() = checkModel {
        runCurrent()
        sourcePending = CompletableDeferred()
        vm.open(course, emptyList(), emptyList())
        runCurrent()
        assertTrue(vm.uiState.value.busy)
        snapshot.value = snapshot.value.copy(assignments = listOf(Assignment(1, 1, 1, "Changed brief", "New cached instructions", 0, accountId = course.accountId)))
        runCurrent()
        sourcePending!!.complete(listOf(PracticeFixtures.source))
        runCurrent()
        assertTrue(vm.uiState.value.sourceChanged)
        assertEquals(PracticeProblem.CHANGED, vm.uiState.value.problem)
        vm.select(PracticeFixtures.source.id)
        vm.options(PracticeOptions(count = 2))
        vm.generate()
        runCurrent()
        assertEquals(0, ai.calls)
        assertNull(vm.uiState.value.draft)
        sourcePending = null
        vm.reload()
        runCurrent()
        assertFalse(vm.uiState.value.sourceChanged)
        assertTrue(vm.uiState.value.sources.any { it.id == "assignment:1" })
    }

    @Test fun reloadingACourseScopedLibraryKeepsTheSavedLibraryOpen() = checkModel {
        val record = saved()
        assertEquals(PracticeStage.LIBRARY, vm.uiState.value.stage)
        vm.reload()
        runCurrent()
        assertEquals(PracticeStage.LIBRARY, vm.uiState.value.stage)
        assertEquals(record, vm.uiState.value.records.single())
        assertEquals(1, ai.calls)
    }

    @Test fun editsReorderAndDeletionStayInTheDraftUntilExplicitSave() = checkModel {
        generated()
        var applied = false
        val original = vm.uiState.value.draft!!.questions.first()
        vm.edit(original.copy(prompt = "Edited practice question?")) { applied = true }
        assertTrue(applied)
        vm.reorder(original.id, 1)
        assertEquals(original.id, vm.uiState.value.draft!!.questions.last().id)
        vm.removeQuestion("q2")
        vm.save()
        runCurrent()
        assertEquals("Edited practice question?", vm.uiState.value.records.single().set.questions.single().prompt)
        assertNull(vm.uiState.value.draft)
    }

    @Test fun invalidEditsAndFailedSetSavesPreserveTheDraftForRetry() = checkModel {
        generated()
        var applied = false
        vm.edit(vm.uiState.value.draft!!.questions.first().copy(answer = "Wrong answer")) { applied = true }
        assertFalse(applied)
        assertEquals(PracticeProblem.INPUT, vm.uiState.value.problem)
        val draft = vm.uiState.value.draft
        storage.fail = true
        vm.save()
        runCurrent()
        assertEquals(draft, vm.uiState.value.draft)
        assertEquals(PracticeProblem.SAVE, vm.uiState.value.problem)
        storage.fail = false
        vm.save()
        runCurrent()
        assertNull(vm.uiState.value.draft)
        assertEquals(1, vm.uiState.value.records.size)
    }

    @Test fun canceledAndFailedGenerationCannotProduceAHiddenOrLateSavedSet() = checkModel {
        openSelected()
        ai.pending = CompletableDeferred()
        vm.generate()
        runCurrent()
        assertTrue(vm.uiState.value.busy)
        vm.cancel()
        ai.pending!!.complete(PracticeFixtures.generation)
        runCurrent()
        assertNull(vm.uiState.value.draft)
        assertEquals(PracticeProblem.CANCELED, vm.uiState.value.problem)
        ai.pending = null
        ai.fail = true
        vm.generate()
        runCurrent()
        assertEquals(PracticeProblem.GENERATE, vm.uiState.value.problem)
        assertTrue(vm.uiState.value.records.isEmpty())
    }

    @Test fun emptyEvidenceResultStaysInSourceSelectionAndNeverBecomesASavedSet() = checkModel {
        openSelected()
        ai.pending = CompletableDeferred(PracticeFixtures.generation.copy(status = "insufficient_evidence", message = "Not enough evidence.", questions = emptyList()))
        vm.generate()
        runCurrent()
        assertNull(vm.uiState.value.draft)
        assertEquals(PracticeStage.SELECT, vm.uiState.value.stage)
        assertEquals("Not enough evidence.", vm.uiState.value.generationMessage)
    }

    @Test fun cachedMetadataChangesInvalidateUnsavedGenerationButDoNotRewriteSavedProgress() = checkModel {
        generated()
        snapshot.value = snapshot.value.copy(assignments = listOf(Assignment(1, 1, 1, "Changed brief", "New source text", 0, accountId = course.accountId)))
        runCurrent()
        assertNull(vm.uiState.value.draft)
        assertTrue(vm.uiState.value.sourceChanged)
        vm.generate()
        runCurrent()
        assertEquals(PracticeProblem.INPUT, vm.uiState.value.problem)
    }

    @Test fun unrelatedSnapshotTimestampDoesNotDiscardAnEditedDraft() = checkModel {
        generated()
        vm.title("Edited title")
        val draft = vm.uiState.value.draft
        snapshot.value = snapshot.value.copy(updatedAt = 100)
        runCurrent()
        assertEquals(draft, vm.uiState.value.draft)
    }

    @Test fun checkedAnswersAndSelfReportedRecallPersistOneAttemptAtATime() = checkModel {
        val record = saved()
        vm.start(record, false)
        assertFalse(vm.uiState.value.run!!.revealed)
        vm.choose(1)
        vm.answer(null)
        runCurrent()
        assertTrue(vm.uiState.value.run!!.recorded)
        assertFalse(vm.uiState.value.record!!.progress.single().lastOutcome)
        vm.answer(null)
        runCurrent()
        assertEquals(1, vm.uiState.value.record!!.progress.single().attempts)
        vm.next()
        vm.reveal()
        runCurrent()
        assertEquals(1, vm.uiState.value.record!!.progress.size)
        vm.answer(true)
        runCurrent()
        vm.next()
        assertEquals(PracticeStage.RESULTS, vm.uiState.value.stage)
        assertEquals(2, vm.uiState.value.run!!.outcomes.size)
    }

    @Test fun failedAnswerSaveKeepsTheRevealedAnswerAndRetryDoesNotDoubleCount() = checkModel {
        val record = saved()
        vm.start(record, false)
        vm.choose(0)
        storage.fail = true
        vm.answer(null)
        runCurrent()
        assertTrue(vm.uiState.value.run!!.revealed)
        assertFalse(vm.uiState.value.run!!.recorded)
        vm.next()
        assertEquals(0, vm.uiState.value.run!!.index)
        storage.fail = false
        vm.retryAttempt()
        runCurrent()
        assertEquals(1, vm.uiState.value.record!!.progress.single().attempts)
        vm.retryAttempt()
        runCurrent()
        assertEquals(1, vm.uiState.value.record!!.progress.single().attempts)
    }

    @Test fun reopeningTheLibraryRestoresProgressWithoutAiOrUniversityCalls() = checkModel {
        val record = saved()
        vm.start(record, false)
        vm.choose(0)
        vm.answer(null)
        runCurrent()
        val calls = ai.calls
        vm.close()
        vm.openSaved()
        runCurrent()
        assertEquals(1, vm.uiState.value.records.single().progress.single().attempts)
        assertEquals(calls, ai.calls)
        assertTrue(universityCalls.all { it in setOf("getSnapshot", "getSyncState") })
    }

    @Test fun duplicateSetIdsAcrossConnectedOwnersCannotSelectTheWrongAccount() = checkModel {
        runCurrent()
        local.save(PracticeFixtures.set())
        local.save(PracticeFixtures.set(owner = "other"))
        runCurrent()
        vm.openSaved()
        runCurrent()
        vm.start(vm.uiState.value.records.single { it.set.accountId == "other" }, false)
        assertEquals("other", vm.uiState.value.record!!.set.accountId)
        vm.choose(0)
        vm.answer(null)
        runCurrent()
        assertTrue(vm.uiState.value.records.single { it.set.accountId == "owner" }.progress.isEmpty())
    }

    @Test fun disconnectingTheOwnerClearsDraftsAndActiveSourceStateWithoutAccountWrites() = checkModel {
        generated()
        owners.accounts.value = owners.accounts.value.filterNot { it.id == "owner" }
        runCurrent()
        assertNull(vm.uiState.value.course)
        assertNull(vm.uiState.value.draft)
        assertTrue(vm.uiState.value.sources.isEmpty())
        assertEquals(PracticeProblem.REMOVED, vm.uiState.value.problem)
    }
}
