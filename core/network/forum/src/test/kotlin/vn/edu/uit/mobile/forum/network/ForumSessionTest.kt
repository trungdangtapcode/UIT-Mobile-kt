package vn.edu.uit.mobile.forum.network

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import vn.edu.uit.mobile.domain.*

class ForumSessionTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    @Test fun uit204LoginUsesOwnCookiesCsrfAndExplicitIdentityVerification() = runTest {
        val routes = mutableListOf<String>()
        val http = HttpClient(
            MockEngine { req ->
                routes += req.url.encodedPath
                assertEquals("forum.uit.edu.vn", req.url.host)
                assertEquals("XMLHttpRequest", req.headers["X-Requested-With"])
                assertNull(req.headers["Discourse-Present"])
                assertNull(req.headers["Authorization"])
                when (req.url.encodedPath) {
                    "/session/csrf.json" -> respond("""{"csrf":"csrf-value"}""", headers = headersOf(HttpHeaders.ContentType to listOf("application/json"), HttpHeaders.SetCookie to listOf("_forum_session=forum-only; Path=/; Secure; HttpOnly")))
                    "/session" -> {
                        assertEquals(HttpMethod.Post, req.method)
                        assertEquals("csrf-value", req.headers["X-CSRF-Token"])
                        assertEquals("_forum_session=forum-only", req.headers[HttpHeaders.Cookie])
                        val body = (req.body as OutgoingContent.ByteArrayContent).bytes().toString(Charsets.UTF_8)
                        assertTrue(body.contains("login=student") && body.contains("password=not-real"))
                        respond("", HttpStatusCode.NoContent, headersOf(HttpHeaders.SetCookie, "_t=forum-token; Path=/; Secure; HttpOnly"))
                    }
                    else -> {
                        assertTrue(req.headers[HttpHeaders.Cookie].orEmpty().contains("_t=forum-token"))
                        respond("""{"current_user":{"id":7,"username":"student","name":"Test","can_create_topic":true}}""", headers = json)
                    }
                }
            }
        ) { followRedirects = false }
        val client = ForumSessionClient(http)
        try {
            assertEquals(7L, client.login("student", "not-real").id)
            assertEquals(listOf("/session/csrf.json", "/session", "/session/current.json"), routes)
            assertFalse(client.snapshot().contains("not-real"))
            client.clear()
            assertEquals("[]", client.snapshot())
        } finally {
            http.close()
        }
    }

    @Test fun malformedRestoreCannotLeaveHalfRestoredCredentials() {
        val http = HttpClient(MockEngine { respond("{}", headers = json) })
        try {
            val client = ForumSessionClient(http)
            assertThrows(IllegalArgumentException::class.java) { client.restore("""[{"name":"_t","value":"allowed","expires":0},{"name":"unrelated","value":"rejected","expires":0}]""") }
            assertEquals("[]", client.snapshot())
        } finally {
            http.close()
        }
    }

    @Test fun cookieBoundaryRejectsParentDomainInsecureAndUnrelatedCookies() = runTest {
        val http = HttpClient(
            MockEngine {
                respond(
                    "{}",
                    headers = headersOf(
                        HttpHeaders.ContentType to listOf("application/json"),
                        HttpHeaders.SetCookie to listOf(
                            "_t=parent; Domain=.uit.edu.vn; Path=/; Secure",
                            "_forum_session=insecure; Path=/",
                            "MoodleSession=unrelated; Path=/; Secure",
                            "_t=wrong-path; Path=/other; Secure"
                        )
                    )
                )
            }
        )
        val client = ForumSessionClient(http)
        client.get(ForumRequest.Categories)
        assertEquals("[]", client.snapshot())
        assertThrows(IllegalArgumentException::class.java) { client.restore("""[{"name":"MoodleSession","value":"wrong","expires":0}]""") }
        http.close()
    }

    @Test fun redirectedRequestsNeverForwardCredentialsAndWritesAreNeverRetried() = runTest {
        var calls = 0
        val http = HttpClient(
            MockEngine { req ->
                calls++
                if (req.url.encodedPath == "/session/csrf.json") {
                    respond("""{"csrf":"ok"}""", headers = json)
                } else {
                    respond("", HttpStatusCode.TemporaryRedirect, headersOf(HttpHeaders.Location, "https://portal.uit.edu.vn/steal"))
                }
            }
        ) { followRedirects = false }
        val client = ForumSessionClient(http)
        try {
            client.write(HttpMethod.Post, "/posts.json", listOf("raw" to "test"))
            fail("Redirect accepted")
        } catch (e: ForumException) {
            assertEquals(ForumFailure.AUTHENTICATION, e.reason)
        }
        assertEquals(2, calls)
        http.close()
    }

    @Test fun ambiguousWritesConflictAndExpiredSessionsAreDistinguishable() = runTest {
        for ((status, expected) in listOf(503 to ForumFailure.UNCERTAIN, 409 to ForumFailure.CONFLICT, 403 to ForumFailure.FORBIDDEN, 429 to ForumFailure.RATE_LIMIT, 422 to ForumFailure.REJECTED)) {
            var writes = 0
            val http = HttpClient(
                MockEngine { req ->
                    if (req.method == HttpMethod.Get) {
                        respond("""{"csrf":"ok"}""", headers = json)
                    } else {
                        writes++
                        respond("""{"errors":["Rejected"]}""", HttpStatusCode.fromValue(status), json)
                    }
                }
            )
            try {
                ForumSessionClient(http).write(HttpMethod.Post, "/posts.json")
                fail("Failure accepted")
            } catch (e: ForumException) {
                assertEquals(expected, e.reason)
            }
            assertEquals(1, writes)
            http.close()
        }
    }

    @Test fun lateCookieResponseCannotRestoreDisconnectedSession() = runTest {
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val http = HttpClient(
            MockEngine {
                started.complete(Unit)
                withContext(NonCancellable) { gate.await() }
                respond("{}", headers = headersOf(HttpHeaders.ContentType to listOf("application/json"), HttpHeaders.SetCookie to listOf("_t=late; Path=/; Secure")))
            }
        )
        val client = ForumSessionClient(http)
        val work = async { client.get(ForumRequest.Categories) }
        started.await()
        client.clear()
        gate.complete(Unit)
        try {
            work.await()
            fail("Stale response accepted")
        } catch (_: CancellationException) { }
        assertEquals("[]", client.snapshot())
        http.close()
    }

    @Test fun editIncludesOriginalTextAndDoesNotOverwriteConflictingRevision() = runTest {
        val http = HttpClient(
            MockEngine { req ->
                if (req.method == HttpMethod.Get) {
                    respond("""{"csrf":"ok"}""", headers = json)
                } else {
                    assertEquals(HttpMethod.Put, req.method)
                    assertEquals("/posts/8.json", req.url.encodedPath)
                    val form = parseQueryString((req.body as OutgoingContent.ByteArrayContent).bytes().toString(Charsets.UTF_8))
                    assertEquals("original", form["post[original_text]"])
                    assertEquals("changed", form["post[raw]"])
                    respond("{}", HttpStatusCode.Conflict, json)
                }
            }
        )
        try {
            ForumMemberApi(ForumSessionClient(http)).send(ForumUser(7, "student", "Test"), ForumCommand.Edit(ForumEditable(8, 9, 1, "original", 2), "changed", "reason"))
            fail("Conflict ignored")
        } catch (e: ForumException) {
            assertEquals(ForumFailure.CONFLICT, e.reason)
        }
        http.close()
    }

    @Test fun forbiddenIdentityProbeInvalidatesAuthentication() = runTest {
        val http = HttpClient(MockEngine { respond("{}", HttpStatusCode.Forbidden, json) })
        try {
            ForumSessionClient(http).current()
            fail("Unverifiable identity accepted")
        } catch (e: ForumException) {
            assertEquals(ForumFailure.AUTHENTICATION, e.reason)
        } finally {
            http.close()
        }
    }
}
