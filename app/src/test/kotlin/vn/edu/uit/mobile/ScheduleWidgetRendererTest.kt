package vn.edu.uit.mobile

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.datetime.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import vn.edu.uit.mobile.common.*
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.model.*
import vn.edu.uit.mobile.widgets.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScheduleWidgetRendererTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val data = WorkspacePreviewData
    private val state get() = BuildScheduleWidgetUseCase()(data.snapshot, true, data.now)

    @Before fun preferences() {
        context.getSharedPreferences("appearance", Context.MODE_PRIVATE).edit().clear().putBoolean("dark", true).putBoolean("dynamic", false).commit()
    }

    @Test fun compactWidgetUsesRealRemoteViewsAndOpensTheSelectedDate() {
        val view = render(ScheduleWidgetKind.NEXT, state, 360, 144, "widget-next-360-phone")
        assertEquals("Advanced Database Systems", view.findViewById<TextView>(R.id.widget_row_0_title).text.toString())
        assertTrue(view.findViewById<TextView>(R.id.widget_row_0_room).text.contains("B3.14"))
        assertTrue(view.findViewById<TextView>(R.id.widget_saved).text.contains("07:55"))
        view.findViewById<View>(R.id.widget_row_0).performClick()
        val launched = shadowOf(context as Application).nextStartedActivity
        assertEquals(WidgetRoute("schedule", "2026-10-26"), WidgetRoute.read(launched))
        assertEquals(MainActivity::class.java.name, launched.component?.className)
    }

    @Test fun todayWidgetShowsTheNextClassesAndADeadline() {
        val view = render(ScheduleWidgetKind.TODAY, state, 390, 260, "widget-today-390-phone")
        assertEquals(View.VISIBLE, view.findViewById<View>(R.id.widget_row_1).visibility)
        assertEquals(View.GONE, view.findViewById<View>(R.id.widget_row_2).visibility)
        assertTrue(view.findViewById<TextView>(R.id.widget_deadline_title).text.contains("Architecture review"))
        view.findViewById<View>(R.id.widget_deadline).performClick()
        assertEquals(WidgetRoute("deadlines"), WidgetRoute.read(shadowOf(context as Application).nextStartedActivity))
    }

    @Test fun largerWidgetCanShowThreeClassesWithDeviceColors() {
        context.getSharedPreferences("appearance", Context.MODE_PRIVATE).edit().putBoolean("dynamic", true).commit()
        val extra = data.snapshot.timetable.first().copy(classCode = "TEST.003", courseName = "Networking laboratory", lessonStart = 9, lessonEnd = 10, room = "C.02")
        val expanded = BuildScheduleWidgetUseCase()(data.snapshot.copy(timetable = data.snapshot.timetable + extra), true, data.now)
        val view = render(ScheduleWidgetKind.TODAY, expanded, 412, 335, "widget-today-412-device-colors-phone")
        assertEquals(View.VISIBLE, view.findViewById<View>(R.id.widget_row_2).visibility)
    }

    @Test fun largerTextKeepsTheRoomAndDeadlineInsideACompactPhone() {
        val scaled = context.createConfigurationContext(Configuration(context.resources.configuration).apply { fontScale = 1.3f })
        val view = render(ScheduleWidgetKind.TODAY, state, 360, 260, "widget-today-360-large-text-phone", scaled)
        assertEquals(View.GONE, view.findViewById<View>(R.id.widget_row_1).visibility)
        assertInside(view, R.id.widget_row_0_room)
        assertInside(view, R.id.widget_deadline_title)
        assertInside(view, R.id.widget_saved)
    }

    @Test fun smallerLauncherCellsKeepTheRoomAndSavedTimeInsideTheWidget() {
        val scaled = context.createConfigurationContext(Configuration(context.resources.configuration).apply { fontScale = 1.3f })
        for ((kind, height) in listOf(ScheduleWidgetKind.NEXT to 124, ScheduleWidgetKind.TODAY to 180)) {
            val view = render(kind, state, 360, height, renderingContext = scaled)
            assertInside(view, R.id.widget_row_0_room)
            assertInside(view, R.id.widget_saved)
            if (kind == ScheduleWidgetKind.TODAY) {
                assertInside(view, R.id.widget_deadline_title)
            } else {
                assertEquals(View.GONE, view.findViewById<View>(R.id.widget_compact_deadline).visibility)
            }
        }
    }

    @Test fun signOutReplacesCourseDataWithASignInAction() {
        val disconnected = BuildScheduleWidgetUseCase()(data.snapshot, false, data.now)
        val view = render(ScheduleWidgetKind.NEXT, disconnected, 360, 144, "widget-disconnected-360-phone")
        assertTrue(view.findViewById<TextView>(R.id.widget_row_0_title).text.contains("Sign in"))
        assertFalse(allText(view).contains("Advanced Database"))
        view.findViewById<View>(R.id.widget_row_0).performClick()
        assertEquals("profile", WidgetRoute.read(shadowOf(context as Application).nextStartedActivity)?.destination)
    }

    @Test fun assignmentOnlyDeadlineUsesTheScheduleDateAndAnUnknownStatusLabel() {
        val snapshot = data.snapshot.copy(events = emptyList(), assignments = listOf(Assignment(500, 5, 600, "Test coursework", due = data.now + 3600, accountId = "preview")))
        val widget = BuildScheduleWidgetUseCase()(snapshot, true, data.now)
        val view = render(ScheduleWidgetKind.NEXT, widget, 360, 144)
        assertTrue(view.findViewById<TextView>(R.id.widget_compact_deadline).text.contains("Check status"))
        view.findViewById<View>(R.id.widget_compact_deadline).performClick()
        assertEquals(WidgetRoute("schedule", "2026-10-26"), WidgetRoute.read(shadowOf(context as Application).nextStartedActivity))
    }

    @Test fun overdueDeadlineOpensTheOverdueQueue() {
        val widget = BuildScheduleWidgetUseCase()(data.snapshot.copy(events = listOf(data.events.first().copy(start = data.now - 60))), true, data.now)
        val view = render(ScheduleWidgetKind.TODAY, widget, 390, 260)
        assertTrue(view.findViewById<TextView>(R.id.widget_deadline_meta).text.contains("Overdue"))
        view.findViewById<View>(R.id.widget_deadline).performClick()
        assertEquals("overdue", WidgetRoute.read(shadowOf(context as Application).nextStartedActivity)?.destination)
    }

    @Test fun routeValidationRejectsUnknownOriginsActionsAndInvalidDates() {
        for (uri in listOf("https://widget/schedule", "uit-mobile://external/schedule", "uit-mobile://widget/delete", "uit-mobile://widget/schedule?date=2026-99-99", "uit-mobile://widget:8080/schedule")) {
            assertNull(WidgetRoute.read(Intent(Intent.ACTION_VIEW, Uri.parse(uri))))
        }
        assertNull(WidgetRoute.read(Intent(Intent.ACTION_MAIN, Uri.parse("uit-mobile://widget/schedule"))))
        assertNotEquals(WidgetRoute("schedule", "2026-10-26").pendingIntent(context), WidgetRoute("deadlines").pendingIntent(context))
    }

    @Test fun frameHasThreeSmallCutsAndALargerPhysicalBottomRightCut() {
        val bitmap = ScheduleWidgetRenderer.frame(320, 144, Color.WHITE, Color.BLUE)
        assertEquals(0, Color.alpha(bitmap.getPixel(1, 1)))
        assertEquals(0, Color.alpha(bitmap.getPixel(318, 1)))
        assertEquals(0, Color.alpha(bitmap.getPixel(318, 142)))
        assertTrue(Color.alpha(bitmap.getPixel(300, 130)) > 0)
        assertEquals(0, Color.alpha(bitmap.getPixel(310, 139)))
    }

    @Test
    @Config(sdk = [26])
    fun androidEightCanInflateTheWidgetAndUseTheUitPalette() {
        context.getSharedPreferences("appearance", Context.MODE_PRIVATE).edit().putBoolean("dynamic", true).commit()
        val view = render(ScheduleWidgetKind.NEXT, state, 360, 144)
        assertTrue(view.findViewById<TextView>(R.id.widget_row_0_room).text.contains("B3.14"))
        assertEquals(View.VISIBLE, view.findViewById<View>(R.id.widget_compact_deadline).visibility)
    }

    private fun render(kind: ScheduleWidgetKind, widget: ScheduleWidgetState, phoneWidth: Int, height: Int, image: String? = null, renderingContext: Context = context): View {
        val width = phoneWidth - 32
        val remote = ScheduleWidgetRenderer.render(renderingContext, kind, widget, width, height, data.now)
        val container = FrameLayout(renderingContext).apply {
            setBackgroundColor(Color.rgb(6, 20, 38))
            setPadding(16, 16, 16, 16)
        }
        val view = remote.apply(renderingContext, container)
        container.addView(view, FrameLayout.LayoutParams(width, height))
        container.measure(View.MeasureSpec.makeMeasureSpec(phoneWidth, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height + 32, View.MeasureSpec.EXACTLY))
        container.layout(0, 0, phoneWidth, height + 32)
        if (image != null && java.lang.Boolean.getBoolean("roborazzi.test.record")) {
            val bitmap = Bitmap.createBitmap(phoneWidth, height + 32, Bitmap.Config.ARGB_8888)
            container.draw(Canvas(bitmap))
            File(System.getProperty("uit.preview.dir"), "$image.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        return view
    }

    private fun allText(view: View): String = if (view is TextView) {
        view.text.toString()
    } else if (view is ViewGroup) {
        (0 until view.childCount).joinToString { allText(view.getChildAt(it)) }
    } else {
        ""
    }
    private fun assertInside(root: View, id: Int) {
        val child = root.findViewById<View>(id)
        var bottom = child.bottom
        var ancestor = child.parent as? View
        while (ancestor != null && ancestor != root) {
            bottom += ancestor.top
            ancestor = ancestor.parent as? View
        }
        assertTrue("Widget content must stay inside its frame", bottom <= root.height)
        assertTrue(child.height > 0)
    }
}
