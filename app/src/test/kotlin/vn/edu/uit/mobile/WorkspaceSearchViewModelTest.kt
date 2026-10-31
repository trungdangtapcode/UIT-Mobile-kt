package vn.edu.uit.mobile

import android.app.Application
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
class WorkspaceSearchViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val course = Course(1, "one", "IT004", "Databases")
    private val file = CourseFile("SQL notes.pdf", "https://courses.example.org/pluginfile.php/1/file.pdf")
    private class Accounts : AccountRepository {
        override val accounts = MutableStateFlow(listOf(Account("one", "https://courses.example.org", 1, "Synthetic", PortalType.MOODLE)))
        override suspend fun remove(accountId: String) {
            accounts.value = emptyList()
        }
        override suspend fun saveSessions(sessions: List<PortalSession>) = Unit
    }
    private inner class Portal : FakePortalRepository() {
        var downloads = 0
        var gate: CompletableDeferred<Unit>? = null
        init {
            snapshot.value = PortalSnapshot(courses = listOf(course), sections = mapOf("one:1" to listOf(CourseSection(1, "Week 1", listOf(CourseModule(2, 1, "SQL lesson", "resource", "", files = listOf(file)))))))
        }
        override suspend fun download(course: Course, file: CourseFile): vn.edu.uit.mobile.common.AppResult<DownloadedFile, vn.edu.uit.mobile.common.AppError> {
            downloads++
            gate?.let { withContext(NonCancellable) { it.await() } }
            return vn.edu.uit.mobile.common.AppResult.Success(DownloadedFile("/synthetic/file.pdf", "synthetic"))
        }
    }

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    @After fun reset() {
        Dispatchers.resetMain()
    }

    @Test fun queriesAndOpeningExactResultsDoNotSendNetworkRequestsAndBackPreservesText() = runTest(dispatcher) {
        val portal = Portal()
        val vm = WorkspaceSearchViewModel(portal, Accounts())
        runCurrent()
        vm.query("SQL notes")
        val hit = vm.uiState.value.hits.first { it.file != null }
        vm.open(hit.key)
        assertEquals(file, vm.uiState.value.hits.first { it.key == vm.uiState.value.selected }.file)
        assertEquals(0, portal.downloads)
        vm.back()
        assertEquals("SQL notes", vm.uiState.value.query)
        assertNull(vm.uiState.value.selected)
    }

    @Test fun removalImmediatelyDropsResultsEvenBeforeTheSnapshotChanges() = runTest(dispatcher) {
        val accounts = Accounts()
        val vm = WorkspaceSearchViewModel(Portal(), accounts)
        runCurrent()
        vm.open(vm.uiState.value.hits.first().key)
        accounts.remove("one")
        runCurrent()
        assertTrue(vm.uiState.value.hits.isEmpty())
        assertNull(vm.uiState.value.selected)
    }

    @Test fun obsoleteDownloadsCannotOpenAfterLeavingOrRemovingTheirAccount() = runTest(dispatcher) {
        val portal = Portal().apply { gate = CompletableDeferred() }
        val accounts = Accounts()
        val vm = WorkspaceSearchViewModel(portal, accounts)
        runCurrent()
        val hit = vm.uiState.value.hits.first { it.file != null }
        vm.open(hit.key)
        vm.download(file)
        runCurrent()
        accounts.remove("one")
        runCurrent()
        portal.gate!!.complete(Unit)
        runCurrent()
        assertFalse(vm.uiState.value.busy)
        assertFalse(vm.canOpen(WorkspaceSearchFile("/synthetic/file.pdf", hit.key, 1)))
    }
}
