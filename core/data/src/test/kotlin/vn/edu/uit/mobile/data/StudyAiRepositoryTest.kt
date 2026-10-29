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
    private fun directResponse(status: String = "completed", result: String = response): String = buildJsonObject {
        put("status", status)
        put("model", "synthetic-openai-model")
        put("created_at", 1.5)
        put(
            "output",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("type", "reasoning")
                        put("summary", buildJsonArray {})
                    }
                )
                add(
                    buildJsonObject {
                        put("type", "message")
                        put(
                            "content",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("type", "output_text")
                                        put("text", result)
                                    }
                                )
                            }
                        )
                    }
                )
            }
        )
    }.toString()
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

    @Test fun freshConnectionDefaultsToDirectWhileExistingGatewaySettingsArePreserved() = runTest {
        val storage = Storage()
        val repo = ConnectedStudyAiRepository(storage, Accounts(), HttpClient(MockEngine { error("No network expected") }))
        assertEquals(StudyConnectionMode.DIRECT, repo.connection().mode)
        assertFalse(repo.connection().configured)
        storage.write(
            "connection",
            buildJsonObject {
                put("endpoint", "https://study.example.org")
                put("token", token)
            }.toString()
        )
        assertEquals(StudyConnectionMode.GATEWAY, repo.connection().mode)
        assertTrue(repo.connection().configured)
    }

    @Test fun directModeCallsOnlyTheOfficialOpenAIOriginWithStructuredOutputAndNoGateway() = runTest {
        val key = "synthetic-personal-openai-key-for-tests"
        var calls = 0
        val repo = ConnectedStudyAiRepository(
            Storage(),
            Accounts(),
            HttpClient(
                MockEngine { request ->
                    calls++
                    assertEquals("https://api.openai.com/v1/responses", request.url.toString())
                    assertEquals("Bearer $key", request.headers[HttpHeaders.Authorization])
                    assertNull(request.headers[HttpHeaders.Cookie])
                    val payload = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                    assertFalse(payload.getValue("store").jsonPrimitive.boolean)
                    assertEquals("chosen-test-model", payload.getValue("model").jsonPrimitive.content)
                    assertEquals("json_schema", payload.getValue("text").jsonObject.getValue("format").jsonObject.getValue("type").jsonPrimitive.content)
                    val selected = Json.parseToJsonElement(payload.getValue("input").jsonPrimitive.content).jsonObject
                    assertEquals(setOf("version", "mode", "question", "language", "sources"), selected.keys)
                    assertFalse(payload.toString().contains(key))
                    assertFalse(payload.containsKey("tools"))
                    respond(directResponse())
                }
            )
        )
        repo.configureDirect(key, "chosen-test-model")
        assertEquals(StudyConnectionMode.DIRECT, repo.connection().mode)
        assertEquals("chosen-test-model", repo.connection().model)
        assertEquals("It preserves consistency.", repo.generate(course, "ask", listOf(source), "Question", "English").items.single().text)
        assertEquals(1, calls)
    }

    @Test fun switchingConnectionModesNeverReusesCredentialsOrFallsBackToTheOtherService() = runTest {
        val targets = mutableListOf<String>()
        val credentials = mutableListOf<String?>()
        val repo = ConnectedStudyAiRepository(
            Storage(),
            Accounts(),
            HttpClient(
                MockEngine { request ->
                    targets += request.url.host
                    credentials += request.headers[HttpHeaders.Authorization]
                    if (request.url.host == "api.openai.com") respond("", HttpStatusCode.Unauthorized) else respond(response)
                }
            )
        )
        repo.configure("https://study.example.org", token)
        repo.configureDirect("synthetic-personal-openai-key-for-tests", "test-model")
        assertTrue(runCatching { repo.generate(course, "ask", listOf(source), "Question", "English") }.isFailure)
        assertEquals(listOf("api.openai.com"), targets)
        repo.configure("https://study.example.org", token)
        repo.generate(course, "ask", listOf(source), "Question", "English")
        assertEquals(listOf("api.openai.com", "study.example.org"), targets)
        assertEquals("Bearer $token", credentials.last())
        assertFalse(repo.connection().model.isNotBlank())
    }

    @Test fun directModeRejectsIncompleteRefusedAndForeignEvidenceResponses() = runTest {
        val refused = """{"status":"completed","output":[{"type":"message","content":[{"type":"refusal","refusal":"Synthetic refusal"}]}]}"""
        for (raw in listOf(directResponse("incomplete"), refused, directResponse(result = response.replace("module:1", "foreign")))) {
            val repo = ConnectedStudyAiRepository(Storage(), Accounts(), HttpClient(MockEngine { respond(raw) }))
            repo.configureDirect("synthetic-personal-openai-key-for-tests", "test-model")
            assertTrue(runCatching { repo.generate(course, "ask", listOf(source), "Question", "English") }.isFailure)
        }
    }

    @Test fun configuringEitherModeDoesNotSendANetworkRequestAndInvalidModelDoesNotReplaceSettings() = runTest {
        val repo = ConnectedStudyAiRepository(Storage(), Accounts(), HttpClient(MockEngine { error("Configuration must stay local") }))
        repo.configureDirect("synthetic-personal-openai-key-for-tests", "test-model")
        assertTrue(runCatching { repo.configureDirect("synthetic-personal-openai-key-for-tests", "bad/model") }.isFailure)
        assertEquals(StudyConnectionMode.DIRECT, repo.connection().mode)
        assertEquals("test-model", repo.connection().model)
        assertTrue(runCatching { repo.configure("https://study.example.org", "sk-synthetic-personal-key-must-not-be-a-gateway-token") }.isFailure)
        assertEquals(StudyConnectionMode.DIRECT, repo.connection().mode)
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
        val repo = ConnectedStudyAiRepository(storage, accounts, client)
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
        val repo = ConnectedStudyAiRepository(Storage(), accounts, client)
        repo.configure("https://study.example.org", token)
        val result = runCatching { repo.generate(course, "ask", listOf(source), "Question", "English") }
        assertTrue(result.isFailure)
    }

    @Test fun unsupportedResponseAndForeignEvidenceAreRejected() = runTest {
        for (raw in listOf(response.replace("module:1", "foreign-source"), response.replace("version\":1", "version\":2"), "<html>Sign in</html>", "a".repeat(65537))) {
            val repo = ConnectedStudyAiRepository(Storage(), Accounts(), HttpClient(MockEngine { respond(raw) }))
            repo.configure("https://study.example.org", token)
            assertTrue(runCatching { repo.generate(course, "ask", listOf(source), "Question", "English") }.isFailure)
        }
    }

    @Test fun savedTasksRemainSeparateFromGatewayConnectionAndCourseAccounts() = runTest {
        val accounts = Accounts()
        val repo = ConnectedStudyAiRepository(Storage(), accounts, HttpClient(MockEngine { error("No network expected") }))
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
            val repo = ConnectedStudyAiRepository(Storage(), Accounts(), HttpClient(MockEngine { respond("", status) }))
            repo.configure("https://study.example.org", token)
            assertTrue(runCatching { repo.generate(course, "ask", listOf(source), "Question", "English") }.isFailure)
        }
    }
}
