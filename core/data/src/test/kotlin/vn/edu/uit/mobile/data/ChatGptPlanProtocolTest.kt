package vn.edu.uit.mobile.data

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.model.*

class ChatGptPlanProtocolTest {
    @Test fun anExpiredNonrenewableGrantOffersSignInWithoutSendingInference() = runBlocking {
        ChatGptTestSupport().use { f ->
            val repo = ConnectedStudyAiRepository(f.storage, Accounts(), f.client)
            f.connect(repo.chatGpt)
            repo.configureChatGpt("fixture-model")
            val records = Json.parseToJsonElement(f.storage.read("chatgpt_accounts")!!).jsonArray
            f.storage.write("chatgpt_accounts", JsonArray(records.map { JsonObject(it.jsonObject + mapOf("expires_at" to JsonPrimitive(1), "refresh_token" to JsonPrimitive(""))) }).toString())
            val error = runCatching { repo.generate(Course(1, "fixture-account", "IT004", "Fixture"), "ask", listOf(StudySource("module:1", "Source", "A transaction preserves consistency.")), "Question", "English") }.exceptionOrNull() as ChatGptException
            assertEquals(ChatGptProblem.REAUTH, error.problem)
            repo.chatGpt.initialize()
            assertEquals(ChatGptProblem.REAUTH, repo.chatGpt.state.value.problem)
            assertEquals(0, f.requests.count { it.endsWith("/v1/responses") })
        }
    }

    @Test fun adaptsOrdinaryStructuredRequestsWithoutChangingEvidenceSchemas() {
        val ordinary = OpenAiStudyProtocol.request("fixture-model", buildJsonObject { put("language", "English") })
        val plan = ChatGptPlanProtocol.request(ordinary)
        assertTrue(plan.getValue("stream").jsonPrimitive.boolean)
        assertFalse(plan.getValue("store").jsonPrimitive.boolean)
        assertFalse(plan.containsKey("max_output_tokens"))
        assertEquals(ordinary.getValue("text"), plan.getValue("text"))
        assertEquals("user", plan.getValue("input").jsonArray.single().jsonObject.getValue("role").jsonPrimitive.content)
    }

    @Test fun assistantToolsAreNamespacedAndHistoryIsKeptLocallyInInput() {
        val input = buildJsonArray {
            add(
                buildJsonObject {
                    put("role", "user")
                    put("content", "Question")
                }
            )
        }
        val ordinary = AssistantProtocol.request("fixture-model", AssistantProtocol.payload(input, 0, "English"))
        val plan = ChatGptPlanProtocol.request(ordinary)
        val namespace = plan.getValue("tools").jsonArray.single().jsonObject
        assertEquals("namespace", namespace.getValue("type").jsonPrimitive.content)
        assertEquals("uit", namespace.getValue("name").jsonPrimitive.content)
        assertEquals(AssistantProtocol.tools, namespace.getValue("tools"))
        assertEquals(input, plan.getValue("input"))
        assertFalse(plan.containsKey("previous_response_id"))
    }
    private class Accounts : AccountRepository {
        override val accounts = MutableStateFlow(listOf(Account("fixture-account", "https://courses.uit.edu.vn", 1, "Fixture", PortalType.MOODLE)))
        override suspend fun remove(accountId: String) = Unit
        override suspend fun saveSessions(sessions: List<PortalSession>) = Unit
    }

    @Test fun quotaFailurePreservesSafeDiagnosticsAndNeverFallsBackOrRetries() = runBlocking {
        ChatGptTestSupport().use { f ->
            val repo = ConnectedStudyAiRepository(f.storage, Accounts(), f.client)
            f.connect(repo.chatGpt)
            repo.configureChatGpt("fixture-model")
            f.responseStatus = 429
            f.response = """{"error":{"code":"subscription_sharing_usage_limit_exceeded","message":"Untrusted private service text"}}"""
            val error = runCatching { repo.generate(Course(1, "fixture-account", "IT004", "Fixture"), "ask", listOf(StudySource("module:1", "Source", "A transaction preserves consistency.")), "Question", "English") }.exceptionOrNull() as ChatGptException
            assertEquals(ChatGptProblem.QUOTA, error.problem)
            assertEquals(429, error.diagnostic?.status)
            assertEquals("subscription_sharing_usage_limit_exceeded", error.diagnostic?.code)
            assertFalse(error.toString().contains("private service text"))
            assertEquals(1, f.requests.count { it.endsWith("/v1/responses") })
            assertEquals(StudyConnectionMode.CHATGPT, repo.connection().mode)
        }
    }

    @Test fun responseFromAnOldConnectionCannotPopulateTheNewConnection() = runBlocking {
        ChatGptTestSupport().use { f ->
            val repo = ConnectedStudyAiRepository(f.storage, Accounts(), f.client)
            f.connect(repo.chatGpt)
            repo.configureChatGpt("fixture-model")
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.beforeResponse = {
                entered.complete(Unit)
                release.await()
            }
            f.response = "data: " + buildJsonObject {
                put("type", "response.completed")
                put(
                    "response",
                    buildJsonObject {
                        put("status", "completed")
                        put("model", "fixture-model")
                        put("created_at", f.now)
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
                                                        put("text", """{"items":[{"kind":"unknown","text":"Unverified","citations":[]}]}""")
                                                    }
                                                )
                                            }
                                        )
                                    }
                                )
                            }
                        )
                    }
                )
            } + "\n\n"
            val work = async { runCatching { repo.generate(Course(1, "fixture-account", "IT004", "Fixture"), "ask", listOf(StudySource("module:1", "Source", "A transaction preserves consistency.")), "Question", "English") } }
            entered.await()
            repo.configureDirect("fixture-new-api-key-not-forwarded", "different-model")
            release.complete(Unit)
            assertTrue(work.await().isFailure)
            assertEquals(StudyConnectionMode.DIRECT, repo.connection().mode)
            assertEquals("Bearer fixture-initial-access-token", f.responseBearer)
        }
    }

    @Test fun selectedChatGptAccountStreamsAnAnswerThroughTheExistingSourceValidator() = runBlocking {
        ChatGptTestSupport().use { f ->
            val repo = ConnectedStudyAiRepository(f.storage, Accounts(), f.client)
            f.connect(repo.chatGpt)
            // Recreate the native repository after a retained login with an empty
            // picker, as reported on the owner's phone. This is a controlled
            // catalog larger than the 655,730-byte live web-probe observation.
            val saved = Json.parseToJsonElement(f.storage.read("chatgpt_accounts")!!).jsonArray
            val emptyModels = saved.map { JsonObject(it.jsonObject + ("models" to JsonArray(emptyList()))) }
            f.storage.write("chatgpt_accounts", JsonArray(emptyModels).toString())
            f.modelBody = """{"models":[{"slug":"fixture-model","display_name":"Fixture model","visibility":"list","capabilities":"${"x".repeat(700000)}"}]}"""
            val restored = ConnectedStudyAiRepository(f.storage, Accounts(), f.client)
            restored.chatGpt.initialize()
            assertTrue(restored.chatGpt.state.value.accounts.single().signedIn)
            assertTrue(restored.chatGpt.state.value.models.isEmpty())
            assertFalse(restored.connection().configured)
            restored.chatGpt.reloadModels()
            assertNull(restored.chatGpt.state.value.problem)
            assertEquals("fixture-model", restored.chatGpt.state.value.models.single().id)
            assertFalse(restored.connection().configured)
            restored.configureChatGpt("fixture-model")
            assertEquals(StudyConnectionMode.CHATGPT, restored.connection().mode)
            assertTrue(restored.connection().configured)
            assertTrue(withTimeout(5000) { restored.connectionChanges.first { it.configured } }.configured)
            assertEquals(1, f.authorizationQueries.size)
            val config = Json.parseToJsonElement(f.storage.read("connection")!!).jsonObject
            assertFalse(config.containsKey("token"))
            val answer = """{"items":[{"kind":"answer","text":"Transactions preserve consistency.","citations":[{"sourceId":"module:1","quote":"A transaction preserves consistency."}]}]}"""
            val completed = buildJsonObject {
                put("status", "completed")
                put("model", "fixture-model")
                put("created_at", f.now)
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
                                                put("text", answer)
                                            }
                                        )
                                    }
                                )
                            }
                        )
                    }
                )
            }
            f.response = "data: " + buildJsonObject {
                put("type", "response.completed")
                put("response", completed)
            } + "\n\n"
            val course = Course(1, "fixture-account", "IT004", "Fixture course")
            val result = restored.generate(course, "ask", listOf(StudySource("module:1", "Fixture source", "A transaction preserves consistency.")), "What does it preserve?", "English")
            assertEquals("Transactions preserve consistency.", result.items.single().text)
            assertEquals("Bearer fixture-initial-access-token", f.responseBearer)
            assertTrue(f.responseRequest!!.getValue("stream").jsonPrimitive.boolean)
            assertFalse(f.responseRequest!!.containsKey("max_output_tokens"))
            assertTrue(f.requests.all { it.startsWith("https://auth.openai.com/") || it.startsWith("https://api.openai.com/") })
            assertTrue(runCatching { restored.embed(course, listOf("text"), PassageEmbeddingConfig()) }.isFailure)
            restored.disconnect()
            assertFalse(restored.connection().configured)
        }
    }
}
