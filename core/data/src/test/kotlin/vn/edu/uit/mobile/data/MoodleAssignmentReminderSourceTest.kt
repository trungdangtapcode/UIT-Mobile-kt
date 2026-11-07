package vn.edu.uit.mobile.data

import io.ktor.client.request.*
import io.ktor.http.*
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import vn.edu.uit.mobile.common.*
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.model.*
import vn.edu.uit.mobile.network.*

class MoodleAssignmentReminderSourceTest {
    private val session = PortalSession(Account("fixture-account", "https://courses.uit.edu.vn", 1, "Fixture", PortalType.MOODLE), "fixture-cookie", "fixture-key")
    private class Sessions(session: PortalSession) : SessionStore {
        override val sessions = MutableStateFlow(listOf(session))
        override suspend fun save(sessions: List<PortalSession>) {
            this.sessions.value = sessions
        }
        override suspend fun remove(accountId: String) {
            sessions.value = sessions.value.filterNot { it.account.id == accountId }
        }
    }

    @Test fun unsupportedAjaxUsesOnlyFreshStudentCalendarActionsAndNeverOpensAssignmentPages() = runTest {
        val now = dateAt(LocalDate(2026, 11, 8), LocalTime(10, 0))
        for (definitionsAvailable in listOf(false, true)) {
            val vault = Sessions(session)
            val methods = mutableListOf<String>()
            val transport = object : PortalTransport {
                override val session = this@MoodleAssignmentReminderSourceTest.session
                override suspend fun download(url: String, destination: File): DownloadedFile = error("No downloads")
                override suspend fun request(path: String, method: HttpMethod, configure: HttpRequestBuilder.() -> Unit): ByteArray {
                    assertTrue("Background work must not open a module page", path.startsWith("/lib/ajax/service.php?"))
                    assertEquals(HttpMethod.Post, method)
                    val call = Json.parseToJsonElement(HttpRequestBuilder().apply(configure).body as String).jsonArray.single().jsonObject
                    val name = call.getValue("methodname").jsonPrimitive.content
                    methods += name
                    if (name == "mod_assign_get_submission_status" || name == "mod_assign_get_assignments" && !definitionsAvailable) return """[{"error":true,"exception":{"errorcode":"invalidfunction","message":"Not available for ajax"}}]""".toByteArray()
                    val data = when (name) {
                        "core_enrol_get_users_courses" -> """[{"id":4,"shortname":"IT001","fullname":"Fixture course"}]"""
                        "core_course_get_enrolled_courses_by_timeline_classification" -> """{"courses":[],"nextoffset":-1}"""
                        "mod_assign_get_assignments" -> obj("courses" to listOf(obj("assignments" to (12L..16L).map { obj("id" to it, "cmid" to it * 10, "name" to "Fixture $it", "duedate" to now + 86400) }))).toString()
                        "core_calendar_get_calendar_monthly_view" -> obj(
                            "weeks" to listOf(
                                obj(
                                    "days" to listOf(
                                        obj(
                                            "events" to (12L..16L).map { id ->
                                                obj(
                                                    "id" to id,
                                                    "timestart" to now + 86400,
                                                    "course" to obj("id" to 4),
                                                    "name" to "Fixture $id",
                                                    "eventtype" to if (id == 15L) "close" else "due",
                                                    "modulename" to "assign",
                                                    "instance" to id,
                                                    "action" to obj("actionable" to (id != 13L), "url" to if (id == 16L) "https://courses.uit.edu.vn/mod/quiz/view.php?id=160&action=editsubmission" else "https://courses.uit.edu.vn/mod/assign/view.php?id=${id * 10}&action=${if (id == 14L) "grader" else "editsubmission"}")
                                                )
                                            }
                                        )
                                    )
                                )
                            )
                        ).toString()
                        else -> error("Unexpected read: $name")
                    }
                    return """[{"error":false,"data":$data}]""".toByteArray()
                }
            }
            val batch = MoodleAssignmentReminderSource(vault) { transport }.read(session.account, now, AssignmentReminderPolicy())
            assertEquals(listOf(12L), batch.items.map { it.assignment.id })
            assertEquals(AssignmentReminderBasis.CALENDAR_ACTION, batch.items.single().basis)
            assertEquals(if (definitionsAvailable) 1 else 0, methods.count { it == "mod_assign_get_submission_status" })
            assertTrue(batch.current())
        }
    }

    @Test fun actualGatewayReadsOnlyWhitelistedMethodsAcrossMonthBoundaryAndSkipsUnknownOrSubmitted() = runTest {
        val now = dateAt(LocalDate(2026, 11, 30), LocalTime(10, 0))
        val vault = Sessions(session)
        val methods = mutableListOf<String>()
        val months = mutableSetOf<Int>()
        val transport = object : PortalTransport {
            override val session = this@MoodleAssignmentReminderSourceTest.session
            override suspend fun download(url: String, destination: File): DownloadedFile = error("No downloads allowed")
            override suspend fun request(path: String, method: HttpMethod, configure: HttpRequestBuilder.() -> Unit): ByteArray {
                assertTrue(path.startsWith("/lib/ajax/service.php?"))
                assertEquals(HttpMethod.Post, method)
                val builder = HttpRequestBuilder().apply(configure)
                val call = Json.parseToJsonElement(builder.body as String).jsonArray.single().jsonObject
                val name = call.getValue("methodname").jsonPrimitive.content
                methods += name
                val args = call.getValue("args").jsonObject
                val data = when (name) {
                    "core_enrol_get_users_courses" -> """[{"id":4,"shortname":"IT001","fullname":"Fixture course"}]"""
                    "core_course_get_enrolled_courses_by_timeline_classification" -> """{"courses":[],"nextoffset":-1}"""
                    "core_calendar_get_calendar_monthly_view" -> {
                        months += args.getValue("month").jsonPrimitive.int
                        """{"weeks":[{"days":[{"events":[{"id":10,"timestart":${now + 86400},"eventtype":"due","modulename":"assign","instance":12,"course":{"id":4}}]}]}]}"""
                    }
                    "mod_assign_get_assignments" -> obj(
                        "courses" to listOf(
                            obj(
                                "assignments" to (12L..25L).map { id ->
                                    obj(
                                        "id" to id,
                                        "cmid" to id * 10,
                                        "name" to "Fixture task $id",
                                        "duedate" to now + if (id == 12L) {
                                            20 * 86400
                                        } else if (id == 15L) {
                                            2 * 86400
                                        } else {
                                            86400
                                        }
                                    )
                                }
                            )
                        )
                    ).toString()
                    "mod_assign_get_submission_status" -> {
                        val id = args.getValue("assignid").jsonPrimitive.long
                        when (id) {
                            12L -> """{"lastattempt":{"submission":{"status":"draft"}}}"""
                            13L -> """{"lastattempt":{"submission":{"status":"submitted"}}}"""
                            14L -> """{"lastattempt":{"submission":{"status":""}}}"""
                            15L -> """{"lastattempt":{"submissionsenabled":true,"locked":false,"canedit":true,"cansubmit":false}}"""
                            16L -> """{"lastattempt":{"submission":{"status":"draft"},"teamsubmission":{"status":"submitted"}}}"""
                            17L -> """{"lastattempt":{"submission":{"status":"new"},"locked":true}}"""
                            18L -> """{"lastattempt":{"submission":{"status":"new"},"extensionduedate":${now + 9 * 86400}}}"""
                            20L -> """{"lastattempt":{"teamsubmission":{"status":"draft"},"submissionsenabled":1,"locked":0,"canedit":1}}"""
                            21L -> """{"lastattempt":{"submission":{"status":"submitted"},"teamsubmission":{"status":"draft"}}}"""
                            22L -> """{"lastattempt":{"submissionsenabled":false,"locked":false,"canedit":true}}"""
                            23L -> """{"lastattempt":{"submissionsenabled":true,"canedit":true}}"""
                            24L -> """{"lastattempt":{"submissionsenabled":true,"locked":false,"canedit":false,"cansubmit":false}}"""
                            25L -> """{"lastattempt":{"submission":{"status":"new"},"submissionsenabled":false}}"""
                            else -> """{"lastattempt":{}}"""
                        }
                    }
                    else -> error("Unexpected university method: $name")
                }
                return """[{"data":$data,"error":false}]""".toByteArray()
            }
        }
        val source = MoodleAssignmentReminderSource(vault) { transport }
        val batch = source.read(session.account, now, AssignmentReminderPolicy())
        assertEquals(setOf(11, 12), months)
        assertEquals(listOf(12L, 20L, 15L), batch.items.sortedWith(compareBy({ it.due }, { it.assignment.id })).map { it.assignment.id })
        assertEquals(now + 86400, batch.items.first { it.assignment.id == 12L }.due)
        assertEquals(14, methods.count { it == "mod_assign_get_submission_status" })
        assertTrue(batch.current())
        vault.remove(session.account.id)
        assertFalse(batch.current())
    }
}
