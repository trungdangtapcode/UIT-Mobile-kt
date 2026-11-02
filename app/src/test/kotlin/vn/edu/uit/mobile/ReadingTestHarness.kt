package vn.edu.uit.mobile

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.lang.reflect.Proxy
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import vn.edu.uit.mobile.courses.*
import vn.edu.uit.mobile.data.*
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.model.*

/** Authored cached PDF + independently prerendered pages; platform PdfRenderer is tested on Android. */
internal class ReadingTestHarness(private val root: File, dispatcher: CoroutineDispatcher) {
    val course = ReadingFixtures.course
    val file = ReadingFixtures.file
    val ownerFlow = MutableStateFlow(listOf(Account("owner", "https://example.invalid", 1, "Synthetic student", PortalType.MOODLE)))
    val owners = object : AccountRepository {
        override val accounts = ownerFlow
        override suspend fun remove(accountId: String) = error("University mutation forbidden")
        override suspend fun saveSessions(sessions: List<PortalSession>) = error("University mutation forbidden")
    }
    var failWrite = false
    var committedFailure = false
    var failRead = false
    val values = mutableMapOf<String, String>()
    private val storage = object : StudyStorage {
        override fun read(key: String): String? {
            check(!failRead)
            return values[key]
        }
        override fun write(key: String, value: String) {
            check(!failWrite)
            values[key] = value
            check(!committedFailure)
        }
        override fun remove(key: String) {
            check(!failWrite)
            values.remove(key)
        }
        override fun removeAccount(accountId: String) = error("Account cleanup is explicitly simulated by the test")
    }
    val repo = LocalReadingRepository(storage, owners, dispatcher)
    val snapshot = MutableStateFlow(PortalSnapshot(courses = listOf(course), sections = mapOf("owner:1" to listOf(CourseSection(1, "Lecture 07", listOf(CourseModule(1, 1, "Graph traversal", "resource", "", files = listOf(file))))))))
    val universityCalls = mutableListOf<String>()
    private val portal = Proxy.newProxyInstance(PortalRepository::class.java.classLoader, arrayOf(PortalRepository::class.java)) { _, method, _ ->
        universityCalls += method.name
        if (method.name == "getSnapshot") snapshot else throw AssertionError("University operation forbidden: ${method.name}")
    } as PortalRepository
    val pdfFile = File(root, "synthetic.pdf")
    init {
        updateBytes(ReadingFixtures.pdf)
    }
    fun updateBytes(bytes: ByteArray) {
        root.mkdirs()
        pdfFile.writeBytes(bytes)
        File(pdfFile.path + ".sha256").writeText(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
    }
    private val spec = OfflineDownloadSpec(course, "section:1", "Lecture 07", file)
    val downloads = object : OfflineDownloadStorage {
        override suspend fun load() = OfflineDownloadJournal(listOf(OfflineDownloadItem(spec, OfflineDownloadStatus.SAVED, ReadingFixtures.pdf.size.toLong())))
        override suspend fun save(journal: OfflineDownloadJournal) = error("No download journal mutation expected")
        override suspend fun cached(course: Course, file: CourseFile) = if (course.accountId == "owner" && pdfFile.isFile) VerifiedOfflineFile(pdfFile.path, pdfFile.length()) else null
        override suspend fun remove(course: Course, file: CourseFile) = error("Source PDF deletion forbidden")
        override suspend fun usedBytes(accountIds: Set<String>) = pdfFile.length()
    }
    var pendingLoad: CompletableDeferred<Unit>? = null
    var failOpen = false
    var failPage: Int? = null
    var closed = 0
    var opened = 0
    val rendered = mutableListOf<Int>()
    private val loader = createReadingDocumentLoader(downloads, dispatcher)
    private val factory = ReadingPdfFactory {
        check(!failOpen)
        opened++
        object : ReadingPdf {
            override val pageCount = 3
            override fun render(page: Int): Bitmap {
                check(page != failPage)
                rendered += page
                val expected = javaClass.getResourceAsStream("/reading/document.sha256")!!.bufferedReader().use { it.readText().trim() }
                check(expected == ReadingFixtures.identity.version) { "Regenerate prerendered fixture pages" }
                return javaClass.getResourceAsStream("/reading/page-$page.png")!!.use { BitmapFactory.decodeStream(it)!! }
            }
            override fun close() {
                closed++
            }
        }
    }
    val vm = CourseReadingViewModel(
        repo,
        owners,
        portal,
        downloads,
        ReadingDocumentLoader { c, f ->
            pendingLoad?.await()
            loader.load(c, f)
        },
        factory,
        dispatcher
    ) { 1793586000 }
}
