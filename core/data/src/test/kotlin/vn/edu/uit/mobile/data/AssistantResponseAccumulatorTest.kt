package vn.edu.uit.mobile.data

import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import vn.edu.uit.mobile.domain.*

class AssistantResponseAccumulatorTest {
    private fun event(type: String, fields: JsonObject) = "data: " + JsonObject(fields + ("type" to JsonPrimitive(type))) + "\n\n"
    private fun lifecycle(
        type: String = "response.created",
        body: JsonObject = buildJsonObject {
            put("id", "r")
            put("model", "fixture-model")
        }
    ) = event(type, buildJsonObject { put("response", body) })
    private fun item(type: String, index: Int, value: JsonObject) = event(
        type,
        buildJsonObject {
            put("output_index", index)
            put("item", value)
        }
    )
    private fun done(index: Int, value: JsonObject) = item("response.output_item.done", index, value)
    private fun terminal(
        body: JsonObject = buildJsonObject {
            put("id", "r")
            put("output", JsonNull)
        }
    ) = lifecycle("response.completed", body)
    private val tool = JsonObject(assistantCall("c", "search_announcements", "{\"query\":\"\"}") + mapOf("id" to JsonPrimitive("fc"), "namespace" to JsonPrimitive("uit")))
    private fun reasoning(blob: String) = buildJsonObject {
        put("id", "rs")
        put("type", "reasoning")
        put("encrypted_content", blob)
        put("summary", JsonArray(emptyList()))
    }
    private suspend fun read(wire: String, selected: String? = null): JsonObject = AssistantStream.read(ByteReadChannel(wire), ChatGptDiagnostic(200, operation = "responses"), selected) {}
    private suspend fun rejects(wire: String, code: String) {
        val error = runCatching { read(wire) }.exceptionOrNull() as StudyAiException
        assertEquals(code, error.diagnostic!!.code)
    }

    @Test fun compactFormsKeepIndexedOrderNamespaceAndFreshEncryptedReasoning() = runTest {
        for (output in listOf(null, JsonNull, JsonArray(emptyList()))) {
            val wire = lifecycle() + done(1, tool) + done(0, reasoning("opaque-v1")) +
                lifecycle(
                    "response.in_progress",
                    buildJsonObject {
                        put("id", "r")
                        put("output", JsonArray(listOf(reasoning("opaque-v2"))))
                    }
                ) +
                terminal(
                    buildJsonObject {
                        put("id", "r")
                        output?.let { put("output", it) }
                    }
                )
            val result = read(wire)
            val items = result.getValue("output").jsonArray
            assertEquals("opaque-v2", items[0].jsonObject.getValue("encrypted_content").jsonPrimitive.content)
            assertEquals(tool, items[1])
            assertEquals("completed", result.getValue("status").jsonPrimitive.content)
        }
    }

    @Test fun selectedModelFillsOnlyMissingProviderModelAndIsLabelled() = runTest {
        val wire = lifecycle(body = buildJsonObject { put("id", "r") }) + done(0, tool) + terminal()
        val result = read(wire, "selected-model")
        assertEquals("selected-model", result.getValue("model").jsonPrimitive.content)
        assertEquals("requested", result.getValue("model_source").jsonPrimitive.content)
        rejects(wire, "missing_response_model")
    }

    @Test fun responseAndToolIdentityChangesCannotBeAccepted() = runTest {
        rejects(lifecycle() + done(0, tool) + terminal(buildJsonObject { put("id", "different") }), "stream_response_identity_changed")
        val foreign = JsonObject(tool + ("namespace" to JsonPrimitive("foreign")))
        rejects(lifecycle() + item("response.output_item.added", 0, tool) + done(0, foreign) + terminal(), "stream_item_identity_changed")
        rejects(lifecycle() + done(0, tool) + done(1, tool) + terminal(), "duplicate_stream_identity")
        val changed = JsonObject(tool + ("arguments" to JsonPrimitive("{\"query\":\"changed\"}")))
        rejects(
            lifecycle() + done(0, tool) + terminal(
                buildJsonObject {
                    put("id", "r")
                    put("output", JsonArray(listOf(changed)))
                }
            ),
            "stream_item_content_changed"
        )
    }

    @Test fun partialAddedItemsAndDeltasNeverBecomeCompletedAnswers() = runTest {
        val partial = buildJsonObject {
            put("id", "msg")
            put("type", "message")
            put("role", "assistant")
            put("content", JsonArray(emptyList()))
        }
        rejects(lifecycle() + item("response.output_item.added", 0, partial) + terminal(), "unfinished_stream_items")
        rejects(
            lifecycle(
                body = buildJsonObject {
                    put("id", "r")
                    put("model", "fixture-model")
                    put("output", JsonArray(listOf(partial)))
                }
            ) + done(1, reasoning("opaque")) + terminal(),
            "unfinished_stream_items"
        )
        rejects(lifecycle() + done(0, tool), "stream_ended_without_completion")
        rejects(
            lifecycle() + done(0, tool) + terminal(
                buildJsonObject {
                    put("id", "r")
                    put("status", "in_progress")
                }
            ),
            "inconsistent_completion_status"
        )
    }

    @Test fun lifecycleCannotDuplicateOrDowngradeCompletedItems() = runTest {
        val duplicate = lifecycle(
            "response.in_progress",
            buildJsonObject {
                put("id", "r")
                put("output", JsonArray(listOf(tool, tool)))
            }
        )
        rejects(lifecycle() + done(0, tool) + duplicate + terminal(), "duplicate_stream_identity")
        val partial = JsonObject(tool + ("status" to JsonPrimitive("in_progress")))
        val update = lifecycle(
            "response.in_progress",
            buildJsonObject {
                put("id", "r")
                put("output", JsonArray(listOf(partial)))
            }
        )
        rejects(lifecycle() + done(0, tool) + update + terminal(), "unfinished_stream_item")
    }

    @Test fun completedItemsRemainBoundedBeforeTheTerminalFrame() = runTest {
        val other = JsonObject(reasoning("y".repeat(150000)) + ("id" to JsonPrimitive("other")))
        rejects(lifecycle() + done(0, reasoning("x".repeat(150000))) + done(1, other) + terminal(), "stream_result_limit")
    }

    @Test fun commentaryStaysOutOfDraftAndStructuredFinalButRemainsInHistory() = runTest {
        val answer = assistantAnswer("""[{"kind":"UNKNOWN","text":"Hello.","references":[]}]""").getValue("output").jsonArray.single().jsonObject
        val commentary = JsonObject(
            answer + mapOf(
                "id" to JsonPrimitive("comment"),
                "phase" to JsonPrimitive("commentary"),
                "content" to JsonArray(
                    listOf(
                        buildJsonObject {
                            put("type", "output_text")
                            put("text", "commentary-should-stay-hidden")
                        }
                    )
                )
            )
        )
        val final = JsonObject(answer + mapOf("id" to JsonPrimitive("answer"), "phase" to JsonPrimitive("final_answer")))
        val delta = event(
            "response.output_text.delta",
            buildJsonObject {
                put("output_index", 0)
                put("delta", "commentary-should-stay-hidden")
            }
        )
        val previews = mutableListOf<String>()
        val wire = lifecycle() + item("response.output_item.added", 0, commentary) + delta + done(0, commentary) + done(1, final) + terminal()
        val result = AssistantStream.read(ByteReadChannel(wire)) { previews += it }
        assertTrue(previews.none { it.contains("commentary-should-stay-hidden") })
        assertEquals("Hello.", AssistantProtocol.facts(OpenAiStudyProtocol.directPayload(result).toString()).single().text)
        assertEquals("commentary", result.getValue("output").jsonArray.first().jsonObject.getValue("phase").jsonPrimitive.content)
    }
}
