package vn.edu.uit.mobile.data

import io.ktor.utils.io.*
import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import vn.edu.uit.mobile.domain.*

/** Inspect original bytes without replaying a request or trusting an arbitrary HTTP-200 page. */
internal object StudyResponseReader {
    private const val PREFIX_LIMIT = 4096
    private const val JSON_LIMIT = 262144
    private enum class Shape { SSE, JSON, DOCUMENT, OTHER, EMPTY }

    fun mediaType(raw: String?): String {
        val value = raw?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (value.isEmpty()) return "missing"
        return value.takeIf { it.matches(Regex("[a-z0-9!#$&^_.+-]{1,64}/[a-z0-9!#$&^_.+-]{1,64}")) } ?: "invalid"
    }
    fun encoding(raw: String?): String = raw?.trim()?.lowercase(Locale.ROOT)?.takeIf { it in setOf("identity", "gzip", "br", "deflate", "zstd") } ?: if (raw.isNullOrBlank()) "missing" else "other"

    private fun shape(bytes: ByteArray): Shape? {
        if (bytes.isEmpty()) return null
        val bom = byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte())
        if (bytes.size < 3 && bytes.indices.all { bytes[it] == bom[it] }) return null
        val start = if (bytes.size >= 3 && bytes.copyOfRange(0, 3).contentEquals(bom)) 3 else 0
        val text = bytes.copyOfRange(start, bytes.size).toString(Charsets.UTF_8).trimStart(' ', '\t', '\r', '\n')
        if (text.isEmpty()) return null
        if (text[0] in "{[") return Shape.JSON
        if (text[0] == '<') return Shape.DOCUMENT
        val fields = listOf("data:", "event:", "id:", "retry:", ":")
        if (fields.any(text::startsWith)) return Shape.SSE
        return if (fields.any { it.startsWith(text) }) null else Shape.OTHER
    }

    suspend fun read(channel: ByteReadChannel, metadata: ChatGptDiagnostic, requestedModel: String?, onText: (String) -> Unit): JsonObject {
        val prefix = ByteArrayOutputStream()
        val buffer = ByteArray(512)
        var kind: Shape? = null
        while (prefix.size() < PREFIX_LIMIT && kind == null) {
            currentCoroutineContext().ensureActive()
            val count = channel.readAvailable(buffer, 0, minOf(buffer.size, PREFIX_LIMIT - prefix.size()))
            if (count < 0) break
            prefix.write(buffer, 0, count)
            kind = shape(prefix.toByteArray())
        }
        val bytes = prefix.toByteArray()
        kind = kind ?: if (bytes.isEmpty()) Shape.EMPTY else Shape.OTHER
        fun failure(code: String, bodyShape: String, size: Int = bytes.size, phase: String = "response_body", parameter: String = "") = StudyAiException(
            "The AI service returned an unsupported or failed response.",
            metadata.copy(code = code, bodyShape = bodyShape, responseBytes = size, phase = phase, parameter = parameter)
        )
        return when (kind) {
            Shape.SSE -> coroutineScope {
                val replay = ByteChannel(autoFlush = true)
                val pump = launch {
                    try {
                        replay.writeFully(bytes)
                        channel.copyTo(replay)
                        replay.close()
                    } catch (e: Exception) {
                        replay.close(e)
                    }
                }
                try {
                    AssistantStream.read(replay, metadata.copy(bodyShape = "sse"), requestedModel, onText)
                } finally {
                    pump.cancel()
                    replay.cancel(null)
                }
            }
            Shape.JSON -> {
                val out = ByteArrayOutputStream().apply { write(bytes) }
                val chunk = ByteArray(4096)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = channel.readAvailable(chunk, 0, chunk.size)
                    if (count < 0) break
                    if (out.size() + count > JSON_LIMIT) throw failure("response_too_large", "json", out.size() + count)
                    out.write(chunk, 0, count)
                }
                val parsed = try {
                    Json.parseToJsonElement(out.toString("UTF-8").removePrefix("\uFEFF"))
                } catch (_: IllegalArgumentException) {
                    throw failure("invalid_json", "unreadable-json", out.size())
                }
                val value = parsed as? JsonObject ?: throw failure("unexpected_json_response", "json-array", out.size())
                val error = value["error"] as? JsonObject ?: value.takeIf { (it["type"] as? JsonPrimitive)?.contentOrNull == "error" || it.containsKey("code") && it.containsKey("message") }
                val status = (value["status"] as? JsonPrimitive)?.contentOrNull
                if (error != null || value.containsKey("detail") || value["error"]?.let { it != JsonNull } == true || status == "failed") {
                    val code = field(error?.get("code"), "[A-Za-z0-9_.-]{1,100}").ifEmpty { if (value.containsKey("detail")) "json_detail_response" else "json_error_response" }
                    throw failure(code, if (value.containsKey("detail")) "detail" else "error-object", out.size(), "json_error", field(error?.get("param"), "[A-Za-z0-9_.\\[\\]-]{1,160}"))
                }
                if (status == "incomplete") throw failure("response_incomplete", "incomplete-json", out.size(), "response.incomplete", field((value["incomplete_details"] as? JsonObject)?.get("reason"), "[A-Za-z0-9_.-]{1,100}"))
                if (status != "completed") throw failure("json_response_not_completed", "json-object", out.size(), "response_decode")
                if ((value["output"] as? JsonArray)?.any { message -> ((message as? JsonObject)?.get("content") as? JsonArray)?.any { (it as? JsonObject)?.get("type") == JsonPrimitive("refusal") } == true } == true) throw failure("response_refused", "completed-json", out.size(), "response.refusal.done")
                try {
                    AssistantResponseAccumulator(requestedModel).finish(value).also { AssistantProtocol.output(it) }
                } catch (e: AssistantAssemblyException) {
                    throw failure(e.code, "completed-json", out.size(), "response_assembly")
                } catch (_: IllegalArgumentException) {
                    throw failure("response_output_invalid", "completed-json", out.size(), "response_decode")
                }
            }
            Shape.DOCUMENT -> throw failure("unexpected_response_document", "html-or-xml")
            Shape.EMPTY -> throw failure("empty_response_body", "empty")
            else -> throw failure("unexpected_response_body", "binary-or-text")
        }
    }

    private fun field(value: JsonElement?, pattern: String): String = (value as? JsonPrimitive)?.contentOrNull?.takeIf { it.matches(Regex(pattern)) }.orEmpty()
}
