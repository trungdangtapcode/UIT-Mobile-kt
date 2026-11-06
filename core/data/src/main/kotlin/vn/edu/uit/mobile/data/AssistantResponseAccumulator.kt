package vn.edu.uit.mobile.data

import kotlinx.serialization.json.*

internal class AssistantAssemblyException(val code: String) : IllegalStateException(code)

/** Only finalized output items may fill a compact terminal response; deltas are previews. */
internal class AssistantResponseAccumulator(private val requestedModel: String?) {
    private var metadata = JsonObject(emptyMap())
    private val added = mutableMapOf<Int, JsonObject>()
    private val completed = mutableMapOf<Int, JsonObject>()
    private val identityFields = setOf("type", "id", "call_id", "name", "namespace", "role")

    private fun invalid(code: String): Nothing = throw AssistantAssemblyException(code)
    private fun text(item: JsonObject, key: String): String? = (item[key] as? JsonPrimitive)?.contentOrNull
    private fun objectValue(value: JsonElement?): JsonObject = value as? JsonObject ?: invalid("invalid_stream_item")
    private fun item(value: JsonElement?): JsonObject = objectValue(value).also {
        if (text(it, "type") !in setOf("message", "reasoning", "function_call")) invalid("unsupported_stream_item")
    }
    private fun sameIdentity(a: JsonObject, b: JsonObject): Boolean = listOf("id", "call_id").any { key ->
        val value = text(a, key)
        !value.isNullOrEmpty() && value == text(b, key)
    }
    private fun compatible(a: JsonObject, b: JsonObject) {
        for (key in identityFields) {
            val left = a[key]?.takeUnless { it == JsonNull }
            val right = b[key]?.takeUnless { it == JsonNull }
            if (left != null && right != null && left != right) invalid("stream_item_identity_changed")
        }
    }
    private fun finalizedCompatible(a: JsonObject, b: JsonObject) {
        compatible(a, b)
        if (text(a, "type") == "function_call" && a["arguments"] != null && b["arguments"] != null && a["arguments"] != b["arguments"]) invalid("stream_item_content_changed")
        fun content(value: JsonElement?): JsonArray? = (value as? JsonArray)?.let { parts ->
            JsonArray(parts.map { part -> JsonObject(objectValue(part).filterKeys { it in setOf("type", "text", "refusal") }) })
        }
        val left = content(a["content"])
        val right = content(b["content"])
        if (left != null && right != null && left != right) invalid("stream_item_content_changed")
    }
    private fun merge(a: JsonObject, b: JsonObject) = JsonObject(a + b.filterValues { it != JsonNull })
    private fun known(): Map<Int, JsonObject> = added + completed
    private fun matchedIndex(value: JsonObject): Int? {
        val matches = known().filterValues { sameIdentity(it, value) }.keys
        if (matches.size > 1) invalid("duplicate_stream_identity")
        return matches.singleOrNull()
    }
    private fun index(event: JsonObject, value: JsonObject): Int {
        val supplied = event["output_index"]
        val index = if (supplied == null) matchedIndex(value) ?: ((known().keys.maxOrNull() ?: -1) + 1) else (supplied as? JsonPrimitive)?.intOrNull ?: invalid("invalid_stream_index")
        if (index !in 0..9) invalid("stream_item_limit")
        if (matchedIndex(value)?.let { it != index } == true) invalid("duplicate_stream_identity")
        return index
    }
    private fun budget() {
        if (known().size > 10 || JsonArray(completed.toSortedMap().values.toList()).toString().toByteArray(Charsets.UTF_8).size > 262144) invalid("stream_result_limit")
    }
    private fun finalized(value: JsonObject) {
        if (text(value, "status")?.let { it != "completed" } == true) invalid("unfinished_stream_item")
    }

    fun lifecycle(value: JsonElement?) {
        val response = objectValue(value)
        val initializeItems = metadata.isEmpty() && known().isEmpty()
        val previousId = text(metadata, "id")
        val nextId = text(response, "id")
        if (previousId != null && nextId != null && previousId != nextId) invalid("stream_response_identity_changed")
        metadata = merge(metadata, JsonObject(response - "output"))
        if (metadata.toString().toByteArray(Charsets.UTF_8).size > 262144) invalid("stream_result_limit")
        val output = response["output"]
        if (output != null && output != JsonNull && output !is JsonArray) invalid("invalid_terminal_output")
        val replacements = mutableSetOf<Int>()
        (output as? JsonArray)?.forEachIndexed { position, raw ->
            val replacement = item(raw)
            if (initializeItems) {
                added(
                    buildJsonObject {
                        put("output_index", position)
                        put("item", replacement)
                    }
                )
            }
            matchedIndex(replacement)?.let { index ->
                if (!replacements.add(index)) invalid("duplicate_stream_identity")
                completed[index]?.let { old ->
                    finalizedCompatible(old, replacement)
                    completed[index] = merge(old, replacement)
                }
            }
        }
        budget()
    }

    fun added(event: JsonObject) {
        val value = item(event["item"])
        val index = index(event, value)
        if (index in added || index in completed) invalid("duplicate_stream_index")
        added[index] = JsonObject(value.filterKeys { it in identityFields || it == "phase" })
        budget()
    }

    fun done(event: JsonObject) {
        val value = item(event["item"])
        val index = index(event, value)
        finalized(value)
        added[index]?.let { compatible(it, value) }
        completed[index]?.let { finalizedCompatible(it, value) }
        completed[index] = merge(completed[index] ?: added[index] ?: JsonObject(emptyMap()), value)
        budget()
    }

    fun previewAllowed(event: JsonObject): Boolean {
        val index = (event["output_index"] as? JsonPrimitive)?.intOrNull
        return index == null || text(known()[index] ?: JsonObject(emptyMap()), "phase") != "commentary"
    }

    fun finish(value: JsonElement?): JsonObject {
        val response = objectValue(value)
        val status = text(response, "status")
        if (status != null && status != "completed") invalid("inconsistent_completion_status")
        if (response["error"]?.let { it != JsonNull } == true) invalid("completed_response_has_error")
        lifecycle(response)
        val terminal = response["output"] as? JsonArray
        val output = if (terminal.isNullOrEmpty()) {
            if (added.keys.any { it !in completed }) invalid("unfinished_stream_items")
            completed.toSortedMap().values.toList()
        } else {
            val final = mutableMapOf<Int, JsonObject>()
            terminal.forEachIndexed { position, raw ->
                val value = item(raw)
                finalized(value)
                val index = matchedIndex(value) ?: position
                if (index !in 0..9 || index in final) invalid("duplicate_stream_identity")
                added[index]?.let { compatible(it, value) }
                completed[index]?.let { finalizedCompatible(it, value) }
                final[index] = merge(known()[index] ?: JsonObject(emptyMap()), value)
            }
            if (known().keys.any { it !in final }) invalid("incomplete_terminal_output")
            final.toSortedMap().values.toList()
        }
        if (output.isEmpty() || output.size > 10) invalid("missing_completed_output")
        output.forEach(::finalized)
        val model = text(metadata, "model")?.takeIf { it.isNotBlank() } ?: requestedModel
        if (model.isNullOrBlank()) invalid("missing_response_model")
        return JsonObject(
            metadata + mapOf("status" to JsonPrimitive("completed"), "model" to JsonPrimitive(model), "output" to JsonArray(output)) +
                if (text(metadata, "model").isNullOrBlank()) mapOf("model_source" to JsonPrimitive("requested")) else emptyMap()
        ).also { if (it.toString().toByteArray(Charsets.UTF_8).size > 262144) invalid("stream_result_limit") }
    }
}
