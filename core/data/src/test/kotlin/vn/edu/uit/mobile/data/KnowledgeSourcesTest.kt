package vn.edu.uit.mobile.data

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.model.*

class KnowledgeSourcesTest {
    @get:Rule val folder = TemporaryFolder()
    private fun saved(name: String, text: String): File = File(folder.root, name).apply {
        writeText(text)
        File(path + ".sha256").writeText(MessageDigest.getInstance("SHA-256").digest(readBytes()).joinToString("") { "%02x".format(it) })
    }
    private fun loader(files: Map<String, File>, replace: (() -> Unit)? = null) = createKnowledgeSourceLoader(object : OfflineDownloadStorage {
        override suspend fun load() = error("No journal reads")
        override suspend fun save(journal: OfflineDownloadJournal) = error("No university or journal writes")
        override suspend fun cached(course: Course, file: CourseFile): VerifiedOfflineFile? {
            val value = files[file.name] ?: return null
            val verified = VerifiedOfflineFile(value.path, value.length())
            replace?.invoke()
            return verified
        }
        override suspend fun remove(course: Course, file: CourseFile) = error("No document deletion")
        override suspend fun usedBytes(accountIds: Set<String>) = 0L
    })
    private fun sections(vararg names: String) = listOf(CourseSection(1, "Authored materials", listOf(CourseModule(1, 1, "Sources", "resource", "", files = names.map { CourseFile(it, "https://example.invalid/$it") }))))

    @Test fun notebookSourceExcludesExecutionOutputsAndSourceFileIsUnchanged() = runTest {
        val notebook = """{"nbformat":4,"cells":[{"cell_type":"markdown","source":["# Graph traversal\n","BFS uses a queue."]},{"cell_type":"code","source":"print('BFS')","outputs":[{"text":"OUTPUT_SENTINEL"}]}]}"""
        val file = saved("lesson.ipynb", notebook)
        val source = loader(mapOf("lesson.ipynb" to file)).load(KnowledgeFixtures.course, sections("lesson.ipynb"), emptyList()).single()
        assertTrue(source.text.contains("BFS uses a queue."))
        assertFalse(source.text.contains("OUTPUT_SENTINEL"))
        assertEquals(notebook, file.readText())
    }

    @Test fun replacementAfterCacheVerificationCannotEnterTheSourceCatalog() = runTest {
        val file = saved("lesson.txt", "Original teaching text")
        val sources = loader(mapOf("lesson.txt" to file)) { file.writeText("Replaced bytes") }.load(KnowledgeFixtures.course, sections("lesson.txt"), emptyList())
        assertTrue(sources.isEmpty())
    }

    @Test fun unreadableOrCorruptFilesDoNotHideOtherUsableSources() = runTest {
        val good = saved("good.txt", "BFS uses a queue.")
        val bad = saved("bad.txt", "Corrupt source").apply { File(path + ".sha256").delete() }
        val sources = loader(mapOf("good.txt" to good, "bad.txt" to bad)).load(KnowledgeFixtures.course, sections("bad.txt", "good.txt"), emptyList())
        assertEquals(listOf("good.txt"), sources.map { it.title })
    }
}
