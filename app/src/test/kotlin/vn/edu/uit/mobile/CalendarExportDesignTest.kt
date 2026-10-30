package vn.edu.uit.mobile

import android.app.Application
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import kotlinx.datetime.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import vn.edu.uit.mobile.common.*
import vn.edu.uit.mobile.designsystem.*
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.model.*
import vn.edu.uit.mobile.schedule.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CalendarExportDesignTest {
    @get:Rule val compose = createComposeRule()

    @After fun restoreFontScale() {
        RuntimeEnvironment.setFontScale(1f)
    }
    private val day = LocalDate(2026, 10, 26)
    private val slot = DaaTimetableSlot("IT004.O21", "IT004", "Database Systems", 3, 1, 1, 3, "A101", "Synthetic lecturer", "$day", "2026-12-28")
    private val snapshot = PortalSnapshot(timetable = listOf(slot), events = listOf(CalendarEvent("synthetic:1", 1, "synthetic", null, "Database project due", dateAt(day, LocalTime(23, 0)), deadline = true)), updatedAt = dateAt(day, LocalTime(6, 0)))

    @Test fun exportRequiresExplicitSaveAndIncludesExactlyThePreviewedSelection() {
        var exported: CalendarExportPlan? = null
        compose.setContent { Frame { CalendarExportContent(snapshot, day, onSave = { exported = it }) } }
        capture("calendar-export-390-phone")
        assertNull(exported)
        compose.onNodeWithTag("calendar-export-content").performScrollToNode(hasText("Preview · 2 events"))
        capture("calendar-export-preview-390-phone")
        compose.onNodeWithText("Database Systems").assertIsDisplayed()
        compose.onNodeWithText("Save .ics file").performClick()
        assertEquals(2, exported!!.entries.size)
        assertEquals(day, exported!!.options.from)
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-mdpi")
    fun invalidDatesAndEmptyTypesDisableExportOnCompactPhones() {
        var sent = false
        compose.setContent { Frame { CalendarExportContent(snapshot, day, onSave = { sent = true }) } }
        capture("calendar-export-360-phone")
        compose.onNodeWithText("End date · YYYY-MM-DD").performScrollTo().performTextReplacement("2026-10-25")
        compose.onNodeWithText("Save .ics file").assertIsNotEnabled()
        compose.onNodeWithText("End date · YYYY-MM-DD").performTextReplacement("2026-10-26")
        for (type in listOf("Classes", "Exams", "Deadlines", "Other events")) {
            compose.onNodeWithText(type, substring = false).performScrollTo().performClick()
        }
        compose.onNodeWithText("Save .ics file").assertIsNotEnabled()
        assertFalse(sent)
    }

    @Test
    @Config(qualifiers = "w412dp-h915dp-mdpi")
    fun largeTextPreviewExplainsCanceledClassesAndMissingDuration() {
        RuntimeEnvironment.setFontScale(1.3f)
        val notice = ClassScheduleChangeNotice(1, ScheduleChangeType.CANCELED, slot.classCode, "Canceled", "", "$day", 1, 3)
        compose.setContent { Frame { CalendarExportContent(snapshot.copy(notices = listOf(notice)), day) } }
        compose.onNodeWithTag("calendar-export-content").performScrollToNode(hasText("Preview · 2 events"))
        capture("calendar-export-412-large-text-phone")
        compose.onNodeWithText("Canceled").assertExists()
        compose.onNodeWithText("2026-10-26 · 23:00 · end time not provided").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Save .ics file").assertIsDisplayed()
    }

    @Test fun emptySavedScheduleDoesNotOfferAFileAndBackReturnsToSchedule() {
        var back = false
        compose.setContent { Frame { CalendarExportContent(PortalSnapshot(), day, onBack = { back = true }) } }
        compose.onNodeWithText("Save .ics file").assertIsNotEnabled()
        compose.onAllNodesWithText("Schedule", substring = false).onFirst().performClick()
        assertTrue(back)
    }

    @Test fun scheduleExposesTheNativeExportEntryPoint() {
        var opened = false
        compose.setContent { Frame { ScheduleContent(ScheduleState(snapshot, day), onExport = { opened = true }) } }
        compose.onNodeWithContentDescription("Export calendar file").performClick()
        assertTrue(opened)
    }

    @Composable private fun Frame(content: @Composable () -> Unit) {
        UitTheme(dark = true, dynamic = false) { WorkspaceFrame(1, {}, {}, "Synthetic student", true, false, snapshot.updatedAt) { content() } }
    }
    private fun capture(name: String) {
        if (java.lang.Boolean.getBoolean("roborazzi.test.record")) compose.onRoot().captureRoboImage(File(System.getProperty("uit.preview.dir"), "$name.png").absolutePath)
    }
}
