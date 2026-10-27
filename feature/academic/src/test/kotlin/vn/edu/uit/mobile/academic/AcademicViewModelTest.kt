package vn.edu.uit.mobile.academic

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import vn.edu.uit.mobile.common.*
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.model.*

@OptIn(ExperimentalCoroutinesApi::class)
class AcademicViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    @After fun cleanup() {
        Dispatchers.resetMain()
    }

    @Test fun openingSeveralTabsDoesNotDuplicateRequestsAndRefreshCanRetry() = runTest(dispatcher) {
        var reads = 0
        val pending = CompletableDeferred<Unit>()
        val repository = object : FakePortalRepository() {
            override suspend fun refreshStudentServices(): AppResult<Unit, AppError> {
                reads++
                pending.await()
                return AppResult.Failure(AppError.NetworkOffline)
            }
        }
        repository.snapshot.value = PortalSnapshot(portalLinked = true)
        val viewModel = AcademicViewModel(repository)
        runCurrent()
        viewModel.onIntent(AcademicIntent.OpenServices)
        runCurrent()
        viewModel.onIntent(AcademicIntent.OpenServices)
        viewModel.onIntent(AcademicIntent.RefreshServices)
        runCurrent()
        assertEquals(1, reads)
        assertTrue(viewModel.uiState.value.servicesLoading)
        pending.complete(Unit)
        runCurrent()
        assertFalse(viewModel.uiState.value.servicesLoading)
        assertEquals(AppError.NetworkOffline, viewModel.uiState.value.servicesError)
        viewModel.onIntent(AcademicIntent.RefreshServices)
        runCurrent()
        assertEquals(2, reads)
    }

    @Test fun disconnectedPortalCannotIssueServiceReads() = runTest(dispatcher) {
        var reads = 0
        val repository = object : FakePortalRepository() {
            override suspend fun refreshStudentServices(): AppResult<Unit, AppError> {
                reads++
                return AppResult.Success(Unit)
            }
        }
        val viewModel = AcademicViewModel(repository)
        runCurrent()
        viewModel.onIntent(AcademicIntent.OpenServices)
        runCurrent()
        assertEquals(0, reads)
        assertFalse(viewModel.uiState.value.servicesLoading)
    }
}
