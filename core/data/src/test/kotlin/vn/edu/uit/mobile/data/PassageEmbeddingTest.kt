package vn.edu.uit.mobile.data

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.model.*

class PassageEmbeddingTest {
    private val course = Course(1, "owner", "IT004", "Database")
    private val config = PassageEmbeddingConfig(dimensions = 128)
    private class Memory : StudyStorage {
        val data = mutableMapOf<String, String>()
        override fun read(key: String) = data[key]
        override fun write(key: String, value: String) {
            data[key] = value
        }
        override fun remove(key: String) {
            data.remove(key)
        }
        override fun removeAccount(accountId: String) = Unit
    }
    private inner class Owners : AccountRepository {
        override val accounts = MutableStateFlow(listOf(Account("owner", "https://courses.example.org", 1, "Synthetic", PortalType.MOODLE)))
        override suspend fun remove(accountId: String) {
            accounts.value = emptyList()
        }
        override suspend fun saveSessions(sessions: List<PortalSession>) = Unit
    }
    private fun response(indices: List<Int>, dimensions: Int = 128) = buildJsonObject {
        put("model", config.model)
        put("usage", buildJsonObject { put("total_tokens", 4) })
        put(
            "data",
            JsonArray(
                indices.map { index ->
                    buildJsonObject {
                        put("index", index)
                        put("embedding", JsonArray(List(dimensions) { JsonPrimitive(if (it == index) 1f else 0f) }))
                    }
                }
            )
        )
    }.toString()

    @Test fun fixedEndpointAndExplicitPayloadReorderReturnedIndicesWithoutLeakingAccountData() = runTest {
        val client = HttpClient(
            MockEngine { request ->
                assertEquals("https://api.openai.com/v1/embeddings", request.url.toString())
                assertNull(request.headers["Cookie"])
                val json = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                assertEquals(setOf("model", "input", "dimensions", "encoding_format"), json.keys)
                assertEquals(config.model, json.getValue("model").jsonPrimitive.content)
                assertEquals(listOf("Transaction isolation", "Compiler pipeline"), json.getValue("input").jsonArray.map { it.jsonPrimitive.content })
                respond(response(listOf(1, 0)), headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
        )
        val repo = ConnectedStudyAiRepository(Memory(), Owners(), client)
        repo.configureDirect("synthetic-personal-key-for-tests-only", "gpt-6-luna")
        val result = repo.embed(course, listOf("Transaction isolation", "Compiler pipeline"), config)
        assertEquals(1f, result.vectors[0][0])
        assertEquals(1f, result.vectors[1][1])
        assertEquals(4L, result.tokens)
    }

    @Test fun malformedDimensionsAndDuplicateIndicesAreRejected() = runTest {
        for (body in listOf(response(listOf(0, 0)), response(listOf(0, 1), 127))) {
            val client = HttpClient(MockEngine { respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json")) })
            val repo = ConnectedStudyAiRepository(Memory(), Owners(), client)
            repo.configureDirect("synthetic-personal-key-for-tests-only", "gpt-6-luna")
            assertTrue(runCatching { repo.embed(course, listOf("One", "Two"), config) }.isFailure)
        }
    }

    @Test fun gatewayAndRemovedAccountsCannotSendEmbeddingRequests() = runTest {
        var calls = 0
        val client = HttpClient(
            MockEngine {
                calls++
                error("No request permitted")
            }
        )
        val owners = Owners()
        val repo = ConnectedStudyAiRepository(Memory(), owners, client)
        repo.configure("https://gateway.example.org", "synthetic-gateway-token-for-testing-only")
        assertTrue(runCatching { repo.embed(course, listOf("Text"), config) }.isFailure)
        repo.configureDirect("synthetic-personal-key-for-tests-only", "gpt-6-luna")
        owners.remove("owner")
        assertTrue(runCatching { repo.embed(course, listOf("Text"), config) }.isFailure)
        assertEquals(0, calls)
    }

    @Test fun rejectedAccessDoesNotEchoProviderBodyAndConnectionChangesInvalidateResults() = runTest {
        val memory = Memory()
        var reject = true
        val client = HttpClient(
            MockEngine {
                if (reject) {
                    respond("private-provider-debug-text", HttpStatusCode.Unauthorized)
                } else {
                    memory.remove("connection")
                    respond(response(listOf(0)), headers = headersOf(HttpHeaders.ContentType, "application/json"))
                }
            }
        )
        val repo = ConnectedStudyAiRepository(memory, Owners(), client)
        repo.configureDirect("synthetic-personal-key-for-tests-only", "gpt-6-luna")
        val error = runCatching { repo.embed(course, listOf("Text"), config) }.exceptionOrNull()
        assertNotNull(error)
        assertFalse(error!!.message.orEmpty().contains("private-provider-debug-text"))
        reject = false
        assertTrue(runCatching { repo.embed(course, listOf("Text"), config) }.isFailure)
    }
}
