package vn.edu.uit.mobile.data

import app.cash.turbine.test
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import vn.edu.uit.mobile.common.*
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.model.*
import vn.edu.uit.mobile.network.*

class OfflinePortalRepositoryTest {
    @get:Rule val folder = TemporaryFolder()
    private val session = PortalSession(Account("one", "https://courses.uit.edu.vn", 1, "Student", PortalType.MOODLE), "MoodleSession=fake", "key")
    private class Sessions(initial: List<PortalSession>) : SessionStore {
        override val sessions = MutableStateFlow(initial)
        override suspend fun save(sessions: List<PortalSession>) {
            this.sessions.value = sessions
        }
        override suspend fun remove(accountId: String) {
            sessions.value = sessions.value.filterNot { it.account.id == accountId }
        }
    }
    private class Store(initial: PortalSnapshot) : SnapshotStore {
        val value = MutableStateFlow(initial)
        override fun observe(accountId: String): Flow<PortalSnapshot> = value
        override suspend fun read(accountId: String): PortalSnapshot = value.value
        override suspend fun write(accountId: String, snapshot: PortalSnapshot) {
            value.value = snapshot
        }
        override suspend fun delete(accountId: String) {
            value.value = PortalSnapshot()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun cacheIsAvailableWithoutWaitingForNetworkAndOfflineSyncRetainsIt() = runTest {
        val course = Course(42, "one", "IT004.O21", "Cached course")
        val cached = PortalSnapshot(courses = listOf(course), updatedAt = 123)
        val store = Store(cached)
        val http = HttpClient(MockEngine { throw java.io.IOException("offline") })
        val repository = OfflinePortalRepository(Sessions(listOf(session)), store, folder.root, AppDispatchers(StandardTestDispatcher(testScheduler)), { SafePortalTransport(it, http) })
        repository.snapshot.test {
            assertEquals(cached, awaitItem())
            val sync = repository.refresh()
            assertTrue(sync is AppResult.Failure)
            assertEquals(AppError.NetworkOffline, repository.syncState.first().error)
            assertEquals(cached, store.read("one"))
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        http.close()
    }

    @Test fun removingAccountDropsCachedData() = runTest {
        val sessions = Sessions(listOf(session))
        val store = Store(PortalSnapshot(courses = listOf(Course(42, "one", "A", "Cached"))))
        val repository = VaultAccountRepository(sessions, store)
        repository.remove("one")
        assertTrue(repository.accounts.first().isEmpty())
        assertTrue(store.read("one").courses.isEmpty())
    }
}
