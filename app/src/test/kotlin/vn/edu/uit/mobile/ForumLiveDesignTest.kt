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
            compose.onNodeWithTag("forum-filter").performClick()
            compose.onNodeWithText("Nổi bật").performClick()
            settled()
            assertEquals(ForumFilter.TOP, vm.uiState.value.filter)
            capture("forum-real-top")
            compose.onNodeWithTag("forum-period").performClick()
            compose.onNodeWithText("Tháng qua").performClick()
            settled()
            assertEquals(ForumPeriod.MONTHLY, vm.uiState.value.period)
            capture("forum-real-top-monthly")
            compose.onNodeWithTag("forum-filter").performClick()
            compose.onNodeWithText("Sôi nổi").performClick()
            settled()
            assertEquals(ForumFilter.HOT, vm.uiState.value.filter)
            capture("forum-real-hot")
            compose.onNodeWithTag("forum-filter").performClick()
            compose.onNodeWithText("Nhiều bình chọn").performClick()
            settled()
            assertEquals(ForumFilter.VOTES, vm.uiState.value.filter)
            capture("forum-real-votes")
            compose.onNodeWithText("Thẻ").performClick()
            settled()
            val tags = vm.uiState.value.tags
            assertTrue(tags.isNotEmpty())
            val selectedTag = tags.first { it.count > 0 }
            compose.onNodeWithTag("forum-tag-query").performTextInput(selectedTag.name)
            compose.onNodeWithTag("forum-tag-${selectedTag.id}").assertIsDisplayed()
            capture("forum-real-tags")
            compose.onNodeWithTag("forum-tag-${selectedTag.id}").performClick()
            settled()
            assertEquals(selectedTag.id, vm.uiState.value.tag?.id)
            capture("forum-real-tag-topics")
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
                compose.onNodeWithTag("forum-reader").performScrollToNode(hasTestTag("forum-image-preview"))
                compose.onAllNodesWithTag("forum-image-preview").onFirst().performScrollTo()
                compose.waitUntil(30000) {
                    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                    compose.onAllNodesWithTag("forum-image-open").fetchSemanticsNodes().isNotEmpty()
                }
                compose.onAllNodesWithTag("forum-image-open").onFirst().performScrollTo().performClick()
                compose.onNodeWithTag("forum-image-viewer").assertIsDisplayed()
                compose.waitUntil(30000) {
                    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                    compose.onAllNodesWithTag("forum-image-loading").fetchSemanticsNodes().isEmpty()
                }
                compose.onNodeWithText("Ảnh không khả dụng hoặc vượt giới hạn kích thước an toàn.").assertDoesNotExist()
                capture("forum-real-image-fullscreen")
                compose.onNodeWithText("Phóng to").performClick()
                capture("forum-real-image-zoom")
                compose.onNodeWithText("Đóng ảnh").performClick()
            }
            var edited: ForumPost? = null
            for (candidate in feed.filter { it.title.startsWith("Thông báo") }.take(4)) {
                compose.runOnIdle { vm.topic(candidate.id) }
                settled()
                edited = vm.uiState.value.posts.firstOrNull { it.number == 1 && it.canViewHistory && it.version > 1 }
                if (edited != null) break
            }
            val historyPost = requireNotNull(edited) { "No editable official post available for live history verification" }
            compose.onNodeWithTag("forum-reader").performScrollToNode(hasTestTag("forum-history-${historyPost.id}"))
            compose.onNodeWithTag("forum-history-${historyPost.id}").performClick()
            compose.waitUntil(30000) {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                !vm.uiState.value.historyLoading
            }
            assertNull(vm.uiState.value.historyError)
            assertEquals(historyPost.id, vm.uiState.value.history?.postId)
            compose.onNodeWithTag("forum-history-reader").assertIsDisplayed()
            capture("forum-real-history")
            compose.onNodeWithText("Trước").performClick()
            capture("forum-real-history-before")
            compose.onNodeWithText("Sau").performClick()
            capture("forum-real-history-after")
            compose.runOnIdle { vm.closeHistory() }
            File(output, "provenance.txt").writeText(
                buildString {
                    appendLine("Captured: ${Instant.now()}")
                    appendLine("Runtime: actual production Compose/ForumViewModel/LocalForumRepository/AnonymousForumClient on Robolectric")
                    appendLine("Network: live HTTPS to https://forum.uit.edu.vn; no fixture response substitution")
                    appendLine("Account: anonymous; this does not verify app SSO or an installed Android device")
                    appendLine("Local persistence for this host test: in-memory StudyStorage; no Android Keystore claim")
                    appendLine("Discovery: anonymous_list_filters; Top weekly/monthly, Hot, Votes; /tags.json and /tag/${selectedTag.id}/l/latest.json")
                    appendLine("Topic: ${selected.url}; stream IDs: ${initial.stream.size}; initial bodies: ${initial.posts.size}")
                    appendLine("History: https://forum.uit.edu.vn/t/${historyPost.topicId}/${historyPost.number}; post ID ${historyPost.id}; version ${historyPost.version}")
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
