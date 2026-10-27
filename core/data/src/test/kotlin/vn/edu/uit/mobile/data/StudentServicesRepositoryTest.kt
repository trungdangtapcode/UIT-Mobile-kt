package vn.edu.uit.mobile.data

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import vn.edu.uit.mobile.common.*
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.model.*
import vn.edu.uit.mobile.network.*

class StudentServicesRepositoryTest {
    @get:Rule val directory = TemporaryFolder()
    private val portal = PortalSession(Account("portal-test", "https://portal.uit.edu.vn", 1, "Test Student", PortalType.UIT_PORTAL), "ums_portal_session=synthetic")
    private class Sessions(initial: List<PortalSession>) : SessionStore {
        override val sessions = MutableStateFlow(initial)
        override suspend fun save(sessions: List<PortalSession>) {
            this.sessions.value = sessions
        }
        override suspend fun remove(accountId: String) {
            sessions.value = sessions.value.filterNot { it.account.id == accountId }
        }
    }
    private class Store : SnapshotStore {
        val values = mutableMapOf<String, MutableStateFlow<PortalSnapshot>>()
        private fun entry(id: String) = values.getOrPut(id) { MutableStateFlow(PortalSnapshot()) }
        override fun observe(accountId: String): Flow<PortalSnapshot> = entry(accountId)
        override suspend fun read(accountId: String) = entry(accountId).value
        override suspend fun write(accountId: String, snapshot: PortalSnapshot) {
            entry(accountId).value = snapshot
        }
        override suspend fun delete(accountId: String) {
            entry(accountId).value = PortalSnapshot()
        }
    }

    @Test fun offlineServiceRefreshRetainsEachCachedSectionAndItsTimestamp() = runTest {
        val sessions = Sessions(listOf(portal))
        val store = Store()
        val cached = StudentServicesSnapshot(tuition = TuitionSummary(emptyList(), 100), procedures = listOf(ProcedureSection(ProcedureKind.TRANSCRIPTS, emptyList(), fetchedAt = 101)))
        store.write(portal.account.id, PortalSnapshot(studentServices = cached))
        val http = HttpClient(MockEngine { throw java.io.IOException("offline") })
        val repository = OfflinePortalRepository(sessions, store, directory.root, AppDispatchers(StandardTestDispatcher(testScheduler)), { SafePortalTransport(it, http) })
        assertTrue(repository.refreshStudentServices() is AppResult.Failure)
        val result = requireNotNull(store.read(portal.account.id).studentServices)
        assertEquals(cached.tuition, result.tuition)
        assertEquals(cached.procedures, result.procedures)
        assertEquals(9, result.issues.size)
        assertTrue(result.issues.all { it.failure == ServiceFailureKind.NETWORK })
        assertTrue(repository.snapshot.first().portalLinked)
        assertEquals(result, repository.snapshot.first().studentServices)
    }

    @Test fun oneBadServiceDoesNotPreventOtherReadsAndOnlyTuitionUsesPost() = runTest {
        val store = Store()
        val cached = ProcedureSection(ProcedureKind.TRANSCRIPTS, listOf(ProcedureRecord("test", "Official transcript", "Processing", "2026-10-01", "")), fetchedAt = 50)
        store.write(portal.account.id, PortalSnapshot(studentServices = StudentServicesSnapshot(procedures = listOf(cached))))
        val requests = mutableListOf<Pair<HttpMethod, String>>()
        val http = HttpClient(
            MockEngine { request ->
                val path = request.url.encodedPath
                requests += request.method to path
                val body = when {
                    path == "/api/sv/tuition" -> """{"records":[]}"""
                    path.endsWith("giay-xac-nhan") -> """{"history":{"xnsv":[],"vvnh":[],"ggt":[],"xntt":[],"drl":[],"udgd":[],"gkhk":[]}}"""
                    path.endsWith("xin-bang-diem") -> """{"unexpected":true}"""
                    path.endsWith("hoan-thi") -> """{"history":[],"reexamHistory":[]}"""
                    path.endsWith("xac-nhan-chung-chi") -> """{"certs":[]}"""
                    path.endsWith("tot-nghiep") -> """{"data":{"conditions":[],"debts":[]}}"""
                    else -> """{"history":[]}"""
                }
                respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
        )
        val repository = OfflinePortalRepository(Sessions(listOf(portal)), store, directory.root, AppDispatchers(StandardTestDispatcher(testScheduler)), { SafePortalTransport(it, http) })
        assertTrue(repository.refreshStudentServices() is AppResult.Failure)
        val result = requireNotNull(store.read(portal.account.id).studentServices)
        assertEquals(cached, result.procedures.single { it.kind == ProcedureKind.TRANSCRIPTS })
        assertNotNull(result.tuition)
        assertNotNull(result.graduation)
        assertEquals(ServiceFailureKind.INVALID_RESPONSE, result.issues.single().failure)
        assertEquals(9, requests.size)
        assertEquals(listOf("/api/sv/tuition"), requests.filter { it.first == HttpMethod.Post }.map { it.second })
    }

    @Test fun expiredSessionStopsFurtherStudentServiceRequests() = runTest {
        var requests = 0
        val http = HttpClient(
            MockEngine {
                requests++
                respond("", HttpStatusCode.Unauthorized)
            }
        )
        val store = Store()
        val repository = OfflinePortalRepository(Sessions(listOf(portal)), store, directory.root, AppDispatchers(StandardTestDispatcher(testScheduler)), { SafePortalTransport(it, http) })
        val result = repository.refreshStudentServices() as AppResult.Failure
        assertEquals(AppError.SessionExpired, result.error)
        assertEquals(1, requests)
        assertEquals(ServiceFailureKind.SIGN_IN, store.read(portal.account.id).studentServices?.issues?.single()?.failure)
    }

    @Test fun serviceCacheDisappearsWhenPortalAccountIsRemovedWhileMoodleRemains() = runTest {
        val moodle = portal.copy(account = portal.account.copy(id = "moodle", baseUrl = "https://courses.uit.edu.vn", portal = PortalType.MOODLE))
        val sessions = Sessions(listOf(moodle, portal))
        val store = Store()
        store.write(portal.account.id, PortalSnapshot(studentServices = StudentServicesSnapshot(tuition = TuitionSummary(emptyList(), 123))))
        val repository = OfflinePortalRepository(sessions, store, directory.root)
        assertTrue(repository.snapshot.first().portalLinked)
        VaultAccountRepository(sessions, store).remove(portal.account.id)
        assertFalse(repository.snapshot.first().portalLinked)
        assertNull(repository.snapshot.first().studentServices)
        assertNull(store.read(portal.account.id).studentServices)
    }
}
