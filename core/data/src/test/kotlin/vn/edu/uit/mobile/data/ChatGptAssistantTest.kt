package vn.edu.uit.mobile.data

import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import vn.edu.uit.mobile.daa.AnnouncementRemote
import vn.edu.uit.mobile.domain.*

/** Controlled OAuth and Responses endpoints; no subscriber inference or university writes. */
class ChatGptAssistantTest {
    private fun notices() = LocalAnnouncementRepository(
        object : AnnouncementRemote {
            override suspend fun list(source: AnnouncementSource, page: Int) = AnnouncementPage(emptyList(), false)
            override suspend fun article(item: OfficialAnnouncement) = error("No article should be read for this greeting")
            override suspend fun image(url: String): ByteArray = error("No media read")
        },
        AssistantMemory()
    )

    @Test fun actualGreetingWithPlanRequestShapeAndExplicitApiCredential() = runBlocking {
        assumeTrue(System.getenv("UIT_CHATGPT_RESPONSE_LIVE") == "1")
        val key = requireNotNull(System.getenv("OPENAI_API_KEY"))
        val model = System.getenv("OPENAI_MODEL")?.takeIf { it.isNotBlank() } ?: "gpt-6-luna"
        var calls = 0
        var previews = 0
        val requestShapes = mutableListOf<JsonObject>()
        studyAiHttpClient().use { client ->
            client.plugin(HttpSend).intercept { request ->
                check(request.url.toString() == "https://api.openai.com/v1/responses")
                val ordinary = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                requestShapes += ordinary
                request.setBody(TextContent(ChatGptPlanProtocol.request(ordinary).toString(), ContentType.Application.Json))
                request.headers.remove(HttpHeaders.ContentLength)
                calls++
                execute(request)
            }
            val study = ConnectedStudyAiRepository(AssistantMemory(), AssistantNoAccounts(), client)
            study.configureDirect(key, model)
            val repo = LocalAssistantRepository(AssistantMemory(), notices(), study::assistantModel)
            val id = repo.create("Controlled greeting check", AssistantScope())
            repo.ask(id, "hello", "Vietnamese") { if (it.draft.isNotBlank()) previews++ }
            val firstTurnCalls = calls
            repo.ask(id, "hello again", "Vietnamese") { if (it.draft.isNotBlank()) previews++ }
            val turns = repo.library.value.workspaces.single().turns
            turns.forEach { turn ->
                assertEquals(AssistantTurnStatus.COMPLETE, turn.status)
                AssistantRules.verify(turn.facts, turn.evidence)
            }
            val turn = turns.last()
            assertEquals(2, turns.size)
            assertTrue(calls >= 4)
            assertTrue(requestShapes[firstTurnCalls].getValue("input").jsonArray.any { it.jsonObject["role"]?.jsonPrimitive?.content == "assistant" && it.jsonObject["phase"]?.jsonPrimitive?.content == "final_answer" })
            val report = buildJsonObject {
                put("runtime", "Production Kotlin repository, OkHttp, SSE and tool loop on Linux JVM; not Android")
                put("credentialMode", "Explicit owner-provided API key; not a subscriber token")
                put("requestAdapter", "Production ChatGptPlanProtocol in test-only HttpSend interceptor")
                put("model", turn.model)
                put("question", "hello")
                put("followUp", "hello again")
                put("completedTurns", turns.size)
                put("historicalFinalPhaseReplayed", true)
                put("sourceMode", "Controlled empty announcement cache; no university network or account operations")
                put("providerRequests", calls)
                put("visiblePreviews", previews)
                put("status", turn.status.name)
                put("factKinds", JsonArray(turn.facts.map { JsonPrimitive(it.kind.name) }))
                put("actualSubscriberInference", false)
                put("physicalAndroidAcceptance", false)
            }
            val output = File("build/outputs/chatgpt-response-live").apply { mkdirs() }
            File(output, "greeting.json").writeText(report.toString())
        }
    }

    @Test fun greetingCompletesThroughNativeChatGptToolLoopAndValidatedHistory() = runBlocking {
        ChatGptTestSupport().use { f ->
            val study = ConnectedStudyAiRepository(f.storage, AssistantNoAccounts(), f.client)
            f.connect(study.chatGpt)
            study.configureChatGpt("fixture-model")
            val requests = mutableListOf<JsonObject>()
            f.beforeResponse = {
                val request = requireNotNull(f.responseRequest)
                requests += request
                val next = if (requests.size == 1) {
                    assistantOutput(
                        buildJsonObject {
                            put("type", "reasoning")
                            put("id", "rs")
                            put("encrypted_content", "fixture-opaque-content")
                            put("summary", JsonArray(emptyList()))
                        },
                        buildJsonObject {
                            put("type", "message")
                            put("role", "assistant")
                            put("phase", "commentary")
                            put(
                                "content",
                                buildJsonArray {
                                    add(
                                        buildJsonObject {
                                            put("type", "output_text")
                                            put("text", "Checking selected notices.")
                                        }
                                    )
                                }
                            )
                        },
                        JsonObject(assistantCall("search", "search_announcements", "{\"query\":\"\"}") + ("namespace" to JsonPrimitive("uit")))
                    )
                } else {
                    val answer = assistantAnswer("""[{"kind":"UNKNOWN","text":"Hello. Ask about your selected notices.","references":[]}]""").getValue("output").jsonArray.single().jsonObject
                    assistantOutput(JsonObject(answer + ("phase" to JsonPrimitive("final_answer"))))
                }
                f.response = assistantCompactSse(next)
            }
            val repo = LocalAssistantRepository(AssistantMemory(), notices(), study::assistantModel)
            val id = repo.create("Greeting regression", AssistantScope())
            repo.ask(id, "hello", "English")
            val turn = repo.library.value.workspaces.single().turns.single()
            assertEquals(AssistantTurnStatus.COMPLETE, turn.status)
            assertEquals("Hello. Ask about your selected notices.", turn.facts.single().text)
            AssistantRules.verify(turn.facts, turn.evidence)
            assertEquals(2, requests.size)
            assertEquals("namespace", requests.first().getValue("tools").jsonArray.single().jsonObject.getValue("type").jsonPrimitive.content)
            assertTrue(requests.last().getValue("input").jsonArray.any { it.jsonObject["type"]?.jsonPrimitive?.content == "function_call_output" })
            assertTrue(requests.last().getValue("input").jsonArray.any { it.jsonObject["encrypted_content"]?.jsonPrimitive?.content == "fixture-opaque-content" })
            assertTrue(requests.last().getValue("input").jsonArray.any { it.jsonObject["phase"]?.jsonPrimitive?.content == "commentary" })
            assertEquals("Bearer fixture-initial-access-token", f.responseBearer)
        }
    }

    @Test fun planQuotaFailureInsideHttp200IsNotMisreportedAsCitationFailureOrRetried() = runBlocking {
        ChatGptTestSupport().use { f ->
            val study = ConnectedStudyAiRepository(f.storage, AssistantNoAccounts(), f.client)
            f.connect(study.chatGpt)
            study.configureChatGpt("fixture-model")
            f.response = "data: {\"type\":\"response.failed\",\"response\":{\"error\":{\"code\":\"subscription_sharing_usage_limit_exceeded\",\"message\":\"fixture-private-message\"}}}\n\n"
            val repo = LocalAssistantRepository(AssistantMemory(), notices(), study::assistantModel)
            val id = repo.create("Quota regression", AssistantScope())
            val error = runCatching { repo.ask(id, "hello", "English") }.exceptionOrNull() as ChatGptException
            assertEquals(ChatGptProblem.QUOTA, error.problem)
            assertEquals("response.failed", error.diagnostic!!.phase)
            assertEquals("responses", error.diagnostic!!.operation)
            assertEquals("fixture-response-request", error.diagnostic!!.requestId)
            assertEquals(1, f.requests.count { it.endsWith("/responses") })
            assertEquals(AssistantTurnStatus.FAILED, repo.library.value.workspaces.single().turns.single().status)
            assertFalse(error.diagnostic!!.report().contains("fixture-private-message"))
            assertEquals(ChatGptProblem.QUOTA, study.chatGpt.state.value.problem)
            f.beforeResponse = { assertNull(study.chatGpt.state.value.problem) }
            f.response = assistantSseComplete(assistantOutput(assistantCall("explicit-retry", "search_announcements", "{\"query\":\"\"}")))
            study.assistantModel().step(AssistantProtocol.payload(JsonArray(emptyList()), 0, "English"))
            assertNull(study.chatGpt.state.value.diagnostic)
            assertEquals(2, f.requests.count { it.endsWith("/responses") })
        }
    }

    @Test fun rejectedRequestRetainsSafeParameterAndDoesNotBecomeANetworkError() = runBlocking {
        ChatGptTestSupport().use { f ->
            val study = ConnectedStudyAiRepository(f.storage, AssistantNoAccounts(), f.client)
            f.connect(study.chatGpt)
            study.configureChatGpt("fixture-model")
            f.responseStatus = 400
            f.response = """{"error":{"code":"invalid_request_error","param":"tools[0].name","message":"fixture-private-message"}}"""
            val error = runCatching { study.assistantModel().step(AssistantProtocol.payload(JsonArray(emptyList()), 0, "English")) }.exceptionOrNull() as ChatGptException
            assertEquals(ChatGptProblem.UNSUPPORTED, error.problem)
            assertEquals("http_error", error.diagnostic!!.phase)
            assertEquals("tools[0].name", error.diagnostic!!.parameter)
            assertEquals(400, error.diagnostic!!.status)
            assertFalse(error.diagnostic!!.report().contains("fixture-private-message"))
            assertEquals(1, f.requests.count { it.endsWith("/responses") })
        }
    }
}
