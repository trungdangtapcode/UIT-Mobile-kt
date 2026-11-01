package vn.edu.uit.mobile

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import java.io.File
import kotlinx.datetime.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.*
import vn.edu.uit.mobile.common.*
import vn.edu.uit.mobile.designsystem.*
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.feature.schedule.R as ScheduleStrings
import vn.edu.uit.mobile.model.*
import vn.edu.uit.mobile.schedule.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalRoborazziApi::class)
class TimetableImageDesignTest {
    @get:Rule val compose = createComposeRule()
    private val day = LocalDate(2026, 10, 26)
    private val slot = DaaTimetableSlot("IT004.O21", "IT004", "Database Systems", 3, 1, 1, 3, "A101", "Synthetic lecturer", "$day", "2026-12-28")
    private val snapshot = PortalSnapshot(timetable = listOf(slot, slot.copy(classCode = "SE104.O21", courseName = "Software Engineering", lessonStart = 6, lessonEnd = 8, room = "B202")), profile = StudentProfile("SYNTHETIC-ID", "Sample student"), updatedAt = dateAt(day, LocalTime(6, 0)))

    @After fun restore() {
        RuntimeEnvironment.setFontScale(1f)
    }

    @Test fun previewRequiresExplicitShareAndOnlySendsTheVisiblePrivacySelection() {
        var shared: TimetableImagePlan? = null
        var bytes: ByteArray? = null
        compose.setContent {
            Frame {
                TimetableImageContent(snapshot, day, onShare = { plan, bitmap ->
                    shared = plan
                    bytes = java.io.ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
                })
            }
        }
        ready("Share PNG")
        assertNull(shared)
        compose.onNodeWithTag("timetable-image-content").performScrollToNode(hasTestTag("timetable-image-preview"))
        capture("timetable-image-preview-390-phone")
        compose.onNodeWithText("Share PNG").performClick()
        assertEquals(2, shared!!.count)
        assertEquals("", shared!!.identity)
        assertTrue(shared!!.days.single().rows.all { it.room.isBlank() })
        if (java.lang.Boolean.getBoolean("roborazzi.test.record")) File(System.getProperty("uit.preview.dir"), "timetable-shared-image.png").writeBytes(requireNotNull(bytes))
        compose.onNodeWithTag("timetable-image-content").performScrollToNode(hasText("Course names and class codes"))
        assertTrue(compose.onNodeWithText("Course names and class codes").fetchSemanticsNode().boundsInRoot.height >= 48f)
        compose.onNodeWithText("Course names and class codes").performClick()
        compose.onNodeWithText("Rooms", substring = false).performScrollTo().performClick()
        compose.onNodeWithText("Student name and ID").performScrollTo().performClick()
        ready("Share PNG")
        compose.onNodeWithText("Share PNG").performClick()
        assertEquals("Sample student · SYNTHETIC-ID", shared!!.identity)
        assertTrue(shared!!.days.single().rows.all { it.title.isBlank() && it.classCode.isBlank() })
        assertEquals(listOf("A101", "B202"), shared!!.days.single().rows.map { it.room })
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-mdpi")
    fun invalidRangePreventsSharingAndAnEmptySavedDayIsExplicit() {
        var shared = false
        compose.setContent { Frame { TimetableImageContent(snapshot.copy(timetable = emptyList()), day, onShare = { _, _ -> shared = true }) } }
        ready("Share PNG")
        compose.onNodeWithText("Through · YYYY-MM-DD").performScrollTo().performTextReplacement("2026-10-25")
        compose.onNodeWithText("Share PNG").assertIsNotEnabled()
        assertFalse(shared)
        compose.onNodeWithText("Through · YYYY-MM-DD").performTextReplacement("2026-10-26")
        ready("Share PNG")
        compose.onNodeWithTag("timetable-image-content").performScrollToNode(hasText("Read image contents"))
        compose.onNodeWithText("Read image contents").performClick()
        compose.onNodeWithTag("timetable-image-content").performScrollToNode(hasText("No classes in saved data."))
        capture("timetable-image-empty-360-phone")
    }

    @Test
    @Config(qualifiers = "vi-w412dp-h915dp-mdpi")
    fun vietnameseLargeTextKeepsPrivacyChoicesAndShareReachable() {
        RuntimeEnvironment.setFontScale(1.3f)
        val app = RuntimeEnvironment.getApplication()
        val share = app.getString(ScheduleStrings.string.ti_share)
        val identity = app.getString(ScheduleStrings.string.ti_identity)
        compose.setContent { Frame { TimetableImageContent(snapshot, day) } }
        ready(share)
        compose.onNodeWithTag("timetable-image-content").performScrollToNode(hasText(identity))
        compose.onNodeWithText(identity).assertIsOff().assertIsDisplayed()
        capture("timetable-image-privacy-412-vietnamese-phone")
        compose.onNodeWithText(share).assertIsDisplayed()
    }

    @Test fun changedSourceReplacesThePreviewAndMissingSavedDataDisablesShare() {
        var source by mutableStateOf(snapshot)
        var shared: TimetableImagePlan? = null
        compose.setContent { Frame { TimetableImageContent(source, day, onShare = { plan, _ -> shared = plan }) } }
        ready("Share PNG")
        compose.runOnIdle { source = snapshot.copy(timetable = listOf(slot.copy(courseName = "Changed course"))) }
        ready("Share PNG")
        compose.onNodeWithText("Share PNG").performClick()
        assertEquals("Changed course", shared!!.days.single().rows.single().title)
        compose.runOnIdle { source = PortalSnapshot() }
        compose.onNodeWithText("Share PNG").assertIsNotEnabled()
    }

    @Test fun scheduleExposesImageSharingAndBackReturnsWithoutSharing() {
        var opened = false
        var back = false
        var show by mutableStateOf(false)
        compose.setContent {
            Frame {
                if (show) {
                    TimetableImageContent(snapshot, day, onBack = {
                        show = false
                        back = true
                    })
                } else {
                    ScheduleContent(ScheduleState(snapshot, day), onShareImage = {
                        opened = true
                        show = true
                    })
                }
            }
        }
        compose.onNodeWithText("Share timetable image").performClick()
        assertTrue(opened)
        compose.onAllNodesWithText("Schedule", substring = false).onFirst().performClick()
        assertTrue(back)
        compose.onNodeWithText("Share timetable image").assertExists()
    }

    private fun ready(label: String) {
        compose.waitUntil(20_000) { compose.onAllNodes(hasText(label) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
    }

    @Composable private fun Frame(content: @Composable () -> Unit) {
        UitTheme(dark = true, dynamic = false) { WorkspaceFrame(1, {}, {}, "Synthetic", true, false, snapshot.updatedAt) { content() } }
    }
    private fun capture(name: String) {
        if (java.lang.Boolean.getBoolean("roborazzi.test.record")) {
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(64)
            compose.waitForIdle()
            captureScreenRoboImage(File(System.getProperty("uit.preview.dir"), "$name.png").absolutePath)
        }
    }
}
