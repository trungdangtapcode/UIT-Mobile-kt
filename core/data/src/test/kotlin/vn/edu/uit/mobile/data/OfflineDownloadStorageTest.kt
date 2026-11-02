package vn.edu.uit.mobile.data

import kotlinx.coroutines.test.runTest
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.model.*

class OfflineDownloadStorageTest {
    @get:Rule val folder = TemporaryFolder()
    private class Journal : StudyStorage {
        val data = mutableMapOf<String, String>()
        override fun read(key: String) = data[key]
        override fun write(key: String, value: String) {
            data[key] = value
        }
        override fun remove(key: String) {
            data.remove(key)
        }
        override fun removeAccount(accountId: String) = Unit
    }
    private val course = Course(1, "one", "IT004", "Databases")
    private val file = CourseFile("Notes.pdf", "https://courses.example.org/notes.pdf")

    @Test fun checksumsGateAvailabilityAndDeletionIsScopedToTheExactResource() = runTest {
        val store = PrivateOfflineDownloadStorage(folder.root, Journal())
        val destination = materialDestination(folder.root, course, file)
        destination.parentFile!!.mkdirs()
        destination.writeText("Synthetic PDF bytes")
        assertNull(store.cached(course, file))
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(destination.readBytes()).joinToString("") { "%02x".format(it) }
        writeMaterialChecksum(destination, hash)
        assertNotNull(store.cached(course, file))
        assertNull(store.cached(course.copy(accountId = "other"), file))
        assertTrue(store.usedBytes(setOf("one")) >= destination.length())
        destination.appendText("corrupt")
        assertNull(store.cached(course, file))
        store.remove(course, file)
        assertFalse(destination.exists())
        assertFalse(java.io.File(destination.path + ".sha256").exists())
    }

    @Test fun privateJournalRoundTripsAndLongUnicodeNamesStayInsideTheAccountFolder() = runTest {
        val store = PrivateOfflineDownloadStorage(folder.root, Journal())
        val item = OfflineDownloadItem(OfflineDownloadSpec(course, "section:1", "Week 1", file), OfflineDownloadStatus.WAITING_WIFI)
        store.save(OfflineDownloadJournal(listOf(item), false))
        val loaded = store.load()
        assertFalse(loaded.wifiOnly)
        assertEquals(item.id, loaded.items.single().id)
        assertEquals(OfflineDownloadStatus.WAITING_WIFI, loaded.items.single().status)
        val destination = materialDestination(folder.root, course, file.copy(name = "../" + "Tài liệu ".repeat(70) + ".pdf"))
        assertTrue(destination.name.toByteArray().size <= 213)
        assertTrue(destination.name.endsWith(".pdf"))
        assertTrue(destination.canonicalFile.toPath().startsWith(folder.root.canonicalFile.toPath()))
    }

    @Test fun freeTimeBlockStorageIsolatesAccountSetsAndClearsWhenRequested() {
        val store = PrivateFreeTimeBlockStorage(Journal())
        val block = PersonalTimeBlock("b1", "Study group", 1_000L, 2_000L)
        store.save(setOf("b", "a"), listOf(block))
        assertEquals(listOf(block), store.load(setOf("a", "b")))
        assertTrue(store.load(setOf("a")).isEmpty())
        store.clear(setOf("a", "b"))
        assertTrue(store.load(setOf("a", "b")).isEmpty())
    }

    @Test fun backgroundQueueRunnerDownloadsQueuedItemsAndHonorsWifiGating() = runTest {
        val store = PrivateOfflineDownloadStorage(folder.root, Journal())
        val item = OfflineDownloadItem(OfflineDownloadSpec(course, "section:1", "Week 1", file), OfflineDownloadStatus.QUEUED)
        store.save(OfflineDownloadJournal(listOf(item), wifiOnly = true))
        val accounts = object : AccountRepository {
            override val accounts = kotlinx.coroutines.flow.MutableStateFlow(listOf(Account("one", "https://courses.example.org", 1, "Synthetic", PortalType.MOODLE)))
            override suspend fun remove(accountId: String) = Unit
            override suspend fun saveSessions(sessions: List<PortalSession>) = Unit
        }
        val portal = object : FakePortalRepository() {
            override suspend fun download(course: Course, file: CourseFile): vn.edu.uit.mobile.common.AppResult<DownloadedFile, vn.edu.uit.mobile.common.AppError> {
                val dest = materialDestination(folder.root, course, file)
                dest.parentFile!!.mkdirs()
                dest.writeText("PDF content")
                val hash = java.security.MessageDigest.getInstance("SHA-256").digest(dest.readBytes()).joinToString("") { "%02x".format(it) }
                writeMaterialChecksum(dest, hash)
                return vn.edu.uit.mobile.common.AppResult.Success(DownloadedFile(dest.path, hash))
            }
        }
        assertTrue(runOfflineDownloadQueue(portal, accounts, store) { false })
        assertEquals(OfflineDownloadStatus.WAITING_WIFI, store.load().items.single().status)
        assertFalse(runOfflineDownloadQueue(portal, accounts, store) { true })
        assertEquals(OfflineDownloadStatus.SAVED, store.load().items.single().status)
    }

    @Test fun offlineCourseSourceLoaderExtractsVerifiedPdfAndTextFilesOnly() = runTest {
        val store = PrivateOfflineDownloadStorage(folder.root, Journal())
        val loader = createOfflineCourseSourceLoader(store)
        val txtFile = CourseFile("Syllabus.md", "https://courses.example.org/syllabus.md")
        val pdfFile = CourseFile("Slides.pdf", "https://courses.example.org/slides.pdf")
        val notebookFile = CourseFile("Lab.ipynb", "https://courses.example.org/lab.ipynb")
        val unverifiedFile = CourseFile("Secret.txt", "https://courses.example.org/secret.txt")

        val txtDest = materialDestination(folder.root, course, txtFile).apply {
            parentFile!!.mkdirs()
            writeText("# Relational Algebra\nProjection and selection operators.")
        }
        writeMaterialChecksum(txtDest, java.security.MessageDigest.getInstance("SHA-256").digest(txtDest.readBytes()).joinToString("") { "%02x".format(it) })

        val compressedStream = java.io.ByteArrayOutputStream().use { out ->
            java.util.zip.DeflaterOutputStream(out).use { deflater ->
                deflater.write("BT /F1 12 Tf (Transactions preserve ACID consistency.) Tj ET".toByteArray(Charsets.ISO_8859_1))
            }
            out.toByteArray()
        }
        val pdfBytes = "%PDF-1.4\n1 0 obj\n<< /Filter /FlateDecode >>\nstream\n".toByteArray(Charsets.ISO_8859_1) +
            compressedStream +
            "\nendstream\nendobj\n%%EOF".toByteArray(Charsets.ISO_8859_1)
        val pdfDest = materialDestination(folder.root, course, pdfFile).apply {
            parentFile!!.mkdirs()
            writeBytes(pdfBytes)
        }
        writeMaterialChecksum(pdfDest, java.security.MessageDigest.getInstance("SHA-256").digest(pdfDest.readBytes()).joinToString("") { "%02x".format(it) })

        materialDestination(folder.root, course, unverifiedFile).apply {
            parentFile!!.mkdirs()
            writeText("Unverified text without checksum")
        }

        val notebookDest = materialDestination(folder.root, course, notebookFile).apply {
            parentFile!!.mkdirs()
            writeText("""{"nbformat":4,"metadata":{"owner":"METADATA_SENTINEL"},"cells":[{"cell_type":"markdown","source":["# Circuit lesson\n","Measure the prepared state."]},{"cell_type":"code","source":"if ready:\n    measure()","outputs":[{"text":"OUTPUT_SENTINEL"}]}]}""")
        }
        writeMaterialChecksum(notebookDest, java.security.MessageDigest.getInstance("SHA-256").digest(notebookDest.readBytes()).joinToString("") { "%02x".format(it) })

        val section = CourseSection(1, "Week 1", listOf(CourseModule(10, course.id, "Materials", "resource", "", files = listOf(txtFile, pdfFile, notebookFile, unverifiedFile))))
        val sources = loader.load(course, listOf(section), emptyList())
        assertEquals(3, sources.size)
        assertTrue(sources.all { it.id.startsWith("file:") && it.text.length <= 12000 })
        assertTrue(sources.any { it.title == "Syllabus.md" && it.text.contains("Projection and selection operators.") })
        assertTrue(sources.any { it.title == "Slides.pdf" && it.text.contains("Transactions preserve ACID consistency.") })
        val notebook = sources.single { it.title == "Lab.ipynb" }
        assertTrue(notebook.text.contains("if ready:\n    measure()"))
        assertFalse(notebook.text.contains("METADATA_SENTINEL") || notebook.text.contains("OUTPUT_SENTINEL"))
    }
}
