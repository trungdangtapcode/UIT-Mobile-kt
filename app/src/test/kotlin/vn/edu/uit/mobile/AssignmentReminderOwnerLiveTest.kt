package vn.edu.uit.mobile

import android.Manifest
import android.app.*
import androidx.room.Room
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import okio.Buffer
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import vn.edu.uit.mobile.common.*
import vn.edu.uit.mobile.data.*
import vn.edu.uit.mobile.database.*
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.model.*
import vn.edu.uit.mobile.network.*
import vn.edu.uit.mobile.widgets.WidgetRoute

/** Explicit owner test: stdin-only session, live whitelisted reads, host Android notifications. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AssignmentReminderOwnerLiveTest {
    @Test fun ownerDeadlineSourceAndActualNotificationUseTheProductionPath() = runBlocking {
        assumeTrue(System.getenv("UIT_REMINDER_OWNER_LIVE") == "1")
        val input = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val n = System.`in`.read(buffer)
            if (n < 0) break
            require(input.size() + n <= 65536)
            input.write(buffer, 0, n)
        }
        val grant = runCatching { Json.parseToJsonElement(input.toString("UTF-8")).jsonObject }.getOrElse { error("Owner session input is invalid") }
        val cookie = grant.getValue("cookie").jsonPrimitive.content
        val sesskey = grant.getValue("sesskey").jsonPrimitive.content
        val userId = grant.getValue("userId").jsonPrimitive.long
        require(cookie.length in 10..32768 && cookie.none { it == '\r' || it == '\n' })
        require(sesskey.matches(Regex("[A-Za-z0-9]{8,256}")) && userId > 0)
        val session = PortalSession(Account("owner-live-diagnostic", "https://courses.uit.edu.vn", userId, "Owner diagnostic", PortalType.MOODLE), cookie, sesskey)
        val vault = object : SessionStore {
            override val sessions = MutableStateFlow(listOf(session))
            override suspend fun save(sessions: List<PortalSession>) {
                this.sessions.value = sessions
            }
            override suspend fun remove(accountId: String) {
                sessions.value = emptyList()
            }
        }
        val permitted = setOf("core_enrol_get_users_courses", "core_course_get_enrolled_courses_by_timeline_classification", "core_calendar_get_calendar_monthly_view", "mod_assign_get_assignments", "mod_assign_get_submission_status")
        val calls = mutableListOf<JsonObject>()
        var pending = emptyList<AssignmentReminderItem>()
        var failure: String? = null
        var completed = false
        var repeated = false
        val now = System.currentTimeMillis() / 1000
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val manager = app.getSystemService(NotificationManager::class.java)
        val db = Room.inMemoryDatabaseBuilder(app, UitDatabase::class.java).allowMainThreadQueries().build()
        try {
            val source = MoodleAssignmentReminderSource(vault) { leased ->
                val client = HttpClient(OkHttp) {
                    install(HttpTimeout) {
                        requestTimeoutMillis = 30000
                        connectTimeoutMillis = 15000
                        socketTimeoutMillis = 25000
                    }
                    engine {
                        config {
                            followRedirects(false)
                            followSslRedirects(false)
                            retryOnConnectionFailure(false)
                            addNetworkInterceptor { chain ->
                                val request = chain.request()
                                check(request.url.scheme == "https" && request.url.host == "courses.uit.edu.vn" && request.url.encodedPath == "/lib/ajax/service.php" && request.method == "POST") { "Unapproved university route blocked" }
                                val bytes = Buffer().apply { requireNotNull(request.body).writeTo(this) }.readUtf8()
                                val method = Json.parseToJsonElement(bytes).jsonArray.single().jsonObject.getValue("methodname").jsonPrimitive.content
                                check(method in permitted) { "Unapproved university method blocked" }
                                val response = chain.proceed(request)
                                val root = runCatching { Json.parseToJsonElement(response.peekBody(4 * 1024 * 1024).string()).jsonArray.firstOrNull()?.jsonObject }.getOrNull()
                                val error = (root?.get("exception") as? JsonObject)?.get("errorcode")?.jsonPrimitive?.contentOrNull?.takeIf { it.matches(Regex("[A-Za-z0-9_.-]{1,100}")) }.orEmpty()
                                synchronized(calls) {
                                    calls += buildJsonObject {
                                        put("method", method)
                                        put("status", response.code)
                                        put("code", error)
                                    }
                                }
                                response
                            }
                        }
                    }
                }
                SafePortalTransport(leased, client)
            }
            val capture = object : AssignmentReminderSource {
                override suspend fun accounts() = source.accounts()
                override suspend fun read(account: Account, now: Long, policy: AssignmentReminderPolicy) = source.read(account, now, policy).also { pending = it.items }
            }
            val delivery = AssignmentReminderDelivery(capture, RoomAssignmentReminderLedger(db.dao()), AssignmentReminderNotifier(app), now = { now })
            completed = withTimeout(150000) { delivery.deliver() }
            val count = calls.size
            assertTrue("Live source did not complete", completed)
            assertEquals(if (pending.isEmpty()) 0 else 1, manager.activeNotifications.size)
            if (pending.isNotEmpty()) {
                val notification = manager.activeNotifications.single().notification
                assertEquals("deadlines", WidgetRoute.read(shadowOf(notification.contentIntent).savedIntent)?.destination)
                assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
            }
            assertTrue(delivery.deliver())
            repeated = calls.size == count && manager.activeNotifications.size == if (pending.isEmpty()) 0 else 1
            assertTrue("Immediate duplicate was not suppressed", repeated)
        } catch (e: Exception) {
            failure = e.javaClass.simpleName
        } finally {
            val report = buildJsonObject {
                put("recordedAt", java.time.Instant.now().toString())
                put("runtime", "Production Kotlin and Robolectric Android framework; not physical Android")
                put("universityAccountUsed", true)
                put("universityWritesAllowed", false)
                put("credentialsWrittenToDiskByJvm", false)
                put("sourceCompleted", completed)
                put("pendingCount", pending.size)
                put("notificationPosted", manager.activeNotifications.isNotEmpty())
                put("immediateDuplicateSuppressed", repeated)
                put("calls", JsonArray(calls))
                failure?.let { put("failureType", it) }
                put(
                    "privateItems",
                    JsonArray(
                        pending.map {
                            buildJsonObject {
                                put("course", it.course.shortname)
                                put("assignment", it.assignment.name)
                                put("due", it.due)
                                put("basis", it.basis.name)
                            }
                        }
                    )
                )
            }
            println("UIT_REMINDER_LIVE_REPORT $report")
            manager.cancelAll()
            vault.remove(session.account.id)
            db.close()
        }
        assertTrue("Live source acceptance failed; inspect the private redacted report", completed && repeated && failure == null)
    }
}
