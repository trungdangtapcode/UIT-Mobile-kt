package vn.edu.uit.mobile.moodle

import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.*
import vn.edu.uit.mobile.common.*
import vn.edu.uit.mobile.network.*

class HtmlScrapingMoodleApiClient(private val transport: PortalTransport) : MoodleApiClient {
    private val parser = HtmlMoodleParser(transport.session.account.baseUrl)
    private suspend fun page(path: String): String = transport.request(path).toString(Charsets.UTF_8)
    override suspend fun call(method: String, args: Map<String, JsonElement>): JsonElement {
        val p = JsonObject(args)
        val courseId = p.long("courseid")
        return when (method) {
            "core_enrol_get_users_courses" -> parser.courses(page("/my/courses.php"))
            "core_course_get_contents" -> contents(courseId)
            "mod_assign_get_assignments" -> {
                val ids = MoodleAjaxHelper.normalizeArgs(args).list("courseids").map { it.long() }
                obj(
                    "courses" to ids.map { id ->
                        val modules = contents(id).flatMap { it.list("modules") }.filter { it.str("modname") == "assign" }
                        obj("id" to id, "assignments" to batched(modules) { parser.assignment(page("/mod/assign/view.php?id=${it.long("id")}&forceview=1"), it.long("id"), id) })
                    }
                )
            }
            "mod_assign_get_submission_status" -> {
                require(p.long("cmid") > 0) { "Course module ID is required for HTML submission status" }
                parser.submission(page("/mod/assign/view.php?id=${p.long("cmid")}&forceview=1"))
            }
            "mod_assign_save_submission" -> save(p)
            "mod_forum_get_forums_by_courses" -> {
                val ids = MoodleAjaxHelper.normalizeArgs(args).list("courseids").map { it.long() }
                JsonArray(
                    ids.flatMap { id ->
                        contents(id).flatMap { it.list("modules") }.filter { it.str("modname") == "forum" }.map {
                            obj("id" to it.long("instance"), "cmid" to it.long("id"), "name" to it.str("name"))
                        }
                    }
                )
            }
            "mod_forum_get_forum_discussions" -> {
                val path = if (p.long("cmid") > 0) "/mod/forum/view.php?id=${p.long("cmid")}&forceview=1" else "/mod/forum/view.php?f=${p.long("forumid")}"
                val discussions = parser.forums(page(path))
                obj(
                    "discussions" to batched(discussions) { row ->
                        val post = parser.thread(page("/mod/forum/discuss.php?d=${row.long("id")}"))
                        JsonObject(row.jsonObject + mapOf("message" to post.field("message"), "attachments" to post.field("attachments"), "firstpost" to post.field("id")))
                    }
                )
            }
            "gradereport_user_get_grade_items" -> parser.grades(page("/grade/report/user/index.php?id=$courseId"))
            "core_enrol_get_enrolled_users" -> parser.participants(page("/user/index.php?id=$courseId&perpage=5000"))
            else -> throw PortalException("servicenotavailable", "This action is unavailable through the student HTML portal")
        }
    }
    private suspend fun contents(id: Long): JsonArray {
        val sections = parser.contents(page("/course/view.php?id=$id"), id)
        return JsonArray(
            sections.map { section ->
                val enriched = batched(section.list("modules")) { module ->
                    if (module.str("modname") !in setOf("resource", "folder", "url")) {
                        module
                    } else {
                        val doc = parser.document(page("/mod/${module.str("modname")}/view.php?id=${module.long("id")}&forceview=1"))
                        val files = (module.list("contents") + parser.files(doc)).distinctBy { it.str("fileurl") }
                        JsonObject(module.jsonObject + ("contents" to JsonArray(files)))
                    }
                }
                JsonObject(section.jsonObject + ("modules" to JsonArray(enriched)))
            }
        )
    }
    private suspend fun save(p: JsonObject): JsonElement {
        val cmid = p.long("cmid")
        require(cmid > 0)
        val tokens = parser.hiddenTokens(page("/mod/assign/view.php?id=$cmid&action=editsubmission"))
        val item = p.field("plugindata").long("files_filemanager")
        require(item > 0)
        val values = tokens + mapOf("id" to "$cmid", "action" to "savesubmission", "sesskey" to transport.session.sesskey, "files_filemanager" to "$item", "submitbutton" to "Save changes")
        val html = transport.request("/mod/assign/view.php?id=$cmid", HttpMethod.Post) {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(values.entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" })
        }.toString(Charsets.UTF_8)
        parser.verifySaved(html)
        return JsonArray(emptyList())
    }
    private suspend fun <T, R> batched(items: List<T>, action: suspend (T) -> R): List<R> = coroutineScope {
        val limit = Semaphore(4)
        items.map { async { limit.withPermit { action(it) } } }.awaitAll()
    }
}
