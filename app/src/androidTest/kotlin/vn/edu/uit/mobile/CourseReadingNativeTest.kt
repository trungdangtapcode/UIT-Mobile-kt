package vn.edu.uit.mobile

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.lang.reflect.Proxy
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import vn.edu.uit.mobile.common.SessionStore
import vn.edu.uit.mobile.courses.*
import vn.edu.uit.mobile.data.*
import vn.edu.uit.mobile.designsystem.*
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.model.*

/** Real Android PdfRenderer, Compose and Keystore; all documents/accounts/notes are authored fixtures. */
@RunWith(AndroidJUnit4::class)
class CourseReadingNativeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun cleanup(name: String, alias: String) {
        context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        context.deleteSharedPreferences(name)
        KeyStore.getInstance("AndroidKeyStore").apply {
            load(null)
            deleteEntry(alias)
        }
    }

    @Test fun rendererReadsDifferentPagesAndRejectsLockedOrCorruptPdfWithoutChangingOriginal() {
        val original = ReadingFixtures.pdf.copyOf()
        val document = NativePdfDocument(original, context.cacheDir)
        try {
            assertEquals(3, document.pageCount)
            val first = document.render(1)
            val second = document.render(2)
            assertTrue(first.width > 0 && first.height > 0 && first.width <= 4096 && first.height <= 4096)
            assertFalse(first.sameAs(second))
            assertNotEquals(first.getPixel(0, 0), first.getPixel(80, 80))
            assertTrue(runCatching { document.render(0) }.isFailure)
        } finally {
            document.close()
        }
        assertTrue(runCatching { document.render(1) }.isFailure)
        assertArrayEquals(ReadingFixtures.pdf, original)
        val locked = InstrumentationRegistry.getInstrumentation().context.assets.open("reading-locked.pdf").use { it.readBytes() }
        assertTrue(runCatching { NativePdfDocument(locked, context.cacheDir).close() }.isFailure)
        assertTrue(runCatching { NativePdfDocument("%PDF-corrupt".toByteArray(), context.cacheDir).close() }.isFailure)
        // Constructor failure must release its descriptor so a later valid PDF still opens.
        NativePdfDocument(original, context.cacheDir).use { assertEquals(3, it.pageCount) }
    }

    @Test fun encryptedReadingRecordsRestoreExportAndRemoveByOwner() = runBlocking {
        val suffix = UUID.randomUUID().toString()
        val name = "reading-native-$suffix"
        val alias = "uit.reading.test.$suffix"
        try {
            val storage = StudyPrivateStorage(context, name, alias)
            val sessions = object : SessionStore {
                override val sessions = MutableStateFlow(listOf("one", "two").map { PortalSession(Account(it, "https://example.invalid", 1, "Synthetic", PortalType.MOODLE), "synthetic") })
                override suspend fun save(sessions: List<PortalSession>) {
                    this.sessions.value = sessions
                }
                override suspend fun remove(accountId: String) {
                    sessions.value = sessions.value.filterNot { it.account.id == accountId }
                }
            }
            val snapshots = object : SnapshotStore {
                override fun observe(accountId: String) = flowOf(PortalSnapshot())
                override suspend fun read(accountId: String) = PortalSnapshot()
                override suspend fun write(accountId: String, snapshot: PortalSnapshot) = Unit
                override suspend fun delete(accountId: String) = Unit
            }
            val owners = VaultAccountRepository(sessions, snapshots, onRemove = storage::removeAccount)
            val repo = LocalReadingRepository(storage, owners)
            fun record(owner: String) = ReadingFixtures.initial.copy(identity = ReadingFixtures.identity.copy(accountId = owner, documentId = offlineDownloadId(ReadingFixtures.course.copy(accountId = owner), ReadingFixtures.file)))
            val saved = repo.save(record("one"), record("one").copy(position = ReadingPosition(2, 2f, .2f, .4f), bookmarks = setOf(1, 2), notes = listOf(ReadingFixtures.note.copy(text = "Private synthetic native note"))))
            repo.save(record("two"), record("two"))
            val restored = LocalReadingRepository(StudyPrivateStorage(context, name, alias), owners).load(saved.identity, saved.title, 1, 3)
            assertEquals(saved, restored)
            assertFalse(context.getSharedPreferences(name, Context.MODE_PRIVATE).all.values.any { it.toString().contains("Private synthetic native note") || it.toString().contains(saved.title) })
            val exported = repo.export(restored)
            assertTrue(exported.contains("Private synthetic native note"))
            assertFalse(exported.contains("\"accountId\""))
            assertFalse(exported.contains("example.invalid"))
            owners.remove("one")
            val remaining = LocalReadingRepository(storage, owners).collections.first()
            assertEquals(listOf("two"), remaining.map { it.accountId })
            assertTrue(remaining.single().records.single().notes.isEmpty())
            assertEquals(1, context.getSharedPreferences(name, Context.MODE_PRIVATE).all.size)
        } finally {
            cleanup(name, alias)
        }
    }

    @Test fun actualReaderNavigatesPinsSavesRestoresExportsAndClearsRemovedOwner() {
        val suffix = UUID.randomUUID().toString()
        val name = "reading-ui-$suffix"
        val alias = "uit.reading.ui.$suffix"
        val root = File(context.cacheDir, "reading-ui-$suffix").apply { mkdirs() }
        val pdf = File(root, "course.pdf").apply { writeBytes(ReadingFixtures.pdf) }
        File(pdf.path + ".sha256").writeText(digest(ReadingFixtures.pdf))
        val connected = MutableStateFlow(listOf(Account("owner", "https://example.invalid", 1, "Synthetic student", PortalType.MOODLE)))
        val owners = object : AccountRepository {
            override val accounts = connected
            override suspend fun remove(accountId: String) = error("University writes forbidden")
            override suspend fun saveSessions(sessions: List<PortalSession>) = error("University writes forbidden")
        }
        val portal = Proxy.newProxyInstance(PortalRepository::class.java.classLoader, arrayOf(PortalRepository::class.java)) { _, method, _ -> if (method.name == "getSnapshot") MutableStateFlow(PortalSnapshot()) else error("University operation forbidden") } as PortalRepository
        val downloads = object : OfflineDownloadStorage {
            override suspend fun load() = OfflineDownloadJournal()
            override suspend fun save(journal: OfflineDownloadJournal) = error("Download mutation forbidden")
            override suspend fun cached(course: Course, file: CourseFile) = VerifiedOfflineFile(pdf.path, pdf.length())
            override suspend fun remove(course: Course, file: CourseFile) = error("PDF mutation forbidden")
            override suspend fun usedBytes(accountIds: Set<String>) = pdf.length()
        }
        val vm = CourseReadingViewModel(LocalReadingRepository(StudyPrivateStorage(context, name, alias), owners), owners, portal, downloads, createReadingDocumentLoader(downloads), nativeReadingPdfFactory(context.cacheDir))
        val models = ViewModelStore().apply { put("reader", vm) }
        try {
            compose.setContent { UitTheme(dark = true, dynamic = false) { WorkspaceFrame(2, {}, {}, "Synthetic student", true, false, 1793586000) { CourseReadingScreen(vm) } } }
            compose.runOnIdle { vm.open(ReadingFixtures.course, ReadingFixtures.file) }
            ready(vm)
            screenshot("reading-native-page")
            compose.onNodeWithContentDescription("Next page").performScrollTo().performClick()
            ready(vm, 2)
            compose.onNodeWithContentDescription("Bookmark this page").performClick()
            compose.waitUntil(15000) { vm.uiState.value.record?.bookmarks == setOf(2) && !vm.uiState.value.saving }
            compose.onNodeWithTag("reading-content").performScrollToNode(hasText("Add note"))
            compose.onNodeWithText("Add note").performClick()
            compose.onNodeWithTag("reading-page").performTouchInput { click(center) }
            compose.onNodeWithTag("reading-note-editor").performTextInput("Synthetic native page note")
            compose.onNodeWithTag("reading-save-note").performClick()
            compose.waitUntil(15000) { vm.uiState.value.record?.notes?.size == 1 && vm.uiState.value.draft == null && !vm.uiState.value.saving }
            compose.onNodeWithTag("reading-page").performTouchInput {
                pinch(center - Offset(40f, 0f), center + Offset(40f, 0f), center - Offset(100f, 0f), center + Offset(100f, 0f))
            }
            compose.waitUntil(15000) { vm.uiState.value.position.zoom > 1f }
            compose.onNodeWithText("Fit page").performScrollTo().performClick()
            compose.onNodeWithTag("reading-page").performScrollTo().performTouchInput { doubleClick(center) }
            compose.waitUntil(15000) { vm.uiState.value.position.zoom > 1f }
            compose.runOnIdle { vm.savePosition() }
            compose.waitUntil(15000) { !vm.uiState.value.dirty && !vm.uiState.value.saving }
            compose.runOnIdle { vm.libraryFromDocument() }
            compose.waitUntil(15000) { vm.uiState.value.record == null && !vm.uiState.value.loading }
            compose.runOnIdle { vm.open(ReadingFixtures.course, ReadingFixtures.file) }
            ready(vm, 2)
            assertEquals(1, vm.uiState.value.record!!.notes.size)
            assertEquals(setOf(2), vm.uiState.value.record!!.bookmarks)
            assertEquals(2f, vm.uiState.value.position.zoom)
            screenshot("reading-native-restored")
            compose.runOnIdle { vm.export { _, _ -> } }
            compose.waitUntil(15000) { vm.pendingExport != null }
            val destination = File(root, "chosen-notes.json")
            assertTrue(runBlocking { vm.exportTo { destination.writeText(it) } })
            File(context.filesDir, "auth-smoke/reading-export.json").apply {
                parentFile!!.mkdirs()
                writeText(destination.readText())
            }
            assertTrue(destination.readText().contains("Synthetic native page note"))
            assertFalse(destination.readText().contains("example.invalid"))
            assertArrayEquals(ReadingFixtures.pdf, pdf.readBytes())
            compose.runOnIdle { connected.value = emptyList() }
            compose.waitUntil(15000) { vm.uiState.value.image == null && vm.uiState.value.record == null }
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { models.clear() }
            root.deleteRecursively()
            cleanup(name, alias)
        }
    }
    private fun ready(vm: CourseReadingViewModel, page: Int = 1) {
        compose.waitUntil(15000) { vm.uiState.value.image != null && vm.uiState.value.position.page == page && !vm.uiState.value.loading && !vm.uiState.value.saving }
    }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        val destination = File(context.filesDir, "auth-smoke/$name.png").apply { parentFile!!.mkdirs() }
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        destination.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
