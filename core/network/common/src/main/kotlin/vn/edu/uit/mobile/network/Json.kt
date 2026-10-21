package vn.edu.uit.mobile.network

import kotlinx.serialization.json.*
import vn.edu.uit.mobile.common.*

val PortalJson = Json { ignoreUnknownKeys = true }
fun Any?.asJson(): JsonElement = when (this) {
    null -> JsonNull
    is JsonElement -> this
    is String -> JsonPrimitive(this)
    is Number -> JsonPrimitive(this)
    is Boolean -> JsonPrimitive(this)
    is Map<*, *> -> JsonObject(entries.associate { it.key.toString() to it.value.asJson() })
    is Iterable<*> -> JsonArray(map { it.asJson() })
    else -> error("Unsupported JSON value")
}
fun obj(vararg pairs: Pair<String, Any?>): JsonObject = pairs.toMap().asJson().jsonObject
fun JsonElement.field(key: String): JsonElement = (this as? JsonObject)?.get(key) ?: JsonNull
fun JsonElement.str(key: String = ""): String = ((if (key.isEmpty()) this else field(key)) as? JsonPrimitive)?.contentOrNull.orEmpty()
fun JsonElement.long(key: String = ""): Long = str(key).toLongOrNull() ?: 0
fun JsonElement.int(key: String = ""): Int = long(key).toInt()
fun JsonElement.list(key: String = ""): List<JsonElement> = (if (key.isEmpty()) this else field(key)) as? JsonArray ?: emptyList()
fun JsonElement.flag(key: String): Boolean = str(key).let { it == "true" || it == "1" }
fun parseJson(bytes: ByteArray): JsonElement = try {
    PortalJson.parseToJsonElement(bytes.toString(Charsets.UTF_8))
} catch (e: Exception) {
    throw InvalidResponseException("Portal returned invalid JSON")
}

fun sanitizeHtml(fragment: String, baseUrl: String): String {
    val doc = com.fleeksoft.ksoup.Ksoup.parse(fragment, baseUrl)
    doc.select("script, style, form, input").remove()
    doc.select("*").forEach { element ->
        element.attributes().asList().map { it.key }.filter { it.startsWith("on", true) }.forEach { element.removeAttr(it) }
        for (attribute in listOf("href", "src", "data", "action")) {
            if (!element.hasAttr(attribute)) continue
            val resolved = runCatching { MoodleUrlResolver.resolve(baseUrl, element.attr(attribute)) }.getOrNull()
            val clean = resolved?.let(UrlSanitizer::credentialFreeUrl)
            if (clean == null) element.removeAttr(attribute) else element.attr(attribute, clean)
        }
    }
    return doc.body().html()
}
