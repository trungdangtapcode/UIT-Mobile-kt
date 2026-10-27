package vn.edu.uit.mobile.daa

import com.fleeksoft.ksoup.Ksoup
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import vn.edu.uit.mobile.common.*
import vn.edu.uit.mobile.model.*
import vn.edu.uit.mobile.network.*

/** Academic reads for the current student portal; legacy Drupal remains separate. */
class UitPortalClient(private val transport: PortalTransport) {
    suspend fun timetable(): List<DaaTimetableSlot> = UitPortalParser.timetable(parseJson(transport.request("/api/sinh-vien/tkb/dang-ky")))
    suspend fun transcript(): AcademicTranscript = UitPortalParser.transcript(parseJson(transport.request("/api/sinh-vien/bang-diem")))
    suspend fun profile(): StudentProfile = UitPortalParser.profile(transport.request("/sinh-vien/ho-so").toString(Charsets.UTF_8))
    suspend fun profileDetails(): StudentProfileDetails = UitProfileParser.parse(transport.request("/sinh-vien/ho-so").toString(Charsets.UTF_8))
    suspend fun profileInsurance(): ProfileSection = UitProfileParser.insurance(parseJson(transport.request("/api/sinh-vien/bao-hiem")))
    suspend fun profilePortrait(): ByteArray = transport.request("/api/sinh-vien/anh-the").also {
        val jpeg = it.size >= 3 && it[0] == 0xff.toByte() && it[1] == 0xd8.toByte() && it[2] == 0xff.toByte()
        val png = it.size >= 8 && it.copyOfRange(0, 8).contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a))
        if (it.size > 2 * 1024 * 1024 || !(jpeg || png)) throw InvalidResponseException("Student portrait is unavailable")
    }
    suspend fun exams(): List<DaaExamSlot> {
        val term = parseJson(transport.request("/api/sinh-vien/tkb/dang-ky"))
        val semester = term.int("hocKy")
        val firstDate = term.list("rows").firstNotNullOfOrNull { parsePortalDate(it.str("startDate")) } ?: today()
        // yearId is an internal database ID, whereas this endpoint expects a year name.
        val year = if (firstDate.monthNumber >= 8) firstDate.year else firstDate.year - 1
        if (semester !in 1..3) throw InvalidResponseException("UIT Portal active term is invalid")
        return listOf("midterm", "final_term").flatMap { examination ->
            UitPortalParser.exams(
                parseJson(
                    transport.request("/api/sv/exam-schedule", HttpMethod.Post) {
                        contentType(ContentType.Application.Json)
                        setBody(obj("academic" to emptyList<String>(), "year_name" to year.toString(), "examination" to examination, "semester" to "semester_$semester").toString())
                    }
                )
            )
        }.distinctBy { "${it.classCode}:${it.examDate}:${it.examShift}:${it.startTime}:${it.room}" }
    }
}

object UitPortalParser {
    fun timetable(data: JsonElement): List<DaaTimetableSlot> {
        val rows = data.field("rows") as? JsonArray ?: throw InvalidResponseException("UIT Portal timetable rows are missing")
        return rows.flatMap { row ->
            val start = parsePortalDate(row.str("startDate")) ?: throw InvalidResponseException("UIT Portal timetable start date is invalid")
            val end = parsePortalDate(row.str("endDate")) ?: throw InvalidResponseException("UIT Portal timetable end date is invalid")
            row.list("slots").map { slot ->
                val day = slot.int("thu").let { if (it == 8) 7 else it - 1 }
                val first = slot.int("tietBatDau")
                val last = slot.int("tietKetThuc")
                if (day !in 1..7 || first !in 1..10 || last !in first..10 || end < start) throw InvalidResponseException("UIT Portal timetable slot is invalid")
                DaaTimetableSlot(row.str("code"), row.str("subjectCode"), row.str("subjectName"), row.int("credit"), day, first, last, slot.str("room"), row.str("lecturer"), start.toString(), end.toString(), row.int("weekly2").coerceAtLeast(1))
            }
        }
    }

    fun transcript(data: JsonElement): AcademicTranscript {
        val semesters = data.field("bySemester").field("semester_groups") as? JsonArray ?: throw InvalidResponseException("UIT Portal transcript semesters are missing")
        val summary = data.field("bySemester").field("summary")
        val earned = summary.int("accumulated_credits")
        val total = data.field("byCtdt").field("statistics").int("total_program_credit")
        return AcademicTranscript(
            semesters = semesters.map { semester ->
                val courses = semester.list("subjects").map { row ->
                    CourseGradeRow(row.str("subject_code"), row.str("subject_name"), row.int("number_of_credit"), number(row.str("process_point")), number(row.str("midterm_score")), number(row.str("practice_point")), number(row.str("final_point")), number(row.str("course_point")), row.str("status_point"))
                }
                SemesterGradeSummary("${semester.str("semester_label")} · ${semester.str("year_name")}", number(semester.str("average_point")), semester.int("total_credit"), courses.filter { (it.totalScore ?: 0.0) >= 5.0 }.sumOf { it.credits }, courses)
            },
            cumulativeGpa = number(summary.str("gpa_accumulated")),
            totalCreditsEarned = earned,
            graduationProgress = total.takeIf { it > 0 }?.let { (earned * 100.0 / it).coerceIn(0.0, 100.0) }
        )
    }

    fun exams(data: JsonElement): List<DaaExamSlot> {
        val items = data.field("items") as? JsonArray ?: throw InvalidResponseException("UIT Portal exam items are missing")
        return items.mapNotNull { row ->
            val date = parsePortalDate(row.str("date")) ?: return@mapNotNull null
            val shift = Regex("\\d+").find(row.str("shift"))?.value?.toIntOrNull() ?: 0
            val time = Regex("\\d{1,2}:\\d{2}").find(row.str("start_time"))?.value?.let { value ->
                val parts = value.split(':')
                runCatching { kotlinx.datetime.LocalTime(parts[0].toInt(), parts[1].toInt()).toString() }.getOrNull()
            }
            if (time == null && shift !in 1..4) throw InvalidResponseException("UIT Portal exam start time is invalid")
            DaaExamSlot(row.str("section_class_code").ifBlank { row.str("section_class_id") }, row.str("subject_name").ifBlank { row.str("subject_code") }, shift, date.toString(), row.str("room"), startTime = time)
        }
    }

    fun profile(body: String): StudentProfile {
        val objects = flightObjects(body)
        val data = objects.firstOrNull { "studentCode" in it && "academic" in it } ?: throw InvalidResponseException("UIT Portal profile data is missing")
        val academic = data.field("academic")
        return StudentProfile(data.str("studentCode"), data.str("fullName"), parsePortalDate(data.field("personal").str("date_of_birth"))?.toString().orEmpty(), academic.str("faculty_name"), academic.str("training_program"))
    }

    /** Decode the portal's server-rendered Flight records without executing page scripts. */
    internal fun flightObjects(body: String): List<JsonObject> {
        val stream = Ksoup.parse(body).select("script").mapNotNull { script ->
            val text = script.data().trim()
            if (!text.startsWith("self.__next_f.push(")) return@mapNotNull null
            val frame = runCatching { PortalJson.parseToJsonElement(text.substringAfter('(').substringBeforeLast(')')).jsonArray }.getOrNull() ?: return@mapNotNull null
            if (frame.firstOrNull()?.jsonPrimitive?.intOrNull == 1) frame.getOrNull(1)?.jsonPrimitive?.content else null
        }.joinToString("")
        return buildList {
            fun collect(element: JsonElement) {
                when (element) {
                    is JsonObject -> {
                        add(element)
                        element.values.forEach(::collect)
                    }
                    is JsonArray -> element.forEach(::collect)
                    else -> Unit
                }
            }
            stream.lineSequence().forEach { line ->
                val payload = line.substringAfter(':', "")
                if (payload.startsWith('{') || payload.startsWith('[')) runCatching { PortalJson.parseToJsonElement(payload) }.getOrNull()?.let(::collect)
            }
        }
    }
}
