package vn.edu.uit.mobile.domain

import kotlinx.datetime.*
import vn.edu.uit.mobile.common.*
import vn.edu.uit.mobile.model.*

enum class WorkloadIssue { MISSING_CALENDAR, SOURCE_UNAVAILABLE, LOCAL_STATE, STALE, UNKNOWN_END, CONFLICT, PERSONAL_BLOCKS, UNCONFIRMED_BUDGET, UNSCHEDULED, REFRESH_FAILED }
enum class WorkloadPressure { SHORTFALL, UNCERTAIN, WITHIN_ENTERED_BUDGET }
data class WorkloadTask(val item: WorkloadItem, val estimate: WorkloadEstimate?)
data class WorkloadCapacity(val conservative: Long, val optimistic: Long)
data class WorkloadCheckpoint(val cutoff: Long, val demand: Long, val missingEstimates: Int, val capacity: WorkloadCapacity, val pressure: WorkloadPressure)
data class StudyInterval(val start: Long, val end: Long)
data class WorkloadDay(val date: LocalDate, val tasks: List<WorkloadTask>, val capacity: WorkloadCapacity, val available: List<StudyInterval> = emptyList())
data class WorkloadSourceStamp(val account: Account, val updatedAt: Long)
data class WorkloadResult(val from: LocalDate, val through: LocalDate, val tasks: List<WorkloadTask>, val overdue: List<WorkloadTask>, val submittedCount: Int, val checkpoints: List<WorkloadCheckpoint>, val days: List<WorkloadDay>, val issues: Set<WorkloadIssue>, val assumedExamEnds: Int, val orphanEstimates: List<WorkloadEstimate>, val sources: List<WorkloadSourceStamp>)

/** A deterministic projection of recorded inputs, not an automatic study-session scheduler. */
class WorkloadEngine {
    private data class Interval(val start: Long, val end: Long)
    private data class CapacityDay(val start: Long, val end: Long, val budget: Int, val low: List<Interval>, val high: List<Interval>)

    fun analyze(primaryId: String, sources: List<WorkloadAccount>, policy: WorkloadPolicy, now: Long, personal: List<PersonalTimeBlock> = emptyList(), personalFailed: Boolean = false, refreshFailed: Boolean = false): WorkloadResult {
        validateWorkloadPolicy(policy)
        require(sources.size <= 30 && sources.map { it.account.id }.distinct().size == sources.size)
        require(personal.size <= 10 && personal.map { it.id }.distinct().size == personal.size && personal.all { it.start < it.end })
        val today = Instant.fromEpochSeconds(now).toLocalDateTime(UitTimeZone).date
        require(today.year in 1970..9998)
        val through = today.plus(policy.horizonDays - 1, DateTimeUnit.DAY)
        val horizonEnd = dateAt(through.plus(1, DateTimeUnit.DAY), LocalTime(0, 0))
        val primary = sources.single { it.account.id == primaryId }
        val calendar = if (primary.account.portal != PortalType.MOODLE) primary else sources.firstOrNull { it.account.id == policy.calendarAccountId && it.account.portal != PortalType.MOODLE }
        val selected = (listOf(primary) + listOfNotNull(calendar)).distinctBy { it.account.id }
        val issues = mutableSetOf<WorkloadIssue>()
        if (!primary.configured) issues += WorkloadIssue.UNCONFIRMED_BUDGET
        if (calendar == null) issues += WorkloadIssue.MISSING_CALENDAR
        if (personalFailed) issues += WorkloadIssue.PERSONAL_BLOCKS
        if (refreshFailed) issues += WorkloadIssue.REFRESH_FAILED
        selected.forEach { source ->
            if (source.sourceFailed || source.snapshot.updatedAt <= 0) issues += WorkloadIssue.SOURCE_UNAVAILABLE
            if (source.storageFailed) issues += WorkloadIssue.LOCAL_STATE
            if (source.snapshot.updatedAt > now || now - source.snapshot.updatedAt > policy.staleHours * 3600L) issues += WorkloadIssue.STALE
        }
        val index = WorkloadSourceIndex()
        val all = selected.flatMap { source ->
            val estimates = source.estimates.associateBy { it.ref }
            index.items(source).map { item -> WorkloadTask(item, estimates[item.ref]) }
        }
        if (primary.snapshot.assignments.any { it.accountId == primaryId && it.due <= 0 && all.none { task -> task.item.ref == WorkloadRef(primaryId, WorkloadKind.ASSIGNMENT, "assignment:${it.id}") } }) issues += WorkloadIssue.UNSCHEDULED
        require(all.size <= 2000)
        val future = all.filter { it.item.due in now until horizonEnd && it.item.officialSubmitted != true }.sortedWith(compareBy({ it.item.due }, { it.item.ref.id }))
        val overdue = all.filter { it.item.due < now && it.item.officialSubmitted != true }.sortedWith(compareBy({ it.item.due }, { it.item.ref.id }))
        if (future.any { it.item.conflict }) issues += WorkloadIssue.CONFLICT
        val known = mutableListOf<Interval>()
        val unknown = mutableListOf<Interval>()
        var assumed = 0
        val buffer = policy.travelBuffer * 60L
        fun block(start: Long, end: Long, uncertain: Boolean) {
            require(start in -62135596800L..253402300799L && end in -62135596800L..253402300799L)
            if (start - buffer >= horizonEnd || end + buffer <= now) return
            val interval = Interval(maxOf(now, start - buffer), minOf(horizonEnd, end + buffer))
            if (interval.start < interval.end) (if (uncertain) unknown else known) += interval
        }
        selected.forEach { source ->
            val estimates = source.estimates.associateBy { it.ref }
            val snapshot = source.snapshot
            val schedule = GetUnifiedScheduleUseCase()(snapshot, today.minus(1, DateTimeUnit.DAY), through)
            val remaining = schedule.filter { it.kind == ScheduleKind.CANCELED }.flatMap { FindFreeTimeUseCase().remainingClassPeriods(snapshot, it) }
            (schedule.filter { it.kind in setOf(ScheduleKind.CLASS, ScheduleKind.MAKE_UP) } + remaining).forEach { block(it.start, it.end, false) }
            index.items(source).filter { it.ref.kind == WorkloadKind.EXAM && it.due < horizonEnd && it.due >= dateAt(today, LocalTime(0, 0)) }.forEach { exam ->
                val duration = estimates[exam.ref]?.examMinutes
                if (duration != null) {
                    block(exam.due, exam.due + duration * 60L, false)
                    assumed++
                } else {
                    block(exam.due, horizonEnd, true)
                }
            }
            if (policy.includeOtherEvents) {
                snapshot.events.filter { it.accountId == source.account.id && !it.deadline }.distinctBy { Triple(it.key, it.start, it.end) }.forEach { event ->
                    if (event.end > event.start) {
                        block(event.start, event.end, false)
                    } else {
                        block(event.start, horizonEnd, true)
                    }
                }
            }
        }
        personal.forEach { block(it.start, it.end, false) }
        if (unknown.isNotEmpty()) issues += WorkloadIssue.UNKNOWN_END
        val capacityDays = (0 until policy.horizonDays).map { offset ->
            val date = today.plus(offset, DateTimeUnit.DAY)
            val rule = policy.days.single { it.isoDay == date.dayOfWeek.isoDayNumber }
            val start = maxOf(now, dateAt(date, LocalTime(rule.startMinute / 60, rule.startMinute % 60)))
            val end = dateAt(date, LocalTime(rule.endMinute / 60, rule.endMinute % 60))
            CapacityDay(start, end, rule.minutes, free(start, end, known + unknown), free(start, end, known))
        }
        fun usable(intervals: List<Interval>, cutoff: Long, budget: Int): Long {
            if (budget == 0) return 0
            val minutes = intervals.map { (minOf(it.end, cutoff) - it.start).coerceAtLeast(0) / 60 }.filter { it >= policy.minimumSession }.sortedDescending()
            // At most floor(B/m) separate sessions fit in a daily budget B with minimum m.
            return minOf(budget.toLong(), minutes.take(budget / policy.minimumSession).sum())
        }
        val missingBusyData = calendar == null || personalFailed || refreshFailed || selected.any { it.sourceFailed || it.snapshot.updatedAt <= 0 }
        val full = capacityDays.map { day -> WorkloadCapacity(if (missingBusyData) 0 else usable(day.low, Long.MAX_VALUE, day.budget), usable(day.high, Long.MAX_VALUE, day.budget)) }
        val lowPrefix = longArrayOf(0) + full.runningFold(0L) { total, c -> total + c.conservative }.drop(1).toLongArray()
        val highPrefix = longArrayOf(0) + full.runningFold(0L) { total, c -> total + c.optimistic }.drop(1).toLongArray()
        fun capacity(cutoff: Long): WorkloadCapacity {
            if (cutoff <= now) return WorkloadCapacity(0, 0)
            if (cutoff >= horizonEnd) return WorkloadCapacity(lowPrefix.last(), highPrefix.last())
            val date = Instant.fromEpochSeconds(cutoff).toLocalDateTime(UitTimeZone).date
            val offset = date.toEpochDays() - today.toEpochDays()
            if (offset !in capacityDays.indices) return WorkloadCapacity(0, 0)
            val day = capacityDays[offset]
            return WorkloadCapacity(lowPrefix[offset] + if (missingBusyData) 0 else usable(day.low, cutoff, day.budget), highPrefix[offset] + usable(day.high, cutoff, day.budget))
        }
        var demand = 0L
        var missing = 0
        val checkpoints = future.groupBy { it.item.due - policy.deadlineLead * 60L }.toSortedMap().map { (cutoff, tasks) ->
            demand += tasks.sumOf { it.estimate?.remainingMinutes?.toLong() ?: 0 }
            missing += tasks.count { it.estimate?.remainingMinutes == null }
            val available = capacity(cutoff)
            val pressure = when {
                demand > available.optimistic -> WorkloadPressure.SHORTFALL
                missing > 0 || issues.isNotEmpty() || demand > available.conservative -> WorkloadPressure.UNCERTAIN
                else -> WorkloadPressure.WITHIN_ENTERED_BUDGET
            }
            WorkloadCheckpoint(cutoff, demand, missing, available, pressure)
        }
        val days = full.mapIndexed { i, c ->
            val date = today.plus(i, DateTimeUnit.DAY)
            WorkloadDay(date, future.filter { Instant.fromEpochSeconds(it.item.due).toLocalDateTime(UitTimeZone).date == date }, c, if (missingBusyData) emptyList() else capacityDays[i].low.map { StudyInterval(it.start, it.end) })
        }
        val currentIds = all.map { it.item.ref.id }.toSet()
        return WorkloadResult(today, through, future, overdue, all.count { it.item.officialSubmitted == true && it.item.due in now until horizonEnd }, checkpoints, days, issues, assumed, selected.flatMap { it.estimates }.filter { it.ref.id !in currentIds }, selected.map { WorkloadSourceStamp(it.account, it.snapshot.updatedAt) })
    }

    private fun free(start: Long, end: Long, occupied: List<Interval>): List<Interval> {
        if (start >= end) return emptyList()
        val ordered = occupied.filter { it.start < end && it.end > start }.sortedWith(compareBy({ it.start }, { it.end }))
        val free = mutableListOf<Interval>()
        var cursor = start
        ordered.forEach { interval ->
            if (interval.start > cursor) free += Interval(cursor, minOf(end, interval.start))
            cursor = maxOf(cursor, interval.end)
        }
        if (cursor < end) free += Interval(cursor, end)
        return free.map { Interval(Math.floorDiv(it.start + 59, 60) * 60, Math.floorDiv(it.end, 60) * 60) }.filter { it.start < it.end }
    }
}
