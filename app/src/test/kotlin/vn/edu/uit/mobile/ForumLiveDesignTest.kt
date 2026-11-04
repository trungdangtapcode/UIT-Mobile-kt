package vn.edu.uit.mobile

import android.app.Application
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import java.io.File
import java.time.Instant
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*
import vn.edu.uit.mobile.data.*
import vn.edu.uit.mobile.designsystem.*
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.forum.*
import vn.edu.uit.mobile.forum.network.*

/** Opt-in real-network visual verification. Captures stay in ignored build output. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "vi-rVN-w390dp-h844dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalRoborazziApi::class)
class ForumLiveDesignTest {
    @get:Rule val compose = createComposeRule()

    @Test fun realServerThroughProductionReader() {
        Assume.assumeTrue(java.lang.Boolean.getBoolean("uit.forum.live"))
        val output = File(requireNotNull(System.getProperty("uit.forum.live.dir"))).apply { mkdirs() }
        val requests = mutableListOf<String>()
        val client = AnonymousForumClient()
        val transport = object : ForumTransport {
            override suspend fun get(request: ForumRequest): String {
                synchronized(requests) { requests += ForumUrls.key(request) }
                return client.get(request).also { println("Live GET completed: ${ForumUrls.key(request)}") }
            }
            override suspend fun image(url: String) = client.image(url)
        }
        val storage = object : StudyStorage {
            val entries = mutableMapOf<String, String>()
            override fun read(key: String) = entries[key]
            override fun write(key: String, value: String) {
                entries[key] = value
            }
            override fun remove(key: String) {
                entries.remove(key)
            }
            override fun removeAccount(accountId: String) = error("No account operation allowed")
        }
        val vm = ForumViewModel(LocalForumRepository(transport, storage))
        val models = ViewModelStore().apply { put("forum", vm) }
        fun settled() {
            compose.waitUntil(120000) {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                !vm.uiState.value.loading
            }
            assertNull("Live Forum request failed", vm.uiState.value.error)
            compose.waitForIdle()
        }
        fun capture(name: String) {
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(64)
            captureScreenRoboImage(File(output, "$name.png").absolutePath)
        }
        try {
            compose.setContent { UitTheme(dark = true, dynamic = false) { WorkspaceFrame(3, {}, {}, "", false, false, 0) { ForumScreen(vm) } } }
            compose.runOnIdle { vm.open() }
            settled()
            val feed = vm.uiState.value.topics
            assertTrue(feed.isNotEmpty())
            capture("forum-real-latest")
            compose.runOnIdle { vm.tab(ForumTab.CATEGORIES) }
            settled()
            capture("forum-real-categories")
            compose.runOnIdle { vm.tab(ForumTab.SEARCH) }
            settled()
            compose.onNodeWithTag("forum-query").performTextInput("uit")
            compose.onAllNodesWithText("Tìm kiếm").onLast().performClick()
            settled()
            assertTrue(vm.uiState.value.hits.isNotEmpty())
            capture("forum-real-search")
            val selected = feed.maxBy { it.posts }
            compose.runOnIdle { vm.topic(selected.id) }
            settled()
            val initial = vm.uiState.value
            assertTrue(initial.posts.isNotEmpty())
            assertTrue(initial.stream.size > initial.posts.size)
            capture("forum-real-topic")
            compose.onNodeWithTag("forum-reader").performScrollToIndex(1)
            capture("forum-real-first-post")
            compose.runOnIdle { vm.window(true) }
            settled()
            assertTrue(vm.uiState.value.end > initial.end)
            val jump = (selected.posts / 2).coerceAtLeast(1)
            compose.runOnIdle { vm.topic(selected.id, jump) }
            settled()
            assertTrue(vm.uiState.value.posts.any { it.number >= jump - 10 })
            capture("forum-real-jump")
            val jumpedNumbers = vm.uiState.value.posts.map { it.number }
            val announcement = feed.firstOrNull { it.title.startsWith("Thông báo") }
            if (announcement != null) {
                compose.runOnIdle { vm.topic(announcement.id) }
                settled()
                assertTrue(vm.uiState.value.posts.isNotEmpty())
                capture("forum-real-announcement")
                compose.onNodeWithTag("forum-reader").performScrollToIndex(1)
                capture("forum-real-announcement-body")
            }
            File(output, "provenance.txt").writeText(
                buildString {
                    appendLine("Captured: ${Instant.now()}")
                    appendLine("Runtime: actual production Compose/ForumViewModel/LocalForumRepository/AnonymousForumClient on Robolectric")
                    appendLine("Network: live HTTPS to https://forum.uit.edu.vn; no fixture response substitution")
                    appendLine("Account: anonymous; this does not verify app SSO or an installed Android device")
                    appendLine("Local persistence for this host test: in-memory StudyStorage; no Android Keystore claim")
                    appendLine("Topic: ${selected.url}; stream IDs: ${initial.stream.size}; initial bodies: ${initial.posts.size}")
                    appendLine("Announcement: ${announcement?.url ?: "none in current feed"}")
                    appendLine("Jump requested: $jump; loaded numbers: $jumpedNumbers")
                    appendLine("Requests: ${synchronized(requests) { requests.toList() }}")
                }
            )
        } finally {
            compose.runOnIdle { models.clear() }
        }
    }
}
