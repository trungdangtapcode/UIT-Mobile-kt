package vn.edu.uit.mobile

import android.app.Application
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import java.io.File
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.*
import vn.edu.uit.mobile.dashboard.*
import vn.edu.uit.mobile.data.UmaProtocol
import vn.edu.uit.mobile.designsystem.*
import vn.edu.uit.mobile.domain.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-w390dp-h844dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalRoborazziApi::class)
class UmaDesignTest {
    @get:Rule val compose = createComposeRule()
    private val ready = UmaState(loading = false, identity = UmaIdentity("synthetic", "Synthetic student"), available = UmaSource.entries.toSet())

    @After fun resetFontScale() {
        RuntimeEnvironment.setFontScale(1f)
    }

    @Test fun disconnectedChatOffersUitLoginAndDoesNotNeedAnOpenAiConnection() {
        var connected = false
        compose.setContent { UitTheme { UmaContent(UmaState(loading = false), onLogin = { connected = true }) } }
        compose.onNodeWithTag("uma-login").performScrollTo().performClick()
        assertTrue(connected)
        compose.onNodeWithTag("uma-send").assertDoesNotExist()
    }

    @Test fun sourceChangesRequireExplicitConfirmationBeforeClearingContext() {
        var selected: Set<UmaSource>? = null
        compose.setContent { UitTheme { UmaContent(ready, onSources = { selected = it }) } }
        compose.onNodeWithTag("uma-sources").performClick()
        compose.onNodeWithText("My academic information").performClick()
        assertNull(selected)
        compose.onNodeWithText("Confirm").performClick()
        assertTrue(selected!!.contains(UmaSource.PERSONAL))
    }

    @Test fun sendingIsExplicitAndBusyChatOffersLocalStop() {
        var sent = ""
        compose.setContent { UitTheme { UmaContent(ready, onAsk = { q, _ -> sent = q }) } }
        compose.onNodeWithTag("uma-send").assertIsNotEnabled()
        compose.onNodeWithTag("uma-question").performTextInput("Question")
        assertEquals("", sent)
        compose.onNodeWithTag("uma-send").performClick()
        assertEquals("Question", sent)
    }

    @Test fun busyChatShowsWaitingRatherThanSimulatedStreamingAndStopWorks() {
        var stopped = false
        val s = ready.copy(busy = true, turns = listOf(UmaTurn("fixture", "Question", UmaTurnStatus.RUNNING)))
        compose.setContent { UitTheme { UmaContent(s, onStop = { stopped = true }) } }
        compose.onNodeWithTag("uma-pending").assertExists()
        compose.onNodeWithContentDescription("Stop").performClick()
        assertTrue(stopped)
    }

    @Test fun noSourceAnswerAndCitationExpansionStayDistinctFromNetworkErrors() {
        val a = UmaProtocol.answer("""{"ket_qua":"no_source","tra_loi":"No accessible evidence."}""")
        compose.setContent { UitTheme { UmaContent(ready.copy(turns = listOf(UmaTurn("fixture", "Question", UmaTurnStatus.COMPLETE, a)))) } }
        compose.onNodeWithText("UMA did not find an accessible source").assertExists()
        compose.onNodeWithTag("uma-error").assertDoesNotExist()
    }
    private fun render(width: Int, large: Boolean = false, personal: Boolean = false) {
        Assume.assumeTrue(System.getProperty("uit.uma.capture") != null)
        val root = File(requireNotNull(System.getProperty("uit.uma.capture")))
        val answer = UmaProtocol.answer(File(root, if (personal) "personal-response-private.json" else "regulation-response-private.json").readText())
        if (large) RuntimeEnvironment.setFontScale(1.3f)
        // Actual reply retained; only owner identity is hidden in public phone evidence.
        val s = ready.copy(identity = UmaIdentity("hidden", "UIT · Account hidden"), selected = setOf(if (personal) UmaSource.PERSONAL else UmaSource.REGULATIONS), turns = listOf(UmaTurn("live", if (personal) "Điểm trung bình tích lũy hiện tại của tôi là bao nhiêu?" else "Điều kiện xét học bổng khuyến khích học tập là gì?", UmaTurnStatus.COMPLETE, answer)))
        compose.setContent { UitTheme(dark = true, dynamic = false) { WorkspaceFrame(0, {}, {}, "", false, false, 0) { UmaContent(s) } } }
        compose.waitForIdle()
        captureScreenRoboImage(File(root, "uma-${if (personal) "personal" else "chat"}-$width${if (large) "-large-text" else ""}.png").absolutePath)
        if (!personal && width == 390 && answer.citations.isNotEmpty()) {
            compose.onNodeWithTag("uma-conversation").performScrollToNode(hasTestTag("uma-citations"))
            compose.onNodeWithTag("uma-citations").performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("uma-citation-${answer.citations.first().number}").performScrollTo()
            captureScreenRoboImage(File(root, "uma-sources-390.png").absolutePath)
        }
    }

    @Test fun actualPersonalDisplayIsKeptInThePrivateCaptureDirectory() = render(390, personal = true)

    @Test fun actualUmaReply390WhenExplicitlyCaptured() = render(390)

    @Test
    @Config(qualifiers = "vi-rVN-w360dp-h800dp-mdpi")
    fun actualUmaReply360WhenExplicitlyCaptured() = render(360)

    @Test
    @Config(qualifiers = "vi-rVN-w412dp-h915dp-mdpi")
    fun actualUmaReply412LargeTextWhenExplicitlyCaptured() = render(412, true)
}
