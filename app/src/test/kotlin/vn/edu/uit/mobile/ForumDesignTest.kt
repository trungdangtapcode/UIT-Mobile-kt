package vn.edu.uit.mobile

import android.app.Application
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.*
import vn.edu.uit.mobile.designsystem.*
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.forum.*
import vn.edu.uit.mobile.forum.network.ForumParser

/** Fixture-based behavior checks only; published Forum captures must use live UIT responses. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ForumDesignTest {
    @get:Rule val compose = createComposeRule()

    @After fun reset() {
        RuntimeEnvironment.setFontScale(1f)
    }
    private fun state(): ForumState = ForumState(open = true, categories = listOf(ForumFixtures.category, ForumFixtures.child), topics = listOf(ForumFixtures.topic, ForumFixtures.second), fetchedAt = ForumFixtures.AT)
    private fun show(state: ForumState) {
        compose.setContent { UitTheme(dark = true, dynamic = false) { WorkspaceFrame(3, {}, {}, "", false, false, 0) { ForumContent(state) } } }
    }

    @Test fun spoilerContentIsAbsentFromSemanticsUntilExplicitlyRevealed() {
        val blocks = ForumParser.blocks("<p>Visible</p><div class='spoiler'><p>Concealed answer</p></div>")
        show(state().copy(topic = ForumFixtures.topic, posts = listOf(ForumPost(1, 901, 1, "reader", "", blocks)), stream = listOf(1), end = 1))
        compose.onNodeWithText("Concealed answer").assertDoesNotExist()
        compose.onNodeWithTag("forum-reader").performScrollToNode(hasText("Reveal hidden content"))
        compose.onNodeWithText("Reveal hidden content").performClick()
        compose.onNodeWithText("Concealed answer").assertExists()
        compose.onNodeWithText("Hide content").performClick()
        compose.onNodeWithText("Concealed answer").assertDoesNotExist()
    }

    @Test fun publicFeed390() {
        show(state())
        compose.onNodeWithText(ForumFixtures.TITLE).assertExists()
    }

    @Test fun categoryHierarchy390() {
        show(state().copy(tab = ForumTab.CATEGORIES))
    }

    @Test
    @Config(qualifiers = "w412dp-h892dp-mdpi")
    fun postSearch412() {
        val doc = ForumParser.parse(ForumRequest.Search("small input"), ForumFixtures.SEARCH)
        show(state().copy(tab = ForumTab.SEARCH, query = "small input", hits = doc.hits))
        compose.onNodeWithText("Post #99").assertExists()
    }

    @Test fun topicAndRichBody390() {
        val request = ForumRequest.Topic(901)
        val doc = ForumParser.parse(request, ForumFixtures.response(request))
        show(state().copy(topic = doc.topic, posts = doc.posts, stream = doc.stream, start = 0, end = 20))
        compose.onNodeWithTag("forum-reader").performScrollToNode(hasText("Prepare your workspace"))
        compose.onNodeWithText("Load image").performScrollTo().assertIsDisplayed()
    }

    @Test fun refreshingLocalLibraryKeepsItsDestination() {
        var destination: ForumTab? = null
        var requestedFeed = false
        compose.setContent { UitTheme(dark = true, dynamic = false) { ForumContent(state().copy(tab = ForumTab.SAVED), onTab = { destination = it }, onLatest = { _, _ -> requestedFeed = true }) } }
        compose.onNodeWithContentDescription("Refresh").performClick()
        org.junit.Assert.assertEquals(ForumTab.SAVED, destination)
        org.junit.Assert.assertFalse(requestedFeed)
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
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-mdpi")
    fun unavailable360() {
        show(state().copy(topic = ForumFixtures.topic, error = ForumFailure.UNAVAILABLE, posts = emptyList()))
        compose.onNodeWithText("This content is private, removed or unavailable to an anonymous reader.").assertExists()
    }

    @Test fun rateLimit390() {
        show(state().copy(error = ForumFailure.RATE_LIMIT))
    }
}
