package vn.edu.uit.mobile.data

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.*
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.model.*

internal class AssistantNoAccounts : AccountRepository {
    override val accounts = MutableStateFlow(emptyList<Account>())
    override suspend fun remove(accountId: String) = error("Account access forbidden")
    override suspend fun saveSessions(sessions: List<PortalSession>) = error("Session access forbidden")
}
class AssistantProtocolTest {
    @Test fun commentaryIsNotConcatenatedIntoTheStructuredFinalAnswer() {
        val final = assistantAnswer("""[{"kind":"UNKNOWN","text":"Hello.","references":[]}]""").getValue("output").jsonArray.single().jsonObject
        val commentary = JsonObject(
            final + mapOf(
                "phase" to JsonPrimitive("commentary"),
                "content" to JsonArray(
                    listOf(
                        buildJsonObject {
                            put("type", "output_text")
                            put("text", "I am checking your sources.")
                        }
                    )
                )
            )
        )
        val response = assistantOutput(commentary, JsonObject(final + ("phase" to JsonPrimitive("final_answer"))))
        val result = OpenAiStudyProtocol.directPayload(response)
        assertEquals("Hello.", AssistantProtocol.facts(result.toString()).single().text)
    }

    @Test fun androidAndGatewayUseIdenticalInstructionsToolsAndFinalSchema() {
        val file = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }.map { File(it, "server/assistant_contract.json") }.first { it.isFile }
        val contract = Json.parseToJsonElement(file.readText()).jsonObject
        assertEquals(AssistantProtocol.INSTRUCTIONS, contract.getValue("instructions").jsonPrimitive.content)
        assertEquals(AssistantProtocol.tools, contract.getValue("tools"))
        assertEquals(AssistantProtocol.schema, contract.getValue("schema"))
    }

    @Test fun providerSchemaEnforcesTheSameSectionAndReferenceLimitsAsTheValidator() {
        val facts = AssistantProtocol.schema.jsonObject.getValue("properties").jsonObject.getValue("facts").jsonObject
        assertEquals(12, facts.getValue("maxItems").jsonPrimitive.int)
        assertEquals(4, facts.getValue("items").jsonObject.getValue("properties").jsonObject.getValue("references").jsonObject.getValue("maxItems").jsonPrimitive.int)
    }

    @Test fun directRequestsAreStatelessStrictAndOnlyAdvertiseReadTools() = runTest {
        val memory = AssistantMemory()
        val client = HttpClient(
            MockEngine { request ->
                assertEquals("https://api.openai.com/v1/responses", request.url.toString())
                assertNull(request.headers[HttpHeaders.Cookie])
                val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                assertFalse(body.getValue("store").jsonPrimitive.boolean)
                assertTrue(body.getValue("stream").jsonPrimitive.boolean)
                assertEquals("text/event-stream", request.headers[HttpHeaders.Accept])
                assertEquals("required", body.getValue("tool_choice").jsonPrimitive.content)
                assertFalse(body.getValue("parallel_tool_calls").jsonPrimitive.boolean)
                assertEquals(setOf("search_announcements", "read_announcement", "read_forum_topic"), body.getValue("tools").jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content }.toSet())
                assertTrue(body.getValue("text").jsonObject.getValue("format").jsonObject.getValue("strict").jsonPrimitive.boolean)
                respond(assistantSseComplete(assistantOutput(assistantCall("call", "search_announcements", "{\"query\":\"\"}"))), headers = headersOf(HttpHeaders.ContentType, "text/event-stream"))
            }
        ) { followRedirects = false }
        val study = ConnectedStudyAiRepository(memory, AssistantNoAccounts(), client)
        study.configureDirect("synthetic-api-key-not-a-real-secret", "gpt-6-luna")
        val result = study.assistantModel().step(
            AssistantProtocol.payload(
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("role", "user")
                            put("content", "Question")
                        }
                    )
                ),
                0,
                "English"
            )
        )
        assertEquals("function_call", result.getValue("output").jsonArray.single().jsonObject.getValue("type").jsonPrimitive.content)
    }

    @Test fun changedConnectionCannotReceiveAnExistingToolTranscript() = runTest {
        var requests = 0
        val study = ConnectedStudyAiRepository(
            AssistantMemory(),
            AssistantNoAccounts(),
            HttpClient(
                MockEngine {
                    requests++
                    error("No requests allowed")
                }
            )
        )
        study.configureDirect("synthetic-api-key-not-a-real-secret", "gpt-6-luna")
        val model = study.assistantModel()
        study.configure("https://gateway.example", "synthetic-gateway-token-at-least-32-characters")
        try {
            model.step(AssistantProtocol.payload(JsonArray(emptyList()), 1, "English"))
            fail("Forwarded transcript to another provider")
        } catch (_: IllegalStateException) { }
        assertEquals(0, requests)
    }

    @Test fun gatewayModeSendsTheGatewayTokenAndRetainsClientOwnedTools() = runTest {
        val token = "synthetic-gateway-token-at-least-32-characters"
        val study = ConnectedStudyAiRepository(
            AssistantMemory(),
            AssistantNoAccounts(),
            HttpClient(
                MockEngine { request ->
                    assertEquals("https://gateway.example/v1/study", request.url.toString())
                    assertEquals("Bearer $token", request.headers[HttpHeaders.Authorization])
                    val payload = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                    assertEquals("assistant", payload.getValue("mode").jsonPrimitive.content)
                    assertFalse(payload.containsKey("tools"))
                    assertFalse(payload.containsKey("instructions"))
                    respond(assistantAnswer("""[{"kind":"UNKNOWN","text":"No evidence.","references":[]}]""").toString(), headers = headersOf(HttpHeaders.ContentType, "application/json"))
                }
            )
        )
        study.configure("https://gateway.example", token)
        study.assistantModel().step(AssistantProtocol.payload(JsonArray(emptyList()), 0, "English"))
    }

    @Test fun incompleteAndRefusedOutputsAreRejected() {
        for (value in listOf(
            buildJsonObject {
                put("status", "incomplete")
                put("output", JsonArray(emptyList()))
            },
            assistantOutput(
                buildJsonObject {
                    put("type", "message")
                    put("content", JsonArray(listOf(buildJsonObject { put("type", "refusal") })))
                }
            )
        )) {
            try {
                AssistantProtocol.output(value)
                fail("Invalid output accepted")
            } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun whitespaceVariationIsAllowedButFabricatedOrWrongParagraphQuotesAreRejected() {
        val evidence = AssistantEvidence("e", "https://portal.uit.edu.vn/bai-viet/a", AnnouncementSource.PORTAL, "Title", "", 1, "a".repeat(64), listOf("The deadline is Friday.\nBring your identification."))
        val fact = AssistantFact(AssistantFactKind.FACT, "Bring identification.", listOf(AssistantReference("e", 0, "Friday. Bring your identification.")))
        AssistantRules.verify(listOf(fact), listOf(evidence))
        for (reference in listOf(AssistantReference("other", 0, "Bring your identification."), AssistantReference("e", 1, "Bring your identification."), AssistantReference("e", 0, "Bring a passport and pay a fee."))) {
            try {
                AssistantRules.verify(listOf(fact.copy(references = listOf(reference))), listOf(evidence))
                fail("Invalid quote accepted")
            } catch (_: IllegalArgumentException) { }
        }
    }
}
