package vn.edu.uit.mobile

import android.app.Application
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.http.*
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.*
import vn.edu.uit.mobile.data.*
import vn.edu.uit.mobile.designsystem.*
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.forum.*
import vn.edu.uit.mobile.forum.network.*

/** Live production UIT public reads only. No fixture content or write requests. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "vi-rVN-w390dp-h844dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalRoborazziApi::class)
class ForumReadingLiveDesignTest {
    @get:Rule val compose = createComposeRule()

    @After fun reset() {
        RuntimeEnvironment.setFontScale(1f)
    }
    private class Memory : StudyStorage {
        val data = mutableMapOf<String, String>()
        override fun read(key: String) = data[key]
        override fun write(key: String, value: String) {
            data[key] = value
        }
        override fun remove(key: String) {
            data.remove(key)
        }
        override fun removeAccount(accountId: String) = error("No account mutation")
    }
    private fun phone(width: Int, large: Boolean = false, light: Boolean = false, media: Boolean = false, table: Boolean = false) {
        Assume.assumeTrue(java.lang.Boolean.getBoolean("uit.forum.live"))
        val output = File(requireNotNull(System.getProperty("uit.forum.live.dir"))).apply { mkdirs() }
        val images = AtomicInteger()
        val requests = mutableListOf<String>()
        val http = HttpClient(OkHttp) {
            followRedirects = false
            install(HttpTimeout) {
                requestTimeoutMillis = 25000
                connectTimeoutMillis = 10000
                socketTimeoutMillis = 20000
            }
            engine {
                config {
                    followRedirects(false)
                    followSslRedirects(false)
                }
            }
        }
        http.plugin(HttpSend).intercept { request ->
            check(request.method == HttpMethod.Get && request.url.host == "forum.uit.edu.vn") { "Read-only public evidence guard rejected a request" }
            check(request.headers["Cookie"] == null && request.headers["Authorization"] == null)
            if (request.url.encodedPath.startsWith("/uploads/")) images.incrementAndGet()
            synchronized(requests) { requests += request.url.encodedPath }
            execute(request)
        }
        val guest = LocalForumRepository(AnonymousForumClient(http), Memory())
        val vm = ForumViewModel(ConnectedForumRepository(guest, ForumSessionClient(http), Memory()))
        val models = ViewModelStore().apply { put("forum", vm) }
        val topicId = if (media) 164929L else 163707L
        val suffix = "$width${if (large) "-large-text" else ""}${if (light) "-light" else ""}"
        fun settled() {
            compose.waitUntil(60000) {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                !vm.uiState.value.loading
            }
            assertNull(vm.uiState.value.error)
            assertTrue(vm.uiState.value.posts.isNotEmpty())
            compose.waitForIdle()
        }
        fun capture(name: String) {
            compose.waitForIdle()
            captureScreenRoboImage(File(output, "$name.png").absolutePath)
        }
        try {
            if (large) RuntimeEnvironment.setFontScale(1.3f)
            compose.setContent { UitTheme(dark = !light, dynamic = false) { WorkspaceFrame(3, {}, {}, "", false, false, 0) { ForumScreen(vm) } } }
            compose.runOnIdle { vm.topic(topicId, if (table) 17 else 1) }
            settled()
            val initial = vm.uiState.value
            assertEquals(topicId, initial.topic?.id)
            if (table) {
                val post = initial.posts.single { it.number == 17 }
                val block = post.blocks.single { it.kind == ForumBlockKind.TABLE }
                assertTrue(block.rows.size > 1)
                assertEquals(block.rows.size, block.cells.size)
                assertTrue(block.rows.all { it.size == 2 })
                val header = block.rows.first().first()
                compose.onNodeWithTag("forum-reader").performScrollToNode(hasText(header))
                compose.onNodeWithText(header).performScrollTo().assertIsDisplayed()
                val reader = compose.onNodeWithTag("forum-reader")
                val headerTop = compose.onNodeWithText(header).getUnclippedBoundsInRoot().top.value
                val readerTop = reader.getUnclippedBoundsInRoot().top.value
                reader.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, headerTop - readerTop - 8f) }
                capture("reading-icpc-table-$suffix")
                val tableNode = compose.onNode(
                    SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange)
                        .and(hasAnyDescendant(hasText(header)))
                )
                tableNode.performSemanticsAction(SemanticsActions.ScrollBy) { it(10000f, 0f) }
                capture("reading-icpc-table-right-$suffix")
                tableNode.performSemanticsAction(SemanticsActions.ScrollBy) { it(-10000f, 0f) }
                val lastTeam = block.rows.drop(1).last { it[1].any(Char::isLetter) }[1]
                compose.waitForIdle()
                val lastBottom = compose.onNodeWithText(lastTeam).getUnclippedBoundsInRoot().bottom.value
                val readerBottom = reader.getUnclippedBoundsInRoot().bottom.value
                reader.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, lastBottom - readerBottom + 80f) }
                compose.onNodeWithText(lastTeam).assertIsDisplayed()
                capture("reading-icpc-table-bottom-$suffix")
                val normalized = block.rows.joinToString("\u001e") { it.joinToString("\u001f") { cell -> cell.replace(Regex("\\s+"), " ").trim() } }
                File(output, "reading-report-table-$suffix.json").writeText(
                    buildJsonObject {
                        put("capturedAt", java.time.Instant.now().toString())
                        put("topicId", topicId)
                        put("postNumber", post.number)
                        put("postId", post.id)
                        put("runtime", "Live anonymous production Kotlin transport/repository/ViewModel/Compose, Robolectric API 35; no physical phone")
                        put("rowsIncludingHeader", block.rows.size)
                        put("columns", 2)
                        put("normalizedCellsSha256", java.security.MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray()).joinToString("") { "%02x".format(it) })
                        put("horizontalScrollExercised", true)
                        put("bottomRowReached", true)
                        put("writes", 0)
                        put("requests", JsonArray(synchronized(requests) { requests.toList() }.map(::JsonPrimitive)))
                    }.toString()
                )
            } else if (!media) {
                capture("reading-icpc-$suffix")
                val post = initial.posts.first()
                compose.onNodeWithTag("forum-reader").performScrollToNode(hasTestTag("forum-actions-${post.id}"))
                compose.onNodeWithTag("forum-actions-${post.id}").performScrollTo()
                capture("reading-icpc-actions-$suffix")
                compose.onNodeWithTag("forum-like-${post.id}").assertIsEnabled()
                compose.onNodeWithTag("forum-reply-${post.id}").assertIsEnabled()
            } else {
                capture("reading-announcement-$suffix")
                compose.onNodeWithTag("forum-reader").performScrollToNode(hasTestTag("forum-image-preview"))
                compose.onAllNodesWithTag("forum-image-preview").onFirst().performScrollTo()
                compose.waitUntil(30000) {
                    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                    compose.onAllNodesWithTag("forum-image-open").fetchSemanticsNodes().isNotEmpty()
                }
                compose.onAllNodesWithTag("forum-image-open").onFirst().performScrollTo()
                compose.onNodeWithText("Tải ảnh").assertDoesNotExist()
                assertTrue(images.get() > 0)
                capture("reading-inline-image-390")
                compose.onAllNodesWithTag("forum-image-open").onFirst().performClick()
                compose.onNodeWithTag("forum-image-viewer").assertIsDisplayed()
                compose.waitUntil(30000) {
                    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                    compose.onAllNodesWithTag("forum-image-loading").fetchSemanticsNodes().isEmpty()
                }
                capture("reading-image-viewer-390")
            }
            if (!table) {
                File(output, "reading-report-$suffix-${if (media) "media" else "icpc"}.json").writeText(
                    buildJsonObject {
                        put("capturedAt", java.time.Instant.now().toString())
                        put("topicId", topicId)
                        put("runtime", "Live anonymous production Kotlin transport/repository/ViewModel/Compose, Robolectric API 35; no physical phone")
                        put("postNumbers", JsonArray(initial.posts.map { JsonPrimitive(it.number) }))
                        put("imageGets", images.get())
                        put("writes", 0)
                        put("requests", JsonArray(synchronized(requests) { requests.toList() }.map(::JsonPrimitive)))
                    }.toString()
                )
            }
        } finally {
            compose.runOnIdle {
                vm.close()
                models.clear()
            }
            http.close()
        }
    }

    @Test fun icpc390() = phone(390)

    @Test
    @Config(qualifiers = "vi-rVN-w360dp-h800dp-mdpi")
    fun icpc360() = phone(360)

    @Test
    @Config(qualifiers = "vi-rVN-w412dp-h915dp-mdpi")
    fun icpc412LargeText() = phone(412, large = true)

    @Test fun icpc390Light() = phone(390, light = true)

    @Test fun announcementImage390() = phone(390, media = true)

    @Test fun icpcTable390() = phone(390, table = true)

    @Test
    @Config(qualifiers = "vi-rVN-w360dp-h800dp-mdpi")
    fun icpcTable360() = phone(360, table = true)
}
