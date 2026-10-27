package vn.edu.uit.mobile.authui

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import vn.edu.uit.mobile.common.*
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.model.*

@OptIn(ExperimentalCoroutinesApi::class)
class StudentProfileViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val first = Account("one", "https://portal.uit.edu.vn", 1, "First Student", PortalType.UIT_PORTAL)
    private val second = first.copy(id = "two", userId = 2, displayName = "Second Student")
    private fun profile(id: String) = StudentProfileDetails(
        id,
        "TEST-$id",
        "Student $id",
        "",
        "",
        "",
        "",
        listOf(
            ProfileSection(
                "images",
                "Documents",
                listOf(
                    ProfileGroup("Transcript", emptyList(), ProfileDocument("transcript", "Transcript", true)),
                    ProfileGroup("ID front", emptyList(), ProfileDocument("id-front", "ID front", true))
                )
            )
        )
    )
    private class Accounts(initial: List<Account>) : AccountRepository {
        override val accounts = MutableStateFlow(initial)
        override suspend fun remove(accountId: String) {
            accounts.value = accounts.value.filterNot { it.id == accountId }
        }
        override suspend fun saveSessions(sessions: List<PortalSession>) {
            accounts.value = sessions.map { it.account }
        }
    }

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    @After fun cleanup() {
        Dispatchers.resetMain()
    }

    @Test fun closedScanCannotReplaceTheNextDocumentWithALateResponse() = runTest(dispatcher) {
        val waiting = CompletableDeferred<Unit>()
        val repository = object : FakePortalRepository() {
            override suspend fun studentProfile(accountId: String): AppResult<StudentProfileDetails, AppError> = AppResult.Success(profile(accountId))
            override suspend fun studentProfileScan(accountId: String, documentId: String): AppResult<ProfileScan, AppError> {
                if (documentId == "transcript") withContext(NonCancellable) { waiting.await() }
                return AppResult.Success(ProfileScan(documentId, documentId, documentId.toByteArray()))
            }
        }
        val model = StudentProfileViewModel(repository, Accounts(listOf(first)))
        runCurrent()
        model.onIntent(StudentProfileIntent.Open("one"))
        runCurrent()
        model.onIntent(StudentProfileIntent.OpenScan("transcript"))
        runCurrent()
        assertTrue(model.uiState.value.scan!!.loading)
        model.onIntent(StudentProfileIntent.CloseScan)
        assertNull(model.uiState.value.scan)
        model.onIntent(StudentProfileIntent.OpenScan("id-front"))
        runCurrent()
        assertEquals("id-front", model.uiState.value.scan?.document?.id)
        waiting.complete(Unit)
        runCurrent()
        assertArrayEquals("id-front".toByteArray(), model.uiState.value.scan?.bytes)
        model.onIntent(StudentProfileIntent.Close)
        assertNull(model.uiState.value.scan)
    }

    @Test fun scanErrorsCanRetryAndAccountChangesClearTheLoadedImage() = runTest(dispatcher) {
        var offline = true
        var scanRequests = 0
        val accounts = Accounts(listOf(first, second))
        val repository = object : FakePortalRepository() {
            override suspend fun studentProfile(accountId: String): AppResult<StudentProfileDetails, AppError> = AppResult.Success(profile(accountId))
            override suspend fun studentProfileScan(accountId: String, documentId: String): AppResult<ProfileScan, AppError> {
                scanRequests++
                return if (offline) AppResult.Failure(AppError.NetworkOffline) else AppResult.Success(ProfileScan(documentId, documentId, byteArrayOf(1, 2)))
            }
        }
        val model = StudentProfileViewModel(repository, accounts)
        runCurrent()
        model.onIntent(StudentProfileIntent.Open("one"))
        runCurrent()
        model.onIntent(StudentProfileIntent.OpenScan("unknown"))
        runCurrent()
        assertEquals(0, scanRequests)
        model.onIntent(StudentProfileIntent.OpenScan("transcript"))
        runCurrent()
        assertEquals(AppError.NetworkOffline, model.uiState.value.scan?.error)
        offline = false
        model.onIntent(StudentProfileIntent.RetryScan)
        runCurrent()
        assertNotNull(model.uiState.value.scan?.bytes)
        model.onIntent(StudentProfileIntent.Open("two"))
        runCurrent()
        assertNull(model.uiState.value.scan)
        model.onIntent(StudentProfileIntent.OpenScan("transcript"))
        runCurrent()
        accounts.remove("two")
        runCurrent()
        assertNull(model.uiState.value.scan)
        assertNull(model.uiState.value.details)
        model.onIntent(StudentProfileIntent.Close)
    }

    @Test fun switchingAccountsNeverDisplaysAnEarlierResponse() = runTest(dispatcher) {
        val slow = CompletableDeferred<Unit>()
        val repository = object : FakePortalRepository() {
            override suspend fun studentProfile(accountId: String): AppResult<StudentProfileDetails, AppError> {
                if (accountId == "one") withContext(NonCancellable) { slow.await() }
                return AppResult.Success(profile(accountId))
            }
        }
        val model = StudentProfileViewModel(repository, Accounts(listOf(first, second)))
        runCurrent()
        model.onIntent(StudentProfileIntent.Open("one"))
        runCurrent()
        model.onIntent(StudentProfileIntent.Open("two"))
        runCurrent()
        assertEquals("two", model.uiState.value.details?.accountId)
        slow.complete(Unit)
        runCurrent()
        assertEquals("two", model.uiState.value.details?.accountId)
        model.onIntent(StudentProfileIntent.Close)
    }

    @Test fun closeAndRemovalClearPersonalDetailsFromScreenState() = runTest(dispatcher) {
        val accounts = Accounts(listOf(first))
        val repository = object : FakePortalRepository() {
            override suspend fun studentProfile(accountId: String): AppResult<StudentProfileDetails, AppError> = AppResult.Success(profile(accountId))
        }
        val model = StudentProfileViewModel(repository, accounts)
        runCurrent()
        model.onIntent(StudentProfileIntent.Open("one"))
        runCurrent()
        assertNotNull(model.uiState.value.details)
        accounts.remove("one")
        runCurrent()
        assertNull(model.uiState.value.details)
        assertNull(model.uiState.value.account)
        model.onIntent(StudentProfileIntent.Close)
        assertEquals(StudentProfileState(), model.uiState.value)
    }

    @Test fun reopeningTheSameAccountRejectsAResponseFromTheClosedScreen() = runTest(dispatcher) {
        val slow = CompletableDeferred<Unit>()
        var requests = 0
        val repository = object : FakePortalRepository() {
            override suspend fun studentProfile(accountId: String): AppResult<StudentProfileDetails, AppError> {
                val firstRequest = ++requests == 1
                if (firstRequest) withContext(NonCancellable) { slow.await() }
                return AppResult.Success(profile(accountId).copy(fullName = if (firstRequest) "Old profile" else "Current profile"))
            }
        }
        val model = StudentProfileViewModel(repository, Accounts(listOf(first)))
        runCurrent()
        model.onIntent(StudentProfileIntent.Open("one"))
        runCurrent()
        model.onIntent(StudentProfileIntent.Close)
        model.onIntent(StudentProfileIntent.Open("one"))
        runCurrent()
        assertEquals("Current profile", model.uiState.value.details?.fullName)
        slow.complete(Unit)
        runCurrent()
        assertEquals("Current profile", model.uiState.value.details?.fullName)
        model.onIntent(StudentProfileIntent.Close)
    }

    @Test fun failedRefreshKeepsOnlyTheSameAccountProfileInMemory() = runTest(dispatcher) {
        var offline = false
        val repository = object : FakePortalRepository() {
            override suspend fun studentProfile(accountId: String): AppResult<StudentProfileDetails, AppError> = if (offline) AppResult.Failure(AppError.NetworkOffline) else AppResult.Success(profile(accountId))
        }
        val model = StudentProfileViewModel(repository, Accounts(listOf(first)))
        runCurrent()
        model.onIntent(StudentProfileIntent.Open("one"))
        runCurrent()
        offline = true
        model.onIntent(StudentProfileIntent.Refresh)
        runCurrent()
        assertEquals("one", model.uiState.value.details?.accountId)
        assertEquals(AppError.NetworkOffline, model.uiState.value.error)
        assertFalse(model.uiState.value.loading)
        model.onIntent(StudentProfileIntent.Close)
    }
}
