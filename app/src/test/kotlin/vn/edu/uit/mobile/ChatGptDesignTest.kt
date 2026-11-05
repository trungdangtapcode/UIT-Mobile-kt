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
import vn.edu.uit.mobile.dashboard.AssistantContent
import vn.edu.uit.mobile.dashboard.AssistantUiState
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
        var modelReloads = 0
        override suspend fun initialize() = Unit
        override suspend fun signIn(openBrowser: suspend (String) -> Unit, accountId: String?, enablePlan: Boolean) {
            signIns++
        }
        override fun cancelSignIn() = Unit
        override suspend fun selectAccount(id: String) {
            state.value = state.value.copy(selected = id)
        }
        override suspend fun reloadModels() {
            modelReloads++
        }
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
        val connector = Connector(ChatGptState(listOf(ChatGptAccount("test", "UI fixture account", true, true)), "test", listOf(ChatGptModel("test-model", "Test model")), busy = true, welcome = true))
        compose.setContent { CompositionLocalProvider(LocalChatGptConnector provides connector) { UitTheme { ChatGptConnectionPanel({}) } } }
        compose.onNodeWithText("Got it").assertDoesNotExist()
        compose.runOnIdle { connector.state.value = connector.state.value.copy(busy = false) }
        compose.onNodeWithText("Got it").assertIsDisplayed().performClick()
        compose.waitUntil { !connector.state.value.welcome }
    }

    @Test fun catalogFailureRetainsTheAccountAndExposesRetryBeforeExplicitActivation() {
        val connector = Connector(
            ChatGptState(
                listOf(ChatGptAccount("test", "Saved ChatGPT account", true, true)),
                "test",
                problem = ChatGptProblem.RESPONSE,
                welcome = true,
                diagnostic = ChatGptDiagnostic(200, "response_too_large", operation = "models")
            )
        )
        var saves = 0
        compose.setContent {
            CompositionLocalProvider(LocalChatGptConnector provides connector) {
                UitTheme {
                    StudyConnectionDialog(StudyConnection(), {}, { _, _, _, _ -> saves++ }, {})
                }
            }
        }
        compose.onNodeWithText("Got it").assertDoesNotExist()
        compose.onNodeWithTag("chatgpt-use").assertDoesNotExist()
        compose.onNodeWithTag("chatgpt-continue").assertDoesNotExist()
        compose.onNodeWithTag("chatgpt-copy-diagnostics").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("chatgpt-reload-models").performScrollTo().assertIsDisplayed()
        if (java.lang.Boolean.getBoolean("roborazzi.test.record")) {
            // Private inspection only: a controlled recovery fixture, never published as subscriber evidence.
            val output = File(System.getProperty("java.io.tmpdir"), "uit-chatgpt-catalog-recovery.png")
            captureScreenRoboImage(output.absolutePath)
        }
        compose.onNodeWithTag("chatgpt-reload-models").performClick()
        compose.waitUntil { connector.modelReloads == 1 }
        assertEquals(0, connector.signIns)
        assertEquals(0, saves)
        compose.runOnIdle {
            connector.state.value = connector.state.value.copy(models = listOf(ChatGptModel("recovered-model", "Recovered model")), problem = null, diagnostic = null)
        }
        compose.onNodeWithText("Got it").performClick()
        compose.onNodeWithTag("chatgpt-use").performScrollTo().performClick()
        assertEquals(1, saves)
    }

    @Test fun modelLoadingDoesNotAskTheUserToCompleteBrowserConsentAgain() {
        val connector = Connector(ChatGptState(listOf(ChatGptAccount("test", "Saved ChatGPT account", true, true)), "test", busy = true, loadingModels = true))
        compose.setContent { CompositionLocalProvider(LocalChatGptConnector provides connector) { UitTheme { ChatGptConnectionPanel({}) } } }
        compose.onNodeWithText("Loading models for this ChatGPT account…").assertIsDisplayed()
        compose.onNodeWithText("Complete the connection in your browser, then return here. No AI request is sent during sign-in.").assertDoesNotExist()
        compose.onNodeWithTag("chatgpt-use").assertDoesNotExist()
    }

    @Test fun assistantConnectionDialogKeepsCatalogDiagnosticsAndRetryReachableOnAPhone() {
        val connector = Connector(
            ChatGptState(
                listOf(ChatGptAccount("test", "Saved ChatGPT account", true, true)),
                "test",
                problem = ChatGptProblem.RESPONSE,
                diagnostic = ChatGptDiagnostic(200, "response_too_large", operation = "models")
            )
        )
        compose.setContent {
            CompositionLocalProvider(LocalChatGptConnector provides connector) {
                UitTheme(dark = true, dynamic = false) {
                    WorkspaceFrame(0, {}, {}, "", false, false, 0) { AssistantContent(AssistantUiState()) }
                }
            }
        }
        compose.onNodeWithContentDescription("AI connection").performClick()
        compose.onNodeWithTag("chatgpt-copy-diagnostics").performScrollTo().assertIsDisplayed()
        if (java.lang.Boolean.getBoolean("roborazzi.test.record")) {
            // Local controlled-state inspection, not an authenticated subscriber capture.
            captureScreenRoboImage(File(System.getProperty("java.io.tmpdir"), "uit-chatgpt-assistant-recovery.png").absolutePath)
        }
        compose.onNodeWithTag("chatgpt-reload-models").performScrollTo().performClick()
        compose.waitUntil { connector.modelReloads == 1 }
        compose.onNodeWithTag("chatgpt-use").assertDoesNotExist()
    }
}
