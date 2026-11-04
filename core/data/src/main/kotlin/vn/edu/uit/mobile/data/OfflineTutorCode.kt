package vn.edu.uit.mobile.data

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import vn.edu.uit.mobile.domain.*

/** Reads verified local teaching files only. Notebook cells are never executed. */
fun createOfflineTutorCodeLoader(storage: OfflineDownloadStorage): OfflineTutorCodeLoader = OfflineTutorCodeLoader { course, sections, assignments ->
    withContext(Dispatchers.IO) {
        val files = (sections.flatMap { it.modules }.filter { it.courseId == course.id }.flatMap { it.files } + assignments.filter { it.courseId == course.id && it.accountId == course.accountId }.flatMap { it.files }).distinctBy { it.url }
        val sources = mutableListOf<TutorCodeSource>()
        var skipped = (files.size - 24).coerceAtLeast(0)
        var characters = 0
        for (file in files.take(24)) {
            currentCoroutineContext().ensureActive()
            val ext = file.name.substringAfterLast('.', "").lowercase()
            if (ext != "ipynb" && ext !in tutorExtensions) {
                skipped++
                continue
            }
            val entries = try {
                val verified = storage.cached(course, file) ?: continue
                val local = File(verified.path)
                if (!local.isFile || local.length() !in 1..8L * 1024 * 1024) {
                    skipped++
                    continue
                }
                val bytes = local.inputStream().use { stream ->
                    val buffer = ByteArray(8 * 1024 * 1024 + 1)
                    var count = 0
                    while (count < buffer.size) {
                        currentCoroutineContext().ensureActive()
                        val read = stream.read(buffer, count, buffer.size - count)
                        if (read < 0) break
                        count += read
                    }
                    require(count <= 8 * 1024 * 1024)
                    buffer.copyOf(count)
                }
                val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                // Recheck the captured bytes: a background download may replace the file after cached().
                if (File(local.path + ".sha256").takeIf { it.isFile }?.readText()?.trim() != hash) {
                    skipped++
                    continue
                }
                val id = offlineDownloadId(course, file).take(32)
                val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
                if (ext == "ipynb") {
                    notebookCodeSources(text, id, file.name.take(200), hash)
                } else {
                    listOf(TutorCodeSource(id, file.name.take(200), text, tutorExtensions.getValue(ext), hash))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                skipped++
                continue
            }
            for (entry in entries) {
                if (entry.code.isBlank()) continue
                if (entry.code.length > 48_000 || sources.size >= 60 || characters + entry.code.length > 512_000) {
                    skipped++
                    continue
                }
                sources += entry
                characters += entry.code.length
            }
        }
        TutorCodeCatalog(sources, skipped)
    }
}

private val tutorExtensions = mapOf("py" to "Python", "kt" to "Kotlin", "java" to "Java", "c" to "C", "h" to "C", "cpp" to "C++", "js" to "JavaScript", "ts" to "TypeScript", "sql" to "SQL", "rs" to "Rust", "go" to "Go", "sh" to "Shell")

internal fun notebookCodeSources(text: String, id: String, title: String, hash: String): List<TutorCodeSource> {
    val notebook = Json.parseToJsonElement(text).jsonObject
    val version = notebook.getValue("nbformat").jsonPrimitive
    require(!version.isString && version.int == 4)
    val cells = notebook.getValue("cells").jsonArray
    require(cells.size <= 10_000)
    val metadata = notebook["metadata"] as? JsonObject
    val languageName = ((metadata?.get("language_info") as? JsonObject)?.get("name") as? JsonPrimitive)?.content.orEmpty()
    val language = TutorRules.codeLanguages.firstOrNull { it.equals(languageName, true) } ?: "Other"
    return cells.mapIndexedNotNull { index, raw ->
        val cell = raw.jsonObject
        if ((cell["cell_type"] as? JsonPrimitive)?.content != "code") return@mapIndexedNotNull null
        fun source(element: JsonElement) = element.jsonPrimitive.let {
            require(it.isString)
            it.content
        }
        val code = when (val value = cell.getValue("source")) {
            is JsonArray -> value.joinToString("") { source(it) }
            else -> source(value)
        }
        TutorCodeSource("$id:cell:${index + 1}", title, code, language, hash, index + 1)
    }
}
