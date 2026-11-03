package vn.edu.uit.mobile

import android.app.Application
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.*
import vn.edu.uit.mobile.daa.*
import vn.edu.uit.mobile.dashboard.*
import vn.edu.uit.mobile.data.*
import vn.edu.uit.mobile.designsystem.*
import vn.edu.uit.mobile.domain.*

/** Actual UIT public responses through the production client, repository, parser and Compose. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "vi-rVN-w390dp-h844dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalRoborazziApi::class)
class AnnouncementsLiveDesignTest {
    @get:Rule val compose = createComposeRule()

    @After fun resetScale() {
        RuntimeEnvironment.setFontScale(1f)
    }
    private fun repository(): LocalAnnouncementRepository = LocalAnnouncementRepository(
        PublicAnnouncementClient(),
        object : StudyStorage {
            private val entries = mutableMapOf<String, String>()
            override fun read(key: String) = entries[key]
            override fun write(key: String, value: String) {
                entries[key] = value
            }
            override fun remove(key: String) {
                entries.remove(key)
            }
            override fun removeAccount(accountId: String) = error("No real account operations")
        }
    )
    private fun capture(name: String) {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(64)
        compose.waitForIdle()
        val output = File(requireNotNull(System.getProperty("uit.announcements.output"))).apply { mkdirs() }
        captureScreenRoboImage(File(output, "$name.png").absolutePath)
    }
    private fun list(width: Int, large: Boolean = false) = runBlocking {
        Assume.assumeTrue(java.lang.Boolean.getBoolean("uit.announcements.live"))
        if (large) RuntimeEnvironment.setFontScale(1.3f)
        val repo = repository()
        repo.refresh()
        val state = repo.state.value
        assertFalse(state.sources.getValue(AnnouncementSource.PORTAL).failed)
        assertTrue(state.items.isNotEmpty())
        compose.setContent { UitTheme(dark = true, dynamic = false) { WorkspaceFrame(3, {}, {}, "", false, false, 0) { AnnouncementsContent(state) } } }
        capture("announcements-$width${if (large) "-large-text" else ""}")
        if (width == 390) {
            compose.onNodeWithContentDescription("Nguồn tin và nhắc báo").performClick()
            capture("announcement-sources-390")
        }
        val output = File(requireNotNull(System.getProperty("uit.announcements.output")))
        File(output, "announcements-$width-provenance.txt").writeText("Captured: ${java.time.Instant.now()}\nRuntime: Robolectric API 35 native Compose host rendering; not a physical Android device\nMode: anonymous public GET; no university credentials\nRoute: ${AnnouncementSource.PORTAL.page(0)}\nSecond page: ${AnnouncementSource.PORTAL.page(1)}\nArticles: ${state.items.size}\nPublished titles and URLs:\n" + state.items.joinToString("\n") { "${it.publishedDate} | ${it.title} | ${it.url}" })
    }

    @Test fun actualPortalList390() = list(390)

    @Test
    @Config(qualifiers = "vi-rVN-w360dp-h800dp-mdpi")
    fun actualPortalList360() = list(360)

    @Test
    @Config(qualifiers = "vi-rVN-w412dp-h915dp-mdpi")
    fun actualPortalList412LargeText() = list(412, true)

    @Test fun actualScholarshipArticle() = runBlocking {
        Assume.assumeTrue(java.lang.Boolean.getBoolean("uit.announcements.live"))
        val repo = repository()
        repo.refresh()
        val item = repo.state.value.items.first { "thong-bao-dang-ky-xet-cap-cac-loai-hoc-bong-hk-1" in it.url }
        val document = repo.open(item.url)
        assertFalse(document.offline)
        assertTrue(document.blocks.isNotEmpty())
        repo.markRead(item.url)
        val detailState = repo.state.value
        compose.setContent { UitTheme(dark = true, dynamic = false) { WorkspaceFrame(3, {}, {}, "", false, false, 0) { AnnouncementsContent(detailState, selected = item.url, document = document, imageLoader = repo::image) } } }
        capture("announcement-scholarship-detail-390")
        compose.onNodeWithText("Nhắc tôi").performClick()
        capture("announcement-reminder-390")
        compose.onNodeWithText("Hủy").performClick()
        compose.onNodeWithTag("announcements-list").performScrollToIndex(6)
        capture("announcement-scholarship-body-390")
        compose.onNodeWithTag("announcements-list").performScrollToNode(hasText("Tải ảnh"))
        compose.onNodeWithText("Tải ảnh").performClick()
        compose.waitUntil(timeoutMillis = 30000) { compose.onAllNodesWithText("Xem toàn màn hình").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Xem toàn màn hình").performScrollTo().performClick()
        capture("announcement-public-image-390")
        File(System.getProperty("uit.announcements.output"), "scholarship-provenance.txt").writeText("Captured: ${java.time.Instant.now()}\nSource: ${item.url}\nBody SHA-256: ${announcementHash(document.item.bodyHtml)}\nRuntime: Robolectric API 35 native Compose; live public GET; no SSO\nImage: https://portal.uit.edu.vn/media/ir.attachment/36566701/file\nImage content: UIT-published instructional screenshot, not the owner account\n")
    }
}
