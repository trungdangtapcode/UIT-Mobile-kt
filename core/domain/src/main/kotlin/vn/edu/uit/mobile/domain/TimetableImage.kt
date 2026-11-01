package vn.edu.uit.mobile.domain

import kotlinx.datetime.*
import vn.edu.uit.mobile.common.*
import vn.edu.uit.mobile.model.*

data class TimetableImageOptions(val from: LocalDate, val through: LocalDate, val courseNames: Boolean = true, val rooms: Boolean = false, val identity: Boolean = false)
data class TimetableImageRow(val start: Long, val end: Long, val kind: ScheduleKind, val title: String, val classCode: String, val room: String)
data class TimetableImageDay(val date: LocalDate, val rows: List<TimetableImageRow>)

/** Already redacted: renderers never receive the raw profile, source URLs or lecturer details. */
data class TimetableImagePlan(val options: TimetableImageOptions, val days: List<TimetableImageDay>, val identity: String, val updatedAt: Long) {
    val count: Int get() = days.sumOf { it.rows.size }
}

class TimetableImageExport {
    fun plan(snapshot: PortalSnapshot, options: TimetableImageOptions): TimetableImagePlan {
        require(options.from.year in 1970..9998 && options.through.year in 1970..9998)
        require(options.through.toEpochDays() - options.from.toEpochDays() in 0..6)
        require(snapshot.updatedAt > 0)
        val entries = GetUnifiedScheduleUseCase()(snapshot, options.from, options.through)
            .filter { it.kind in setOf(ScheduleKind.CLASS, ScheduleKind.CANCELED, ScheduleKind.MAKE_UP) }.distinctBy { it.key }
        require(entries.size <= 60)
        val days = (0..(options.through.toEpochDays() - options.from.toEpochDays())).map { offset ->
            val date = options.from.plus(offset, DateTimeUnit.DAY)
            TimetableImageDay(
                date,
                entries.filter { Instant.fromEpochSeconds(it.start).toLocalDateTime(UitTimeZone).date == date }.map { entry ->
                    // Make-up notice titles can contain reasons or room details; use only a known course name.
                    val title = if (entry.kind == ScheduleKind.MAKE_UP) snapshot.timetable.firstOrNull { it.classCode.equals(entry.classCode, true) }?.courseName ?: entry.course?.fullname.orEmpty() else entry.title
                    TimetableImageRow(entry.start, entry.end, entry.kind, if (options.courseNames) clean(title, 300) else "", if (options.courseNames) clean(entry.classCode, 80) else "", if (options.rooms) clean(entry.room, 120) else "")
                }
            )
        }
        val identity = if (options.identity) listOf(clean(snapshot.profile.fullName, 120), clean(snapshot.profile.studentId, 64)).filter(String::isNotBlank).joinToString(" · ") else ""
        return TimetableImagePlan(options, days, identity, snapshot.updatedAt)
    }
    private fun clean(value: String, limit: Int): String = value.replace(Regex("[\\p{Cntrl}\\s]+"), " ").trim().let { if (it.length <= limit) it else it.take(limit - 1) + "…" }
}
