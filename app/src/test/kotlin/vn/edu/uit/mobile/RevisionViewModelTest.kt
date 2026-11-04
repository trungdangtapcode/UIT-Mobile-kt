package vn.edu.uit.mobile

import android.app.Application
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.datetime.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import vn.edu.uit.mobile.common.*
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.model.*
import vn.edu.uit.mobile.schedule.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class RevisionViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private var now = dateAt(LocalDate(2026, 10, 26), LocalTime(8, 0))
    private val owner = Account("teaching", "https://portal.uit.edu.vn", 1, "Teaching", PortalType.UIT_PORTAL)
    private val policy = WorkloadPolicy(days = (1..7).map { StudyDayBudget(it, 8 * 60, 18 * 60, 240) }, travelBuffer = 0, deadlineLead = 0)
    private val original = WorkloadAccount(owner, PortalSnapshot(exams = listOf(DaaExamSlot("CS", "Database systems", 0, "2026-10-27", "", startTime = "12:00")), updatedAt = now), policy, configured = true)
    private val owners = object : AccountRepository {
        override val accounts = MutableStateFlow(listOf(owner))
        override suspend fun remove(accountId: String) {
            accounts.value = emptyList()
        }
        override suspend fun saveSessions(sessions: List<PortalSession>) = Unit
    }
    private val workloadRepo = object : WorkloadRepository {
        override val accounts = MutableStateFlow(listOf(original))
        override suspend fun savePolicy(accountId: String, policy: WorkloadPolicy) {
            accounts.value = accounts.value.map { it.copy(policy = policy, configured = true) }
        }
        override suspend fun saveEstimate(estimate: WorkloadEstimate) = Unit
        override suspend fun removeEstimate(ref: WorkloadRef) = Unit
    }
    private val repo = object : RevisionRepository {
        override val accounts = MutableStateFlow(listOf(RevisionAccount(owner.id)))
        var reject = false
        var writes = 0
        override suspend fun replace(accountId: String, expected: RevisionWorkspace, value: RevisionWorkspace) {
            check(!reject)
            check(accounts.value.single().workspace == expected)
            writes++
            accounts.value = listOf(RevisionAccount(accountId, value))
        }
    }
    private lateinit var vm: RevisionViewModel

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        val workload = WorkloadViewModel(workloadRepo, owners, clock = { now }, computation = dispatcher, io = dispatcher)
        store.put("workload", workload)
        vm = RevisionViewModel(repo, workload, { now }, dispatcher)
        store.put("revision", vm)
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
    private fun TestScope.openTopic(minutes: String = "120"): WorkloadRef {
        vm.open()
        runCurrent()
        val exam = vm.uiState.value.exams.single()
        vm.selectExam(exam.ref.id)
        vm.addExam(exam.ref.id)
        runCurrent()
        var closed = false
        vm.topic(exam.ref, null, "Transactions", minutes) { closed = true }
        runCurrent()
        assertTrue("Own repository echoes must not cancel the completion callback", closed)
        return exam.ref
    }

    @Test fun generationStaysUnsavedUntilExplicitConfirmationAndEchoesDoNotLoseCallbacks() = checkModel {
        openTopic()
        val writes = repo.writes
        vm.generate()
        runCurrent()
        assertNotNull(vm.uiState.value.draft)
        assertEquals(writes, repo.writes)
        assertTrue(vm.uiState.value.saved.plans.single().sessions.isEmpty())
        vm.saveDraft()
        runCurrent()
        assertNull(vm.uiState.value.draft)
        assertTrue(repo.accounts.value.single().workspace.plans.single().sessions.isNotEmpty())
        assertEquals(writes + 1, repo.writes)
    }

    @Test fun failedAndInvalidTopicSavesKeepTheirDraftOpen() = checkModel {
        val ref = openTopic()
        var closed = false
        vm.topic(ref, null, "", "0") { closed = true }
        runCurrent()
        assertFalse(closed)
        assertEquals(RevisionMessage.INPUT, vm.uiState.value.message)
        repo.reject = true
        vm.topic(ref, null, "Indexes", "30") { closed = true }
        runCurrent()
        assertFalse(closed)
        assertEquals(RevisionMessage.SAVE, vm.uiState.value.message)
        assertEquals(1, vm.uiState.value.saved.plans.single().topics.size)
    }

    @Test fun changingSourcesInvalidatesTheDraftAndNeverReassignsSavedExamProgress() = checkModel {
        val ref = openTopic()
        vm.generate()
        runCurrent()
        assertNotNull(vm.uiState.value.draft)
        workloadRepo.accounts.value = listOf(original.copy(snapshot = original.snapshot.copy(exams = original.snapshot.exams.map { it.copy(examDate = "2026-10-28") })))
        runCurrent()
        assertNull(vm.uiState.value.draft)
        assertEquals(ref, vm.uiState.value.saved.plans.single().exam)
        assertTrue(vm.uiState.value.exams.none { it.ref == ref })
        vm.generate()
        runCurrent()
        assertTrue(RevisionProblem.SOURCE_CHANGED in vm.uiState.value.draft!!.problems)
    }

    @Test fun movesAreCheckedAgainstOtherSessionsAndSuccessfulMovesAreLocked() = checkModel {
        val ref = openTopic()
        vm.generate()
        runCurrent()
        vm.saveDraft()
        runCurrent()
        val session = vm.uiState.value.saved.plans.single().sessions.first()
        var closed = false
        vm.move(ref, session.id, "2026-10-27", "12:00", "13:00") { closed = true }
        runCurrent()
        assertFalse(closed)
        assertEquals(RevisionMessage.INPUT, vm.uiState.value.message)
        vm.move(ref, session.id, "2026-10-26", "16:00", "17:30") { closed = true }
        runCurrent()
        assertTrue(closed)
        assertTrue(vm.uiState.value.saved.plans.single().sessions.first().locked)
    }

    @Test fun reportedCompletionSurvivesReplanningAndCannotBeReducedBelowCreditedEffort() = checkModel {
        val ref = openTopic()
        vm.generate()
        runCurrent()
        vm.saveDraft()
        runCurrent()
        val session = vm.uiState.value.saved.plans.single().sessions.first()
        now = session.end + 60
        vm.session(ref, session.id, RevisionStatus.COMPLETED)
        runCurrent()
        val completed = vm.uiState.value.saved.plans.single().sessions.first()
        assertEquals(RevisionStatus.COMPLETED, completed.status)
        var closed = false
        vm.topic(ref, completed.topicId, "Transactions", "1") { closed = true }
        runCurrent()
        assertFalse(closed)
        vm.generate()
        runCurrent()
        assertTrue(completed in vm.uiState.value.draft!!.workspace.plans.single().sessions)
    }

    @Test fun failedDraftSavesRetainTheProposalForAnExplicitRetry() = checkModel {
        openTopic()
        vm.generate()
        runCurrent()
        val draft = vm.uiState.value.draft
        repo.reject = true
        vm.saveDraft()
        runCurrent()
        assertEquals(draft, vm.uiState.value.draft)
        assertEquals(RevisionMessage.SAVE, vm.uiState.value.message)
        assertTrue(vm.uiState.value.saved.plans.single().sessions.isEmpty())
        repo.reject = false
        vm.saveDraft()
        runCurrent()
        assertNull(vm.uiState.value.draft)
        assertTrue(vm.uiState.value.saved.plans.single().sessions.isNotEmpty())
    }

    @Test fun maximumBelowTheSharedMinimumIsRejectedWithoutDismissingSettings() = checkModel {
        openTopic()
        var closed = false
        vm.options("20", "10", "10") { closed = true }
        runCurrent()
        assertFalse(closed)
        assertEquals(RevisionMessage.INPUT, vm.uiState.value.message)
        assertEquals(RevisionOptions(), vm.uiState.value.saved.options)
    }

    @Test fun malformedExamSourcesKeepLocalHistoryAndExposeAnAnalysisErrorWithoutCrashing() = checkModel {
        val ref = openTopic()
        workloadRepo.accounts.value = listOf(original.copy(snapshot = original.snapshot.copy(exams = original.snapshot.exams.map { it.copy(examDate = "invalid-date") })))
        runCurrent()
        assertTrue(vm.uiState.value.exams.isEmpty())
        assertEquals(ref, vm.uiState.value.saved.plans.single().exam)
        assertEquals(WorkloadProblem.ANALYSIS, vm.uiState.value.base.problem)
        assertNull(vm.uiState.value.base.result)
    }

    @Test fun removalAndCloseClearAccountDataAndCancelPendingDrafts() = checkModel {
        openTopic()
        vm.generate()
        runCurrent()
        assertNotNull(vm.uiState.value.draft)
        owners.accounts.value = emptyList()
        repo.accounts.value = emptyList()
        runCurrent()
        assertNull(vm.uiState.value.owner)
        assertNull(vm.uiState.value.draft)
        assertTrue(vm.uiState.value.saved.plans.isEmpty())
        vm.close()
        assertNull(vm.uiState.value.owner)
    }
}
