package vn.edu.uit.mobile

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import java.io.ByteArrayOutputStream
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*
import vn.edu.uit.mobile.designsystem.*
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.forum.*

/** Authored edge cases only. Never export these fixtures as university screenshots. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-w390dp-h844dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ForumReadingUxDesignTest {
    @get:Rule val compose = createComposeRule()
    private val post = ForumPost(7, 9, 17, "Fixture author", "", listOf(ForumBlock(ForumBlockKind.TEXT, "Fixture body")))
    private val state = ForumState(topic = ForumTopic(9, "Fixture discussion", 1, 5, ""), posts = listOf(post), stream = listOf(7), end = 1)
    private val image = ForumBlock(ForumBlockKind.IMAGE, "Fixture image", listOf(ForumLink("", "https://forum.uit.edu.vn/uploads/fixture.png")), imageWidth = 200, imageHeight = 100)
    private fun png(height: Int = 2): ByteArray = ByteArrayOutputStream().use { output ->
        Bitmap.createBitmap(2, height, Bitmap.Config.ARGB_8888).apply {
            eraseColor(0xff0768b5.toInt())
            compress(Bitmap.CompressFormat.PNG, 100, output)
            recycle()
        }
        output.toByteArray()
    }

    @Composable private fun FixtureViewport(content: @Composable () -> Unit) {
        UitTheme { Column(Modifier.fillMaxSize()) { content() } }
    }

    @Test fun safePreviewLoadsWithoutAButtonAndTappingItOpensTheViewer() {
        var requests = 0
        val bytes = png()
        compose.setContent {
            FixtureViewport {
                SemanticArticleBlock(image, {}, {
                    requests++
                    bytes
                }, "https://forum.uit.edu.vn/t/9", {}, autoLoadImages = true)
            }
        }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("forum-image-open").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(1, requests)
        compose.onNodeWithText("Load image").assertDoesNotExist()
        compose.onNodeWithTag("forum-image-open").performClick()
        compose.onNodeWithTag("forum-image-viewer").assertIsDisplayed()
        assertEquals(1, requests)
    }

    @Test fun portraitPreviewRemainsBoundedAndItsImageCanOpen() {
        val bytes = png(4)
        val portrait = image.copy(imageWidth = 200, imageHeight = 400)
        compose.setContent { FixtureViewport { SemanticArticleBlock(portrait, {}, { bytes }, "https://forum.uit.edu.vn/t/9", {}, autoLoadImages = true) } }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("forum-image-open").fetchSemanticsNodes().isNotEmpty() }
        val bounds = compose.onNodeWithTag("forum-image-open").fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.height <= with(compose.density) { 480.dp.toPx() })
        compose.onNodeWithTag("forum-image-open").assertIsDisplayed().performClick()
        compose.onNodeWithTag("forum-image-viewer").assertIsDisplayed()
    }

    @Test fun offscreenImagesWaitForTheViewport() {
        var requests = 0
        val bytes = png()
        compose.setContent {
            FixtureViewport {
                LazyColumn(Modifier.fillMaxSize().testTag("test-list")) {
                    item { Spacer(Modifier.height(1600.dp)) }
                    item {
                        SemanticArticleBlock(image, {}, {
                            requests++
                            bytes
                        }, "https://forum.uit.edu.vn/t/9", {}, autoLoadImages = true)
                    }
                }
            }
        }
        compose.waitForIdle()
        assertEquals(0, requests)
        compose.onNodeWithTag("test-list").performScrollToIndex(1)
        compose.waitUntil(5000) { requests == 1 }
    }

    @Test fun externalImagesNeedExplicitBrowserHandoffAndNeverAutoFetch() {
        var requests = 0
        var opened: String? = null
        val external = image.copy(links = listOf(ForumLink("", "https://outside.example/image.png")))
        compose.setContent {
            FixtureViewport {
                SemanticArticleBlock(external, {}, {
                    requests++
                    png()
                }, "https://forum.uit.edu.vn/t/9", { opened = it }, autoLoadImages = true)
            }
        }
        compose.waitForIdle()
        assertEquals(0, requests)
        compose.onNodeWithText("View image in browser").performClick()
        assertEquals(external.links.single().url, opened)
        assertEquals(0, requests)
    }

    @Test fun hiddenImagesDoNotLoadBeforeTheSpoilerIsRevealed() {
        var requests = 0
        val bytes = png()
        val hidden = ForumBlock(ForumBlockKind.SPOILER, "", children = listOf(image))
        compose.setContent {
            FixtureViewport {
                SemanticArticleBlock(hidden, {}, {
                    requests++
                    bytes
                }, "https://forum.uit.edu.vn/t/9", {}, autoLoadImages = true)
            }
        }
        assertEquals(0, requests)
        compose.onNodeWithText("Reveal hidden content").performClick()
        compose.onNodeWithTag("forum-image-preview").assertIsDisplayed()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("forum-image-open").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(1, requests)
    }

    @Test fun failedImageRequiresAnExplicitRetry() {
        var requests = 0
        val bytes = png()
        compose.setContent {
            FixtureViewport {
                SemanticArticleBlock(image, {}, {
                    requests++
                    if (requests == 1) throw ForumException(ForumFailure.NETWORK)
                    bytes
                }, "https://forum.uit.edu.vn/t/9", {}, autoLoadImages = true)
            }
        }
        compose.waitUntil(5000) { compose.onAllNodesWithText("Retry").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(1, requests)
        compose.waitForIdle()
        assertEquals(1, requests)
        compose.onNodeWithText("Retry").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("forum-image-open").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(2, requests)
    }

    @Test fun guestActionsRemainVisibleAndOnlyRequestLogin() {
        var logins = 0
        var writes = 0
        val actions = ForumAccountActions({ logins++ }, {}, {}, {}, { writes++ })
        compose.setContent { FixtureViewport { ForumContent(state, accountActions = actions) } }
        compose.onNodeWithTag("forum-like-7").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithTag("forum-reply-7").performClick()
        assertEquals(2, logins)
        assertEquals(0, writes)
        compose.onNodeWithTag("forum-copy-7").assertIsEnabled()
    }

    @Test fun aMemberCannotUseActionsTheServerDidNotPermit() {
        var writes = 0
        val actions = ForumAccountActions({}, {}, {}, {}, { writes++ })
        compose.setContent { FixtureViewport { ForumContent(state.copy(user = ForumUser(2, "fixture", "Fixture"), canReply = false), accountActions = actions) } }
        compose.onNodeWithTag("forum-like-7").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("forum-reply-7").assertDoesNotExist()
        compose.onNodeWithTag("forum-post-menu-7").performClick()
        compose.onNodeWithText("Edit post").assertDoesNotExist()
        assertEquals(0, writes)
    }

    @Test fun postSourceActionUsesBrowserHandoffInsteadOfReopeningTheNativePost() {
        var source: String? = null
        var native = 0
        compose.setContent { FixtureViewport { ForumContent(state, onTopic = { _, _, _ -> native++ }, onBrowser = { source = it }) } }
        compose.onNodeWithTag("forum-post-menu-7").performScrollTo().performClick()
        compose.onNodeWithText("Open source").performClick()
        assertEquals("https://forum.uit.edu.vn/t/9/17", source)
        assertEquals(0, native)
    }

    @Test fun failedTopicDoesNotOfferAiOrPhantomWindowControls() {
        var retries = 0
        compose.setContent { FixtureViewport { ForumContent(state.copy(posts = emptyList(), stream = emptyList(), end = 20, error = ForumFailure.NETWORK, networkIssue = ForumNetworkIssue.TLS), onTopic = { _, _, _ -> retries++ }) } }
        compose.onNodeWithText("Fixture discussion").assertExists()
        compose.onNodeWithTag("forum-topic-menu").assertDoesNotExist()
        compose.onNodeWithTag("forum-position").assertDoesNotExist()
        compose.onNodeWithTag("forum-ai-entry").assertDoesNotExist()
        compose.onNodeWithTag("forum-read-retry").performClick()
        assertEquals(1, retries)
        compose.onNodeWithTag("forum-error-details").performClick()
        compose.onNodeWithText("The secure TLS handshake failed. Certificate checks remain enabled.").assertExists()
    }

    @Test fun jumpInputOpensOnlyWhenRequestedAndKeepsSourcePostNumber() {
        var destination = 0
        compose.setContent { FixtureViewport { ForumContent(state, onTopic = { _, n, _ -> destination = n }) } }
        compose.onNodeWithTag("forum-jump").assertDoesNotExist()
        compose.onNodeWithTag("forum-position").performClick()
        compose.onNodeWithTag("forum-jump").performTextInput("20")
        compose.onNodeWithText("Go").performClick()
        assertEquals(20, destination)
    }
}
