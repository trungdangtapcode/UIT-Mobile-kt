package vn.edu.uit.mobile.data

import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.*
import vn.edu.uit.mobile.domain.*

/** Capture bounded verified bytes. Never download, follow URLs or modify source PDFs. */
fun createReadingDocumentLoader(storage: OfflineDownloadStorage, io: CoroutineDispatcher = Dispatchers.IO): ReadingDocumentLoader = ReadingDocumentLoader { course, file ->
    withContext(io) {
        val cached = storage.cached(course, file) ?: error("Verified download unavailable")
        val local = File(cached.path)
        require(local.isFile && local.length() in 1..25L * 1024 * 1024)
        val bytes = local.inputStream().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(65536)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= 25 * 1024 * 1024)
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        require(bytes.size >= 5 && bytes.copyOfRange(0, 5).toString(Charsets.US_ASCII) == "%PDF-")
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        check(File(local.path + ".sha256").takeIf { it.isFile }?.readText()?.trim() == hash)
        VerifiedReadingDocument(course, file, bytes, ReadingIdentity(course.accountId, offlineDownloadId(course, file), hash))
    }
}
