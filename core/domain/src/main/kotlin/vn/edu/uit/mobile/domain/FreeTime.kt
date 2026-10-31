package vn.edu.uit.mobile.domain

import kotlinx.datetime.*
import vn.edu.uit.mobile.common.*
import vn.edu.uit.mobile.model.*

data class FreeTimeOptions(val date: LocalDate, val start: LocalTime = LocalTime(8, 0), val end: LocalTime = LocalTime(18, 0), val minimumMinutes: Int = 60, val bufferMinutes: Int = 15)
data class PersonalTimeBlock(val id: String, val title: String, val start: Long, val end: Long)
data class FreeTimeSlot(val start: Long, val end: Long) {
    val minutes: Long get() = (end - start) / 60
}
data class FreeTimeBusyBlock(val key: String, val title: String, val start: Long, val end: Long, val bufferedStart: Long, val bufferedEnd: Long, val kind: String, val unknownEnd: Boolean = false)
data class FreeTimeResult(val options: FreeTimeOptions, val slots: List<FreeTimeSlot>, val busy: List<FreeTimeBusyBlock>, val deadlines: List<ScheduleEntry>, val canceledCount: Int)

/** Finds candidate intervals in one UIT day; it does not establish actual availability. */
class FindFreeTimeUseCase {
    operator fun invoke(snapshot: PortalSnapshot, options: FreeTimeOptions, personal: List<PersonalTimeBlock> = emptyList()): FreeTimeResult {
        require(options.date.year in 1..9999 && options.start < options.end && options.start.second == 0 && options.end.second == 0 && options.start.nanosecond == 0 && options.end.nanosecond == 0) { "Choose a same-day window with minute precision and an end after its start." }
        require(options.minimumMinutes in 10..720) { "Choose a minimum duration of 10 to 720 minutes." }
        require(options.bufferMinutes in 0..120) { "Choose a travel/break buffer of 0 to 120 minutes." }
        require(personal.size <= 10 && personal.map { it.id }.distinct().size == personal.size && personal.all { it.id.isNotBlank() && it.title.isNotBlank() && it.title.length <= 80 && it.start < it.end }) { "Keep at most ten personal blocks with a title and valid times." }
        val windowStart = dateAt(options.date, options.start)
        val windowEnd = dateAt(options.date, options.end)
        val buffer = options.bufferMinutes * 60L
        val schedule = GetUnifiedScheduleUseCase()(snapshot, options.date.minus(1, DateTimeUnit.DAY), options.date.plus(1, DateTimeUnit.DAY))
        val canceled = schedule.filter { it.kind == ScheduleKind.CANCELED }
        val remaining = canceled.associateWith { remainingClassPeriods(snapshot, it) }
        val timed = (schedule.filter { it.kind in setOf(ScheduleKind.CLASS, ScheduleKind.MAKE_UP, ScheduleKind.EXAM) } + remaining.values.flatten()).toMutableList()
        // Calendar items can start several days before the search window and still overlap it.
        snapshot.events.filterNot { it.deadline }.distinctBy { Triple(it.key, it.start, it.end) }.forEach { event ->
            timed += ScheduleEntry(event.key, event.name, event.start, event.end, ScheduleKind.EVENT)
        }
        val busy = timed.distinctBy { Triple(it.key, it.start, it.end) }.map { entry ->
            block(entry.key, entry.title, entry.start, entry.end, entry.kind.name, windowStart, windowEnd, buffer)
        }.filterNotNull().toMutableList()
        personal.forEach { entry ->
            block("personal:${entry.id}", entry.title, entry.start, entry.end, "PERSONAL", windowStart, windowEnd, buffer)?.let(busy::add)
        }
        busy.sortWith(compareBy({ it.bufferedStart }, { it.bufferedEnd }, { it.key }))
        val slots = mutableListOf<FreeTimeSlot>()
        var cursor = windowStart
        fun gap(end: Long) {
            // Round inward so minute-only labels never offer occupied seconds.
            val roundedStart = Math.floorDiv(cursor + 59, 60) * 60
            val roundedEnd = Math.floorDiv(end, 60) * 60
            if (roundedEnd - roundedStart >= options.minimumMinutes * 60L) slots += FreeTimeSlot(roundedStart, roundedEnd)
        }
        busy.forEach { entry ->
            if (entry.bufferedStart > cursor) gap(entry.bufferedStart)
            cursor = maxOf(cursor, entry.bufferedEnd)
        }
        if (cursor < windowEnd) gap(windowEnd)
        return FreeTimeResult(
            options,
            slots,
            busy,
            schedule.filter { it.kind == ScheduleKind.DEADLINE && it.start >= dateAt(options.date, LocalTime(0, 0)) && it.start < dateAt(options.date.plus(1, DateTimeUnit.DAY), LocalTime(0, 0)) },
            remaining.count { (entry, periods) -> periods.isEmpty() && Instant.fromEpochSeconds(entry.start).toLocalDateTime(UitTimeZone).date == options.date }
        )
    }

    private fun remainingClassPeriods(snapshot: PortalSnapshot, entry: ScheduleEntry): List<ScheduleEntry> {
        val date = Instant.fromEpochSeconds(entry.start).toLocalDateTime(UitTimeZone).date
        val slots = snapshot.timetable.filter { "class:${it.classCode}:$date:${it.lessonStart}" == entry.key }
        if (slots.isEmpty()) return listOf(entry.copy(kind = ScheduleKind.CLASS))
        return slots.flatMap { slot ->
            val notices = snapshot.notices.filter { it.type == ScheduleChangeType.CANCELED && it.classCode.equals(slot.classCode, true) && it.targetDate == date.toString() }
            val periods = (slot.lessonStart..slot.lessonEnd).filter { period -> notices.none { period in it.lessonStart..it.lessonEnd } }
            val runs = mutableListOf<IntRange>()
            for (period in periods) {
                val last = runs.lastOrNull()
                if (last != null && last.last + 1 == period) runs[runs.lastIndex] = last.first..period else runs += period..period
            }
            runs.map { run ->
                entry.copy(key = "remaining:${entry.key}:${run.first}:${run.last}", start = dateAt(date, UitLessonTimeCalculator.start(run.first)), end = dateAt(date, UitLessonTimeCalculator.end(run.last)), kind = ScheduleKind.CLASS)
            }
        }
    }

    private fun block(key: String, title: String, start: Long, reportedEnd: Long, kind: String, windowStart: Long, windowEnd: Long, buffer: Long): FreeTimeBusyBlock? {
        require(start in -62135596800L..253402300799L && reportedEnd in -62135596800L..253402300799L) { "A saved event has an unsupported timestamp. Refresh the schedule before planning." }
        val unknown = reportedEnd <= start
        val end = if (unknown) {
            val date = Instant.fromEpochSeconds(start).toLocalDateTime(UitTimeZone).date
            dateAt(date.plus(1, DateTimeUnit.DAY), LocalTime(0, 0))
        } else {
            reportedEnd
        }
        if (start - buffer >= windowEnd || end + buffer <= windowStart) return null
        return FreeTimeBusyBlock(key, title, start, end, maxOf(windowStart, start - buffer), minOf(windowEnd, end + buffer), kind, unknown)
    }
}
