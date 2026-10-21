package vn.edu.uit.mobile.moodle

import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import java.security.SecureRandom
import kotlinx.serialization.json.*
import vn.edu.uit.mobile.common.*
import vn.edu.uit.mobile.model.*
import vn.edu.uit.mobile.network.*

interface CourseCatalogDataSource {
    suspend fun courses(): List<Course>
    suspend fun sections(courseId: Long): List<CourseSection>
}
interface AssignmentDataSource {
    suspend fun assignments(courseId: Long): List<Assignment>
    suspend fun submission(assignment: Assignment): SubmissionStatus
}
interface ForumDataSource {
    suspend fun discussions(module: CourseModule): List<Discussion>
}
class MoodleGateway(
    private val transport: PortalTransport,
    private val api: MoodleApiClient = ResilientMoodleApiClient(AjaxMoodleApiClient(transport), HtmlScrapingMoodleApiClient(transport))
) : CourseCatalogDataSource,
    AssignmentDataSource,
    ForumDataSource {
    private val account = transport.session.account
    private suspend fun call(name: String, vararg args: Pair<String, Any?>): JsonElement = api.call(name, obj(*args))
    override suspend fun courses(): List<Course> = call("core_enrol_get_users_courses", "userid" to account.userId).list().map {
        Course(it.long("id"), account.id, it.str("shortname"), it.str("fullname"), it.str("categoryname").ifEmpty { it.str("coursecategory") }, it.str("classification").ifEmpty { "all" }, it.flag("hidden"))
    }
    override suspend fun sections(courseId: Long): List<CourseSection> {
        val sections = call("core_course_get_contents", "courseid" to courseId).list().map { section ->
            CourseSection(
                section.long("id"),
                section.str("name"),
                section.list("modules").map { module ->
                    CourseModule(module.long("id"), courseId, module.str("name"), module.str("modname"), safe(module.str("url")), sanitizeHtml(module.str("description"), account.baseUrl), files(module.list("contents")), module.long("instance"))
                }
            )
        }
        if (sections.none { section -> section.modules.any { it.type == "h5pactivity" } }) return sections
        val activities = try {
            call("mod_h5pactivity_get_h5pactivities_by_courses", "courseids[0]" to courseId).list("h5pactivities")
        } catch (e: PortalException) {
            if (!MoodleErrorClassifier.isUnavailableSessionMethod(e)) throw e
            return sections
        }
        val packages = activities.associate { it.long("coursemodule") to files(it.list("package")) }
        return sections.map { section -> section.copy(modules = section.modules.map { module -> module.copy(files = (module.files + packages[module.id].orEmpty()).distinctBy { it.url }) }) }
    }
    override suspend fun assignments(courseId: Long): List<Assignment> = call("mod_assign_get_assignments", "courseids[0]" to courseId).list("courses").flatMap { course ->
        course.list("assignments").map {
            Assignment(it.long("id"), courseId, it.long("cmid"), it.str("name"), sanitizeHtml(it.str("intro"), account.baseUrl), it.long("duedate"), it.long("cutoffdate"), it.long("allowsubmissionsfromdate"), files(it.list("introattachments")), account.id)
        }
    }
    override suspend fun submission(assignment: Assignment): SubmissionStatus {
        val args = obj("assignid" to assignment.id)
        val result = try {
            AjaxMoodleApiClient(transport).raw("mod_assign_get_submission_status", args)
        } catch (e: PortalException) {
            if (!MoodleErrorClassifier.isUnavailableSessionMethod(e)) throw e
            HtmlScrapingMoodleApiClient(transport).call("mod_assign_get_submission_status", obj("assignid" to assignment.id, "cmid" to assignment.cmid))
        }
        val submission = result.field("lastattempt").field("submission")
        val files = submission.list("plugins").flatMap { it.list("fileareas").flatMap { area -> area.list("files") } }
        return SubmissionStatus(submission.str("status"), submission.long("timemodified"), files(files), result.field("feedback").field("grade").str("grade"))
    }
    suspend fun submitFile(assignment: Assignment, name: String, data: ByteArray, confirmed: Boolean) {
        require(confirmed) { "Confirm the file submission first" }
        require(name.isNotBlank() && data.isNotEmpty() && data.size <= SafePortalTransport.MAX_PREVIEW_BYTES)
        val draft = 100000000 + SecureRandom().nextInt(900000000)
        val filePage = transport.request("/user/files.php").toString(Charsets.UTF_8)
        val uploadId = Regex("\"(?:id|repo_id)\"\\s*:\\s*\"?(\\d+)\"?[^{}]{0,500}\"type\"\\s*:\\s*\"upload\"").find(filePage)?.groupValues?.get(1) ?: "5"
        val result = parseJson(
            transport.request("/repository/repository_ajax.php?action=upload", HttpMethod.Post) {
                setBody(
                    MultiPartFormDataContent(
                        formData {
                            append("sesskey", transport.session.sesskey)
                            append("repo_id", uploadId)
                            append("itemid", draft.toString())
                            append("title", name)
                            append("repo_upload_file", data, Headers.build { append(HttpHeaders.ContentDisposition, "filename=\"${name.filter { it.isLetterOrDigit() || it in "._- " }}\"") })
                        }
                    )
                )
            }
        )
        if (result.field("error") !in listOf(JsonNull, JsonPrimitive(false), JsonPrimitive(0), JsonPrimitive(""))) throw InvalidResponseException("File upload was rejected")
        val itemId = result.long("itemid").takeIf { it > 0 } ?: throw InvalidResponseException("Upload returned no draft item ID")
        try {
            AjaxMoodleApiClient(transport).raw("mod_assign_save_submission", obj("assignmentid" to assignment.id, "plugindata[files_filemanager]" to itemId))
        } catch (e: PortalException) {
            if (!MoodleErrorClassifier.isUnavailableSessionMethod(e)) throw e
            HtmlScrapingMoodleApiClient(transport).call("mod_assign_save_submission", obj("assignmentid" to assignment.id, "cmid" to assignment.cmid, "plugindata" to obj("files_filemanager" to itemId)))
        }
    }
    override suspend fun discussions(module: CourseModule): List<Discussion> {
        val result = try {
            if (module.instanceId <= 0) throw PortalException("servicenotavailable", "Forum instance unavailable")
            call("mod_forum_get_forum_discussions", "forumid" to module.instanceId, "sortorder" to -1, "page" to 0, "perpage" to 100)
        } catch (e: PortalException) {
            if (!MoodleErrorClassifier.isUnavailableSessionMethod(e)) throw e
            HtmlScrapingMoodleApiClient(transport).call("mod_forum_get_forum_discussions", obj("cmid" to module.id, "forumid" to module.instanceId))
        }
        return result.list("discussions").map {
            Discussion(it.long("discussion").takeIf { id -> id > 0 } ?: it.long("id"), module.instanceId, it.str("name"), it.str("userfullname"), it.long("created"), sanitizeHtml(it.str("message"), account.baseUrl), files(it.list("attachments")), it.long("firstpost"))
        }
    }
    suspend fun reply(postId: Long, text: String) {
        require(postId > 0 && text.isNotBlank())
        call("mod_forum_add_discussion_post", "postid" to postId, "subject" to "Re: Discussion", "message" to text)
    }
    suspend fun grades(courseId: Long): List<GradeItem> = call("gradereport_user_get_grade_items", "courseid" to courseId, "userid" to account.userId).list("usergrades").flatMap { it.list("gradeitems") }.map {
        GradeItem(it.str("id"), it.str("itemname"), it.str("gradeformatted"), it.str("rangeformatted"), it.str("percentageformatted"), it.str("feedback"))
    }
    suspend fun participants(courseId: Long): List<Participant> = call("core_enrol_get_enrolled_users", "courseid" to courseId).list().map {
        Participant(it.long("id"), it.str("fullname"), it.field("roles").let { roles -> if (roles is JsonArray) roles.joinToString { r -> r.str("name") } else roles.str() }, it.field("groups").let { groups -> if (groups is JsonArray) groups.joinToString { g -> g.str("name") } else groups.str() }, it.str("lastaccess"))
    }
    suspend fun calendar(year: Int, month: Int): List<CalendarEvent> {
        require(year in 1970..9998 && month in 1..12)
        val result = call("core_calendar_get_calendar_monthly_view", "year" to year, "month" to month, "courseid" to 0, "categoryid" to 0, "mini" to 1, "includenavigation" to 0)
        if (result.field("weeks") !is JsonArray || result.str("initialeventsloaded") == "false") throw InvalidResponseException("Moodle did not load calendar events")
        return result.list("weeks").flatMap { it.list("days").flatMap { day -> day.list("events") } }.filter { it.str("visible") !in setOf("0", "false") }.mapNotNull {
            val id = it.long("id")
            val start = it.long("timestart")
            if (id <= 0 || start <= 0) return@mapNotNull null
            val action = it.field("action")
            val deadline = it.str("eventtype") in setOf("due", "close")
            CalendarEvent(
                "${account.id}:$id", id, account.id, it.field("course").long("id").takeIf { c -> c > 0 }, it.str("name"), start,
                start + it.long("timeduration"), deadline, action.flag("actionable"), safe(it.str("url").ifEmpty { action.str("url") }),
                it.long("instance").takeIf { a -> a > 0 && it.str("modulename") == "assign" }
            )
        }.distinctBy { it.key }
    }
    suspend fun notifications(): List<Message> = call("message_popup_get_popup_notifications", "useridto" to account.userId, "newestfirst" to 1, "limit" to 100, "offset" to 0).list("notifications").map {
        Message(it.long("id"), it.str("fullmessage").ifEmpty { it.str("subject") }, it.str("userfromfullname"), it.long("timecreated"), it.long("timeread") > 0)
    }
    suspend fun markRead(id: Long?) {
        if (id == null) {
            call("core_message_mark_all_notifications_as_read", "useridto" to account.userId)
        } else {
            call("core_message_mark_notification_read", "notificationid" to id, "timeread" to kotlinx.datetime.Clock.System.now().epochSeconds)
        }
    }
    suspend fun conversations(): List<Conversation> = call("core_message_get_conversations", "userid" to account.userId, "limitfrom" to 0, "limitnum" to 100).list("conversations").map {
        Conversation(it.long("id"), it.str("name").ifEmpty { it.list("members").firstOrNull()?.str("fullname").orEmpty() }, it.int("unreadcount"))
    }
    suspend fun messages(id: Long): List<Message> = call("core_message_get_conversation_messages", "currentuserid" to account.userId, "convid" to id, "limitfrom" to 0, "limitnum" to 100).list("messages").map {
        Message(it.long("id"), it.str("text"), it.str("useridfrom"), it.long("timecreated"))
    }
    suspend fun sendMessage(id: Long, text: String) {
        require(id > 0 && text.isNotBlank() && text.toByteArray().size <= 4096) { "Messages must contain 1–4096 UTF-8 bytes" }
        call("core_message_send_messages_to_conversation", "conversationid" to id, "messages" to listOf(obj("text" to text, "textformat" to 0)))
    }
    private fun safe(url: String) = UrlSanitizer.credentialFreeUrl(url).orEmpty()
    private fun files(items: List<JsonElement>): List<CourseFile> = items.mapNotNull { item ->
        val url = UrlSanitizer.credentialFreeUrl(item.str("fileurl")) ?: return@mapNotNull null
        CourseFile(item.str("filename"), url, item.long("filesize"))
    }
}
