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
class WorkloadViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = ViewModelStore()
    private val owner = Account("study", "https://courses.uit.edu.vn", 1, "Study", PortalType.MOODLE)
    private val calendar = Account("calendar", "https://portal.uit.edu.vn", 1, "Calendar", PortalType.UIT_PORTAL)
    private val now = dateAt(LocalDate(2026, 10, 26), LocalTime(8, 0))
    private val sync = MutableStateFlow(SyncState())
    private val item = Assignment(1, 1, 101, "Assignment", "", now + 4 * 3600, accountId = owner.id)
    private val owners = object : AccountRepository {
        override val accounts = MutableStateFlow(listOf(owner, calendar))
        override suspend fun remove(accountId: String) {
            accounts.value = accounts.value.filterNot { it.id == accountId }
        }
        override suspend fun saveSessions(sessions: List<PortalSession>) = Unit
    }
    private val repo = object : WorkloadRepository {
        var reject = false
        override val accounts = MutableStateFlow(listOf(WorkloadAccount(owner, PortalSnapshot(assignments = listOf(item), updatedAt = now)), WorkloadAccount(calendar, PortalSnapshot(updatedAt = now))))
        override suspend fun savePolicy(accountId: String, policy: WorkloadPolicy) {
            check(!reject)
            accounts.value = accounts.value.map { if (it.account.id == accountId) it.copy(policy = policy, configured = true) else it }
        }
        override suspend fun saveEstimate(estimate: WorkloadEstimate) {
            check(!reject)
            accounts.value = accounts.value.map { if (it.account.id == estimate.ref.accountId) it.copy(estimates = listOf(estimate)) else it }
        }
        override suspend fun removeEstimate(ref: WorkloadRef) {
            accounts.value = accounts.value.map { it.copy(estimates = it.estimates.filterNot { e -> e.ref == ref }) }
        }
    }
    private fun model(blocks: FreeTimeBlockStorage? = null) = WorkloadViewModel(repo, owners, blocks, { now }, dispatcher, dispatcher, sync).also { models.put("workload", it) }

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    @After fun cleanup() {
        models.clear()
        dispatcher.scheduler.runCurrent()
        Dispatchers.resetMain()
    }

    @Test fun explicitSavesUpdateAnalysisAndZeroNeverChangesOfficialSubmission() = runTest(dispatcher) {
        val vm = model()
        runCurrent()
        vm.open()
        runCurrent()
        assertEquals(1, vm.uiState.value.result!!.tasks.size)
        var saved = false
        val ref = vm.uiState.value.result!!.tasks.single().item.ref
        vm.estimate(ref, "0", "") { saved = true }
        runCurrent()
        assertTrue(saved)
        assertEquals(0, vm.uiState.value.result!!.tasks.single().estimate!!.remainingMinutes)
        assertEquals(0, vm.uiState.value.result!!.submittedCount)
        vm.policy(WorkloadPolicy(calendarAccountId = calendar.id))
        runCurrent()
        assertTrue(vm.uiState.value.selected!!.configured)
        assertFalse(WorkloadIssue.UNCONFIRMED_BUDGET in vm.uiState.value.result!!.issues)
        vm.close()
    }

    @Test fun failedAndInvalidSavesDoNotDismissDraftsOrReplacePriorValues() = runTest(dispatcher) {
        val vm = model()
        runCurrent()
        vm.open()
        runCurrent()
        val ref = vm.uiState.value.result!!.tasks.single().item.ref
        var closed = false
        vm.estimate(ref, "not a number", "") { closed = true }
        runCurrent()
        assertFalse(closed)
        assertEquals(WorkloadProblem.INPUT, vm.uiState.value.problem)
        repo.reject = true
        vm.estimate(ref, "120", "") { closed = true }
        runCurrent()
        assertFalse(closed)
        assertEquals(WorkloadProblem.SAVE, vm.uiState.value.problem)
        assertNull(vm.uiState.value.result!!.tasks.single().estimate)
        vm.close()
    }

    @Test fun selectingAnotherProfileKeepsObservingSourcesAndDisconnectHidesPriorDataImmediately() = runTest(dispatcher) {
        val vm = model()
        runCurrent()
        vm.open()
        runCurrent()
        vm.select(calendar.id)
        runCurrent()
        assertTrue(vm.uiState.value.result!!.tasks.isEmpty())
        repo.accounts.value = repo.accounts.value.map { if (it.account.id == calendar.id) it.copy(snapshot = it.snapshot.copy(exams = listOf(DaaExamSlot("EXAM", "Exam", 1, "2026-10-27", "A101")))) else it }
        runCurrent()
        assertEquals(1, vm.uiState.value.result!!.tasks.size)
        owners.remove(calendar.id)
        runCurrent()
        assertEquals(owner.id, vm.uiState.value.selectedId)
        assertTrue(vm.uiState.value.result!!.tasks.none { it.item.ref.accountId == calendar.id })
        vm.close()
    }

    @Test fun missingPersonalBlocksAreExplicitAndReopeningReloadsThem() = runTest(dispatcher) {
        var fail = true
        var loads = 0
        val storage = object : FreeTimeBlockStorage {
            override fun load(accountIds: Set<String>): List<PersonalTimeBlock> {
                loads++
                check(!fail)
                return emptyList()
            }
            override fun save(accountIds: Set<String>, blocks: List<PersonalTimeBlock>) = Unit
            override fun clear(accountIds: Set<String>) = Unit
        }
        val vm = model(storage)
        runCurrent()
        vm.open()
        runCurrent()
        assertTrue(WorkloadIssue.PERSONAL_BLOCKS in vm.uiState.value.result!!.issues)
        vm.close()
        fail = false
        vm.open()
        runCurrent()
        assertFalse(WorkloadIssue.PERSONAL_BLOCKS in vm.uiState.value.result!!.issues)
        assertEquals(2, loads)
        vm.close()
    }

    @Test fun corruptPrimaryPlanningStateIsRetainedAndAnalysisDoesNotUseDefaults() = runTest(dispatcher) {
        repo.accounts.value = repo.accounts.value.map { if (it.account.id == owner.id) it.copy(storageFailed = true) else it }
        val vm = model()
        runCurrent()
        vm.open()
        runCurrent()
        assertNull(vm.uiState.value.result)
        assertEquals(WorkloadProblem.LOAD, vm.uiState.value.problem)
        vm.policy(WorkloadPolicy())
        runCurrent()
        assertFalse(repo.accounts.value.first().configured)
        vm.close()
    }

    @Test fun failedWorkspaceRefreshMarksRetainedCachedDataAsUncertain() = runTest(dispatcher) {
        val vm = model()
        runCurrent()
        vm.open()
        runCurrent()
        val original = vm.uiState.value.result!!.tasks.single().item.ref
        sync.value = SyncState(error = AppError.NetworkOffline)
        runCurrent()
        assertTrue(WorkloadIssue.REFRESH_FAILED in vm.uiState.value.result!!.issues)
        assertEquals(original, vm.uiState.value.result!!.tasks.single().item.ref)
        assertEquals(0, vm.uiState.value.result!!.checkpoints.single().capacity.conservative)
        sync.value = SyncState()
        runCurrent()
        assertFalse(WorkloadIssue.REFRESH_FAILED in vm.uiState.value.result!!.issues)
        vm.close()
    }
}
