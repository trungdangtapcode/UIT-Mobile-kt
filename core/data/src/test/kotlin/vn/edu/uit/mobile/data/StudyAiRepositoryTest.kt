package vn.edu.uit.mobile.data

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.model.*

@OptIn(ExperimentalCoroutinesApi::class)
class StudyAiRepositoryTest {
    private val course = Course(1, "test-account", "IT004", "Database")
    private val source = StudySource("module:1", "Selected lesson", "A transaction preserves consistency.")
    private val token = "synthetic-gateway-token-not-a-real-credential"
    private val response = """{"version":1,"model":"synthetic-model","generatedAt":1,"items":[{"kind":"answer","text":"It preserves consistency.","citations":[{"sourceId":"module:1","quote":"A transaction preserves consistency."}]}]}"""
    private class Storage : StudyStorage {
        val data = mutableMapOf<String, String>()
        override fun read(key: String) = data[key]
        override fun write(key: String, value: String) {
            data[key] = value
        }
        override fun remove(key: String) {
            data.remove(key)
        }
        override fun removeAccount(accountId: String) {
            data.keys.filter { it != "connection" }.forEach(data::remove)
        }
    }
    private inner class Accounts : AccountRepository {
        override val accounts = MutableStateFlow(listOf(Account(course.accountId, "https://courses.uit.edu.vn", 1, "Synthetic student", PortalType.MOODLE)))
        override suspend fun remove(accountId: String) {
            accounts.value = emptyList()
        }
        override suspend fun saveSessions(sessions: List<PortalSession>) = Unit
    }

    @Test fun selectedTextUsesAnIndependentOriginWithoutAnyUniversitySession() = runTest {
        val storage = Storage()
        val accounts = Accounts()
        var calls = 0
        val client = HttpClient(
            MockEngine { request ->
                calls++
                assertEquals("study.example.org", request.url.host)
                assertEquals("/v1/study", request.url.encodedPath)
                assertEquals("Bearer $token", request.headers[HttpHeaders.Authorization])
                assertNull(request.headers[HttpHeaders.Cookie])
                val payload = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                assertEquals(setOf("version", "mode", "question", "language", "sources"), payload.keys)
                assertEquals(source.text, payload.getValue("sources").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content)
                respond(response, headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
        )
        val repo = GatewayStudyAiRepository(storage, accounts, client)
        repo.configure("https://study.example.org", token)
        val result = repo.generate(course, "ask", listOf(source), "What does it preserve?", "English")
        assertEquals("It preserves consistency.", result.items.single().text)
        assertEquals(1, calls)
        assertEquals(setOf("connection"), storage.data.keys)
    }

    @Test fun accountRemovalDuringGenerationPreventsDisplayingTheAnswer() = runTest {
        val accounts = Accounts()
        val client = HttpClient(
            MockEngine {
                accounts.remove(course.accountId)
                respond(response)
            }
        )
        val repo = GatewayStudyAiRepository(Storage(), accounts, client)
        repo.configure("https://study.example.org", token)
        val result = runCatching { repo.generate(course, "ask", listOf(source), "Question", "English") }
        assertTrue(result.isFailure)
    }

    @Test fun unsupportedResponseAndForeignEvidenceAreRejected() = runTest {
        for (raw in listOf(response.replace("module:1", "foreign-source"), response.replace("version\":1", "version\":2"), "<html>Sign in</html>", "a".repeat(65537))) {
            val repo = GatewayStudyAiRepository(Storage(), Accounts(), HttpClient(MockEngine { respond(raw) }))
            repo.configure("https://study.example.org", token)
            assertTrue(runCatching { repo.generate(course, "ask", listOf(source), "Question", "English") }.isFailure)
        }
    }

    @Test fun savedTasksRemainSeparateFromGatewayConnectionAndCourseAccounts() = runTest {
        val accounts = Accounts()
        val repo = GatewayStudyAiRepository(Storage(), accounts, HttpClient(MockEngine { error("No network expected") }))
        val tasks = listOf(StudyTask("Prepare the report", true))
        repo.configure("https://study.example.org", token)
        repo.saveTasks(course, 2, tasks)
        repo.disconnect()
        assertEquals(tasks, repo.tasks(course, 2))
        assertEquals(emptyList<StudyTask>(), repo.tasks(course.copy(id = 3), 2))
        assertFalse(repo.connection().configured)
        accounts.remove(course.accountId)
        assertTrue(runCatching { repo.tasks(course, 2) }.isFailure)
    }

    @Test fun rateLimitsAndRedirectsHaveExplicitErrorsAndDoNotBecomeAnswers() = runTest {
        for (status in listOf(HttpStatusCode.TooManyRequests, HttpStatusCode.Found, HttpStatusCode.Unauthorized)) {
            val repo = GatewayStudyAiRepository(Storage(), Accounts(), HttpClient(MockEngine { respond("", status) }))
            repo.configure("https://study.example.org", token)
            assertTrue(runCatching { repo.generate(course, "ask", listOf(source), "Question", "English") }.isFailure)
        }
    }
}
