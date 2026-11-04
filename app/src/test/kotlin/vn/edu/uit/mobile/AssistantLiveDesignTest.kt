package vn.edu.uit.mobile

import android.app.Application
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.*
import vn.edu.uit.mobile.daa.*
import vn.edu.uit.mobile.dashboard.*
import vn.edu.uit.mobile.data.*
import vn.edu.uit.mobile.designsystem.*
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.model.*

/** Replay an actual public-source capture through production persistence and Compose. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "vi-rVN-w390dp-h844dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalRoborazziApi::class)
class AssistantLiveDesignTest {
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
        override fun removeAccount(accountId: String) = error("No university account access")
    }
    private val owners = object : AccountRepository {
        override val accounts = MutableStateFlow(emptyList<Account>())
        override suspend fun remove(accountId: String) = error("No university account access")
        override suspend fun saveSessions(sessions: List<PortalSession>) = error("No university session access")
    }
    private fun capturedState(answer: Boolean = false): AssistantUiState = runBlocking {
        Assume.assumeTrue(java.lang.Boolean.getBoolean("uit.assistant.preview"))
        val input = File(requireNotNull(System.getProperty("uit.assistant.capture.input")))
        val noticesFile = File(input, if (answer) "notices.json" else "public-notices.json")
        val workspaceFile = File(input, if (answer) "workspaces.json" else "public-workspaces.json")
        Assume.assumeTrue("Capture actual public context or a successful model run first", noticesFile.isFile && workspaceFile.isFile)
        val notices = LocalAnnouncementRepository(PublicAnnouncementClient(), Memory(mutableMapOf("inbox" to noticesFile.readText())))
        notices.initialize()
        val study = ConnectedStudyAiRepository(Memory(), owners)
        val repo = createAssistantRepository(notices, study, Memory(mutableMapOf("library" to workspaceFile.readText())))
        repo.initialize()
        AssistantUiState(library = repo.library.value, notices = notices.state.value)
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(80)
        compose.waitForIdle()
        val output = File(requireNotNull(System.getProperty("uit.assistant.capture.output"))).apply { mkdirs() }
        captureScreenRoboImage(File(output, "$name.png").absolutePath)
    }
    private fun workspace(width: Int, large: Boolean = false) {
        val initial = capturedState()
        if (large) RuntimeEnvironment.setFontScale(1.3f)
        compose.setContent {
            var selected by remember { mutableStateOf<String?>(null) }
            UitTheme(dark = true, dynamic = false) {
                WorkspaceFrame(0, {}, {}, "", false, false, 0) {
                    AssistantContent(initial.copy(selected = selected), onSelect = { selected = it }, onBack = { selected = null })
                }
            }
        }
        capture("assistant-workspaces-$width${if (large) "-large-text" else ""}")
        compose.onAllNodesWithText("Mở workspace").onFirst().performScrollTo().performClick()
        capture("assistant-chat-empty-$width")
        compose.onNodeWithContentDescription("Context và nguồn tin").performClick()
        capture("assistant-context-$width")
    }

    @Test fun actualPublicContext390() = workspace(390)

    @Test
    @Config(qualifiers = "vi-rVN-w360dp-h800dp-mdpi")
    fun actualPublicContext360() = workspace(360)

    @Test
    @Config(qualifiers = "vi-rVN-w412dp-h915dp-mdpi")
    fun actualPublicContext412LargeText() = workspace(412, true)

    @Test fun actualModelAnswerAndCitationWhenSuccessfulLiveEvidenceExists() {
        val initial = capturedState(answer = true)
        val workspace = initial.library.workspaces.first { it.turns.any { turn -> turn.status == AssistantTurnStatus.COMPLETE } }
        compose.setContent { UitTheme(dark = true, dynamic = false) { WorkspaceFrame(0, {}, {}, "", false, false, 0) { AssistantContent(initial.copy(selected = workspace.id)) } } }
        compose.onNodeWithTag("assistant-content").performScrollToIndex(1)
        capture("assistant-real-model-answer-390")
        compose.onNodeWithTag("assistant-content").performScrollToNode(hasText("[1] Portal UIT", substring = true))
        compose.onAllNodes(hasText("[1] Portal UIT", substring = true)).onFirst().performClick()
        capture("assistant-real-model-evidence-390")
    }
}
