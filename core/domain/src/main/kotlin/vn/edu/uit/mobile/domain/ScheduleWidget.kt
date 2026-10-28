package vn.edu.uit.mobile.domain

import kotlinx.datetime.*
import vn.edu.uit.mobile.common.*
import vn.edu.uit.mobile.model.*

data class ScheduleWidgetState(
    val connected: Boolean,
    val updatedAt: Long,
    val date: LocalDate,
    val next: ScheduleEntry? = null,
    val today: List<ScheduleEntry> = emptyList(),
    val deadline: ScheduleEntry? = null,
    val nextUpdateAt: Long
)

/** A cached reading model; no portal requests or student profile details are needed. */
class BuildScheduleWidgetUseCase {
    operator fun invoke(snapshot: PortalSnapshot, connected: Boolean, now: Long): ScheduleWidgetState {
        val day = Instant.fromEpochSeconds(now).toLocalDateTime(UitTimeZone).date
        val midnight = dateAt(day.plus(1, DateTimeUnit.DAY), LocalTime(0, 0))
        if (!connected || snapshot.updatedAt == 0L) return ScheduleWidgetState(connected, 0, day, nextUpdateAt = midnight + 1)
        val all = GetUnifiedScheduleUseCase()(snapshot, day, day.plus(14, DateTimeUnit.DAY))
        val meetings = all.filter { entry ->
            entry.kind in setOf(ScheduleKind.CLASS, ScheduleKind.MAKE_UP, ScheduleKind.EXAM) &&
                if (entry.kind == ScheduleKind.EXAM) entry.start >= now else entry.end > now
        }
        val completed = snapshot.events.filter { it.submitted == true }.map { it.key }.toSet()
        val deadlines = GetUnifiedScheduleUseCase()(snapshot.copy(timetable = emptyList(), notices = emptyList(), exams = emptyList()), day, day.plus(365, DateTimeUnit.DAY))
        val upcoming = deadlines.filter { it.kind == ScheduleKind.DEADLINE && it.start >= now && it.key !in completed && (it.needsAction || it.key.startsWith("assignment:")) }.minByOrNull { it.start }
        val overdue = snapshot.events.filter { it.deadline && it.needsAction && it.submitted != true && it.start < now }.maxByOrNull { it.start }?.let {
            ScheduleEntry(it.key, it.name, it.start, it.end, ScheduleKind.DEADLINE, course = snapshot.courses.firstOrNull { course -> course.accountId == it.accountId && course.id == it.courseId }, needsAction = true)
        }
        val deadline = upcoming ?: overdue
        val boundary = (meetings.flatMap { listOf(it.start, it.end) } + listOfNotNull(upcoming?.start, midnight)).filter { it > now }.minOrNull() ?: midnight
        return ScheduleWidgetState(true, snapshot.updatedAt, day, meetings.firstOrNull(), meetings.filter { Instant.fromEpochSeconds(it.start).toLocalDateTime(UitTimeZone).date == day }.take(3), deadline, boundary + 1)
    }
}
