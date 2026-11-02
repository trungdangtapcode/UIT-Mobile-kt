package vn.edu.uit.mobile.data

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.*
import java.io.File
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.model.*

class TutorProtocolTest {
    private val fixture = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }.map { File(it, "server/fixtures/tutor-python.json") }.first { it.isFile }.readText().let { Json.parseToJsonElement(it).jsonObject }
    private val output get() = fixture.getValue("result").jsonObject
    private class Storage : StudyStorage {
        val data = mutableMapOf<String, String>()
        override fun read(key: String) = data[key]
        override fun write(key: String, value: String) {
            data[key] = value
        }
        override fun remove(key: String) {
            data.remove(key)
        }
        override fun removeAccount(accountId: String) = error("Account writes forbidden")
    }
    private class Owners(connected: Boolean = false) : AccountRepository {
        override val accounts = MutableStateFlow(if (connected) listOf(Account("PRIVATE_OWNER_SENTINEL", "https://example.invalid", 1, "PRIVATE_PROFILE_SENTINEL", PortalType.MOODLE)) else emptyList())
        override suspend fun remove(accountId: String) = error("Account writes forbidden")
        override suspend fun saveSessions(sessions: List<PortalSession>) = error("Session writes forbidden")
    }
    private fun envelope(body: JsonObject = output, status: String = "completed") = buildJsonObject {
        put("status", status)
        put("model", "synthetic-code-tutor")
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
                                        put("text", body.toString())
                                    }
                                )
                            }
                        )
                    }
                )
            }
        )
    }.toString()
    private val key = "synthetic-api-key-not-a-real-credential"
    private fun client(block: suspend MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) -> io.ktor.client.request.HttpResponseData) = HttpClient(MockEngine(block)) { followRedirects = false }

    @Test fun standaloneRequestSendsNumberedSelectionWithoutAccountMetadataOrTools() = runTest {
        var calls = 0
        val client = client { req ->
            calls++
            assertEquals("https://api.openai.com/v1/responses", req.url.toString())
            val body = Json.parseToJsonElement((req.body as TextContent).text).jsonObject
            assertFalse(body.getValue("store").jsonPrimitive.boolean)
            assertNull(body["tools"])
            assertEquals(TutorProtocol.schema, body.getValue("text").jsonObject.getValue("format").jsonObject.getValue("schema"))
            assertEquals(fixture.getValue("request"), Json.parseToJsonElement(body.getValue("input").jsonPrimitive.content))
            listOf("PRIVATE_OWNER_SENTINEL", "PRIVATE_PROFILE_SENTINEL", key).forEach { assertFalse(body.toString().contains(it)) }
            respond(envelope(), headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        try {
            val repo = ConnectedStudyAiRepository(Storage(), Owners(), client)
            repo.configureDirect(key, "synthetic-code-tutor")
            val result = repo.tutor(null, TutorFixtures.request)
            assertEquals(3, result.hints.size)
            assertEquals(TutorFixtures.request.version, result.selectionVersion)
            assertEquals(1, calls)
        } finally {
            client.close()
        }
    }

    @Test fun gatewayUsesTheSameContractWithNoFallbackOrCodeWhitespaceLoss() = runTest {
        val client = client { req ->
            assertEquals("https://ai.example.invalid/v1/study", req.url.toString())
            assertEquals(fixture.getValue("request"), Json.parseToJsonElement((req.body as TextContent).text))
            respond(
                buildJsonObject {
                    put("version", 1)
                    put("model", "synthetic-code-tutor")
                    put("generatedAt", 1)
                    output.forEach { k, v -> put(k, v) }
                }.toString(),
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        try {
            val repo = ConnectedStudyAiRepository(Storage(), Owners(), client)
            repo.configure("https://ai.example.invalid", key)
            assertEquals(11, repo.tutor(null, TutorFixtures.request).observations.first().codeReferences.single().startLine)
        } finally {
            client.close()
        }
    }

    @Test fun wrongLineReferencesAndNumericStringsAreRejected() = runTest {
        for (string in listOf(false, true)) {
            val badRef = buildJsonObject {
                if (string) put("startLine", "11") else put("startLine", 10)
                put("endLine", if (string) 11 else 10)
                put("quote", "return values[0]")
            }
            val badPoint = buildJsonObject {
                output.getValue("observations").jsonArray.first().jsonObject.forEach { k, v -> put(k, v) }
                put("codeReferences", buildJsonArray { add(badRef) })
            }
            val changed = buildJsonObject {
                output.forEach { k, v -> put(k, v) }
                put("observations", buildJsonArray { add(badPoint) })
            }
            val client = client { respond(envelope(changed), headers = headersOf(HttpHeaders.ContentType, "application/json")) }
            try {
                val repo = ConnectedStudyAiRepository(Storage(), Owners(), client)
                repo.configureDirect(key, "synthetic-code-tutor")
                assertTrue(runCatching { repo.tutor(null, TutorFixtures.request) }.exceptionOrNull() is StudyAiException)
            } finally {
                client.close()
            }
        }
    }

    @Test fun invalidSelectionAndOrphanCourseContextFailBeforeNetwork() = runTest {
        var calls = 0
        val client = client {
            calls++
            error("No transport expected")
        }
        try {
            val repo = ConnectedStudyAiRepository(Storage(), Owners(), client)
            repo.configureDirect(key, "synthetic-code-tutor")
            assertTrue(runCatching { repo.tutor(null, TutorFixtures.request.copy(snippet = TutorSnippet(" "))) }.isFailure)
            assertTrue(runCatching { repo.tutor(null, TutorFixtures.request.copy(sources = listOf(TutorFixtures.context))) }.isFailure)
            assertEquals(0, calls)
        } finally {
            client.close()
        }
    }

    @Test fun removedOwnerRejectsLateCourseAnalysisWithoutUniversityWrites() = runTest {
        val owners = Owners(true)
        val client = client {
            owners.accounts.value = emptyList()
            respond(envelope(), headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        try {
            val repo = ConnectedStudyAiRepository(Storage(), owners, client)
            repo.configureDirect(key, "synthetic-code-tutor")
            assertTrue(runCatching { repo.tutor(Course(1, "PRIVATE_OWNER_SENTINEL", "PRIVATE_COURSE_SENTINEL", "Private"), TutorFixtures.request) }.isFailure)
        } finally {
            client.close()
        }
    }

    @Test fun refusalsIncompleteQuotaAndRedirectResponsesNeverBecomeAnalysisOrRetry() = runTest {
        for (mode in listOf("incomplete", "refusal", "quota", "redirect")) {
            var calls = 0
            val client = client {
                calls++
                when (mode) {
                    "quota" -> respond("{}", HttpStatusCode.TooManyRequests)
                    "redirect" -> respond("", HttpStatusCode.TemporaryRedirect, headersOf(HttpHeaders.Location, "https://portal.uit.edu.vn/"))
                    "refusal" -> respond("""{"status":"completed","output":[{"type":"message","content":[{"type":"refusal"}]}]}""")
                    else -> respond(envelope(status = "incomplete"))
                }
            }
            try {
                val repo = ConnectedStudyAiRepository(Storage(), Owners(), client)
                repo.configureDirect(key, "synthetic-code-tutor")
                assertTrue(runCatching { repo.tutor(null, TutorFixtures.request) }.exceptionOrNull() is StudyAiException)
                assertEquals(1, calls)
            } finally {
                client.close()
            }
        }
    }
}
