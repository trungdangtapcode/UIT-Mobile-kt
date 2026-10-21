package vn.edu.uit.mobile.moodle

import kotlinx.serialization.json.*
import vn.edu.uit.mobile.common.*
import vn.edu.uit.mobile.network.*

object MoodleAjaxHelper {
    @Suppress("UNCHECKED_CAST")
    fun normalizeArgs(params: Map<String, JsonElement>): JsonObject {
        class Node(val array: Boolean, val values: MutableMap<String, Any?> = linkedMapOf())
        fun node(value: Any?): Node? = when (value) {
            is Node -> value
            is JsonObject -> Node(false, value.toMutableMap() as MutableMap<String, Any?>)
            is JsonArray -> Node(true, value.mapIndexed { i, v -> "$i" to v }.toMap().toMutableMap() as MutableMap<String, Any?>)
            else -> null
        }
        val root = Node(false)
        for ((key, value) in params) {
            val head = Regex("^[^\\[\\]]+").find(key)?.value
            val suffix = head?.let { key.substring(it.length) }.orEmpty()
            val brackets = Regex("\\[([^\\[\\]]+)\\]").findAll(suffix).toList()
            if (head == null || suffix.isEmpty() || brackets.isEmpty() || brackets.joinToString("") { it.value } != suffix) {
                root.values[key] = value
                continue
            }
            val raw = listOf(head) + brackets.map { it.groupValues[1] }
            if (raw.any { it in setOf("__proto__", "constructor", "prototype") }) {
                root.values[key] = value
                continue
            }
            val path = raw.mapIndexed { index, part ->
                if (index > 0 && part.matches(Regex("\\d+"))) {
                    (
                        part.toIntOrNull()?.also { require(it <= 100000) { "Array index too large" } }
                            ?: throw IllegalArgumentException("Array index too large")
                        ).toString()
                } else {
                    part
                }
            }
            var current = root
            for (index in path.indices) {
                val part = path[index]
                if (index == path.lastIndex) {
                    current.values[part] = value
                } else {
                    val child = node(current.values[part]) ?: Node(path[index + 1].matches(Regex("\\d+")))
                    current.values[part] = child
                    current = child
                }
            }
        }
        fun materialize(value: Any?): JsonElement = when (value) {
            is Node -> if (value.array) {
                val max = value.values.keys.mapNotNull(String::toIntOrNull).maxOrNull() ?: -1
                JsonArray((0..max).map { materialize(value.values[it.toString()]) })
            } else {
                JsonObject(value.values.mapValues { materialize(it.value) })
            }
            else -> value.asJson()
        }
        return materialize(root).jsonObject
    }

    fun buildAjaxInfo(name: String, params: Map<String, JsonElement>): String = JsonArray(listOf(obj("index" to 0, "methodname" to name, "args" to normalizeArgs(params)))).toString()
    fun unwrapAjaxResponse(data: JsonElement): JsonElement {
        if (data is JsonArray && data.isEmpty()) throw InvalidResponseException("Moodle returned an empty AJAX response")
        val first = if (data is JsonArray) data.first() else data
        if (first !is JsonObject) throw InvalidResponseException("Moodle returned an invalid AJAX response")
        if (first.field("error") !in listOf(JsonNull, JsonPrimitive(false), JsonPrimitive(0), JsonPrimitive("")) || first.str("errorcode").isNotEmpty() || first.field("exception") != JsonNull) {
            val exception = first.field("exception")
            val code = exception.str("errorcode").ifEmpty { first.str("errorcode") }.ifEmpty { exception.str() }
            val message = exception.str("message").ifEmpty { first.str("message") }.ifEmpty { exception.str() }.ifEmpty { "Moodle AJAX request failed" }
            throw PortalException(code, message)
        }
        if (data !is JsonArray) throw InvalidResponseException("Moodle returned an invalid AJAX response")
        return first["data"] ?: throw InvalidResponseException("Moodle returned an AJAX response without data")
    }
}
object MoodleErrorClassifier {
    fun isUnavailableSessionMethod(error: PortalException): Boolean {
        if (SessionHealthClassifier.classifySessionError(error) == vn.edu.uit.mobile.model.SessionHealthState.EXPIRED) return false
        val code = error.errorcode.lowercase()
        if (code.isNotEmpty() && code !in setOf("moodle_exception", "webservice_exception")) {
            return code in setOf("invalid_parameter_exception", "invalidparameter", "servicenotavailable", "invalidfunction", "cannotfindfunction", "wsfunctionnotavailable")
        }
        return Regex("unknown method|not available for ajax|not callable via ajax|cannot find.*function|web\\s*service is not available", RegexOption.IGNORE_CASE).containsMatchIn(error.message.orEmpty())
    }
}
