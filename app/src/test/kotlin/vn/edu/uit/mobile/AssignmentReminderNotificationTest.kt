package vn.edu.uit.mobile

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import vn.edu.uit.mobile.data.*
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.model.*
import vn.edu.uit.mobile.widgets.WidgetRoute

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AssignmentReminderNotificationTest {
    private val account = Account("fixture-account", "https://courses.uit.edu.vn", 1, "Fixture", PortalType.MOODLE)
    private val now = 1800000000L
    private fun items(owner: Account = account) = (1L..6L).map { id ->
        val course = Course(4, owner.id, "IT001", "Programming")
        AssignmentReminderItem(course, Assignment(id, course.id, id * 10, "Fixture task $id", due = now + 86400, accountId = owner.id), now + 86400)
    }

    @Test fun permissionChannelPrivacyGroupingAndDeadlineNavigationUseAndroidNotificationApi() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val manager = app.getSystemService(NotificationManager::class.java)
        app.getSharedPreferences("appearance", Context.MODE_PRIVATE).edit().putString("language", "en").commit()
        val notifier = AssignmentReminderNotifier(app)
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertFalse(notifier.available())
        assertFalse(notifier.post(account, items(), now))
        assertTrue(manager.activeNotifications.isEmpty())
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertTrue(notifier.available())
        assertTrue(notifier.post(account, items(), now))
        val first = manager.activeNotifications.single().notification
        assertTrue(first.extras.getCharSequence(Notification.EXTRA_TITLE).toString().contains("6 assignment"))
        assertTrue(first.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString().contains("And 1 more"))
        assertEquals(Notification.VISIBILITY_PRIVATE, first.visibility)
        assertFalse(first.publicVersion.extras.toString().contains("Fixture task"))
        val intent = shadowOf(first.contentIntent).savedIntent
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("deadlines", WidgetRoute.read(intent)?.destination)
        notifier.post(account, items().take(1), now + 8 * 3600)
        assertEquals(1, manager.activeNotifications.size)
        val other = account.copy(id = "other")
        notifier.post(other, items(other), now)
        assertEquals(2, manager.activeNotifications.size)
        notifier.cancel(account.id)
        assertEquals(1, manager.activeNotifications.size)
        notifier.cancel(other.id)
        assertTrue(manager.activeNotifications.isEmpty())
    }

    @Test fun mutedChannelPreventsDelivery() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val manager = app.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(AssignmentReminderNotifier.CHANNEL, "Fixture mute", NotificationManager.IMPORTANCE_NONE))
        val notifier = AssignmentReminderNotifier(app)
        assertFalse(notifier.available())
        assertFalse(notifier.post(account, items(), now))
        assertTrue(manager.activeNotifications.isEmpty())
    }

    @Test fun backgroundRequestChecksEightHourCheckpointOnFifteenMinuteNetworkTicks() {
        val spec = AssignmentReminderWorker.request().workSpec
        assertEquals(15 * 60 * 1000L, spec.intervalDuration)
        assertEquals(8 * 3600L, AssignmentReminderPolicy().intervalSeconds)
        assertEquals(androidx.work.NetworkType.CONNECTED, spec.constraints.requiredNetworkType)
        assertTrue(spec.constraints.requiresBatteryNotLow())
    }

    @Test fun longMultilineAssignmentNamesCannotOverflowTheGroupedNotification() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val huge = items().map { it.copy(assignment = it.assignment.copy(name = "Fixture\n".repeat(10000))) }
        assertTrue(AssignmentReminderNotifier(app).post(account, huge, now))
        val notification = app.getSystemService(NotificationManager::class.java).activeNotifications.single().notification
        val body = notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString()
        assertEquals(6, body.lines().size)
        assertTrue(body.length < 1600)
    }
}
