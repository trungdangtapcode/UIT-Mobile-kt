package vn.edu.uit.mobile.data

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.*
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.model.*

class PracticeProtocolTest {
    private val source = PracticeFixtures.source
    private val options = PracticeOptions(count = 2, topic = "Database transactions")
    private val course = Course(1, "PRIVATE_OWNER_SENTINEL", "PRIVATE_COURSE_SENTINEL", "PRIVATE_PROFILE_SENTINEL")
    private val token = "synthetic-api-key-not-a-real-credential"
    private val fixture = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }.map { File(it, "server/fixtures/practice-mixed.json") }.first { it.isFile }.readText().let { Json.parseToJsonElement(it).jsonObject }
    private val output get() = fixture.getValue("result").jsonObject
    private class Storage : StudyStorage {
        val values = mutableMapOf<String, String>()
        override fun read(key: String) = values[key]
        override fun write(key: String, value: String) {
            values[key] = value
        }
        override fun remove(key: String) {
            values.remove(key)
        }
        override fun removeAccount(accountId: String) = error("Account writes forbidden")
    }
    private class Owners(id: String) : AccountRepository {
        override val accounts = MutableStateFlow(listOf(Account(id, "https://example.invalid", 1, "PRIVATE_PROFILE_SENTINEL", PortalType.MOODLE)))
        override suspend fun remove(accountId: String) = error("University account mutation forbidden")
        override suspend fun saveSessions(sessions: List<PortalSession>) = error("University account mutation forbidden")
    }
    private fun envelope(data: JsonObject = output, status: String = "completed") = buildJsonObject {
        put("status", status)
        put("model", "synthetic-practice-model")
        put("created_at", 1)
        put(
            "output",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("type", "message")
                        put(
                            "content",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("type", "output_text")
                                        put("text", data.toString())
                                    }
                                )
                            }
                        )
                    }
                )
            }
        )
    }.toString()

    @Test fun directRequestUsesStrictSchemaOnlySelectedTextAndNoUniversityMetadata() = runTest {
        val count = AtomicInteger()
        val client = HttpClient(
            MockEngine { request ->
                count.incrementAndGet()
                assertEquals("https://api.openai.com/v1/responses", request.url.toString())
                val payload = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                assertEquals("synthetic-model", payload.getValue("model").jsonPrimitive.content)
                assertFalse(payload.getValue("store").jsonPrimitive.boolean)
                assertNull(payload["tools"])
                assertEquals(PracticeProtocol.schema, payload.getValue("text").jsonObject.getValue("format").jsonObject.getValue("schema"))
                val selected = Json.parseToJsonElement(payload.getValue("input").jsonPrimitive.content).jsonObject
                assertEquals("practice", selected.getValue("mode").jsonPrimitive.content)
                assertEquals(fixture.getValue("request"), selected)
                listOf("PRIVATE_OWNER_SENTINEL", "PRIVATE_COURSE_SENTINEL", "PRIVATE_PROFILE_SENTINEL", token).forEach { assertFalse(payload.toString().contains(it)) }
                respond(envelope(), headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
        ) { followRedirects = false }
        try {
            val repo = ConnectedStudyAiRepository(Storage(), Owners(course.accountId), client)
            repo.configureDirect(token, "synthetic-model")
            val result = repo.generatePractice(course, listOf(source), options)
            assertEquals(PracticeFixtures.generation.questions, result.questions)
            assertEquals(1, count.get())
        } finally {
            client.close()
        }
    }

    @Test fun gatewayUsesTheSamePracticeContractWithoutDirectKeyFallback() = runTest {
        val client = HttpClient(
            MockEngine { request ->
                assertEquals("https://ai.example.invalid/v1/study", request.url.toString())
                assertEquals(fixture.getValue("request"), Json.parseToJsonElement((request.body as TextContent).text))
                respond(
                    buildJsonObject {
                        put("version", 1)
                        put("model", "synthetic-gateway")
                        put("generatedAt", 1)
                        output.forEach { key, value -> put(key, value) }
                    }.toString(),
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }
        ) { followRedirects = false }
        try {
            val repo = ConnectedStudyAiRepository(Storage(), Owners(course.accountId), client)
            repo.configure("https://ai.example.invalid", token)
            assertEquals("synthetic-gateway", repo.generatePractice(course, listOf(source), options).model)
        } finally {
            client.close()
        }
    }

    @Test fun completedOutputWithBadEvidenceAnswerOrJsonTypesIsRejected() = runTest {
        for (field in listOf("answer", "correctIndex", "citations")) {
            val questions = output.getValue("questions").jsonArray.toMutableList()
            questions[0] = buildJsonObject {
                questions[0].jsonObject.forEach { key, value -> put(key, value) }
                when (field) {
                    "answer" -> put("answer", "Unsupported answer")
                    "correctIndex" -> put("correctIndex", "0")
                    else -> put(
                        "citations",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("sourceId", "outside")
                                    put("quote", "Unknown outside evidence")
                                }
                            )
                        }
                    )
                }
            }
            val changed = buildJsonObject {
                output.forEach { key, value -> put(key, value) }
                put("questions", JsonArray(questions))
            }
            val client = HttpClient(MockEngine { respond(envelope(changed), headers = headersOf(HttpHeaders.ContentType, "application/json")) }) { followRedirects = false }
            try {
                val repo = ConnectedStudyAiRepository(Storage(), Owners(course.accountId), client)
                repo.configureDirect(token, "synthetic-model")
                assertTrue(runCatching { repo.generatePractice(course, listOf(source), options) }.exceptionOrNull() is StudyAiException)
            } finally {
                client.close()
            }
        }
    }

    @Test fun refusalsAndIncompleteAnswersCannotBecomePracticeSets() = runTest {
        for (body in listOf(envelope(status = "incomplete"), """{"status":"completed","model":"synthetic-model","created_at":1,"output":[{"type":"message","content":[{"type":"refusal","refusal":"No"}]}]}""")) {
            val client = HttpClient(MockEngine { respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json")) }) { followRedirects = false }
            try {
                val repo = ConnectedStudyAiRepository(Storage(), Owners(course.accountId), client)
                repo.configureDirect(token, "synthetic-model")
                assertTrue(runCatching { repo.generatePractice(course, listOf(source), options) }.exceptionOrNull() is StudyAiException)
            } finally {
                client.close()
            }
        }
    }

    @Test fun quotaAndRedirectFailuresDoNotRetryOrFollowUniversityUrls() = runTest {
        for (status in listOf(HttpStatusCode.TooManyRequests, HttpStatusCode.Found)) {
            val calls = AtomicInteger()
            val client = HttpClient(
                MockEngine { request ->
                    calls.incrementAndGet()
                    assertEquals("api.openai.com", request.url.host)
                    respond("PRIVATE_PROVIDER_SENTINEL", status, headersOf(HttpHeaders.Location, "https://portal.uit.edu.vn/blocked"))
                }
            ) { followRedirects = false }
            try {
                val repo = ConnectedStudyAiRepository(Storage(), Owners(course.accountId), client)
                repo.configureDirect(token, "synthetic-model")
                val error = runCatching { repo.generatePractice(course, listOf(source), options) }.exceptionOrNull()
                assertNotNull(error)
                assertFalse(error!!.message.orEmpty().contains("PRIVATE_PROVIDER_SENTINEL"))
                assertEquals(1, calls.get())
            } finally {
                client.close()
            }
        }
    }

    @Test fun ownerRemovalDuringGenerationRejectsLateOutputWithoutAccountWrites() = runTest {
        val owners = Owners(course.accountId)
        val client = HttpClient(
            MockEngine {
                owners.accounts.value = emptyList()
                respond(envelope(), headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
        ) { followRedirects = false }
        try {
            val repo = ConnectedStudyAiRepository(Storage(), owners, client)
            repo.configureDirect(token, "synthetic-model")
            assertTrue(runCatching { repo.generatePractice(course, listOf(source), options) }.isFailure)
        } finally {
            client.close()
        }
    }
}
