package vn.edu.uit.mobile.data

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.forum.network.*

class ConnectedForumRepositoryTest {
    private class Storage : StudyStorage {
        val values = mutableMapOf<String, String>()
        var fail = false
        override fun read(key: String) = values[key]
        override fun write(key: String, value: String) {
            if (fail) error("Storage failure")
            values[key] = value
        }
        override fun remove(key: String) {
            values.remove(key)
        }
        override fun removeAccount(accountId: String) = Unit
    }
    private class Guest : ForumRepository {
        var calls = 0
        var saves = 0
        override suspend fun load(request: ForumRequest): ForumLoaded {
            calls++
            return ForumLoaded(ForumDocument(), 1)
        }
        override suspend fun local() = ForumLocalState()
        override suspend fun save(topic: ForumTopic, saved: Boolean): ForumLocalState {
            saves++
            return ForumLocalState()
        }
        override suspend fun position(position: ForumPosition) {
            saves++
        }
        override suspend fun image(url: String) = byteArrayOf()
        override suspend fun clear() = Unit
    }
    private class Server {
        var expired = false
        var id = 7
        var gate: CompletableDeferred<Unit>? = null
        val started = CompletableDeferred<Unit>()
        val http = HttpClient(
            MockEngine { r ->
                val json = headersOf(HttpHeaders.ContentType, "application/json")
                when (r.url.encodedPath) {
                    "/session/csrf.json" -> respond("""{"csrf":"csrf"}""", headers = json)
                    "/session" -> respond("", HttpStatusCode.NoContent, headersOf(HttpHeaders.SetCookie, "_t=private-cookie; Path=/; Secure"))
                    "/session/current.json" -> if (expired) respond("{}", HttpStatusCode.NotFound, json) else respond("""{"current_user":{"id":$id,"username":"member","can_create_topic":true}}""", headers = json)
                    else -> {
                        started.complete(Unit)
                        gate?.let { withContext(NonCancellable) { it.await() } }
                        respond("""{"categories":[{"id":9,"name":"Private category","read_restricted":true,"permission":1}],"anonymous_list_filters":["latest"]}""", headers = json)
                    }
                }
            }
        ) { followRedirects = false }
    }

    @Test fun privateContentAndTemporarySavesNeverEnterGuestStorage() = runTest {
        val server = Server()
        val guest = Guest()
        val storage = Storage()
        val repo = ConnectedForumRepository(guest, ForumSessionClient(server.http), storage)
        try {
            repo.login("member", "not-a-real-password")
            val doc = repo.load(ForumRequest.Categories).document
            assertEquals("Private category", doc.categories.single().name)
            assertTrue(doc.categories.single().canCreate)
            repo.save(ForumTopic(99, "Private title", 9, 1, ""), true)
            assertEquals(1, repo.local().saved.size)
            assertEquals(0, guest.calls)
            assertEquals(0, guest.saves)
            assertFalse(storage.values.values.any { "Private" in it || "not-a-real-password" in it })
            repo.disconnect()
            assertTrue(storage.values.isEmpty())
            assertTrue(repo.local().saved.isEmpty())
        } finally {
            server.http.close()
        }
    }

    @Test fun expiryDoesNotFallBackToGuestOrLeakPendingPrivatePositions() = runTest {
        val server = Server()
        val guest = Guest()
        val storage = Storage()
        val repo = ConnectedForumRepository(guest, ForumSessionClient(server.http), storage)
        try {
            repo.login("member", "not-real")
            server.expired = true
            repeat(2) {
                try {
                    repo.load(ForumRequest.Categories)
                    fail("Expired account accepted")
                } catch (e: ForumException) {
                    assertEquals(ForumFailure.AUTHENTICATION, e.reason)
                }
            }
            repo.position(ForumPosition(ForumTopic(99, "Private", 9, 1, ""), 1, 1))
            assertEquals(0, guest.saves)
            assertEquals(0, guest.calls)
            assertTrue(storage.values.isEmpty())
        } finally {
            server.http.close()
        }
    }

    @Test fun disconnectDiscardsLatePrivatePayloadAndRestoredIdentityMustMatch() = runTest {
        val server = Server()
        val guest = Guest()
        val storage = Storage()
        val repo = ConnectedForumRepository(guest, ForumSessionClient(server.http), storage)
        try {
            repo.login("member", "not-real")
            val saved = storage.values.getValue("session")
            val gate = CompletableDeferred<Unit>()
            server.gate = gate
            val work = async { repo.load(ForumRequest.Categories) }
            server.started.await()
            repo.disconnect()
            gate.complete(Unit)
            try {
                work.await()
                fail("Late payload escaped")
            } catch (_: CancellationException) { }
            storage.values["session"] = saved
            server.id = 8
            val restored = ConnectedForumRepository(guest, ForumSessionClient(server.http), storage)
            assertNull(restored.resume())
            assertTrue(storage.values.isEmpty())
        } finally {
            server.http.close()
        }
    }

    @Test fun failedInitialCredentialPersistenceDoesNotLeaveHiddenLogin() = runTest {
        val server = Server()
        val guest = Guest()
        val storage = Storage().apply { fail = true }
        val client = ForumSessionClient(server.http)
        val repo = ConnectedForumRepository(guest, client, storage)
        try {
            try {
                repo.login("member", "not-real")
                fail("Failed storage accepted")
            } catch (e: ForumException) {
                assertEquals(ForumFailure.STORAGE, e.reason)
            }
            assertEquals("[]", client.snapshot())
            assertTrue(storage.values.isEmpty())
        } finally {
            server.http.close()
        }
    }
}
