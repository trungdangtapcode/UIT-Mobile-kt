package vn.edu.uit.mobile

import android.app.Application
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.*
import vn.edu.uit.mobile.daa.PublicAnnouncementClient
import vn.edu.uit.mobile.dashboard.*
import vn.edu.uit.mobile.data.*
import vn.edu.uit.mobile.designsystem.*
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.forum.*
import vn.edu.uit.mobile.forum.network.ForumParser
import vn.edu.uit.mobile.model.*

/** Actual public UIT response/model replay only. No fixture or authored answer capture. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "vi-rVN-w390dp-h844dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalRoborazziApi::class)
class ForumDigestLiveDesignTest {
    @get:Rule val compose = createComposeRule()

    @After fun resetScale() {
        RuntimeEnvironment.setFontScale(1f)
    }
    private class Memory(private val values: MutableMap<String, String> = mutableMapOf()) : StudyStorage {
        override fun read(key: String) = values[key]
        override fun write(key: String, value: String) {
            values[key] = value
        }
        override fun remove(key: String) {
            values.remove(key)
        }
        override fun removeAccount(accountId: String) = error("No university account")
    }
    private fun input(): File {
        Assume.assumeTrue(java.lang.Boolean.getBoolean("uit.assistant.preview"))
        return File(requireNotNull(System.getProperty("uit.assistant.capture.input")), "forum").also {
            Assume.assumeTrue("Run the actual public Forum model evaluation first", File(it, "report.json").isFile && File(it, "workspaces.json").isFile)
        }
    }
    private fun state(): AssistantUiState = runBlocking {
        val owner = object : AccountRepository {
            override val accounts = MutableStateFlow(emptyList<Account>())
            override suspend fun remove(accountId: String) = error("No account mutation")
            override suspend fun saveSessions(sessions: List<PortalSession>) = error("No session access")
        }
        val notices = LocalAnnouncementRepository(PublicAnnouncementClient(), Memory())
        notices.initialize()
        val repo = createAssistantRepository(notices, ConnectedStudyAiRepository(Memory(), owner), Memory(mutableMapOf("library" to File(input(), "workspaces.json").readText())))
        repo.initialize()
        return@runBlocking AssistantUiState(library = repo.library.value, selected = repo.library.value.workspaces.single().id, notices = notices.state.value)
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(80)
        compose.waitForIdle()
        val output = File(requireNotNull(System.getProperty("uit.assistant.capture.output")), "forum").apply { mkdirs() }
        captureScreenRoboImage(File(output, "$name.png").absolutePath)
    }
    private fun conversation(width: Int, large: Boolean = false, dark: Boolean = true) {
        val initial = state()
        if (large) RuntimeEnvironment.setFontScale(1.3f)
        val workspace = initial.library.workspaces.single()
        val suffix = "$width${if (large) "-large-text" else ""}${if (!dark) "-light" else ""}"
        compose.setContent { UitTheme(dark = dark, dynamic = false) { WorkspaceFrame(0, {}, {}, "", false, false, 0) { AssistantContent(initial) } } }
        compose.onNodeWithTag("assistant-content").performScrollToIndex(0)
        capture("forum-digest-chat-$suffix")
        compose.onNodeWithTag("assistant-content").performScrollToIndex(3 + workspace.turns.first().facts.size)
        capture("forum-digest-followup-$suffix")
        if (width == 390 && dark) {
            val first = workspace.turns.first()
            compose.onNodeWithTag("assistant-content").performScrollToNode(hasTestTag("assistant-sources-${first.id}"))
            compose.onNodeWithTag("assistant-sources-${first.id}").performClick()
            compose.onNodeWithTag("assistant-content").performScrollToNode(hasTestTag("assistant-reference-${first.id}-0"))
            capture("forum-digest-sources-390")
            compose.onNodeWithTag("assistant-reference-${first.id}-0").performClick()
            capture("forum-digest-evidence-390")
        }
    }

    @Test fun actualConversation390() = conversation(390)

    @Test
    @Config(qualifiers = "vi-rVN-w360dp-h800dp-mdpi")
    fun actualConversation360() = conversation(360)

    @Test
    @Config(qualifiers = "vi-rVN-w412dp-h915dp-mdpi")
    fun actualConversation412LargeText() = conversation(412, true)

    @Test fun actualConversation390Light() = conversation(390, dark = false)

    @Test fun actualForumEntryAndRange390() {
        val file = File(input(), "topic-reader.json")
        Assume.assumeTrue(file.isFile)
        val document = ForumParser.parse(ForumRequest.Topic(163707), file.readText())
        val topic = requireNotNull(document.topic)
        val initial = ForumState(open = true, publicCitation = true, topic = topic, stream = document.stream, posts = document.posts, end = document.posts.size)
        compose.setContent { UitTheme(dark = true, dynamic = false) { WorkspaceFrame(3, {}, {}, "", false, false, 0) { ForumContent(initial) } } }
        capture("forum-digest-entry-390")
        compose.onNodeWithTag("forum-reader").performScrollToNode(hasTestTag("forum-ai-entry"))
        compose.onNodeWithTag("forum-ai-entry").performClick()
        compose.onNodeWithTag("forum-ai-count").performTextReplacement("2")
        capture("forum-digest-range-390")
    }
}
