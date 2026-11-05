package vn.edu.uit.mobile

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.*
import vn.edu.uit.mobile.courses.StudyConnectionDialog
import vn.edu.uit.mobile.designsystem.*
import vn.edu.uit.mobile.domain.*

/** Only empty sign-in forms are exported. Connected states below are behavior fixtures. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalRoborazziApi::class)
class ChatGptDesignTest {
    @get:Rule val compose = createComposeRule()
    private class Connector(initial: ChatGptState = ChatGptState()) : ChatGptConnector {
        override val state = MutableStateFlow(initial)
        var signIns = 0
        override suspend fun initialize() = Unit
        override suspend fun signIn(openBrowser: suspend (String) -> Unit, accountId: String?, enablePlan: Boolean) {
            signIns++
        }
        override fun cancelSignIn() = Unit
        override suspend fun selectAccount(id: String) {
            state.value = state.value.copy(selected = id)
        }
        override suspend fun reloadModels() = Unit
        override suspend fun signOut() {
            state.value = ChatGptState()
        }
        override suspend fun acknowledgeWelcome() {
            state.value = state.value.copy(welcome = false)
        }
    }

    @After fun reset() {
        RuntimeEnvironment.setFontScale(1f)
    }
    private fun emptyForm(suffix: String, large: Boolean = false, light: Boolean = false) {
        val connector = Connector()
        var saves = 0
        if (large) RuntimeEnvironment.setFontScale(1.3f)
        compose.setContent {
            CompositionLocalProvider(LocalChatGptConnector provides connector) {
                UitTheme(dark = !light, dynamic = false) {
                    WorkspaceFrame(2, {}, {}, "", false, false, 0) {
                        StudyConnectionDialog(StudyConnection(), {}, { _, _, _, _ -> saves++ }, {})
                    }
                }
            }
        }
        compose.onNodeWithText("Your OpenAI API key").assertDoesNotExist()
        compose.onNodeWithTag("chatgpt-continue").performScrollTo().assertIsDisplayed()
        if (java.lang.Boolean.getBoolean("roborazzi.test.record")) {
            val output = File("build/outputs/chatgpt-design").apply { mkdirs() }
            captureScreenRoboImage(File(output, "chatgpt-sign-in-$suffix.png").absolutePath)
        }
        assertEquals(0, connector.signIns)
        assertEquals(0, saves)
        compose.onNodeWithTag("chatgpt-continue").performClick()
        compose.waitUntil { connector.signIns == 1 }
        assertEquals(0, saves)
    }

    @Test fun emptySignIn390() = emptyForm("390")

    @Test
    @Config(qualifiers = "w360dp-h800dp-mdpi")
    fun emptySignIn360() = emptyForm("360")

    @Test
    @Config(qualifiers = "w412dp-h915dp-mdpi")
    fun emptySignIn412Large() = emptyForm("412-large", large = true)

    @Test
    @Config(qualifiers = "vi-rVN-w390dp-h844dp-mdpi")
    fun emptySignInVietnamese() = emptyForm("390-vi")

    @Test fun emptySignInLight() = emptyForm("390-light", light = true)

    @Test fun selectingChatGptNeverReusesTheApiKeyFieldAndReturnsExplicitModelSelection() {
        val connector = Connector(
            ChatGptState(
                listOf(ChatGptAccount("test-account", "UI fixture account", true, true)),
                "test-account",
                listOf(ChatGptModel("first-model", "First model"), ChatGptModel("second-model", "Second model"))
            )
        )
        var saved: Pair<StudyConnectionMode, String>? = null
        compose.setContent {
            CompositionLocalProvider(LocalChatGptConnector provides connector) {
                UitTheme { StudyConnectionDialog(StudyConnection(configured = true, model = "existing-model"), {}, { mode, _, _, model -> saved = mode to model }, {}) }
            }
        }
        compose.onNodeWithText("Your OpenAI API key").performScrollTo().performTextInput("fixture-api-key-never-forwarded")
        compose.onNodeWithText("ChatGPT account").performScrollTo().performClick()
        compose.onNodeWithText("Your OpenAI API key").assertDoesNotExist()
        compose.onNodeWithTag("chatgpt-model").performScrollTo().performClick()
        compose.onNodeWithText("Second model").performClick()
        assertNull(saved)
        compose.onNodeWithTag("chatgpt-use").performScrollTo().performClick()
        assertEquals(StudyConnectionMode.CHATGPT to "second-model", saved)
        assertEquals(0, connector.signIns)
    }

    @Test fun declinedPlanPermissionDoesNotExposeAnInferenceActivationButton() {
        val connector = Connector(ChatGptState(listOf(ChatGptAccount("test", "UI fixture account", true, false)), "test"))
        compose.setContent { CompositionLocalProvider(LocalChatGptConnector provides connector) { UitTheme { ChatGptConnectionPanel({ error("Not authorized") }) } } }
        compose.onNodeWithTag("chatgpt-use").assertDoesNotExist()
        compose.onNodeWithText("Enable ChatGPT plan usage").assertIsDisplayed()
    }

    @Test fun welcomeWaitsUntilTheConnectionOperationCompletes() {
        val connector = Connector(ChatGptState(listOf(ChatGptAccount("test", "UI fixture account", true, true)), "test", busy = true, welcome = true))
        compose.setContent { CompositionLocalProvider(LocalChatGptConnector provides connector) { UitTheme { ChatGptConnectionPanel({}) } } }
        compose.onNodeWithText("Got it").assertDoesNotExist()
        compose.runOnIdle { connector.state.value = connector.state.value.copy(busy = false) }
        compose.onNodeWithText("Got it").assertIsDisplayed().performClick()
        compose.waitUntil { !connector.state.value.welcome }
    }
}
