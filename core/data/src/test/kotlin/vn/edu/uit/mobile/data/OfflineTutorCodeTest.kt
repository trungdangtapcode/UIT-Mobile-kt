package vn.edu.uit.mobile.data

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.model.*

class OfflineTutorCodeTest {
    @get:Rule val folder = TemporaryFolder()
    private val course = Course(1, "owner", "SYNTHETIC", "Synthetic")
    private val journal = object : StudyStorage {
        val values = mutableMapOf<String, String>()
        override fun read(key: String) = values[key]
        override fun write(key: String, value: String) {
            values[key] = value
        }
        override fun remove(key: String) {
            values.remove(key)
        }
        override fun removeAccount(accountId: String) = error("Account mutation forbidden")
    }
    private val storage by lazy { PrivateOfflineDownloadStorage(folder.root, journal) }
    private fun file(name: String, bytes: ByteArray): CourseFile {
        val item = CourseFile(name, "https://example.invalid/$name")
        val destination = materialDestination(folder.root, course, item).apply {
            parentFile!!.mkdirs()
            writeBytes(bytes)
        }
        writeMaterialChecksum(destination, MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
        return item
    }
    private fun sections(files: List<CourseFile>) = listOf(CourseSection(1, "Synthetic lab", listOf(CourseModule(1, course.id, "Files", "folder", "", files = files))))

    @Test fun notebookImportsOnlyIndividualCodeCellsWithOriginalCellNumbersAndNoOutputs() = runTest {
        val notebook = file("Lab.ipynb", """{"nbformat":4,"metadata":{"authors":[{"name":"PRIVATE_AUTHOR_SENTINEL"}],"language_info":{"name":"python"}},"cells":[{"cell_type":"markdown","source":"NOT_CODE_SENTINEL"},{"cell_type":"code","source":["if ready:\n","\traise RuntimeError('never execute')"],"outputs":[{"text":"PRIVATE_OUTPUT_SENTINEL"}]},{"cell_type":"code","source":"return value"}]}""".toByteArray())
        val result = createOfflineTutorCodeLoader(storage).load(course, sections(listOf(notebook)), emptyList())
        assertEquals(listOf(2, 3), result.sources.map { it.cell })
        assertEquals("if ready:\n\traise RuntimeError('never execute')", result.sources.first().code)
        assertEquals("Python", result.sources.first().codeLanguage)
        assertEquals(64, result.sources.first().fileVersion.length)
        assertTrue(result.sources.all { "SENTINEL" !in it.code })
    }

    @Test fun unverifiedUnsupportedMalformedAndOversizedSourcesNeverEnterTheCodeCatalog() = runTest {
        val valid = file("example.py", TutorFixtures.code.toByteArray())
        val bad = file("bad.ipynb", "not JSON".toByteArray())
        val huge = file("long.py", "x".repeat(48001).toByteArray())
        val invalidUtf = file("invalid.py", byteArrayOf(0xc3.toByte(), 0x28))
        val unsupported = file("private.pdf", "Not code".toByteArray())
        val unverified = file("unverified.py", "PRIVATE_UNVERIFIED_SENTINEL".toByteArray())
        File(materialDestination(folder.root, course, unverified).path + ".sha256").delete()
        val result = createOfflineTutorCodeLoader(storage).load(course, sections(listOf(valid, bad, huge, invalidUtf, unsupported, unverified)), emptyList())
        assertEquals(listOf(TutorFixtures.code), result.sources.map { it.code })
        assertTrue(result.skipped >= 4)
    }

    @Test fun replacingAFileAfterTheCacheCheckCannotBypassCapturedByteVerification() = runTest {
        val item = file("race.py", TutorFixtures.code.toByteArray())
        val racing = object : OfflineDownloadStorage by storage {
            override suspend fun cached(course: Course, file: CourseFile): VerifiedOfflineFile? {
                val verified = storage.cached(course, file)
                verified?.let { File(it.path).writeText("UNVERIFIED_REPLACEMENT_SENTINEL") }
                return verified
            }
        }
        val result = createOfflineTutorCodeLoader(racing).load(course, sections(listOf(item)), emptyList())
        assertTrue(result.sources.isEmpty())
    }

    @Test fun anotherOwnerCannotReadTheSameCourseFileAndNotebookCellLimitsAreReported() = runTest {
        val item = file("code.py", TutorFixtures.code.toByteArray())
        assertTrue(createOfflineTutorCodeLoader(storage).load(course.copy(accountId = "other"), sections(listOf(item)), emptyList()).sources.isEmpty())
        val cells = (1..65).joinToString(",") { """{"cell_type":"code","source":"value = $it"}""" }
        val notebook = file("many.ipynb", """{"nbformat":4,"cells":[$cells]}""".toByteArray())
        val result = createOfflineTutorCodeLoader(storage).load(course, sections(listOf(notebook)), emptyList())
        assertEquals(60, result.sources.size)
        assertEquals(5, result.skipped)
        assertTrue(result.sources.all { it.codeLanguage == "Other" })
    }
}
