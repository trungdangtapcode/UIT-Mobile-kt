package vn.edu.uit.mobile.moodle

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Element
import java.net.URI
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.serialization.json.*
import vn.edu.uit.mobile.common.*
import vn.edu.uit.mobile.network.*

class HtmlMoodleParser(private val baseUrl: String) {
    fun document(html: String) = Ksoup.parse(html, baseUrl).also { LoginPageSnifferPlugin.inspect(html.toByteArray()) }
    fun files(element: Element): List<JsonObject> = element.select("a[href], object[data], iframe[src], embed[src], source[src], video[src], audio[src]").mapNotNull {
        if (it.hasClass("userpicture") || it.parents().any { p -> p.hasClass("userpicture") }) return@mapNotNull null
        val raw = it.attr(
            if (it.hasAttr("data")) {
                "data"
            } else if (it.hasAttr("src")) {
                "src"
            } else {
                "href"
            }
        )
        val resolved = runCatching { MoodleUrlResolver.resolve(baseUrl, raw) }.getOrNull() ?: return@mapNotNull null
        if (URI(resolved).host != URI(baseUrl).host || !Regex("/(?:token)?pluginfile\\.php(?:/|$)|/mod_forum/attachment/").containsMatchIn(URI(resolved).path)) return@mapNotNull null
        val clean = UrlSanitizer.credentialFreeUrl(resolved) ?: return@mapNotNull null
        obj("filename" to java.net.URLDecoder.decode(URI(clean).path.substringAfterLast('/'), "UTF-8"), "fileurl" to clean, "filesize" to 0, "type" to "file")
    }.distinctBy { it.str("fileurl") }
    fun courses(html: String): JsonArray {
        val doc = document(html)
        return JsonArray(
            doc.select("a[href*=/course/view.php?id=]").mapNotNull { a ->
                val id = queryId(a.attr("href"))
                if (id <= 0) null else obj("id" to id, "fullname" to a.text(), "shortname" to a.attr("title").ifEmpty { a.text() })
            }.distinctBy { it.long("id") }
        )
    }
    fun contents(html: String, courseId: Long): JsonArray {
        val doc = document(html)
        if (doc.select(".course-content, [data-region=section]").isEmpty() || (doc.select(".course-$courseId, [data-courseid=$courseId]").isEmpty())) {
            throw InvalidResponseException("Moodle did not return the requested course")
        }
        var sections = doc.select("[data-region=section], li.section")
        val containers: List<Element> = if (sections.isEmpty()) listOf(doc.selectFirst(".course-content")!!) else sections
        return JsonArray(
            containers.mapIndexed { index, section ->
                obj(
                    "id" to (section.attr("data-id").toLongOrNull() ?: section.id().substringAfterLast('-').toLongOrNull() ?: index.toLong()),
                    "name" to (section.selectFirst(".sectionname, h3, h2")?.text() ?: "Course materials"),
                    "modules" to section.select("li.activity[id^=module-], [data-region=activity][data-id]").mapNotNull { activity ->
                        val id = activity.id().removePrefix("module-").toLongOrNull() ?: activity.attr("data-id").toLongOrNull() ?: return@mapNotNull null
                        val link = activity.selectFirst("a.aalink, a.activityinstance, a[href*=/mod/]")
                        val visible = link?.clone()?.also { it.select(".accesshide, .sr-only").remove() }?.text().orEmpty()
                        val type = Regex("modtype_([a-z0-9]+)").find(activity.className())?.groupValues?.get(1)
                            ?: link?.attr("href")?.let { Regex("/mod/([^/]+)/").find(it)?.groupValues?.get(1) }.orEmpty()
                        obj(
                            "id" to id,
                            "name" to visible.ifEmpty { activity.attr("data-activityname") },
                            "modname" to type,
                            "url" to (link?.attr("href")?.let { MoodleUrlResolver.resolve(baseUrl, it) } ?: ""),
                            "description" to (activity.selectFirst(".contentwithoutlink, .activity-description")?.html() ?: ""),
                            "contents" to files(activity)
                        )
                    }
                )
            }
        )
    }
    fun assignment(html: String, cmid: Long, courseId: Long): JsonObject {
        val doc = document(html)
        if (!doc.selectFirst("body")?.id().orEmpty().startsWith("page-mod-assign-")) throw InvalidResponseException("Moodle did not return an assignment page")
        val id = doc.selectFirst("[data-assignmentid]")?.attr("data-assignmentid")?.toLongOrNull()
            ?: doc.selectFirst("input[name=assignid], input[name=assignmentid]")?.attr("value")?.toLongOrNull()
            ?: Regex("iteminstance=(\\d+)").find(html)?.groupValues?.get(1)?.toLongOrNull() ?: 0
        val dates = mutableMapOf<String, Long>()
        doc.select(".date-item").forEach {
            val text = it.text()
            val key = when {
                Regex("^(Due|Hạn nộp)", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "duedate"
                Regex("^(Opened|Opens|Mở)", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "allowsubmissionsfromdate"
                else -> null
            }
            if (key != null) dates[key] = timestamp(it)
        }
        for (key in listOf("duedate", "cutoffdate", "allowsubmissionsfromdate")) {
            doc.selectFirst("[data-$key], input[name=$key]")?.let { dates[key] = (it.attr("data-$key").ifEmpty { it.attr("value") }).toLongOrNull() ?: 0 }
        }
        return obj(
            "id" to id, "course" to courseId, "cmid" to cmid, "name" to (doc.selectFirst("h1, h2")?.text() ?: "Assignment"),
            "intro" to (doc.selectFirst("#intro, .activity-description")?.html() ?: ""), "introattachments" to files(doc.selectFirst("#intro") ?: doc),
            "duedate" to dates["duedate"], "cutoffdate" to dates["cutoffdate"], "allowsubmissionsfromdate" to dates["allowsubmissionsfromdate"]
        )
    }
    fun submission(html: String): JsonObject {
        val doc = document(html)
        val table = doc.selectFirst(".submissionstatustable") ?: throw InvalidResponseException("Submission status is unavailable")
        val raw = table.text().lowercase()
        val status = when {
            raw.contains("draft") || raw.contains("bản nháp") || table.select(".submissionstatusdraft").isNotEmpty() -> "draft"
            raw.contains("submitted for grading") || raw.contains("đã nộp để chấm điểm") || table.select(".submissionstatussubmitted, .submitted").isNotEmpty() -> "submitted"
            else -> "new"
        }
        return obj(
            "lastattempt" to obj(
                "submission" to obj(
                    "status" to status,
                    "timemodified" to timestamp(table),
                    "plugins" to listOf(obj("type" to "file", "fileareas" to listOf(obj("files" to files(table)))))
                )
            ),
            "feedback" to obj("grade" to obj("grade" to (doc.selectFirst(".feedback .grade, .feedbacktable .grade")?.text() ?: "")))
        )
    }
    fun forums(html: String): JsonArray {
        val doc = document(html)
        return JsonArray(
            doc.select("tr.discussion, [data-region=discussion-list-item]").mapNotNull { row ->
                val a = row.selectFirst("a[href*=/mod/forum/discuss.php]") ?: return@mapNotNull null
                val id = queryId(a.attr("href"), "d")
                if (id <= 0) {
                    null
                } else {
                    obj(
                        "id" to id,
                        "discussion" to id,
                        "name" to a.text(),
                        "userfullname" to (row.selectFirst(".author, .author-info")?.text() ?: ""),
                        "created" to timestamp(row)
                    )
                }
            }.distinctBy { it.long("id") }
        )
    }
    fun thread(html: String): JsonObject {
        val doc = document(html)
        val post = doc.selectFirst("[data-region=post], .forumpost") ?: throw InvalidResponseException("Discussion post is unavailable")
        return obj(
            "id" to (post.attr("data-post-id").toLongOrNull() ?: post.id().removePrefix("p").toLongOrNull() ?: 0),
            "message" to (post.selectFirst(".posting, .post-content-container, [data-region=post-content]")?.html() ?: ""),
            "userfullname" to (post.selectFirst(".author, .author-info")?.text() ?: ""),
            "created" to timestamp(post),
            "attachments" to files(post)
        )
    }
    fun grades(html: String): JsonObject {
        val doc = document(html)
        val table = doc.selectFirst("table.user-grade, table.generaltable") ?: throw InvalidResponseException("Grade table is unavailable")
        return obj(
            "usergrades" to listOf(
                obj(
                    "gradeitems" to table.select("tr").mapIndexedNotNull { i, row ->
                        val name = row.selectFirst(".column-itemname")?.text() ?: return@mapIndexedNotNull null
                        obj(
                            "id" to i,
                            "itemname" to name,
                            "gradeformatted" to row.selectFirst(".column-grade")?.text(),
                            "rangeformatted" to row.selectFirst(".column-range")?.text(),
                            "percentageformatted" to row.selectFirst(".column-percentage")?.text(),
                            "feedback" to row.selectFirst(".column-feedback")?.text()
                        )
                    }
                )
            )
        )
    }
    fun participants(html: String): JsonArray {
        val doc = document(html)
        val table = doc.selectFirst("table#participants") ?: throw InvalidResponseException("Participant table is unavailable")
        return JsonArray(
            table.select("tbody tr").mapNotNull { row ->
                val link = row.selectFirst("a[href*=/user/view.php?id=]")
                val id = link?.let { queryId(it.attr("href")) }?.takeIf { it > 0 }
                    ?: row.selectFirst("input[id^=user]")?.id()?.removePrefix("user")?.toLongOrNull() ?: return@mapNotNull null
                obj(
                    "id" to id,
                    "fullname" to link?.clone()?.also { it.select(".userpicture, .sr-only").remove() }?.text(),
                    "roles" to (row.selectFirst(".c2")?.text() ?: ""),
                    "groups" to (row.selectFirst(".c3")?.text() ?: ""),
                    "lastaccess" to (row.selectFirst(".c4")?.text() ?: "")
                )
            }
        )
    }
    fun hiddenTokens(html: String): Map<String, String> {
        val doc = document(html)
        val form = doc.selectFirst("form:has(input[name^=_qf__])") ?: throw InvalidResponseException("Submission form is unavailable")
        return form.select("input[type=hidden][name]").associate { it.attr("name") to it.attr("value") }
    }
    fun verifySaved(html: String) {
        val doc = document(html)
        if (doc.select(".form-error, .validationerror, .notifyproblem, .alert-danger, .errorbox, [aria-invalid=true], [data-field-error]").isNotEmpty()) throw InvalidResponseException("Moodle rejected the submission")
        if (doc.select(".submissionstatustable").isEmpty()) throw InvalidResponseException("Moodle did not confirm saving the submission")
    }
    private fun queryId(url: String, key: String = "id"): Long = Regex("[?&]$key=(\\d+)").find(url)?.groupValues?.get(1)?.toLongOrNull() ?: 0
    private fun timestamp(element: Element): Long {
        val time = element.selectFirst("[data-timestamp], [data-timecreated], [data-timemodified], time[datetime]") ?: element
        val raw = listOf("data-timestamp", "data-timecreated", "data-timemodified", "datetime").firstNotNullOfOrNull { time.attr(it).takeIf(String::isNotBlank) }
        raw?.toLongOrNull()?.let { return if (it > 10000000000L) it / 1000 else it }
        if (raw != null) runCatching { java.time.Instant.parse(raw).epochSecond }.getOrNull()?.let { return it }
        val text = element.text().substringAfter(":", element.text()).trim()
        return runCatching { LocalDateTime.parse(text, DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy, h:mm a", Locale.ENGLISH)).atZone(ZoneId.of("Asia/Ho_Chi_Minh")).toEpochSecond() }.getOrDefault(0)
    }
}
