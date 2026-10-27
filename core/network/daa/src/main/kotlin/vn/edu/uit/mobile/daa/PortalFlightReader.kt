package vn.edu.uit.mobile.daa

import com.fleeksoft.ksoup.Ksoup
import java.util.Collections
import java.util.IdentityHashMap
import kotlinx.serialization.json.*
import vn.edu.uit.mobile.common.InvalidResponseException
import vn.edu.uit.mobile.network.PortalJson

/** Reads data references and byte-length text rows, without evaluating React or page scripts. */
internal object PortalFlightReader {
    fun objects(body: String): List<JsonObject> {
        val stream = Ksoup.parse(body).select("script").mapNotNull { script ->
            val text = script.data().trim()
            if (!text.startsWith("self.__next_f.push(")) return@mapNotNull null
            val frame = runCatching { PortalJson.parseToJsonElement(text.substringAfter('(').substringBeforeLast(')')).jsonArray }.getOrNull() ?: return@mapNotNull null
            if (frame.firstOrNull()?.jsonPrimitive?.intOrNull == 1) frame.getOrNull(1)?.jsonPrimitive?.content else null
        }.joinToString("").toByteArray(Charsets.UTF_8)
        val rows = linkedMapOf<String, JsonElement>()
        var cursor = 0
        fun delimiter(value: Byte): Int {
            var end = cursor
            while (end < stream.size && stream[end] != value) end++
            return end
        }
        while (cursor < stream.size) {
            if (stream[cursor] == '\n'.code.toByte()) {
                cursor++
                continue
            }
            val colon = delimiter(':'.code.toByte())
            if (colon == stream.size) break
            val id = stream.copyOfRange(cursor, colon).toString(Charsets.UTF_8)
            if (!id.matches(Regex("[0-9a-f]+"))) throw InvalidResponseException("Invalid Portal data record")
            cursor = colon + 1
            if (cursor < stream.size && stream[cursor] == 'T'.code.toByte()) {
                cursor++
                val comma = delimiter(','.code.toByte())
                val length = stream.copyOfRange(cursor, comma).toString(Charsets.UTF_8).toIntOrNull(16)
                if (length == null || length < 0 || comma >= stream.size || length > stream.size - comma - 1) throw InvalidResponseException("Incomplete Portal text record")
                cursor = comma + 1
                rows[id] = JsonPrimitive(stream.copyOfRange(cursor, cursor + length).toString(Charsets.UTF_8))
                cursor += length
            } else {
                val end = delimiter('\n'.code.toByte())
                val raw = stream.copyOfRange(cursor, end).toString(Charsets.UTF_8)
                runCatching { PortalJson.parseToJsonElement(raw) }.getOrNull()?.let { rows[id] = it }
                cursor = end + 1
            }
            if (rows.size > 100_000) throw InvalidResponseException("Too many Portal data records")
        }
        val resolved = mutableMapOf<String, JsonElement>()
        val visiting = mutableSetOf<String>()
        val reference = Regex("^\\$([0-9a-f]+)((?::[^:]+)*)$")
        fun resolve(value: JsonElement, depth: Int): JsonElement {
            if (depth > 80) return JsonNull
            return when (value) {
                is JsonObject -> JsonObject(value.mapValues { resolve(it.value, depth + 1) })
                is JsonArray -> JsonArray(value.map { resolve(it, depth + 1) })
                is JsonPrimitive -> {
                    if (!value.isString) return value
                    val text = value.content
                    if (text.startsWith("\$\$")) return JsonPrimitive(text.drop(1))
                    if (text == "\$undefined") return JsonNull
                    val match = reference.matchEntire(text) ?: return value
                    val id = match.groupValues[1]
                    var target = resolved[id] ?: run {
                        if (!visiting.add(id)) return JsonNull
                        val result = rows[id]?.let { resolve(it, depth + 1) } ?: JsonNull
                        visiting.remove(id)
                        resolved[id] = result
                        result
                    }
                    match.groupValues[2].split(':').drop(1).forEach { key ->
                        target = when (val item = target) {
                            is JsonObject -> item[key] ?: JsonNull
                            is JsonArray -> key.toIntOrNull()?.let { item.getOrNull(it) } ?: JsonNull
                            else -> JsonNull
                        }
                    }
                    target
                }
            }
        }
        val seen = Collections.newSetFromMap(IdentityHashMap<JsonElement, Boolean>())
        return buildList {
            fun collect(value: JsonElement) {
                if (!seen.add(value)) return
                if (seen.size > 200_000) throw InvalidResponseException("Portal data is too complex")
                when (value) {
                    is JsonObject -> {
                        add(value)
                        value.values.forEach(::collect)
                    }
                    is JsonArray -> value.forEach(::collect)
                    else -> Unit
                }
            }
            rows.filterValues { it is JsonObject || it is JsonArray }.keys.forEach { collect(resolve(JsonPrimitive("\$$it"), 0)) }
        }
    }
}
