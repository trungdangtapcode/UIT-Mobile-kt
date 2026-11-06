package vn.edu.uit.mobile.data

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import vn.edu.uit.mobile.domain.*

internal data class AssistantRunResult(val facts: List<AssistantFact>, val evidence: List<AssistantEvidence>, val events: List<AssistantToolEvent>, val model: String, val coverage: String, val forumCoverage: AssistantForumCoverage?)

/** Bounded client-owned Responses loop. Provider output never executes as code or arbitrary HTTP. */
internal class AssistantRunner(private val model: AssistantModel, private val announcements: AnnouncementRepository, private val forum: AssistantForumSource) {
    suspend fun run(workspace: AssistantWorkspace, question: String, language: String, guard: suspend () -> Unit, progress: (AssistantProgress) -> Unit): AssistantRunResult = withTimeout(180000) {
        announcements.initialize()
        val tools = AssistantContextTools(announcements, workspace.scope, forum)
        val input = mutableListOf<JsonElement>()
        input += buildJsonObject {
            put("role", "user")
            put("content", "Workspace coverage: " + tools.coverage())
        }
        workspace.turns.filter { it.status == AssistantTurnStatus.COMPLETE }.takeLast(3).forEach { turn ->
            input += buildJsonObject {
                put("role", "user")
                put("content", turn.question)
            }
            input += buildJsonObject {
                put("role", "assistant")
                put("content", "Previous answer (historical context only; retrieve new evidence): " + turn.facts.joinToString("\n") { it.text }.take(4000))
            }
        }
        input += buildJsonObject {
            put("role", "user")
            put("content", question)
        }
        val events = mutableListOf<AssistantToolEvent>()
        val seenCalls = mutableSetOf<String>()
        var calls = 0
        var repaired = false
        repeat(AssistantRules.MAX_STEPS) { step ->
            currentCoroutineContext().ensureActive()
            guard()
            require(input.sumOf { it.toString().toByteArray(Charsets.UTF_8).size } <= 110000) { "Context limit reached" }
            progress(AssistantProgress("thinking"))
            val data = model.stream(AssistantProtocol.payload(JsonArray(input), step, language)) { json ->
                progress(AssistantProgress("writing", draft = AssistantDraft.text(json)))
            }
            guard()
            // Clear an unverified preview before validation, repair or tool execution.
            progress(AssistantProgress("verifying"))
            val output = AssistantProtocol.output(data)
            val functionCalls = output.filter { it.jsonObject["type"]?.jsonPrimitive?.content == "function_call" }
            if (functionCalls.isEmpty()) {
                var candidate: List<AssistantFact>? = null
                try {
                    val facts = AssistantProtocol.facts(OpenAiStudyProtocol.directPayload(data).toString())
                    candidate = facts
                    AssistantRules.verify(facts, tools.evidence)
                    require(facts.none { it.kind == AssistantFactKind.FACT } || tools.evidence.isNotEmpty())
                    require(tools.sourcesEnabled())
                    return@withTimeout AssistantRunResult(facts, tools.evidence, events, data.getValue("model").jsonPrimitive.content.take(100), tools.coverage(), tools.forumCoverage)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    check(!repaired && step < AssistantRules.MAX_STEPS - 1) { "Answer evidence could not be verified" }
                    repaired = true
                    input.addAll(output)
                    input += buildJsonObject {
                        put("role", "user")
                        put("content", AssistantProtocol.repairHint(candidate, tools.evidence))
                    }
                }
            } else {
                require(functionCalls.size <= 2 && calls + functionCalls.size <= AssistantRules.MAX_CALLS)
                input.addAll(output) // Includes opaque reasoning items required by reasoning models.
                functionCalls.forEach { raw ->
                    val call = raw.jsonObject
                    val id = call.getValue("call_id").jsonPrimitive.content
                    require(id.length in 1..160 && seenCalls.add(id))
                    val name = call.getValue("name").jsonPrimitive.content
                    require(call["namespace"]?.jsonPrimitive?.contentOrNull?.let { it == "uit" } != false) { "Unsupported tool namespace" }
                    require(name in AssistantProtocol.toolNames) { "Unsupported tool" }
                    val arguments = call.getValue("arguments").jsonPrimitive.content
                    require(arguments.length <= 1000)
                    calls++
                    progress(AssistantProgress(name))
                    guard()
                    val result = try {
                        tools.call(name, Json.parseToJsonElement(arguments).jsonObject)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        "{\"error\":\"Selected source unavailable, arguments invalid, or evidence budget reached. Narrow the request or report UNKNOWN.\"}" to 0
                    }
                    guard()
                    events += AssistantToolEvent(name, result.second)
                    progress(AssistantProgress(name, result.second))
                    input += buildJsonObject {
                        put("type", "function_call_output")
                        put("call_id", id)
                        put("output", result.first)
                    }
                }
            }
        }
        error("Assistant step limit reached")
    }
}
