package vn.edu.uit.mobile.data

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import vn.edu.uit.mobile.common.*
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.model.*
import vn.edu.uit.mobile.network.*

@OptIn(ExperimentalCoroutinesApi::class)
class StudentProfileRepositoryTest {
    @get:Rule val directory = TemporaryFolder()
    private val first = PortalSession(Account("first", "https://portal.uit.edu.vn", 1, "First Student", PortalType.UIT_PORTAL), "ums_portal_session=first")
    private val second = first.copy(account = first.account.copy(id = "second", userId = 2), cookie = "ums_portal_session=second")
    private val moodle = second.copy(account = second.account.copy(id = "moodle", baseUrl = "https://courses.uit.edu.vn", portal = PortalType.MOODLE))
    private class Sessions(initial: List<PortalSession>) : SessionStore {
        override val sessions = MutableStateFlow(initial)
        override suspend fun save(sessions: List<PortalSession>) {
            this.sessions.value = sessions
        }
        override suspend fun remove(accountId: String) {
            sessions.value = sessions.value.filterNot { it.account.id == accountId }
        }
    }
    private class NoStorage : SnapshotStore {
        override fun observe(accountId: String): Flow<PortalSnapshot> = error("Detailed profiles must not use disk snapshots")
        override suspend fun read(accountId: String): PortalSnapshot = error("Detailed profiles must not use disk snapshots")
        override suspend fun write(accountId: String, snapshot: PortalSnapshot): Unit = error("Detailed profiles must not use disk snapshots")
        override suspend fun delete(accountId: String): Unit = error("Detailed profiles must not use disk snapshots")
    }
    private fun html(): String {
        val profile = """{"studentCode":"TEST-002","fullName":"Second Student","academic":{},"visibleTabs":["personal","insurance"]}"""
        val frame = JsonArray(listOf(JsonPrimitive(1), JsonPrimitive("1:[\"$\",\"div\",null,{\"profile\":$profile}]\n")))
        return "<html><script>self.__next_f.push($frame)</script></html>"
    }
    private fun repository(sessions: Sessions, dispatcher: AppDispatchers, factory: (PortalSession) -> PortalTransport) = OfflinePortalRepository(sessions, NoStorage(), directory.root, dispatcher, factory)

    @Test fun exactAccountUsesOnlyItsPortalCookieAndThreeReadRequestsWithoutStorage() = runTest {
        val requests = mutableListOf<Pair<HttpMethod, String>>()
        val usedAccounts = mutableListOf<String>()
        val http = HttpClient(
            MockEngine { request ->
                requests += request.method to request.url.encodedPath
                assertEquals("ums_portal_session=second", request.headers[HttpHeaders.Cookie])
                when (request.url.encodedPath) {
                    "/sinh-vien/ho-so" -> respond(html(), headers = headersOf(HttpHeaders.ContentType, "text/html"))
                    "/api/sinh-vien/bao-hiem" -> respond("""{"profile":{},"cards":{},"history":[]}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
                    "/api/sinh-vien/anh-the" -> respond(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0), headers = headersOf(HttpHeaders.ContentType, "image/jpeg"))
                    else -> error("Unapproved profile request")
                }
            }
        )
        val repository = repository(Sessions(listOf(first, second)), AppDispatchers(StandardTestDispatcher(testScheduler))) { session ->
            usedAccounts += session.account.id
            SafePortalTransport(session, http)
        }
        val result = repository.studentProfile("second") as AppResult.Success
        assertEquals("second", result.data.accountId)
        assertEquals("TEST-002", result.data.studentCode)
        assertNotNull(result.data.portrait)
        assertEquals(listOf("second"), usedAccounts)
        assertEquals(listOf("/sinh-vien/ho-so", "/api/sinh-vien/bao-hiem", "/api/sinh-vien/anh-the"), requests.map { it.second })
        assertTrue(requests.all { it.first == HttpMethod.Get })
        assertTrue(directory.root.listFiles().orEmpty().isEmpty())
    }

    @Test fun currentMoodleUsesItsLinkedPortalWhileOptionalFailuresKeepMainProfile() = runTest {
        val usedAccounts = mutableListOf<String>()
        val http = HttpClient(
            MockEngine { request ->
                if (request.url.encodedPath == "/sinh-vien/ho-so") respond(html(), headers = headersOf(HttpHeaders.ContentType, "text/html")) else respond("", HttpStatusCode.Unauthorized)
            }
        )
        val repository = repository(Sessions(listOf(first, second, moodle)), AppDispatchers(StandardTestDispatcher(testScheduler))) { session ->
            usedAccounts += session.account.id
            SafePortalTransport(session, http)
        }
        val result = repository.studentProfile("moodle") as AppResult.Success
        assertEquals("moodle", result.data.accountId)
        assertEquals(listOf("second"), usedAccounts)
        assertEquals(ServiceFailureKind.SIGN_IN, result.data.sections.single { it.id == "insurance" }.failure)
        assertNull(result.data.portrait)
    }

    @Test fun unknownUnlinkedAndLegacyAccountsNeverFallBackToAnUnrelatedPortal() = runTest {
        val legacy = moodle.copy(account = moodle.account.copy(id = "legacy", baseUrl = "https://courses.uit.edu.vn/undergraduate"))
        val unlinked = moodle.copy(account = moodle.account.copy(id = "unlinked", userId = 3))
        val repository = repository(Sessions(listOf(first, second, legacy, unlinked)), AppDispatchers(StandardTestDispatcher(testScheduler))) { error("No network request should be made") }
        assertTrue(repository.studentProfile("missing") is AppResult.Failure)
        assertTrue(repository.studentProfile("legacy") is AppResult.Failure)
        assertTrue(repository.studentProfile("unlinked") is AppResult.Failure)
    }

    @Test fun removingLinkedPortalDuringAReadDiscardsTheResultEvenWhenMoodleRemains() = runTest {
        val sessions = Sessions(listOf(second, moodle))
        val waiting = CompletableDeferred<Unit>()
        val http = HttpClient(
            MockEngine { request ->
                if (request.url.encodedPath == "/sinh-vien/ho-so") {
                    waiting.await()
                    respond(html(), headers = headersOf(HttpHeaders.ContentType, "text/html"))
                } else {
                    respond("", HttpStatusCode.NotFound)
                }
            }
        )
        val repository = repository(sessions, AppDispatchers(StandardTestDispatcher(testScheduler))) { SafePortalTransport(it, http) }
        val response = async { repository.studentProfile("moodle") }
        runCurrent()
        sessions.remove("second")
        waiting.complete(Unit)
        assertEquals(AppError.SessionExpired, (response.await() as AppResult.Failure).error)
    }
}
