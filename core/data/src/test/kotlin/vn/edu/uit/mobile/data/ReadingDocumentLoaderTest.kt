package vn.edu.uit.mobile.data

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import vn.edu.uit.mobile.domain.*

class ReadingDocumentLoaderTest {
    @get:Rule val folder = TemporaryFolder()
    private val storage = object : StudyStorage {
        override fun read(key: String): String? = null
        override fun write(key: String, value: String) = error("No journal write expected")
        override fun remove(key: String) = error("No journal write expected")
        override fun removeAccount(accountId: String) = error("No account write expected")
    }
    private fun cached(bytes: ByteArray = ReadingFixtures.pdf): PrivateOfflineDownloadStorage {
        val path = materialDestination(folder.root, ReadingFixtures.course, ReadingFixtures.file).apply {
            parentFile!!.mkdirs()
            writeBytes(bytes)
        }
        writeMaterialChecksum(path, MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
        return PrivateOfflineDownloadStorage(folder.root, storage)
    }

    @Test fun capturesVerifiedOriginalBytesAndContentVersionWithoutChangingSource() = runTest {
        val loader = createReadingDocumentLoader(cached())
        val value = loader.load(ReadingFixtures.course, ReadingFixtures.file)
        assertArrayEquals(ReadingFixtures.pdf, value.bytes)
        assertEquals(ReadingFixtures.identity, value.identity)
        assertArrayEquals(ReadingFixtures.pdf, materialDestination(folder.root, ReadingFixtures.course, ReadingFixtures.file).readBytes())
    }

    @Test fun corruptMissingAnotherOwnerAndNonPdfInputsAreRejected() = runTest {
        val cache = cached()
        val loader = createReadingDocumentLoader(cache)
        assertTrue(runCatching { loader.load(ReadingFixtures.course.copy(accountId = "other"), ReadingFixtures.file) }.isFailure)
        val path = materialDestination(folder.root, ReadingFixtures.course, ReadingFixtures.file)
        path.appendText("tamper")
        assertTrue(runCatching { loader.load(ReadingFixtures.course, ReadingFixtures.file) }.isFailure)
        cached("not a PDF".toByteArray())
        assertTrue(runCatching { loader.load(ReadingFixtures.course, ReadingFixtures.file) }.isFailure)
        path.delete()
        assertTrue(runCatching { loader.load(ReadingFixtures.course, ReadingFixtures.file) }.isFailure)
    }

    @Test fun replacementAfterCacheLookupCannotBypassCapturedByteVerification() = runTest {
        val cache = cached()
        val racing = object : OfflineDownloadStorage by cache {
            override suspend fun cached(course: vn.edu.uit.mobile.model.Course, file: vn.edu.uit.mobile.model.CourseFile): VerifiedOfflineFile? {
                val value = cache.cached(course, file)
                value?.let { File(it.path).appendText("unverified replacement") }
                return value
            }
        }
        assertTrue(runCatching { createReadingDocumentLoader(racing).load(ReadingFixtures.course, ReadingFixtures.file) }.isFailure)
    }
}
