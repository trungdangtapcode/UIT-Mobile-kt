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

class KnowledgeProtocolTest {
    private class Storage : StudyStorage {
        val data = mutableMapOf<String, String>()
        override fun read(key: String) = data[key]
        override fun write(key: String, value: String) {
            data[key] = value
        }
        override fun remove(key: String) {
            data.remove(key)
        }
        override fun removeAccount(accountId: String) = error("No account writes")
    }
    private val owners = object : AccountRepository {
        override val accounts = MutableStateFlow(listOf(Account("owner", "https://example.invalid", 1, "PRIVATE_NAME_SENTINEL", PortalType.MOODLE)))
        override suspend fun remove(accountId: String) = error("No university writes")
        override suspend fun saveSessions(sessions: List<PortalSession>) = error("No university writes")
    }
    private val body get() = buildJsonObject {
        KnowledgeJson.graph(KnowledgeFixtures.graph).forEach { k, v -> put(k, v) }
        put("status", "ready")
        put("message", "")
    }
    private fun envelope(body: JsonObject, direct: Boolean) = buildJsonObject {
        put("model", "synthetic-map-model")
        if (direct) {
            put("status", "completed")
            put("created_at", 1793580000)
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
        } else {
            put("version", 1)
            put("generatedAt", 1793580000)
            body.forEach { k, v -> put(k, v) }
        }
    }

    @Test fun directAndGatewayUseOneBoundedMapContractAndOnlySelectedText() = runTest {
        for (direct in listOf(true, false)) {
            var calls = 0
            val client = HttpClient(
                MockEngine { request ->
                    calls++
                    val raw = (request.body as TextContent).text
                    assertFalse(raw.contains("PRIVATE_NAME_SENTINEL"))
                    assertFalse(raw.contains("example.invalid"))
                    assertEquals(if (direct) "https://api.openai.com/v1/responses" else "https://ai.example.invalid/v1/study", request.url.toString())
                    val parsed = Json.parseToJsonElement(raw).jsonObject
                    val payload = if (direct) {
                        assertFalse(parsed.getValue("store").jsonPrimitive.boolean)
                        assertNull(parsed["tools"])
                        assertEquals(KnowledgeProtocol.schema, parsed.getValue("text").jsonObject.getValue("format").jsonObject.getValue("schema"))
                        Json.parseToJsonElement(parsed.getValue("input").jsonPrimitive.content).jsonObject
                    } else {
                        parsed
                    }
                    assertEquals("knowledge", payload.getValue("mode").jsonPrimitive.content)
                    assertEquals(KnowledgeFixtures.graph.focus, payload.getValue("question").jsonPrimitive.content)
                    respond(envelope(body, direct).toString(), headers = headersOf(HttpHeaders.ContentType, "application/json"))
                }
            )
            try {
                val repo = ConnectedStudyAiRepository(Storage(), owners, client)
                if (direct) repo.configureDirect("synthetic-personal-api-key-not-real", "synthetic-map-model") else repo.configure("https://ai.example.invalid", "synthetic-gateway-token-not-real-12345")
                assertEquals(KnowledgeFixtures.graph, repo.knowledge(KnowledgeFixtures.course, KnowledgeFixtures.sources, KnowledgeFixtures.graph.focus, "English").graph)
                assertEquals(1, calls)
            } finally {
                client.close()
            }
        }
    }

    @Test fun capturedLiveProviderOutputsAlsoPassTheAndroidValidatorWithoutNetwork() {
        val file = generateSequence(java.io.File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }.map { java.io.File(it, "docs/evaluations/knowledge-maps/gpt-6-luna-synthetic.json") }.first { it.isFile }
        val cases = Json.parseToJsonElement(file.readText()).jsonObject.getValue("cases").jsonArray
        assertEquals(3, cases.size)
        cases.forEach { element ->
            val value = element.jsonObject
            assertTrue(value.getValue("validated").jsonPrimitive.boolean)
            val request = value.getValue("request").jsonObject
            val sources = request.getValue("sources").jsonArray.map { source -> source.jsonObject.let { StudySource(PracticeJson.text(it, "id"), PracticeJson.text(it, "title"), PracticeJson.text(it, "text")) } }
            val focus = PracticeJson.text(request, "question")
            val language = PracticeJson.text(request, "language")
            val result = KnowledgeProtocol.result(envelope(value.getValue("result").jsonObject, true), true, focus, language)
            KnowledgeRules.generation(result, sources, focus, language)
        }
    }

    @Test fun unknownQuotesAndMalformedScalarTypesAreRejected() {
        val good = envelope(body, true)
        val bad = buildJsonObject {
            body.forEach { k, v -> put(k, v) }
            put("title", 12)
        }
        assertTrue(runCatching { KnowledgeProtocol.result(envelope(bad, true), true, KnowledgeFixtures.graph.focus, "English") }.isFailure)
        val result = KnowledgeProtocol.result(good, true, KnowledgeFixtures.graph.focus, "English")
        val invalid = result.copy(graph = result.graph.copy(edges = result.graph.edges.map { it.copy(citations = listOf(StudyCitation("invented", "Invented quote"))) }))
        assertTrue(runCatching { KnowledgeRules.generation(invalid, KnowledgeFixtures.sources, KnowledgeFixtures.graph.focus, "English") }.isFailure)
        val incomplete = buildJsonObject {
            good.forEach { k, v -> put(k, v) }
            put("status", "incomplete")
        }
        assertTrue(runCatching { KnowledgeProtocol.result(incomplete, true, KnowledgeFixtures.graph.focus, "English") }.isFailure)
    }
}
