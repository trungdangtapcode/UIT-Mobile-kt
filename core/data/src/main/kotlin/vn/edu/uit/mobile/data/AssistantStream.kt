package vn.edu.uit.mobile.data

import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import vn.edu.uit.mobile.domain.*

/** Responses SSE transport. Only the completed response can reach the citation validator. */
internal object AssistantStream {
    suspend fun read(channel: ByteReadChannel, onText: (String) -> Unit): JsonObject = read(channel, ChatGptDiagnostic(200, operation = "responses"), onText = onText)
    suspend fun read(channel: ByteReadChannel, metadata: ChatGptDiagnostic, requestedModel: String? = null, onText: (String) -> Unit): JsonObject {
        var wireBytes = 0
        val frame = StringBuilder()
        val draft = StringBuilder()
        var lastPreview = 0L
        var eventName = ""
        var firstLine = true
        val accumulator = AssistantResponseAccumulator(requestedModel)
        fun failure(code: String, phase: String = "sse_read", parameter: String = "", shape: String = "sse"): StudyAiException = StudyAiException(
            "The AI response did not complete.",
            metadata.copy(code = code, phase = phase, parameter = parameter, bodyShape = shape, responseBytes = wireBytes)
        )
        while (true) {
            currentCoroutineContext().ensureActive()
            val rawLine = channel.readUTF8Line(524288) ?: throw failure("stream_ended_without_completion")
            val line = if (firstLine) rawLine.removePrefix("\uFEFF") else rawLine
            firstLine = false
            wireBytes += line.toByteArray(Charsets.UTF_8).size + 1
            if (wireBytes > 2097152) throw failure("stream_byte_limit")
            if (line.startsWith("data:")) {
                if (frame.isNotEmpty()) frame.append('\n')
                frame.append(line.substring(5).removePrefix(" "))
                if (frame.length > 262144) throw failure("stream_frame_limit")
            } else if (line.startsWith("event:")) {
                eventName = line.substring(6).trim()
            } else if (line.isEmpty() && frame.isNotEmpty()) {
                if (frame.toString() == "[DONE]") throw failure("stream_ended_without_completion")
                val event = try {
                    Json.parseToJsonElement(frame.toString()).jsonObject
                } catch (_: IllegalArgumentException) {
                    throw failure("invalid_stream_json")
                }
                frame.clear()
                try {
                    when (val type = event["type"]?.jsonPrimitive?.content ?: eventName) {
                        "response.created", "response.queued", "response.in_progress" -> accumulator.lifecycle(event["response"])
                        "response.output_item.added" -> accumulator.added(event)
                        "response.output_item.done" -> accumulator.done(event)
                        "response.output_text.delta" -> {
                            if (accumulator.previewAllowed(event)) {
                                draft.append(event.getValue("delta").jsonPrimitive.content)
                                if (draft.length > 65536) throw failure("stream_draft_limit")
                                val now = System.nanoTime()
                                if (now - lastPreview >= 50000000) {
                                    onText(draft.toString())
                                    lastPreview = now
                                }
                            }
                        }
                        "response.completed" -> {
                            val result = accumulator.finish(event["response"])
                            if (result.toString().toByteArray(Charsets.UTF_8).size > 262144) throw failure("stream_result_limit")
                            try {
                                AssistantProtocol.output(result)
                            } catch (_: IllegalArgumentException) {
                                throw failure("response_output_invalid", "response_decode")
                            }
                            if (draft.isNotEmpty()) onText(draft.toString())
                            return result
                        }
                        "error", "response.failed" -> {
                            val response = event["response"] as? JsonObject
                            val error = (response?.get("error") ?: event["error"]) as? JsonObject ?: event
                            val code = safeField(error["code"], "[A-Za-z0-9_.-]{1,100}").ifEmpty { "response_failed" }
                            throw failure(code, type, safeField(error["param"], "[A-Za-z0-9_.\\[\\]-]{1,160}"), "sse-error")
                        }
                        "response.incomplete" -> {
                            val details = (event["response"] as? JsonObject)?.get("incomplete_details") as? JsonObject
                            throw failure("response_incomplete", type, safeField(details?.get("reason"), "[A-Za-z0-9_.-]{1,100}"))
                        }
                        "response.refusal.delta", "response.refusal.done" -> throw failure("response_refused", type)
                    }
                } catch (e: AssistantAssemblyException) {
                    throw failure(e.code, "response_assembly")
                }
                eventName = ""
            }
        }
    }

    private fun safeField(value: JsonElement?, pattern: String): String = (value as? JsonPrimitive)?.contentOrNull?.takeIf { it.matches(Regex(pattern)) }.orEmpty()
}

/** Extract only answer text strings from incomplete structured JSON, never references/reasoning. */
internal object AssistantDraft {
    fun text(json: String): String {
        val sections = mutableListOf<String>()
        var index = 0
        while (index < json.length && sections.size < 12) {
            if (json[index] != '"') {
                index++
                continue
            }
            val start = index++
            while (index < json.length) {
                if (json[index] == '\\') {
                    index += 2
                } else if (json[index++] == '"') {
                    break
                }
            }
            if (index > json.length || json.getOrNull(index - 1) != '"') break
            val key = json.substring(start, index)
            while (json.getOrNull(index)?.isWhitespace() == true) index++
            if (key != "\"text\"" || json.getOrNull(index) != ':') continue
            index++
            while (json.getOrNull(index)?.isWhitespace() == true) index++
            if (json.getOrNull(index) != '"') continue
            val valueStart = ++index
            while (index < json.length && json[index] != '"') {
                if (json[index] == '\\') index += 2 else index++
            }
            val raw = json.substring(valueStart, minOf(index, json.length))
            // A chunk can end inside an escape sequence, including a Unicode escape.
            for (drop in 0..minOf(6, raw.length)) {
                val value = runCatching { Json.parseToJsonElement("\"" + raw.dropLast(drop) + "\"").jsonPrimitive.content }.getOrNull()
                if (value != null) {
                    if (value.isNotBlank()) sections += value.take(1800)
                    break
                }
            }
            index++
        }
        return sections.joinToString("\n\n")
    }
}
