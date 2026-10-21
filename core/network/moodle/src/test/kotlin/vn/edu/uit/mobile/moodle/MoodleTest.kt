package vn.edu.uit.mobile.moodle

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.http.content.*
import java.io.ByteArrayOutputStream
import java.util.zip.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import vn.edu.uit.mobile.common.*
import vn.edu.uit.mobile.model.*
import vn.edu.uit.mobile.network.*

class MoodleTest {
    @Test fun `normalizes nested bracket keys and retains unsafe and malformed literals`() {
        val result = MoodleAjaxHelper.normalizeArgs(
            obj(
                "courseids[0]" to 19207,
                "courseids[1]" to 19589,
                "plugindata[files_filemanager]" to 123,
                "options[0][name]" to "x",
                "broken[]" to 2,
                "x[constructor][secret]" to 3
            )
        )
        assertEquals(listOf(19207L, 19589L), result.list("courseids").map { it.long() })
        assertEquals(123, result.field("plugindata").int("files_filemanager"))
        assertEquals("x", result.list("options")[0].str("name"))
        assertEquals(2, result.int("broken[]"))
        assertEquals(3, result.int("x[constructor][secret]"))
    }

    @Test fun `unwraps responses and preserves Moodle error codes`() {
        assertEquals(42, MoodleAjaxHelper.unwrapAjaxResponse(JsonArray(listOf(obj("error" to false, "data" to 42)))).int())
        val e = assertThrows(PortalException::class.java) { MoodleAjaxHelper.unwrapAjaxResponse(JsonArray(listOf(obj("error" to true, "exception" to obj("errorcode" to "invalidsesskey", "message" to "Session expired"))))) }
        assertEquals("invalidsesskey", e.errorcode)
        for (invalid in listOf(JsonArray(emptyList()), obj("data" to 1), JsonArray(listOf(obj("error" to false))))) assertThrows(InvalidResponseException::class.java) { MoodleAjaxHelper.unwrapAjaxResponse(invalid) }
    }

    @Test fun `fallback never hides expired sessions`() = runTest {
        var fallback = false
        val primary = object : MoodleApiClient {
            override suspend fun call(method: String, args: Map<String, JsonElement>): JsonElement = throw PortalException("invalidsesskey", "Session expired; web service is not available")
        }
        val html = object : MoodleApiClient {
            override suspend fun call(method: String, args: Map<String, JsonElement>): JsonElement {
                fallback = true
                return JsonNull
            }
        }
        assertThrows(PortalException::class.java) { runBlocking { ResilientMoodleApiClient(primary, html).call("test") } }
        assertFalse(fallback)
    }

    @Test fun `discovery merges six concurrent paginated buckets with primary precedence`() = runTest {
        var active = 0
        var peak = 0
        var calls = 0
        val http = HttpClient(
            MockEngine { request ->
                active++
                peak = maxOf(peak, active)
                calls++
                delay(10)
                val body = PortalJson.parseToJsonElement((request.body as TextContent).text).jsonArray[0]
                val args = body.field("args")
                val data = if (body.str("methodname") == "core_enrol_get_users_courses") {
                    listOf(obj("id" to 1, "fullname" to "Primary")).asJson()
                } else if (args.str("classification") == "all") if (args.int("offset") == 0) {
                    obj("courses" to listOf(obj("id" to 1, "fullname" to "Sparse", "categoryname" to "Computer Science")), "nextoffset" to 100)
                } else {
                    obj("courses" to listOf(obj("id" to 2)), "nextoffset" to -1)
                }
                else {
                    obj("courses" to emptyList<String>(), "nextoffset" to args.int("offset"))
                }
                active--
                respond(JsonArray(listOf(obj("data" to data))).toString())
            }
        )
        val session = PortalSession(Account("one", "https://courses.uit.edu.vn", 1, "Student", PortalType.MOODLE), "MoodleSession=x", "key")
        SafePortalTransport(session, http).use { transport ->
            val courses = AjaxMoodleApiClient(transport).call("core_enrol_get_users_courses").list()
            assertEquals(2, courses.size)
            assertEquals("Primary", courses.first().str("fullname"))
            assertEquals("Computer Science", courses.first().str("categoryname"))
        }
        assertEquals(8, calls)
        assertTrue(peak > 1)
        http.close()
    }

    @Test fun `course parser validates context removes hidden labels and finds inline files`() {
        val parser = HtmlMoodleParser("https://coursesold.uit.edu.vn/sdh")
        val html = """<body class="course-42"><div class="course-content"><li class="section" id="section-1"><h3>Week 1</h3><ul><li id="module-101" class="activity modtype_resource"><a class="aalink" href="/mod/resource/view.php?id=101">Slides<span class="accesshide">File</span></a><a href="/pluginfile.php/42/slides.pdf?token=secret">Download</a></li></ul></li></div></body>"""
        val module = parser.contents(html, 42).first().list("modules").first()
        assertEquals("Slides", module.str("name"))
        assertTrue(module.str("url").contains("/sdh/mod/"))
        assertFalse(module.list("contents").first().str("fileurl").contains("token"))
        assertThrows(InvalidResponseException::class.java) { parser.contents(html, 99) }
    }

    @Test fun `participant parser ignores sortable header ids`() {
        val html = """<table id="participants"><thead><tr><th><a href="/user/index.php?id=999">Name</a></th></tr></thead><tbody><tr><td><a href="/user/view.php?id=12">Student</a></td><td class="c2">Student role</td></tr></tbody></table>"""
        val people = HtmlMoodleParser("https://courses.uit.edu.vn").participants(html)
        assertEquals(listOf(12L), people.map { it.long("id") })
    }

    @Test fun `forum cmid fallback parses discussion without forum instance id`() {
        val parser = HtmlMoodleParser("https://courses.uit.edu.vn")
        val rows = parser.forums("""<table><tr class="discussion"><td><a href="/mod/forum/discuss.php?d=8">Announcement</a></td></tr></table>""")
        assertEquals(8, rows.first().int("discussion"))
        val post = parser.thread("""<article class="forumpost" data-post-id="9"><div class="posting">News<a href="/pluginfile.php/1/notice.pdf">Attachment</a></div><div class="userpicture"><a href="/pluginfile.php/2/avatar.png">Avatar</a></div></article>""")
        assertEquals(1, post.list("attachments").size)
        assertEquals(9, post.int("id"))
    }

    @Test fun `submission parser distinguishes draft from submitted and catches form errors`() {
        val parser = HtmlMoodleParser("https://courses.uit.edu.vn")
        assertEquals("draft", parser.submission("<table class='submissionstatustable'><tr><td>Bản nháp</td></tr></table>").field("lastattempt").field("submission").str("status"))
        assertEquals("draft", parser.submission("<table class='submissionstatustable'><tr><td>Draft (not submitted)</td></tr></table>").field("lastattempt").field("submission").str("status"))
        assertEquals("submitted", parser.submission("<table class='submissionstatustable'><tr><td>Submitted for grading</td></tr></table>").field("lastattempt").field("submission").str("status"))
        assertThrows(InvalidResponseException::class.java) { parser.verifySaved("<div class='form-error'>Rejected</div>") }
    }

    @Test fun `H5P reader extracts summaries and rejects traversal and oversized metadata`() {
        fun zip(vararg entries: Pair<String, String>): ByteArray {
            val output = ByteArrayOutputStream()
            ZipOutputStream(output).use { z ->
                entries.forEach { (name, content) ->
                    z.putNextEntry(ZipEntry(name))
                    z.write(content.toByteArray())
                    z.closeEntry()
                }
            }
            return output.toByteArray()
        }
        val result = H5pPackageReader().read(zip("h5p.json" to """{"title":"Lecture","mainLibrary":"H5P.CoursePresentation"}""", "content/content.json" to """{"presentation":{"slides":[{"title":"Intro","text":"<b>Hello</b>","video":{"path":"https://youtube.com/watch?v=1&token=secret"}}]}}"""))
        assertEquals("Lecture", result.title)
        assertEquals(listOf("Intro", "Hello"), result.entries.first().text)
        assertEquals("https://youtube.com/watch?v=1", result.entries.first().media.first())
        assertThrows(IllegalArgumentException::class.java) { H5pPackageReader().read(zip("../h5p.json" to "{}")) }
        assertThrows(IllegalArgumentException::class.java) { H5pPackageReader().read(zip("h5p.json" to "x".repeat(5 * 1024 * 1024 + 1))) }
    }
}
