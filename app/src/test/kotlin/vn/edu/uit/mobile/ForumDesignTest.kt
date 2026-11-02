package vn.edu.uit.mobile

import android.app.Application
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import java.io.File
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.*
import vn.edu.uit.mobile.designsystem.*
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.forum.*
import vn.edu.uit.mobile.forum.network.ForumParser

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalRoborazziApi::class)
class ForumDesignTest {
    @get:Rule val compose = createComposeRule()

    @After fun reset() {
        RuntimeEnvironment.setFontScale(1f)
    }
    private fun state(): ForumState = ForumState(open = true, categories = listOf(ForumFixtures.category, ForumFixtures.child), topics = listOf(ForumFixtures.topic, ForumFixtures.second), fetchedAt = ForumFixtures.AT)
    private fun show(state: ForumState) {
        compose.setContent { UitTheme(dark = true, dynamic = false) { WorkspaceFrame(3, {}, {}, "", false, false, 0) { ForumContent(state) } } }
    }
    private fun capture(name: String) {
        if (java.lang.Boolean.getBoolean("roborazzi.test.record")) {
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(64)
            compose.waitForIdle()
            captureScreenRoboImage(File(System.getProperty("uit.preview.dir"), "$name.png").absolutePath)
        }
    }

    @Test fun publicFeed390() {
        show(state())
        compose.onNodeWithText(ForumFixtures.TITLE).assertExists()
        capture("forum-latest-390-phone")
    }

    @Test fun categoryHierarchy390() {
        show(state().copy(tab = ForumTab.CATEGORIES))
        capture("forum-categories-390-phone")
    }

    @Test
    @Config(qualifiers = "w412dp-h892dp-mdpi")
    fun postSearch412() {
        val doc = ForumParser.parse(ForumRequest.Search("small input"), ForumFixtures.SEARCH)
        show(state().copy(tab = ForumTab.SEARCH, query = "small input", hits = doc.hits))
        compose.onNodeWithText("Post #99").assertExists()
        capture("forum-search-412-phone")
    }

    @Test fun topicAndRichBody390() {
        val request = ForumRequest.Topic(901)
        val doc = ForumParser.parse(request, ForumFixtures.response(request))
        show(state().copy(topic = doc.topic, posts = doc.posts, stream = doc.stream, start = 0, end = 20))
        capture("forum-topic-390-phone")
        compose.onNodeWithTag("forum-reader").performScrollToNode(hasText("Prepare your workspace"))
        capture("forum-rich-content-390-phone")
        compose.onNodeWithText("Load image").performScrollTo().assertIsDisplayed()
        capture("forum-table-images-390-phone")
    }

    @Test fun restoredReaderKeepsVisibleReplyInsteadOfRepeatingInitialJump() {
        val request = ForumRequest.Topic(901)
        val doc = ForumParser.parse(request, ForumFixtures.response(request))
        val state = state().copy(topic = doc.topic, posts = doc.posts, stream = doc.stream, start = 0, end = 20)
        val restoration = StateRestorationTester(compose)
        restoration.setContent { UitTheme(dark = true, dynamic = false) { WorkspaceFrame(3, {}, {}, "", false, false, 0) { ForumContent(state) } } }
        compose.onNodeWithTag("forum-reader").performScrollToIndex(5)
        compose.onNodeWithTag("forum-post-9").assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        compose.onNodeWithTag("forum-post-9").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "vi-rVN-w360dp-h800dp-mdpi")
    fun localLibrary360VietnameseLargeText() {
        RuntimeEnvironment.setFontScale(1.3f)
        show(state().copy(tab = ForumTab.SAVED, offline = true, local = ForumLocalState(saved = listOf(ForumFixtures.topic), positions = listOf(ForumPosition(ForumFixtures.topic, 1050, 99, 16)), savedAt = mapOf(901L to ForumFixtures.AT - 3600))))
        capture("forum-saved-360-vi-large-phone")
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-mdpi")
    fun unavailable360() {
        show(state().copy(topic = ForumFixtures.topic, error = ForumFailure.UNAVAILABLE, posts = emptyList()))
        compose.onNodeWithText("This content is private, removed or unavailable to an anonymous reader.").assertExists()
        capture("forum-unavailable-360-phone")
    }

    @Test fun rateLimit390() {
        show(state().copy(error = ForumFailure.RATE_LIMIT))
        capture("forum-rate-limit-390-phone")
    }
}
