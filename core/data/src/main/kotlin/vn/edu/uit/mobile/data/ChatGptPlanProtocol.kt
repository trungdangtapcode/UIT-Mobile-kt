package vn.edu.uit.mobile.data

import kotlinx.serialization.json.*

/** The documented SIWC contract differs from ordinary API-key Responses requests. */
internal object ChatGptPlanProtocol {
    private val unsupported = setOf("background", "conversation", "max_output_tokens", "max_tool_calls", "metadata", "moderation", "multi_agent", "prompt", "prompt_cache_retention", "safety_identifier", "temperature", "top_logprobs", "top_p", "truncation", "user", "previous_response_id")
    fun request(ordinary: JsonObject): JsonObject = buildJsonObject {
        ordinary.filterKeys { it !in unsupported && it !in setOf("input", "tools", "stream", "store") }.forEach { (k, v) -> put(k, v) }
        put("store", false)
        put("stream", true)
        val input = ordinary.getValue("input")
        put(
            "input",
            if (input is JsonArray) {
                input
            } else {
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("role", "user")
                            put("content", input)
                        }
                    )
                }
            }
        )
        ordinary["tools"]?.let { tools ->
            put(
                "tools",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("type", "namespace")
                            put("name", "uit")
                            put("description", "Read-only tools for the student's explicitly selected UIT sources.")
                            put("tools", tools)
                        }
                    )
                }
            )
        }
    }
}
