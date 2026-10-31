package vn.edu.uit.mobile

import android.app.Application
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.datetime.LocalDate
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.model.*
import vn.edu.uit.mobile.schedule.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class FreeTimeAccountTest {
    private val dispatcher = StandardTestDispatcher()
    private class Accounts : AccountRepository {
        override val accounts = MutableStateFlow(listOf(Account("one", "https://courses.example.org", 1, "Synthetic", PortalType.MOODLE), Account("calendar-only", "https://other.example.org", 1, "Synthetic", PortalType.MOODLE)))
        override suspend fun remove(accountId: String) {
            accounts.value = accounts.value.filterNot { it.id == accountId }
        }
        override suspend fun saveSessions(sessions: List<PortalSession>) = Unit
    }

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    @After fun cleanup() {
        Dispatchers.resetMain()
    }

    @Test fun removingAnAccountWithoutCoursesStillClearsPlanningDrafts() = runTest(dispatcher) {
        val portal = FakePortalRepository()
        val accounts = Accounts()
        val vm = ScheduleViewModel(portal, accounts)
        runCurrent()
        vm.freeTimePlanner.open(LocalDate(2026, 10, 26))
        vm.freeTimePlanner.add("Private draft", "12:00", "13:00")
        val session = vm.freeTimePlanner.uiState.value.session
        accounts.remove("calendar-only")
        runCurrent()
        assertTrue(vm.freeTimePlanner.uiState.value.personal.isEmpty())
        assertTrue(vm.freeTimePlanner.uiState.value.session > session)
        assertNotNull(vm.freeTimePlanner.uiState.value.message)
        assertTrue(portal.snapshot.value.courses.isEmpty())
    }
}
