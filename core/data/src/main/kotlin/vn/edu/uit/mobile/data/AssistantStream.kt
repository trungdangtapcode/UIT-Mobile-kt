package vn.edu.uit.mobile.data

import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** Responses SSE transport. Only the completed response can reach the citation validator. */
internal object AssistantStream {
    suspend fun read(channel: ByteReadChannel, onText: (String) -> Unit): JsonObject {
        var wireBytes = 0
        val frame = StringBuilder()
        val draft = StringBuilder()
        var lastPreview = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val line = channel.readUTF8Line(524288) ?: error("Assistant stream ended before completion")
            wireBytes += line.toByteArray(Charsets.UTF_8).size + 1
            require(wireBytes <= 2097152) { "Assistant stream exceeded its byte budget" }
            if (line.startsWith("data:")) {
                if (frame.isNotEmpty()) frame.append('\n')
                frame.append(line.substring(5).removePrefix(" "))
                require(frame.length <= 262144)
            } else if (line.isEmpty() && frame.isNotEmpty()) {
                val event = Json.parseToJsonElement(frame.toString()).jsonObject
                frame.clear()
                when (event["type"]?.jsonPrimitive?.content) {
                    "response.output_text.delta" -> {
                        draft.append(event.getValue("delta").jsonPrimitive.content)
                        require(draft.length <= 65536)
                        val now = System.nanoTime()
                        if (now - lastPreview >= 50000000) {
                            onText(draft.toString())
                            lastPreview = now
                        }
                    }
                    "response.completed" -> {
                        val result = event.getValue("response").jsonObject
                        require(result.toString().toByteArray(Charsets.UTF_8).size <= 262144)
                        AssistantProtocol.output(result)
                        if (draft.isNotEmpty()) onText(draft.toString())
                        return result
                    }
                    "error", "response.failed", "response.incomplete", "response.refusal.delta", "response.refusal.done" -> error("Assistant stream failed or was refused")
                }
            }
        }
    }
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
