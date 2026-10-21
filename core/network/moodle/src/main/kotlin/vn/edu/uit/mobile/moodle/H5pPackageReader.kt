package vn.edu.uit.mobile.moodle

import com.fleeksoft.ksoup.Ksoup
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import kotlinx.serialization.json.*
import vn.edu.uit.mobile.common.*
import vn.edu.uit.mobile.model.*
import vn.edu.uit.mobile.network.*

class H5pPackageReader {
    fun read(bytes: ByteArray): H5pSummary {
        require(bytes.size <= 25 * 1024 * 1024) { "H5P packages are limited to 25 MB" }
        val json = mutableMapOf<String, JsonElement>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var count = 0
            var total = 0L
            while (true) {
                val entry = zip.nextEntry ?: break
                require(++count <= 20000) { "Too many H5P entries" }
                require(!entry.name.startsWith('/') && entry.name.split('/').none { it == ".." }) { "Unsafe package path" }
                if (entry.name in setOf("h5p.json", "content/content.json")) {
                    require(!json.containsKey(entry.name)) { "Duplicate H5P metadata" }
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val n = zip.read(buffer)
                        if (n < 0) break
                        require(output.size() + n <= 5 * 1024 * 1024) { "H5P metadata exceeds 5 MB" }
                        output.write(buffer, 0, n)
                    }
                    total += output.size()
                    require(total <= 100L * 1024 * 1024) { "H5P inflated size exceeds limit" }
                    json[entry.name] = parseJson(output.toByteArray())
                } else {
                    // Bound inflated data too, including ZIP entries we do not inspect.
                    val buffer = ByteArray(8192)
                    while (true) {
                        val n = zip.read(buffer)
                        if (n < 0) break
                        total += n
                        require(total <= 100L * 1024 * 1024) { "H5P inflated size exceeds limit" }
                    }
                }
                zip.closeEntry()
            }
        }
        val manifest = json["h5p.json"] ?: throw InvalidResponseException("H5P manifest is missing")
        val content = json["content/content.json"] ?: throw InvalidResponseException("H5P content is missing")
        val entries = content.list("presentation").ifEmpty { content.field("presentation").list("slides") }.ifEmpty { content.list("chapters") }.ifEmpty { listOf(content) }
        return H5pSummary(
            manifest.str("title"),
            manifest.str("mainLibrary"),
            entries.take(1000).mapIndexed { index, entry ->
                val text = linkedSetOf<String>()
                val media = linkedSetOf<String>()
                fun walk(value: JsonElement, key: String, depth: Int) {
                    require(depth <= 100) { "H5P content nesting exceeds limit" }
                    when (value) {
                        is JsonObject -> value.forEach { (k, v) -> walk(v, k, depth + 1) }
                        is JsonArray -> value.forEach { walk(it, key, depth + 1) }
                        is JsonPrimitive -> {
                            val raw = value.content
                            if (key.lowercase() in setOf("alt", "answer", "caption", "description", "heading", "label", "question", "taskdescription", "text", "title")) {
                                val doc = Ksoup.parse(raw)
                                val clean = doc.text().trim()
                                if (clean.isNotEmpty() && text.size < 1000) text += clean
                                doc.select("a[href], iframe[src], video[src], audio[src]").forEach { e ->
                                    UrlSanitizer.credentialFreeUrl(e.attr(if (e.hasAttr("href")) "href" else "src"))?.let { if (media.size < 500) media += it }
                                }
                            }
                            if (key.lowercase() in setOf("path", "url", "src", "source") && media.size < 500) {
                                val url = UrlSanitizer.credentialFreeUrl(raw)
                                if (url != null) {
                                    media += url
                                } else if (!raw.contains(":") && !raw.startsWith('/') && raw.split('/').none { it == ".." } && raw.isNotBlank()) {
                                    media += "content/$raw"
                                }
                            }
                        }
                    }
                }
                walk(entry, "", 0)
                H5pEntry(entry.str("title").ifEmpty { "Slide ${index + 1}" }, text.toList(), media.toList())
            }
        )
    }
}
