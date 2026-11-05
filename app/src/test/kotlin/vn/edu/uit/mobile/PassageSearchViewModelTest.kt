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
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.model.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class PassageSearchViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val fixture = PassageFixtures

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    @After fun cleanup() {
        store.clear()
        Dispatchers.resetMain()
    }
    private class Repo : PassageSearchRepository {
        override val library = MutableStateFlow(PassageLibrary(listOf(PassageFixtures.index)))
        var pendingCatalog: CompletableDeferred<Unit>? = null
        var pendingCheck: CompletableDeferred<Unit>? = null
        var checks = 0
        var failCheck = false
        var pendingSearch: CompletableDeferred<Unit>? = null
        var queries = 0
        var builds = 0
        var downloaded = true
        override suspend fun initialize() = Unit
        override suspend fun candidates(course: Course): List<PassageCandidate> {
            pendingCatalog?.let { withContext(NonCancellable) { it.await() } }
            return listOf(PassageCandidate(PassageFixtures.spec, downloaded))
        }
        override suspend fun build(course: Course, ids: Set<String>, config: PassageConfig, embedding: PassageEmbeddingConfig?, progress: (Int, Int) -> Unit) {
            builds++
        }
        override suspend fun check(course: Course, ids: Set<String>, config: PassageConfig, embedding: PassageEmbeddingConfig?): PassageUpdatePlan {
            checks++
            if (failCheck) error("Local verification failed")
            pendingCheck?.let { withContext(NonCancellable) { it.await() } }
            return PassageUpdatePlan(course.accountId, course.id, "test-review", 1, config, embedding, listOf(PassageFileCheck(PassageFixtures.spec, PassageFileStatus.KEYWORDS_READY, 1, 0, false)), 0, 1, 0, 0, 0)
        }
        override suspend fun update(course: Course, plan: PassageUpdatePlan, progress: (Int, Int) -> Unit) {
            builds++
        }
        override suspend fun search(course: Course, query: String, mode: PassageSearchMode): PassageSearchResult {
            queries++
            pendingSearch?.let { withContext(NonCancellable) { it.await() } }
            return PassageSearchResult(listOf(PassageFixtures.hit), mode, mode)
        }
        override suspend fun inspect(course: Course, hit: PassageHit) = PassageInspection(hit, PassageFixtures.source.parts.single())
        override suspend fun clear(owner: String) {
            library.value = PassageLibrary()
        }
        override suspend fun reset() {
            library.value = PassageLibrary()
        }
    }
    private class Owners : AccountRepository {
        override val accounts = MutableStateFlow(listOf(Account(PassageFixtures.course.accountId, "https://courses.example.org", 1, "Synthetic", PortalType.MOODLE)))
        override suspend fun remove(accountId: String) {
            accounts.value = emptyList()
        }
        override suspend fun saveSessions(sessions: List<PortalSession>) = Unit
    }
    private fun create(repo: Repo, owners: Owners = Owners()): PassageSearchViewModel {
        val portal = FakePortalRepository().apply { snapshot.value = PortalSnapshot(courses = listOf(fixture.course)) }
        val ai = Proxy.newProxyInstance(StudyAiRepository::class.java.classLoader, arrayOf(StudyAiRepository::class.java)) { _, method, _ ->
            when (method.name) {
                "connection" -> StudyConnection()
                "getConnectionChanges" -> kotlinx.coroutines.flow.emptyFlow<StudyConnection>()
                else -> error("No inference or configuration")
            }
        } as StudyAiRepository
        return PassageSearchViewModel(repo, portal.snapshot, owners, ai).also { store.put("vm", it) }
    }

    @Test fun selectionTypingAndModeChangesNeverSubmitOrBuildAutomatically() = runTest(dispatcher) {
        val repo = Repo()
        val vm = create(repo)
        advanceUntilIdle()
        vm.select(fixture.course)
        advanceUntilIdle()
        vm.query("isolation")
        vm.mode(PassageSearchMode.HYBRID)
        advanceUntilIdle()
        assertEquals(0, repo.queries)
        assertEquals(0, repo.builds)
        vm.search()
        advanceUntilIdle()
        assertEquals(1, repo.queries)
        vm.select(fixture.course)
        advanceUntilIdle()
        assertEquals(PassageSearchMode.BM25, vm.state.value.mode)
    }

    @Test fun aLateSearchCannotRestoreResultsAfterLeavingTheScreen() = runTest(dispatcher) {
        val repo = Repo()
        val vm = create(repo)
        advanceUntilIdle()
        vm.select(fixture.course)
        advanceUntilIdle()
        vm.query("isolation")
        repo.pendingSearch = CompletableDeferred()
        vm.search()
        runCurrent()
        vm.close()
        repo.pendingSearch!!.complete(Unit)
        advanceUntilIdle()
        assertNull(vm.state.value.result)
        assertEquals("", vm.state.value.query)
        assertNull(vm.state.value.work)
    }

    @Test fun accountRemovalDuringCatalogLoadCannotSelectOrExposeItsFiles() = runTest(dispatcher) {
        val repo = Repo()
        val owners = Owners()
        val vm = create(repo, owners)
        advanceUntilIdle()
        repo.pendingCatalog = CompletableDeferred()
        vm.select(fixture.course)
        runCurrent()
        owners.remove(fixture.course.accountId)
        runCurrent()
        repo.pendingCatalog!!.complete(Unit)
        advanceUntilIdle()
        assertNull(vm.state.value.course)
        assertTrue(vm.state.value.candidates.isEmpty())
        assertNull(vm.state.value.inspection)
    }

    @Test fun existingScopeIsSelectedAndCheckingNeverUpdatesUntilConfirmed() = runTest(dispatcher) {
        val repo = Repo()
        val vm = create(repo)
        advanceUntilIdle()
        vm.select(fixture.course)
        advanceUntilIdle()
        assertEquals(setOf(fixture.spec.id), vm.state.value.selected)
        vm.check(PassageConfig(), null)
        advanceUntilIdle()
        assertEquals(1, repo.checks)
        assertEquals(0, repo.builds)
        assertNotNull(vm.state.value.plan)
        vm.update()
        advanceUntilIdle()
        assertEquals(1, repo.builds)
        vm.toggle(fixture.spec.id)
        assertNull(vm.state.value.plan)
        vm.update()
        advanceUntilIdle()
        assertEquals(1, repo.builds)
    }

    @Test fun lateLocalCheckCannotExposeAPlanAfterAccountRemoval() = runTest(dispatcher) {
        val repo = Repo()
        val owners = Owners()
        val vm = create(repo, owners)
        advanceUntilIdle()
        vm.select(fixture.course)
        advanceUntilIdle()
        repo.pendingCheck = CompletableDeferred()
        vm.check(PassageConfig(), PassageEmbeddingConfig())
        runCurrent()
        owners.remove(fixture.course.accountId)
        runCurrent()
        repo.pendingCheck!!.complete(Unit)
        advanceUntilIdle()
        assertNull(vm.state.value.course)
        assertNull(vm.state.value.plan)
        assertEquals(0, repo.builds)
    }

    @Test fun reloadingADeletedDownloadKeepsItsSelectionRemovable() = runTest(dispatcher) {
        val repo = Repo()
        val vm = create(repo)
        advanceUntilIdle()
        vm.select(fixture.course)
        advanceUntilIdle()
        repo.downloaded = false
        vm.refresh()
        advanceUntilIdle()
        assertEquals(setOf(fixture.spec.id), vm.state.value.selected)
        vm.toggle(fixture.spec.id)
        assertTrue(vm.state.value.selected.isEmpty())
        vm.toggle(fixture.spec.id)
        assertTrue(vm.state.value.selected.isEmpty())
    }

    @Test fun aFailedRecheckCannotLeaveAnOldTransferReviewActionable() = runTest(dispatcher) {
        val repo = Repo()
        val vm = create(repo)
        advanceUntilIdle()
        vm.select(fixture.course)
        advanceUntilIdle()
        vm.check(PassageConfig(), null)
        advanceUntilIdle()
        assertNotNull(vm.state.value.plan)
        repo.failCheck = true
        vm.check(PassageConfig(), null)
        advanceUntilIdle()
        assertEquals(PassageWork.CHECK, vm.state.value.problem)
        assertNull(vm.state.value.plan)
        vm.update()
        advanceUntilIdle()
        assertEquals(0, repo.builds)
    }
}
