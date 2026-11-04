package vn.edu.uit.mobile.data

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import vn.edu.uit.mobile.domain.*

/** Local verified bytes only. Notebook cells are never executed; outputs and metadata are excluded. */
fun createPassageDocumentLoader(storage: OfflineDownloadStorage): PassageDocumentLoader = PassageDocumentLoader { spec ->
    withContext(Dispatchers.IO) {
        val cached = storage.cached(spec.course, spec.file) ?: error("Verified local download unavailable")
        val file = File(cached.path)
        require(file.isFile && file.length() in 1..8L * 1024 * 1024)
        val bytes = file.inputStream().use { input ->
            val result = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(32768)
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = input.read(buffer)
                if (n < 0) break
                require(result.size() + n <= 8 * 1024 * 1024)
                result.write(buffer, 0, n)
            }
            result.toByteArray()
        }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        require(File(file.path + ".sha256").readText().trim() == hash)
        PassageDocuments.extract(spec, bytes, hash)
    }
}

internal object PassageDocuments {
    fun extract(spec: OfflineDownloadSpec, bytes: ByteArray, hash: String): PassageDocument {
        require(bytes.size in 1..8 * 1024 * 1024)
        val extension = spec.file.name.substringAfterLast('.', "").lowercase()
        var remaining = PassageRules.DOCUMENT_CHARACTERS
        var partial = false
        fun part(ordinal: Int, kind: PassagePartKind, text: String): PassagePart? {
            val length = minOf(remaining, text.length)
            val safe = if (length > 0 && length < text.length && text[length - 1].isHighSurrogate() && text[length].isLowSurrogate()) length - 1 else length
            if (safe < text.length) partial = true
            remaining -= safe
            return text.take(safe).takeIf { it.isNotBlank() }?.let { PassagePart(ordinal, kind, it) }
        }
        fun utf8() = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF").also { require('\u0000' !in it) }
        val parts = when (extension) {
            "ipynb" -> {
                val root = Json.parseToJsonElement(utf8()).jsonObject
                require(root.getValue("nbformat").jsonPrimitive.let { !it.isString && it.int == 4 })
                root.getValue("cells").jsonArray.also { require(it.size <= 10000) }.mapIndexedNotNull { i, value ->
                    val cell = value.jsonObject
                    val kind = when (cell.getValue("cell_type").jsonPrimitive.content) {
                        "markdown" -> PassagePartKind.NOTEBOOK_MARKDOWN
                        "code" -> PassagePartKind.NOTEBOOK_CODE
                        else -> return@mapIndexedNotNull null
                    }
                    fun string(v: JsonElement) = v.jsonPrimitive.also { require(it.isString) }.content
                    val source = cell.getValue("source").let { if (it is JsonArray) it.joinToString("") { v -> string(v) } else string(it) }
                    part(i + 1, kind, source)
                }
            }
            "pdf" -> {
                // This legacy extractor has no page/font-map contract. Never invent a PDF page locator.
                partial = true
                listOfNotNull(part(1, PassagePartKind.PDF_EXTRACT, extractPdfText(bytes, PassageRules.DOCUMENT_CHARACTERS)))
            }
            "html", "htm" -> listOfNotNull(part(1, PassagePartKind.TEXT, com.fleeksoft.ksoup.Ksoup.parse(utf8()).apply { select("script,style,noscript").remove() }.text())).also { partial = true }
            "txt", "md", "markdown", "csv", "json", "xml", "py", "kt", "java", "c", "cpp", "h", "js", "ts", "sql", "r", "yaml", "yml" -> listOfNotNull(part(1, PassagePartKind.TEXT, utf8()))
            else -> error("Unsupported local document format")
        }
        require(parts.isNotEmpty()) { "No supported source text" }
        return PassageDocument(spec, hash, parts, partial)
    }
}
