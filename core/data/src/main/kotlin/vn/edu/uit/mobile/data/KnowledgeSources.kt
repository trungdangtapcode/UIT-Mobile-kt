package vn.edu.uit.mobile.data

import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.*
import vn.edu.uit.mobile.domain.*

/** Capture bounded bytes and verify their digest again before deriving an inspectable source copy. */
fun createKnowledgeSourceLoader(storage: OfflineDownloadStorage): OfflineCourseSourceLoader = OfflineCourseSourceLoader { course, sections, assignments ->
    withContext(Dispatchers.IO) {
        val files = (sections.flatMap { it.modules }.filter { it.courseId == course.id }.flatMap { it.files } + assignments.filter { it.accountId == course.accountId && it.courseId == course.id }.flatMap { it.files }).distinctBy { it.url }.take(24)
        files.mapNotNull { file ->
            currentCoroutineContext().ensureActive()
            try {
                val verified = storage.cached(course, file) ?: return@mapNotNull null
                val local = File(verified.path)
                if (!local.isFile || local.length() !in 1..8L * 1024 * 1024) return@mapNotNull null
                val bytes = local.inputStream().use { input ->
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(32768)
                    while (out.size() <= 8 * 1024 * 1024) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        out.write(buffer, 0, count)
                    }
                    out.toByteArray()
                }
                if (bytes.size > 8 * 1024 * 1024) return@mapNotNull null
                val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                if (File(local.path + ".sha256").readText().trim() != hash) return@mapNotNull null
                val text = extractOfflineDocumentText(file.name, bytes)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                StudySource("file:${offlineDownloadId(course, file).take(32)}", file.name.take(200), text)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }
    }
}
