package vn.edu.uit.mobile

import android.app.Application
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.*
import vn.edu.uit.mobile.courses.*
import vn.edu.uit.mobile.designsystem.*
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.model.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OfflineDownloadsDesignTest {
    @get:Rule val compose = createComposeRule()

    @After fun reset() {
        RuntimeEnvironment.setFontScale(1f)
    }
    private val course = Course(1, "one", "IT004.O21", "Database Systems")
    private val snapshot = PortalSnapshot(courses = listOf(course), sections = mapOf("one:1" to listOf(CourseSection(1, "Week 1", listOf(CourseModule(1, 1, "SQL introduction", "resource", "", files = listOf(CourseFile("SQL introduction.pdf", "https://courses.example.org/one.pdf", 12000))))), CourseSection(2, "Week 2", listOf(CourseModule(2, 1, "Transactions", "resource", "", files = listOf(CourseFile("Transactions.pdf", "https://courses.example.org/two.pdf"))))))))
    private val specs = savedDownloadSpecs(snapshot, setOf("one"))
    private val state = OfflineDownloadsState(snapshot, setOf("one"), listOf(OfflineDownloadItem(specs.first(), OfflineDownloadStatus.SAVED, 12000), OfflineDownloadItem(specs.last(), OfflineDownloadStatus.FAILED, message = "Sign in again, then retry this file.")), wifiOnly = true, wifi = false, ready = true, usedBytes = 12064)

    @Test fun choosingASectionQueuesOnlyTheExplicitPack() {
        var queued = emptyList<OfflineDownloadSpec>()
        compose.setContent { Frame { OfflineDownloadsContent(state, onEnqueue = { queued = it }) } }
        compose.onNodeWithText("Choose file pack").performScrollTo().assertIsDisplayed()
        capture("downloads-390-phone")
        assertTrue(queued.isEmpty())
        compose.onNodeWithText("Choose file pack").performScrollTo().performClick()
        compose.onNodeWithText("Week 1", substring = false).performScrollTo().performClick()
        compose.onNodeWithText("Queue this pack").performScrollTo().performClick()
        assertEquals(listOf(specs.first().id), queued.map { it.id })
        capture("downloads-pack-390-phone")
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-mdpi")
    fun savedAndFailedItemsKeepOpenRetryAndRemoveDistinct() {
        var opened: String? = null
        var removed: String? = null
        var retried: String? = null
        compose.setContent { Frame { OfflineDownloadsContent(state, onOpen = { opened = it }, onRemove = { removed = it }, onRetry = { retried = it }) } }
        compose.onNodeWithTag("offline-downloads-content").performScrollToNode(hasText("Open saved file"))
        capture("downloads-queue-360-phone")
        compose.onNodeWithText("Open saved file").performClick()
        assertEquals(specs.first().id, opened)
        compose.onAllNodesWithText("Remove file").onFirst().performScrollTo().performClick()
        assertEquals(specs.first().id, removed)
        compose.onNodeWithTag("offline-downloads-content").performScrollToNode(hasText("Retry file"))
        compose.onNodeWithText("Retry file").performClick()
        assertEquals(specs.last().id, retried)
    }

    @Test
    @Config(qualifiers = "w412dp-h915dp-mdpi")
    fun largeTextWaitingWifiStateRetainsCancel() {
        RuntimeEnvironment.setFontScale(1.3f)
        val data = state.copy(items = listOf(OfflineDownloadItem(specs.first(), OfflineDownloadStatus.WAITING_WIFI)))
        compose.setContent { Frame { OfflineDownloadsContent(data) } }
        compose.onNodeWithTag("offline-downloads-content").performScrollToNode(hasText("Waiting for Wi-Fi"))
        capture("downloads-412-large-text-phone")
        compose.onNodeWithText("Cancel file").assertIsDisplayed()
    }

    @Test fun oversizedPacksCanBeReducedWithExplicitFileSelection() {
        val many = (0..100).map { CourseFile("File $it.pdf", "https://courses.example.org/file-$it.pdf") }
        val data = state.copy(items = emptyList(), snapshot = snapshot.copy(sections = mapOf("one:1" to listOf(CourseSection(1, "Week 1", listOf(CourseModule(1, 1, "Files", "resource", "", files = many)))))))
        var queued = emptyList<OfflineDownloadSpec>()
        compose.setContent { Frame { OfflineDownloadsContent(data, onEnqueue = { queued = it }) } }
        compose.onNodeWithTag("offline-downloads-content").performScrollToNode(hasText("Choose file pack"))
        compose.onNodeWithText("Choose file pack").performClick()
        compose.onNodeWithText("Queue this pack").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Clear selection").performScrollTo().performClick()
        compose.onNodeWithTag("offline-downloads-content").performScrollToNode(hasContentDescription("Select File 0.pdf"))
        compose.onNodeWithContentDescription("Select File 0.pdf").performClick()
        compose.onNodeWithTag("offline-downloads-content").performScrollToNode(hasText("Queue this pack"))
        compose.onNodeWithText("Queue this pack").performClick()
        assertEquals(listOf("File 0.pdf"), queued.map { it.file.name })
    }

    @Test fun courseLibraryExposesTheDownloadManager() {
        var opened = false
        compose.setContent { Frame { CoursesContent(CoursesState(snapshot = snapshot), onDownloads = { opened = true }) } }
        compose.onNodeWithText("Offline downloads").performClick()
        assertTrue(opened)
    }

    @Composable private fun Frame(content: @Composable () -> Unit) {
        UitTheme(dark = true, dynamic = false) { WorkspaceFrame(2, {}, {}, "Synthetic", true, false, 1, onSearch = {}) { content() } }
    }
    private fun capture(name: String) {
        if (java.lang.Boolean.getBoolean("roborazzi.test.record")) {
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(64)
            compose.waitForIdle()
            compose.onRoot().captureRoboImage(File(System.getProperty("uit.preview.dir"), "$name.png").absolutePath)
        }
    }
}
