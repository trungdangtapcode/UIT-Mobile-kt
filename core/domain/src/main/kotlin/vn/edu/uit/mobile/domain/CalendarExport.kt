package vn.edu.uit.mobile.domain

import java.util.UUID
import kotlinx.datetime.*
import vn.edu.uit.mobile.model.*

enum class CalendarExportType { CLASSES, EXAMS, DEADLINES, OTHER }
data class CalendarExportOptions(val from: LocalDate, val through: LocalDate, val types: Set<CalendarExportType> = CalendarExportType.entries.toSet())
data class CalendarExportPlan(val options: CalendarExportOptions, val entries: List<ScheduleEntry>, val updatedAt: Long) {
    val fileName: String get() = "uit-schedule-${options.from}-${options.through}.ics"
}

/** A finite calendar snapshot. Recurrence is expanded by the shared timetable rules. */
class CalendarFileExport {
    fun plan(snapshot: PortalSnapshot, options: CalendarExportOptions): CalendarExportPlan {
        require(options.through.toEpochDays() - options.from.toEpochDays() in 0..89) { "Choose one to 90 days, with the end on or after the start." }
        require(options.types.isNotEmpty()) { "Choose at least one event type." }
        val entries = GetUnifiedScheduleUseCase()(snapshot, options.from, options.through).filter { type(it.kind) in options.types }.distinctBy { it.key }
        require(entries.size <= 2000) { "Choose a shorter range with at most 2,000 events." }
        return CalendarExportPlan(options, entries, snapshot.updatedAt)
    }

    fun render(plan: CalendarExportPlan, generatedAt: Long): String {
        require(plan.entries.size in 1..2000) { "There are no events to export." }
        val lines = mutableListOf("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//UIT Mobile//Schedule export//EN", "CALSCALE:GREGORIAN")
        for (entry in plan.entries) {
            val description = buildString {
                append("UIT Mobile saved schedule. Times originate in Asia/Ho_Chi_Minh (GMT+07).\n")
                append("Type: ${label(entry.kind)}")
                if (entry.classCode.isNotBlank()) append("\nClass: ${entry.classCode}")
                if (entry.end <= entry.start) append("\nEnd time not provided.")
                if (plan.updatedAt > 0) append("\nSnapshot saved: ${Instant.fromEpochSeconds(plan.updatedAt)}")
                append("\nFile export only; check UIT Mobile for subsequent updates.")
            }
            lines += "BEGIN:VEVENT"
            lines += "UID:${UUID.nameUUIDFromBytes(("uit-mobile-calendar-v1:" + entry.key).toByteArray(Charsets.UTF_8))}@uit-mobile.local"
            lines += "DTSTAMP:${utc(generatedAt)}"
            lines += "DTSTART:${utc(entry.start)}"
            if (entry.end > entry.start) lines += "DTEND:${utc(entry.end)}"
            lines += "SUMMARY:${escape(if (entry.kind == ScheduleKind.CANCELED) "Canceled: ${entry.title}" else entry.title)}"
            if (entry.room.isNotBlank()) lines += "LOCATION:${escape(entry.room)}"
            lines += "DESCRIPTION:${escape(description)}"
            lines += "CATEGORIES:${label(entry.kind)}"
            lines += "CLASS:PRIVATE"
            lines += "TRANSP:${if (entry.kind in setOf(ScheduleKind.CANCELED, ScheduleKind.DEADLINE)) "TRANSPARENT" else "OPAQUE"}"
            if (entry.kind == ScheduleKind.CANCELED) lines += "STATUS:CANCELLED"
            lines += "END:VEVENT"
        }
        lines += "END:VCALENDAR"
        return lines.joinToString("\r\n", postfix = "\r\n") { fold(it) }
    }

    private fun type(kind: ScheduleKind) = when (kind) {
        ScheduleKind.CLASS, ScheduleKind.CANCELED, ScheduleKind.MAKE_UP -> CalendarExportType.CLASSES
        ScheduleKind.EXAM -> CalendarExportType.EXAMS
        ScheduleKind.DEADLINE -> CalendarExportType.DEADLINES
        ScheduleKind.EVENT -> CalendarExportType.OTHER
    }
    private fun label(kind: ScheduleKind) = when (kind) {
        ScheduleKind.CLASS -> "Class"
        ScheduleKind.CANCELED -> "Canceled class"
        ScheduleKind.MAKE_UP -> "Make-up class"
        ScheduleKind.EXAM -> "Exam"
        ScheduleKind.DEADLINE -> "Deadline"
        ScheduleKind.EVENT -> "Event"
    }
    private fun utc(seconds: Long): String {
        val time = Instant.fromEpochSeconds(seconds).toLocalDateTime(TimeZone.UTC)
        require(time.year in 1..9999) { "The schedule contains an unsupported date." }
        return "%04d%02d%02dT%02d%02d%02dZ".format(java.util.Locale.ROOT, time.year, time.monthNumber, time.dayOfMonth, time.hour, time.minute, time.second)
    }
    private fun escape(value: String) = value.replace("\\", "\\\\").replace("\r\n", "\n").replace('\r', '\n').replace("\n", "\\n").replace(";", "\\;").replace(",", "\\,").filter { it.code >= 32 || it == '\t' }
    private fun fold(line: String): String = buildString {
        var octets = 0
        line.codePoints().forEach { point ->
            val character = String(Character.toChars(point))
            val size = character.toByteArray(Charsets.UTF_8).size
            if (octets + size > 75) {
                append("\r\n ")
                octets = 1
            }
            append(character)
            octets += size
        }
    }
}
