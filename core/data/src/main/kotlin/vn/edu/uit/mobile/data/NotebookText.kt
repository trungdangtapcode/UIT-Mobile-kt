package vn.edu.uit.mobile.data

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.serialization.json.*

/** Reads notebook v4 source without executing cells or importing outputs, metadata or attachments. */
internal fun extractNotebookText(bytes: ByteArray): String? = runCatching {
    require(bytes.size in 1..8 * 1024 * 1024)
    val json = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
    val notebook = Json.parseToJsonElement(json).jsonObject
    val format = notebook.getValue("nbformat").jsonPrimitive
    require(!format.isString && format.int == 4)
    val cells = notebook.getValue("cells").jsonArray
    require(cells.size <= 10_000)
    val out = StringBuilder()
    for ((index, element) in cells.withIndex()) {
        val cell = element.jsonObject
        val type = cell.getValue("cell_type").jsonPrimitive.let {
            require(it.isString)
            it.content
        }
        if (type !in setOf("markdown", "code")) continue
        fun string(value: JsonElement) = value.jsonPrimitive.let {
            require(it.isString)
            it.content
        }
        val source = when (val value = cell.getValue("source")) {
            is JsonArray -> value.joinToString("") { string(it) }
            else -> string(value)
        }
        if (source.isBlank()) continue
        val heading = if (type == "code") "Code" else "Markdown"
        val part = "${if (out.isEmpty()) "" else "\n\n"}[$heading cell ${index + 1}]\n$source"
        out.append(part.take(12_000 - out.length))
        if (out.length == 12_000) break
    }
    out.toString().trim().takeIf { it.isNotBlank() }
}.getOrNull()
