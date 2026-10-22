package vn.edu.uit.mobile.daa

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Element
import java.text.Normalizer
import kotlinx.datetime.Instant
import kotlinx.serialization.json.*
import vn.edu.uit.mobile.common.*
import vn.edu.uit.mobile.model.*
import vn.edu.uit.mobile.network.*

internal fun normalized(value: String): String = Normalizer.normalize(value.lowercase().replace('đ', 'd'), Normalizer.Form.NFD).replace(Regex("\\p{M}"), "").replace(Regex("[^a-z0-9]+"), " ").trim()
fun unwrapDrupalHtml(body: String): String {
    LoginPageSnifferPlugin.inspect(body.toByteArray())
    if (!body.trimStart().startsWith('[')) return body
    val commands = runCatching { PortalJson.parseToJsonElement(body).jsonArray }.getOrElse { throw InvalidResponseException("Invalid Drupal AJAX response") }
    val inserts = commands.filter { it.str("command") == "insert" }.map { it.str("data") }
    if (inserts.isEmpty()) throw InvalidResponseException("Drupal returned no content")
    return inserts.joinToString("\n").also { LoginPageSnifferPlugin.inspect(it.toByteArray()) }
}
internal class DaaTable(table: Element) {
    private val rows = table.select("tr")
    private val headerRow = rows.indexOfFirst { it.select("th").isNotEmpty() }.let { if (it < 0) 0 else it }
    private val headers = rows.getOrNull(headerRow)?.select("th, td")?.map { normalized(it.text()) }.orEmpty()
    val data: List<Map<String, String>> = buildList {
        val spans = mutableMapOf<Int, Pair<Int, String>>()
        rows.drop(headerRow + 1).forEach { row ->
            val values = mutableMapOf<Int, String>()
            spans.toMap().forEach { (index, span) ->
                values[index] = span.second
                if (span.first <= 1) spans.remove(index) else spans[index] = span.first - 1 to span.second
            }
            var index = 0
            row.children().filter { it.tagName() in setOf("td", "th") }.forEach { cell ->
                while (index in values) index++
                val text = cell.clone().also { it.select("br").forEach { br -> br.after(" ") } }.text()
                val width = cell.attr("colspan").toIntOrNull() ?: 1
                repeat(width) {
                    values[index] = text
                    val height = cell.attr("rowspan").toIntOrNull() ?: 1
                    if (height > 1) spans[index] = height - 1 to text
                    index++
                }
            }
            if (values.isNotEmpty()) add(headers.mapIndexed { i, key -> key to values[i].orEmpty() }.toMap())
        }
    }
}
internal fun Map<String, String>.column(vararg aliases: String): String = aliases.firstNotNullOfOrNull { this[normalized(it)]?.takeIf(String::isNotBlank) }.orEmpty()
internal fun number(raw: String): Double? = raw.trim().replace(',', '.').toDoubleOrNull()
internal fun title(article: Element): String = article.select("[property]").firstOrNull { it.attr("property").split(' ').any { p -> p in setOf("dc:title", "og:title") } }?.let { it.attr("content").ifEmpty { it.text() } }
    ?: article.selectFirst("h2, h1, .node-title")?.text().orEmpty()
internal fun created(article: Element): Long = article.select("[property], time[datetime]").firstNotNullOfOrNull { e ->
    e.attr("content").ifEmpty { e.attr("datetime") }.takeIf(String::isNotBlank)?.let { runCatching { Instant.parse(it).epochSeconds }.getOrNull() }
} ?: 0
class DaaTimetableParser {
    fun parse(body: String): List<DaaTimetableSlot> {
        val doc = Ksoup.parse(unwrapDrupalHtml(body))
        val tables = doc.select("table")
        if (tables.isEmpty()) {
            if (normalized(doc.text()).contains("chua co") || doc.select(".view-empty").isNotEmpty()) return emptyList()
            throw InvalidResponseException("DAA timetable table is missing")
        }
        return tables.flatMap { DaaTable(it).data }.mapNotNull { row ->
            val classCode = row.column("Mã lớp", "Mã lớp học phần", "Lớp", "classCode").uppercase()
            if (!Regex("[A-Z]{2,}\\d{3}\\.[A-Z0-9.]+").matches(classCode)) return@mapNotNull null
            val dayRaw = normalized(row.column("Thứ", "Ngày học", "dayOfWeek"))
            val day = if (dayRaw in setOf("cn", "chu nhat", "sunday")) 7 else (Regex("[2-7]").find(dayRaw)?.value?.toInt()?.minus(1) ?: throw InvalidResponseException("Invalid timetable weekday"))
            val periods = try {
                UitLessonTimeCalculator.parsePeriods(row.column("Tiết", "Tiết học", "periods"))
            } catch (e: IllegalArgumentException) {
                throw InvalidResponseException("Invalid timetable periods")
            }
            val dates = row.column("Thời gian", "Thời gian học", "Ngày bắt đầu kết thúc")
            val allDates = Regex("\\d{1,2}[/-]\\d{1,2}[/-]\\d{2,4}").findAll(dates).map { it.value }.toList()
            val start = parsePortalDate(row.column("Bắt đầu", "Ngày bắt đầu", "BĐ", "startDate").ifEmpty { allDates.firstOrNull().orEmpty() }) ?: throw InvalidResponseException("Timetable start date is missing")
            val end = parsePortalDate(row.column("Kết thúc", "Ngày kết thúc", "KT", "endDate").ifEmpty { allDates.getOrNull(1).orEmpty() }) ?: throw InvalidResponseException("Timetable end date is missing")
            if (end < start) throw InvalidResponseException("Invalid semester date range")
            DaaTimetableSlot(
                classCode, row.column("Mã môn", "Mã môn học").ifEmpty { classCode.substringBefore('.') }, row.column("Tên môn học", "Tên môn", "Môn học"),
                number(row.column("TC", "Số TC", "Số tín chỉ", "Tín chỉ"))?.toInt() ?: 0, day, periods.first, periods.last, row.column("Phòng", "Phòng học"),
                row.column("Giảng viên", "GV", "Tên giảng viên"), start.toString(), end.toString()
            )
        }.distinct()
    }
}
class DaaExamParser {
    fun parse(body: String): List<DaaExamSlot> {
        val doc = Ksoup.parse(unwrapDrupalHtml(body))
        if (doc.select("table").isEmpty() && doc.select(".view-empty").isEmpty()) throw InvalidResponseException("DAA exam table is missing")
        return doc.select("table").flatMap { DaaTable(it).data }.mapNotNull { row ->
            val code = row.column("Mã lớp", "Mã lớp học phần", "Lớp")
            if (code.isBlank()) return@mapNotNull null
            val shift = Regex("[1-4]").find(row.column("Ca", "Ca thi"))?.value?.toInt() ?: throw InvalidResponseException("Invalid exam shift")
            val date = parsePortalDate(row.column("Ngày thi", "Ngày")) ?: throw InvalidResponseException("Invalid exam date")
            DaaExamSlot(code.uppercase(), row.column("Tên môn", "Tên môn học", "Môn học"), shift, date.toString(), row.column("Phòng", "Phòng thi"), row.column("SBD", "Số báo danh", "Chỗ ngồi"))
        }.distinct()
    }
}
class DaaNoticeParser {
    fun changes(body: String): List<ClassScheduleChangeNotice> = articles(body).flatMap { article ->
        val heading = title(article)
        val text = article.text()
        val type = when {
            normalized(heading).contains("nghi") -> ScheduleChangeType.CANCELED
            normalized(heading).contains("bu") -> ScheduleChangeType.MAKE_UP
            else -> return@flatMap emptyList()
        }
        val codes = Regex("[A-Z]{2,}\\d{3}\\.[A-Z0-9]+(?:\\.[A-Z0-9]+)?").findAll(text.uppercase()).map { it.value }.toSet()
        val date = parsePortalDate(Regex("(?:Ngày|ngày|Date)\\s*(?:học|nghỉ|bù)?\\s*:?\\s*(\\d{1,2}[/-]\\d{1,2}[/-]\\d{2,4})").find(text)?.groupValues?.get(1) ?: text)
            ?: throw InvalidResponseException("Class notice date is missing")
        val rawPeriods = Regex("(?:Tiết|tiết)\\s*:\\s*([0-9– -]+)").find(text)?.groupValues?.get(1)?.trim()
        val periods = rawPeriods?.let { UitLessonTimeCalculator.parsePeriods(it) } ?: (1..10)
        val room = Regex("(?:Phòng|phòng)\\s*:\\s*([^\\s,;]+)").find(text)?.groupValues?.get(1).orEmpty()
        codes.map { code -> ClassScheduleChangeNotice(nodeId(article), type, code, heading, room, date.toString(), periods.first, periods.last, created(article)) }
    }
    fun announcements(body: String): List<DaaAnnouncement> = articles(body).map {
        val path = it.attr("about").ifEmpty { "/node/${nodeId(it)}" }
        val url = UrlSanitizer.credentialFreeUrl(MoodleUrlResolver.resolve("https://daa.uit.edu.vn", path)) ?: ""
        DaaAnnouncement(nodeId(it), title(it), url, created(it), sanitizeHtml(it.selectFirst(".field-name-body, .content")?.html().orEmpty(), "https://daa.uit.edu.vn"))
    }
    private fun articles(body: String): List<Element> {
        val doc = Ksoup.parse(unwrapDrupalHtml(body))
        val articles = doc.select("article[id^=node-], .node[id^=node-]").distinctBy { it.id() }
        if (articles.isEmpty() && doc.select(".view-empty").isEmpty()) throw InvalidResponseException("DAA announcement list is missing")
        return articles
    }
    private fun nodeId(e: Element): Long = e.id().removePrefix("node-").toLongOrNull() ?: throw InvalidResponseException("Invalid Drupal node ID")
}
class DaaAcademicParser {
    fun profile(body: String): StudentProfile {
        val doc = Ksoup.parse(unwrapDrupalHtml(body))
        val values = mutableMapOf<String, String>()
        doc.select("tr").forEach { row ->
            val cells = row.select("td, th")
            if (cells.size >= 2) values[normalized(cells[0].text())] = cells[1].text()
        }
        doc.select(".field").forEach { field ->
            val label = field.selectFirst(".field-label")?.text()
            if (label != null) values[normalized(label)] = field.selectFirst(".field-item")?.text().orEmpty()
        }
        val id = values.column("MSSV", "Mã số sinh viên", "Mã sinh viên")
        if (id.isBlank()) throw InvalidResponseException("DAA student profile is missing")
        return StudentProfile(
            id,
            values.column("Họ và tên", "Họ tên"),
            parsePortalDate(values.column("Ngày sinh"))?.toString().orEmpty(),
            values.column("Khoa"),
            values.column("Hệ đào tạo", "Chương trình đào tạo"),
            doc.selectFirst("#hinhthe img[src]")?.attr("src")?.let { UrlSanitizer.credentialFreeUrl(MoodleUrlResolver.resolve("https://daa.uit.edu.vn", it)) }.orEmpty()
        )
    }
    fun transcript(body: String): AcademicTranscript {
        val doc = Ksoup.parse(unwrapDrupalHtml(body))
        val semesters = doc.select("table").mapNotNull { table ->
            val rows = DaaTable(table).data
            val courses = rows.mapNotNull { row ->
                val code = row.column("Mã môn", "Mã môn học")
                if (code.isBlank()) return@mapNotNull null
                CourseGradeRow(
                    code, row.column("Tên môn", "Tên môn học"), number(row.column("TC", "Số TC", "Tín chỉ"))?.toInt() ?: 0,
                    number(row.column("QT", "Quá trình")), number(row.column("GK", "Giữa kỳ")), number(row.column("TH", "Thực hành")), number(row.column("CK", "Cuối kỳ")), number(row.column("TK", "Tổng kết", "Điểm tổng kết")), row.column("Kết quả", "Trạng thái")
                )
            }
            if (courses.isEmpty()) return@mapNotNull null
            val container = table.parent() ?: table
            val semester = table.selectFirst("caption")?.text() ?: container.selectFirst("h2, h3, h4")?.text() ?: table.previousElementSibling()?.text() ?: "Semester"
            val gpa = Regex("(?:ĐTB|GPA|Trung bình)[^0-9]{0,30}([0-9]+[.,][0-9]+)", RegexOption.IGNORE_CASE).find(container.text())?.groupValues?.get(1)?.let(::number)
            SemesterGradeSummary(semester, gpa, courses.sumOf { it.credits }, courses.filter { (normalized(it.status).contains("qua") || normalized(it.status).contains("mien")) || (it.totalScore ?: -1.0) >= 5.0 }.sumOf { it.credits }, courses)
        }
        if (semesters.isEmpty() && doc.select(".view-empty").isEmpty()) throw InvalidResponseException("DAA transcript table is missing")
        fun summary(label: String): Double? = Regex("$label[^0-9]{0,30}([0-9]+(?:[.,][0-9]+)?)", RegexOption.IGNORE_CASE).find(normalized(doc.text()))?.groupValues?.get(1)?.let(::number)
        val cumulative = doc.selectFirst("[data-cumulative-gpa]")?.attr("data-cumulative-gpa")?.let(::number)
            ?: Regex("(?:tích lũy|tích luỹ)[^0-9]{0,30}([0-9]+[.,][0-9]+)", RegexOption.IGNORE_CASE).find(doc.text())?.groupValues?.get(1)?.let(::number)
        return AcademicTranscript(
            semesters,
            cumulative,
            doc.selectFirst("[data-credits-earned]")?.attr("data-credits-earned")?.toIntOrNull() ?: semesters.sumOf { it.creditsEarned },
            doc.selectFirst("[data-graduation-progress]")?.attr("data-graduation-progress")?.let(::number)
        )
    }
}
