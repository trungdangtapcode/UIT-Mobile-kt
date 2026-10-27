package vn.edu.uit.mobile.domain

import kotlinx.datetime.*
import vn.edu.uit.mobile.common.*
import vn.edu.uit.mobile.model.*

class CourseCrossLinker {
    fun match(classCode: String, courses: List<Course>): Course? {
        val token = classCode.uppercase().trim()
        if (token.isEmpty()) return null
        val regex = Regex("(?<![A-Z0-9.])${Regex.escape(token)}(?![A-Z0-9.])")
        val matches = courses.filter { regex.containsMatchIn("${it.shortname.uppercase()} ${it.fullname.uppercase()}") }
        return matches.singleOrNull()
    }
}
class CrossLinkCourseUseCase(private val linker: CourseCrossLinker = CourseCrossLinker()) {
    operator fun invoke(classCode: String, courses: List<Course>): Course? = linker.match(classCode, courses)
}
class GetUnifiedScheduleUseCase(private val linker: CourseCrossLinker = CourseCrossLinker()) {
    operator fun invoke(snapshot: PortalSnapshot, from: LocalDate, through: LocalDate): List<ScheduleEntry> {
        require(through >= from)
        require(through.toEpochDays() - from.toEpochDays() <= 366) { "Choose a range of at most one year" }
        val result = mutableListOf<ScheduleEntry>()
        var date = from
        while (date <= through) {
            snapshot.timetable.filter {
                it.dayOfWeek == date.dayOfWeek.isoDayNumber &&
                    date.toString() >= it.startDate &&
                    date.toString() <= it.endDate &&
                    ((date.toEpochDays() - LocalDate.parse(it.startDate).toEpochDays()) / 7) % it.weekInterval.coerceAtLeast(1) == 0
            }.forEach { slot ->
                val canceled = snapshot.notices.any { it.type == ScheduleChangeType.CANCELED && it.classCode.equals(slot.classCode, true) && it.targetDate == date.toString() && it.lessonStart <= slot.lessonEnd && it.lessonEnd >= slot.lessonStart }
                result += ScheduleEntry(
                    "class:${slot.classCode}:$date:${slot.lessonStart}",
                    slot.courseName,
                    dateAt(date, UitLessonTimeCalculator.start(slot.lessonStart)),
                    dateAt(date, UitLessonTimeCalculator.end(slot.lessonEnd)),
                    if (canceled) ScheduleKind.CANCELED else ScheduleKind.CLASS,
                    slot.classCode,
                    slot.room,
                    linker.match(slot.classCode, snapshot.courses)
                )
            }
            date = date.plus(1, DateTimeUnit.DAY)
        }
        snapshot.notices.filter { it.type == ScheduleChangeType.MAKE_UP && it.targetDate >= from.toString() && it.targetDate <= through.toString() }.distinctBy { "${it.nodeId}:${it.classCode}" }.forEach {
            val d = LocalDate.parse(it.targetDate)
            result += ScheduleEntry("makeup:${it.nodeId}:${it.classCode}", it.title, dateAt(d, UitLessonTimeCalculator.start(it.lessonStart)), dateAt(d, UitLessonTimeCalculator.end(it.lessonEnd)), ScheduleKind.MAKE_UP, it.classCode, it.room, linker.match(it.classCode, snapshot.courses))
        }
        snapshot.exams.filter { it.examDate >= from.toString() && it.examDate <= through.toString() }.forEach {
            val start = dateAt(LocalDate.parse(it.examDate), UitLessonTimeCalculator.examStart(it))
            result += ScheduleEntry("exam:${it.classCode}:${it.examDate}:${it.examShift}", it.courseName, start, start, ScheduleKind.EXAM, it.classCode, it.room, linker.match(it.classCode, snapshot.courses))
        }
        val rangeStart = dateAt(from, LocalTime(0, 0))
        val rangeEnd = dateAt(through.plus(1, DateTimeUnit.DAY), LocalTime(0, 0))
        snapshot.events.filter { it.start >= rangeStart && it.start < rangeEnd }.distinctBy { it.key }.forEach { e ->
            result += ScheduleEntry(
                e.key,
                e.name,
                e.start,
                e.end,
                if (e.deadline) ScheduleKind.DEADLINE else ScheduleKind.EVENT,
                course = snapshot.courses.firstOrNull { it.id == e.courseId && it.accountId == e.accountId },
                needsAction = e.needsAction
            )
        }
        snapshot.assignments.filter { it.due >= rangeStart && it.due < rangeEnd }.forEach { assignment ->
            val hasCalendarOverride = snapshot.events.any { event ->
                event.accountId == assignment.accountId &&
                    event.deadline &&
                    (event.assignmentId == assignment.id || event.url.contains("/mod/assign/view.php?id=${assignment.cmid}"))
            }
            if (!hasCalendarOverride) {
                result += ScheduleEntry(
                    "assignment:${assignment.accountId}:${assignment.id}",
                    assignment.name,
                    assignment.due,
                    assignment.due,
                    ScheduleKind.DEADLINE,
                    course = snapshot.courses.firstOrNull { it.accountId == assignment.accountId && it.id == assignment.courseId }
                )
            }
        }
        return result.sortedWith(compareBy({ it.start }, { it.key }))
    }
}
class CalculateDeadlineRemindersUseCase {
    operator fun invoke(events: List<CalendarEvent>, sent: Set<String>, now: Long): List<DeadlineReminder> = events.mapNotNull { event ->
        val remaining = event.start - now
        if (!event.deadline || !event.needsAction || remaining <= 0 || remaining > 86400) return@mapNotNull null
        val hours = if (remaining <= 3600) 1 else 24
        val key = "${event.key}:${event.start}:$hours"
        if (key in sent) null else DeadlineReminder(key, event, hours)
    }.distinctBy { it.key }
}
class SubmitAssignmentUseCase(private val repository: PortalRepository) {
    suspend operator fun invoke(course: Course, assignment: Assignment, name: String, data: ByteArray, confirmed: Boolean): AppResult<Unit, AppError> {
        if (!confirmed) return AppResult.Failure(AppError.ValidationError("Confirm the file submission first"))
        if (data.isEmpty() || name.isBlank()) return AppResult.Failure(AppError.ValidationError("Choose a nonempty file"))
        return repository.submitFile(course, assignment, name, data, true)
    }
}
class ProjectGpaUseCase {
    operator fun invoke(currentGpa: Double, earnedCredits: Int, planned: List<Pair<Int, Double>>): Double {
        require(currentGpa in 0.0..10.0 && earnedCredits >= 0 && planned.all { it.first > 0 && it.second in 0.0..10.0 })
        val credits = earnedCredits + planned.sumOf { it.first }
        require(credits > 0)
        return (currentGpa * earnedCredits + planned.sumOf { it.first * it.second }) / credits
    }
}
