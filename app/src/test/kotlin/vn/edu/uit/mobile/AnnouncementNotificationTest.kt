package vn.edu.uit.mobile

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import vn.edu.uit.mobile.daa.*
import vn.edu.uit.mobile.data.*
import vn.edu.uit.mobile.domain.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AnnouncementNotificationTest {
    @Test fun deniedPermissionRetainsPendingAndDeliveryHasAReaderRoute() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val manager = app.getSystemService(NotificationManager::class.java)
        val memory = object : StudyStorage {
            var data: String? = null
            override fun read(key: String) = data
            override fun write(key: String, value: String) {
                data = value
            }
            override fun remove(key: String) {
                data = null
            }
            override fun removeAccount(accountId: String) = error("No account operations")
        }
        var now = System.currentTimeMillis() / 1000
        val date = java.time.Instant.ofEpochSecond(now).atZone(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).toLocalDate().toString()
        val first = OfficialAnnouncement("https://portal.uit.edu.vn/bai-viet/fixture-first", AnnouncementSource.PORTAL, "Fixture first notice", date)
        val second = first.copy(url = "https://portal.uit.edu.vn/bai-viet/fixture-second", title = "Fixture second notice")
        var items = listOf(first)
        val remote = object : AnnouncementRemote {
            override suspend fun list(source: AnnouncementSource, page: Int) = AnnouncementPage(items, false)
            override suspend fun article(item: OfficialAnnouncement) = item.copy(bodyHtml = "<p>Fixture content</p>")
            override suspend fun image(url: String): ByteArray = error("No image network")
        }
        val repo = LocalAnnouncementRepository(remote, memory) { now }
        repo.alerts(true)
        repo.refresh()
        items = listOf(first, second)
        repo.refresh()
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        AnnouncementNotifier(app).deliver(repo)
        assertEquals(setOf(second.url), repo.state.value.pending)
        assertTrue(manager.activeNotifications.isEmpty())
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        AnnouncementNotifier(app).deliver(repo)
        assertTrue(repo.state.value.pending.isEmpty())
        val notification = manager.activeNotifications.single().notification
        assertEquals(second.url, shadowOf(notification.contentIntent).savedIntent.getStringExtra(AnnouncementSyncWorker.EXTRA_ANNOUNCEMENT))
        assertTrue(repo.state.value.items.first { it.url == second.url }.unread)
        manager.cancelAll()
        AnnouncementNotifier(app).deliver(repo)
        assertTrue(manager.activeNotifications.isEmpty())
        // Reminder delivery is local and does not require a remote refresh.
        now -= 120
        repo.remind(second.url, now + 60)
        AnnouncementNotifier(app).deliver(repo)
        assertTrue(repo.state.value.items.first { it.url == second.url }.reminderDelivered)
        assertEquals(1, manager.activeNotifications.size)
    }
}
