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
import vn.edu.uit.mobile.common.*
import vn.edu.uit.mobile.courses.*
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.model.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class OfflineDownloadsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val course = Course(1, "one", "IT004", "Databases")
    private val fileA = CourseFile("A.pdf", "https://courses.example.org/a.pdf")
    private val fileB = CourseFile("B.pdf", "https://courses.example.org/b.pdf")
    private val snapshot = PortalSnapshot(courses = listOf(course), sections = mapOf("one:1" to listOf(CourseSection(1, "Week 1", listOf(CourseModule(1, 1, "Files", "resource", "", files = listOf(fileA, fileB)))))))
    private val specs = savedDownloadSpecs(snapshot, setOf("one"))
    private class Accounts : AccountRepository {
        override val accounts = MutableStateFlow(listOf(Account("one", "https://courses.example.org", 1, "Synthetic", PortalType.MOODLE)))
        override suspend fun remove(accountId: String) {
            accounts.value = emptyList()
        }
        override suspend fun saveSessions(sessions: List<PortalSession>) = Unit
    }
    private class Storage : OfflineDownloadStorage {
        val cache = mutableMapOf<String, VerifiedOfflineFile>()
        var journal = OfflineDownloadJournal()
        override suspend fun load() = journal
        override suspend fun save(journal: OfflineDownloadJournal) {
            this.journal = journal
        }
        override suspend fun cached(course: Course, file: CourseFile) = cache[offlineDownloadId(course, file)]
        override suspend fun remove(course: Course, file: CourseFile) {
            cache.remove(offlineDownloadId(course, file))
        }
        override suspend fun usedBytes(accountIds: Set<String>) = cache.values.sumOf { it.bytes }
    }
    private inner class Portal(val storage: Storage) : FakePortalRepository() {
        var calls = 0
        var fail: String? = null
        var gate: CompletableDeferred<Unit>? = null
        init {
            this.snapshot.value = this@OfflineDownloadsViewModelTest.snapshot
        }
        override suspend fun download(course: Course, file: CourseFile): AppResult<DownloadedFile, AppError> {
            calls++
            gate?.await()
            if (file.name == fail) return AppResult.Failure(AppError.NetworkOffline)
            val id = offlineDownloadId(course, file)
            storage.cache[id] = VerifiedOfflineFile("/synthetic/$id.pdf", 123)
            return AppResult.Success(DownloadedFile("/synthetic/$id.pdf", "synthetic"))
        }
    }

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    @After fun reset() {
        Dispatchers.resetMain()
    }

    @Test fun packsDeduplicateFilesAndRetryOnlyTheFailedResource() = runTest(dispatcher) {
        val storage = Storage()
        val portal = Portal(storage).apply { fail = "B.pdf" }
        val vm = OfflineDownloadsViewModel(portal, Accounts(), storage, MutableStateFlow(true))
        runCurrent()
        vm.enqueue(specs + specs)
        runCurrent()
        assertEquals(2, vm.uiState.value.items.size)
        assertEquals(OfflineDownloadStatus.SAVED, vm.uiState.value.items.first().status)
        assertEquals(OfflineDownloadStatus.FAILED, vm.uiState.value.items.last().status)
        assertEquals(2, portal.calls)
        portal.fail = null
        vm.retry(specs.last().id)
        runCurrent()
        assertTrue(vm.uiState.value.items.all { it.status == OfflineDownloadStatus.SAVED })
        assertEquals(3, portal.calls)
        vm.enqueue(specs)
        runCurrent()
        assertEquals(3, portal.calls)
    }

    @Test fun requeuingARenamedUrlPreservesTheOriginalLocatorUntilRemoval() = runTest(dispatcher) {
        val storage = Storage()
        val portal = Portal(storage)
        val vm = OfflineDownloadsViewModel(portal, Accounts(), storage, MutableStateFlow(true))
        runCurrent()
        vm.enqueue(listOf(specs.first()))
        runCurrent()
        portal.snapshot.value = snapshot.copy(sections = mapOf("one:1" to listOf(CourseSection(1, "Week 1", listOf(CourseModule(1, 1, "Files", "resource", "", files = listOf(fileA.copy(name = "Renamed.pdf"))))))))
        runCurrent()
        val renamed = savedDownloadSpecs(portal.snapshot.value, setOf("one")).single()
        vm.enqueue(listOf(renamed))
        runCurrent()
        assertEquals(fileA.name, vm.uiState.value.items.single().spec.file.name)
        assertEquals(1, portal.calls)
        vm.remove(renamed.id)
        runCurrent()
        assertTrue(storage.cache.isEmpty())
    }

    @Test fun sharedResourcesRemainMembersOfEverySectionPack() {
        val source = snapshot.copy(sections = mapOf("one:1" to listOf(CourseSection(1, "Week 1", listOf(CourseModule(1, 1, "Files", "resource", "", files = listOf(fileA)))), CourseSection(2, "Week 2", listOf(CourseModule(2, 1, "Repeat", "resource", "", files = listOf(fileA)))))))
        val memberships = savedDownloadSpecs(source, setOf("one"))
        assertEquals(setOf("section:1", "section:2"), memberships.map { it.section }.toSet())
        assertEquals(1, memberships.map { it.id }.distinct().size)
    }

    @Test fun verifiedCacheIsAvailableWithoutWifiWhileMissingFilesWait() = runTest(dispatcher) {
        val storage = Storage().apply { cache[specs.first().id] = VerifiedOfflineFile("/synthetic/a.pdf", 20) }
        val portal = Portal(storage)
        val wifi = MutableStateFlow(false)
        val vm = OfflineDownloadsViewModel(portal, Accounts(), storage, wifi)
        runCurrent()
        vm.enqueue(specs)
        runCurrent()
        assertEquals(OfflineDownloadStatus.SAVED, vm.uiState.value.items.first().status)
        assertEquals(OfflineDownloadStatus.WAITING_WIFI, vm.uiState.value.items.last().status)
        assertEquals(0, portal.calls)
        wifi.value = true
        runCurrent()
        assertTrue(vm.uiState.value.items.all { it.status == OfflineDownloadStatus.SAVED })
    }

    @Test fun wifiLossCancelsTheActiveTransferAndResumesWhenWifiReturns() = runTest(dispatcher) {
        val storage = Storage()
        val portal = Portal(storage).apply { gate = CompletableDeferred() }
        val wifi = MutableStateFlow(true)
        val vm = OfflineDownloadsViewModel(portal, Accounts(), storage, wifi)
        runCurrent()
        vm.enqueue(listOf(specs.first()))
        runCurrent()
        assertEquals(OfflineDownloadStatus.DOWNLOADING, vm.uiState.value.items.single().status)
        wifi.value = false
        runCurrent()
        assertEquals(OfflineDownloadStatus.WAITING_WIFI, vm.uiState.value.items.single().status)
        assertTrue(storage.cache.isEmpty())
        portal.gate = null
        wifi.value = true
        runCurrent()
        assertEquals(OfflineDownloadStatus.SAVED, vm.uiState.value.items.single().status)
    }

    @Test fun cancelAndRemovalDoNotDeleteAnotherSavedFile() = runTest(dispatcher) {
        val storage = Storage()
        val portal = Portal(storage).apply { gate = CompletableDeferred() }
        val vm = OfflineDownloadsViewModel(portal, Accounts(), storage, MutableStateFlow(true))
        runCurrent()
        vm.enqueue(specs)
        runCurrent()
        vm.cancel(specs.first().id)
        runCurrent()
        portal.gate = null
        vm.cancel(specs.last().id)
        runCurrent()
        vm.retry(specs.first().id)
        runCurrent()
        vm.retry(specs.last().id)
        runCurrent()
        vm.remove(specs.first().id)
        runCurrent()
        assertFalse(storage.cache.containsKey(specs.first().id))
        assertTrue(storage.cache.containsKey(specs.last().id))
        assertEquals(specs.last().id, vm.uiState.value.items.single().id)
    }

    @Test fun accountRemovalCancelsTheQueueAndRejectsOldFileOpenEffects() = runTest(dispatcher) {
        val storage = Storage()
        val portal = Portal(storage)
        val accounts = Accounts()
        val vm = OfflineDownloadsViewModel(portal, accounts, storage, MutableStateFlow(true))
        runCurrent()
        vm.enqueue(listOf(specs.first()))
        runCurrent()
        var effect: OfflineOpenFile? = null
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.files.collect { effect = it } }
        vm.open(specs.first().id)
        runCurrent()
        assertTrue(vm.canOpen(effect!!))
        vm.dismissOpenRequests()
        assertFalse(vm.canOpen(effect!!))
        vm.open(specs.first().id)
        runCurrent()
        assertTrue(vm.canOpen(effect!!))
        accounts.remove("one")
        runCurrent()
        assertTrue(vm.uiState.value.items.isEmpty())
        assertFalse(vm.canOpen(effect!!))
    }

    @Test fun restartMarksInterruptedFilesForExplicitRetryAndVerifiesSavedCopies() = runTest(dispatcher) {
        val storage = Storage().apply {
            journal = OfflineDownloadJournal(specs.map { OfflineDownloadItem(it, OfflineDownloadStatus.DOWNLOADING) }, false)
            cache[specs.first().id] = VerifiedOfflineFile("/synthetic/a.pdf", 20)
        }
        val portal = Portal(storage)
        val vm = OfflineDownloadsViewModel(portal, Accounts(), storage, MutableStateFlow(true))
        runCurrent()
        assertFalse(vm.uiState.value.wifiOnly)
        assertEquals(OfflineDownloadStatus.SAVED, vm.uiState.value.items.first().status)
        assertEquals(OfflineDownloadStatus.FAILED, vm.uiState.value.items.last().status)
        assertEquals(0, portal.calls)
        storage.cache.clear()
        vm.refreshSaved()
        runCurrent()
        assertTrue(vm.uiState.value.items.all { it.status == OfflineDownloadStatus.FAILED })
    }
}
