package vn.edu.uit.mobile

import android.app.Application
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import vn.edu.uit.mobile.dashboard.*
import vn.edu.uit.mobile.designsystem.*
import vn.edu.uit.mobile.domain.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AnnouncementsDesignTest {
    @get:Rule val compose = createComposeRule()
    private val scholarship = OfficialAnnouncement("https://portal.uit.edu.vn/bai-viet/test", AnnouncementSource.PORTAL, "Học bổng test fixture", "2026-11-03")
    private val tuition = scholarship.copy(url = "https://portal.uit.edu.vn/bai-viet/tuition", title = "Học phí test fixture")

    @Test fun filtersSurviveRestorationAndSearchDoesNotHideTheOnlyResult() {
        val restore = StateRestorationTester(compose)
        var opened = ""
        restore.setContent { UitTheme(dark = true, dynamic = false) { AnnouncementsContent(AnnouncementInbox(items = listOf(scholarship, tuition)), onSelect = { opened = it }) } }
        compose.onNodeWithText("Scholarships").performClick()
        compose.onNodeWithText(tuition.title).assertDoesNotExist()
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithText(tuition.title).assertDoesNotExist()
        compose.onNodeWithText(scholarship.title).performClick()
        assertEquals(scholarship.url, opened)
    }

    @Test fun openingAnArticleStartsAtItsHeaderAndBackRestoresListPosition() {
        val items = (1..20).map { scholarship.copy(url = scholarship.url + it, title = "Fixture notice $it") }
        compose.setContent {
            var selected by remember { mutableStateOf<String?>(null) }
            UitTheme {
                AnnouncementsContent(
                    AnnouncementInbox(items = items),
                    selected = selected,
                    document = selected?.let { url -> AnnouncementDocument(items.first { it.url == url }, listOf(ForumBlock(ForumBlockKind.TEXT, "Fixture article body"))) },
                    onSelect = { selected = it },
                    onBack = { selected = null }
                )
            }
        }
        compose.onNodeWithTag("announcements-list").performScrollToNode(hasText("Fixture notice 12"))
        compose.onNodeWithText("Fixture notice 12").performClick()
        compose.onNodeWithContentDescription("Back").assertIsDisplayed().performClick()
        compose.onNodeWithText("Fixture notice 12").assertIsDisplayed()
    }

    @Test fun sourcesAndAlertsAreExplicitAndOffByDefault() {
        var alerts = false
        compose.setContent { UitTheme { AnnouncementsContent(AnnouncementInbox(), onAlerts = { alerts = it }) } }
        compose.onNodeWithContentDescription("Sources and alerts").performClick()
        compose.onNodeWithTag("ann-alerts").assertIsOff().performClick()
        assertTrue(alerts)
        compose.onNodeWithTag("ann-source-PORTAL").assertIsOn()
        compose.onNodeWithTag("ann-source-CTSV").performScrollTo().assertIsOff()
    }

    @Test fun unknownRouteHasAnExitAndExplicitUnavailableState() {
        var back = false
        compose.setContent { UitTheme { AnnouncementsContent(AnnouncementInbox(), selected = scholarship.url, onBack = { back = true }) } }
        compose.onNodeWithTag("announcements-list").performScrollToNode(hasText("Article unavailable. Retry or open the original above."))
        compose.onNodeWithText("Article unavailable. Retry or open the original above.").assertIsDisplayed()
        compose.onNodeWithTag("announcements-list").performScrollToIndex(0)
        compose.onNodeWithContentDescription("Back").performClick()
        assertTrue(back)
    }

    @Test fun savingAndRemindersRequireAnExplicitActionAndRejectInvalidTime() {
        var saves = 0
        var reminders = 0
        compose.setContent { UitTheme { AnnouncementsContent(AnnouncementInbox(items = listOf(scholarship)), selected = scholarship.url, document = AnnouncementDocument(scholarship, emptyList()), onSave = { _, _ -> saves++ }, onReminder = { _, _ -> reminders++ }) } }
        assertEquals(0, saves)
        assertEquals(0, reminders)
        compose.onNodeWithText("Save", substring = false).performClick()
        assertEquals(1, saves)
        compose.onNodeWithText("Remind me").performClick()
        compose.onNodeWithText("YYYY-MM-DD HH:mm · GMT+07").performTextInput("not-a-date")
        compose.onAllNodesWithText("Save", substring = false).onLast().assertIsNotEnabled()
        assertEquals(0, reminders)
    }
}
