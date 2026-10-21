package vn.edu.uit.mobile.moodle

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import vn.edu.uit.mobile.common.*
import vn.edu.uit.mobile.model.*
import vn.edu.uit.mobile.network.*

class MoodleWritesTest {
    private val session = PortalSession(Account("account", "https://courses.uit.edu.vn", 1, "Student", PortalType.MOODLE), "MoodleSession=fake", "key")
    private val assignment = Assignment(7, 42, 3, "Exercise", accountId = "account")

    @Test fun `upload requires confirmation and saves returned draft item via normalized AJAX`() = runTest {
        val paths = mutableListOf<String>()
        val http = HttpClient(
            MockEngine { request ->
                paths += request.url.encodedPath
                when (request.url.encodedPath) {
                    "/user/files.php" -> respond("""<script>{"id":17,"type":"upload"}</script>""")
                    "/repository/repository_ajax.php" -> {
                        assertEquals(HttpMethod.Post, request.method)
                        assertTrue(request.body.contentType.toString().contains("multipart/form-data"))
                        respond("""{"itemid":123456789,"error":false}""")
                    }
                    else -> {
                        val payload = PortalJson.parseToJsonElement((request.body as TextContent).text).jsonArray.single()
                        assertEquals("mod_assign_save_submission", payload.str("methodname"))
                        assertEquals(7, payload.field("args").int("assignmentid"))
                        assertEquals(123456789, payload.field("args").field("plugindata").int("files_filemanager"))
                        respond("""[{"error":false,"data":[]}]""")
                    }
                }
            }
        )
        SafePortalTransport(session, http).use {
            val gateway = MoodleGateway(it)
            assertThrows(IllegalArgumentException::class.java) { runBlocking { gateway.submitFile(assignment, "answer.pdf", byteArrayOf(1), false) } }
            assertTrue(paths.isEmpty())
            gateway.submitFile(assignment, "answer.pdf", byteArrayOf(1), true)
        }
        assertEquals(listOf("/user/files.php", "/repository/repository_ajax.php", "/lib/ajax/service.php"), paths)
    }

    @Test fun `HTML save uses scraped form token and follows confirmation redirect as GET`() = runTest {
        var saved = false
        var writes = 0
        val http = HttpClient(
            MockEngine { request ->
                when (request.url.encodedPath) {
                    "/user/files.php" -> respond("""{"id":5,"type":"upload"}""")
                    "/repository/repository_ajax.php" -> respond("""{"itemid":123}""")
                    "/lib/ajax/service.php" -> respond("""[{"error":true,"exception":{"errorcode":"servicenotavailable"}}]""")
                    else -> if (request.method == HttpMethod.Post) {
                        writes++
                        val body = (request.body as TextContent).text
                        assertTrue(body.contains("_qf__assign=1"))
                        assertTrue(body.contains("files_filemanager=123"))
                        saved = true
                        respond("", HttpStatusCode.SeeOther, headersOf(HttpHeaders.Location, "/mod/assign/view.php?id=3"))
                    } else if (!saved) {
                        respond("""<form><input type="hidden" name="_qf__assign" value="1"></form>""")
                    } else {
                        respond("""<table class="submissionstatustable"><tr><td>Draft (not submitted)</td></tr></table>""")
                    }
                }
            }
        )
        SafePortalTransport(session, http).use { MoodleGateway(it).submitFile(assignment, "answer.pdf", byteArrayOf(1), true) }
        assertEquals(1, writes)
    }

    @Test fun `private message byte limit is enforced and failed sends are never retried`() = runTest {
        var calls = 0
        val http = HttpClient(
            MockEngine {
                calls++
                respond("Unavailable", HttpStatusCode.InternalServerError)
            }
        )
        SafePortalTransport(session, http).use {
            val gateway = MoodleGateway(it)
            assertThrows(IllegalArgumentException::class.java) { runBlocking { gateway.sendMessage(1, "é".repeat(2049)) } }
            assertEquals(0, calls)
            assertThrows(PortalException::class.java) { runBlocking { gateway.sendMessage(1, "Hello") } }
        }
        assertEquals(1, calls)
    }

    @Test fun `unavailable contents method falls back to verified HTML and enriches resource objects`() = runTest {
        var calls = 0
        val http = HttpClient(
            MockEngine { request ->
                calls++
                when (request.url.encodedPath) {
                    "/lib/ajax/service.php" -> respond("""[{"error":true,"exception":{"errorcode":"servicenotavailable"}}]""")
                    "/course/view.php" -> respond("""<body class="course-42"><div class="course-content"><ul><li class="section"><ul><li id="module-101" class="activity modtype_resource"><a class="aalink" href="/mod/resource/view.php?id=101">Slides</a></li></ul></li></ul></div></body>""")
                    else -> respond("""<object data="/pluginfile.php/1/slides.pdf?token=secret"></object>""")
                }
            }
        )
        SafePortalTransport(session, http).use {
            val module = MoodleGateway(it).sections(42).single().modules.single()
            assertEquals("Slides", module.name)
            assertEquals("https://courses.uit.edu.vn/pluginfile.php/1/slides.pdf", module.files.single().url)
        }
        assertEquals(3, calls)
    }
}
