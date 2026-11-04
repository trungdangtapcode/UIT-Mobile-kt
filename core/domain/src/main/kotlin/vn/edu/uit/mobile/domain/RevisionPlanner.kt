package vn.edu.uit.mobile.domain

import java.util.UUID
import kotlinx.datetime.*
import vn.edu.uit.mobile.common.*

enum class RevisionProblem { SOURCE_CHANGED, INPUT_QUALITY, SESSION_CONFLICT, DAILY_BUDGET, SESSION_LIMIT, COMPUTATION_LIMIT, OPTIONS }
data class RevisionUnplaced(val exam: WorkloadRef, val topicId: String, val minutes: Int)
data class RevisionDraft(val workspace: RevisionWorkspace, val unplaced: List<RevisionUnplaced>, val problems: Set<RevisionProblem>)

/** Earliest-deadline/earliest-gap constructive heuristic. Never claims scheduling completeness. */
class RevisionPlanner(private val maximumIntervalChecks: Long = 5_000_000) {
    init {
        require(maximumIntervalChecks in 1..50_000_000)
    }
    private fun date(time: Long) = Instant.fromEpochSeconds(time).toLocalDateTime(UitTimeZone).date
    private fun fixed(s: RevisionSession, now: Long) = s.status == RevisionStatus.COMPLETED || s.status == RevisionStatus.PLANNED && (s.locked || s.start < now && s.end > now)
    private fun current(plan: RevisionPlan, exams: List<WorkloadItem>) = exams.any { it.ref == plan.exam && it.due == plan.due && !it.conflict }
    private fun overlaps(a: RevisionSession, b: RevisionSession, rest: Long) = a.start < b.end + rest && b.start < a.end + rest

    fun inspect(workspace: RevisionWorkspace, projection: WorkloadResult, policy: WorkloadPolicy, exams: List<WorkloadItem>, now: Long): Set<RevisionProblem> {
        val problems = mutableSetOf<RevisionProblem>()
        if (projection.issues.any { it != WorkloadIssue.UNKNOWN_END }) problems += RevisionProblem.INPUT_QUALITY
        if (workspace.options.maximumSession < policy.minimumSession) problems += RevisionProblem.OPTIONS
        val planned = workspace.plans.filter { it.active }.flatMap { p -> p.sessions.filter { it.status == RevisionStatus.PLANNED && (it.end > now || it.locked) }.map { p to it } }
        workspace.plans.filter { it.active }.forEach { if (!current(it, exams)) problems += RevisionProblem.SOURCE_CHANGED }
        planned.forEach { (p, s) ->
            val day = projection.days.firstOrNull { it.date == date(s.start) }
            val valid = s.end > now && date(s.start) == date(s.end - 1) && s.minutes in policy.minimumSession..workspace.options.maximumSession && s.end <= p.due - policy.deadlineLead * 60L && day?.available?.any { it.start <= maxOf(s.start, now) && it.end >= s.end } == true
            if (!valid) problems += RevisionProblem.SESSION_CONFLICT
        }
        val occupied = workspace.plans.flatMap { p -> p.sessions.filter { it.status == RevisionStatus.COMPLETED || p.active && it.status == RevisionStatus.PLANNED && (it.end > now || it.locked) } }
        val rest = workspace.options.breakMinutes * 60L
        occupied.forEachIndexed { i, a -> occupied.drop(i + 1).forEach { b -> if ((a.status == RevisionStatus.PLANNED || b.status == RevisionStatus.PLANNED) && overlaps(a, b, rest)) problems += RevisionProblem.SESSION_CONFLICT } }
        occupied.filter { date(it.start) >= date(now) }.groupBy { date(it.start) }.forEach { (date, sessions) ->
            if (sessions.sumOf { it.minutes.toLong() } > policy.days.single { it.isoDay == date.dayOfWeek.isoDayNumber }.minutes) problems += RevisionProblem.DAILY_BUDGET
        }
        return problems
    }

    fun generate(workspace: RevisionWorkspace, projection: WorkloadResult, policy: WorkloadPolicy, exams: List<WorkloadItem>, now: Long, checkCancellation: () -> Unit = {}): RevisionDraft {
        validateWorkloadPolicy(policy)
        workspace.plans.firstOrNull()?.let { validateRevision(workspace, it.exam.accountId) }
        val kept = workspace.copy(
            plans = workspace.plans.map { plan ->
                if (!plan.active) {
                    plan
                } else {
                    plan.copy(
                        sessions = plan.sessions.mapNotNull { s ->
                            when {
                                fixed(s, now) || s.status == RevisionStatus.MISSED -> s
                                s.end <= now -> s.copy(status = RevisionStatus.MISSED)
                                else -> null
                            }
                        }
                    )
                }
            }
        )
        val blockers = inspect(kept, projection, policy, exams, now)
        if (blockers.isNotEmpty()) return RevisionDraft(workspace, emptyList(), blockers)
        val all = kept.plans.associate { it.exam to it.sessions.toMutableList() }
        val occupied = kept.plans.flatMap { p -> p.sessions.filter { it.status == RevisionStatus.COMPLETED || p.active && it.status == RevisionStatus.PLANNED } }.toMutableList()
        val used = occupied.groupBy { date(it.start) }.mapValues { (_, items) -> items.sumOf { it.minutes } }.toMutableMap()
        val unplaced = mutableListOf<RevisionUnplaced>()
        val problems = mutableSetOf<RevisionProblem>()
        val breakSeconds = workspace.options.breakMinutes * 60L
        var totalSessions = kept.plans.sumOf { it.sessions.size }
        var intervalChecks = 0L
        kept.plans.filter { it.active }.sortedWith(compareBy({ it.due }, { it.exam.id })).forEach { plan ->
            val sessions = all.getValue(plan.exam)
            val cutoff = plan.due - policy.deadlineLead * 60L
            plan.topics.forEach { topic ->
                checkCancellation()
                var remaining = topic.minutes - sessions.filter { it.topicId == topic.id && it.status != RevisionStatus.MISSED }.sumOf { it.effort }
                for (day in projection.days) {
                    if (remaining <= 0 || RevisionProblem.COMPUTATION_LIMIT in problems) break
                    val budget = policy.days.single { it.isoDay == day.date.dayOfWeek.isoDayNumber }.minutes
                    for (window in day.available) {
                        var cursor = maxOf(window.start, Math.floorDiv(now + 59, 60) * 60 + workspace.options.startDelay * 60L)
                        val end = minOf(window.end, cutoff)
                        while (remaining > 0 && cursor + policy.minimumSession * 60L <= end && budget - (used[day.date] ?: 0) >= policy.minimumSession) {
                            checkCancellation()
                            intervalChecks += occupied.size + 1
                            if (intervalChecks > maximumIntervalChecks) {
                                problems += RevisionProblem.COMPUTATION_LIMIT
                                break
                            }
                            if (totalSessions >= 2048) {
                                problems += RevisionProblem.SESSION_LIMIT
                                break
                            }
                            val next = occupied.filter { it.end + breakSeconds > cursor && it.start - breakSeconds < end }.minByOrNull { it.start }
                            if (next != null && next.start - breakSeconds <= cursor) {
                                cursor = next.end + breakSeconds
                                continue
                            }
                            val stop = minOf(end, next?.let { it.start - breakSeconds } ?: end)
                            val room = minOf(((stop - cursor) / 60).toInt(), budget - (used[day.date] ?: 0), workspace.options.maximumSession)
                            if (room < policy.minimumSession) {
                                cursor = next?.let { it.end + breakSeconds } ?: end
                                continue
                            }
                            var duration = minOf(room, maxOf(policy.minimumSession, remaining))
                            val budgetLeft = budget - (used[day.date] ?: 0)
                            // Avoid stranding a subminimum daily remainder when two sessions fit.
                            if (remaining > duration && budgetLeft - duration in 1 until policy.minimumSession && budgetLeft >= 2 * policy.minimumSession) duration = budgetLeft - policy.minimumSession
                            val credit = minOf(remaining, duration)
                            val id = UUID.nameUUIDFromBytes("${plan.exam.id}\u0000${topic.id}\u0000$cursor".toByteArray(Charsets.UTF_8)).toString()
                            val s = RevisionSession(id, topic.id, cursor, cursor + duration * 60L, credit)
                            sessions += s
                            occupied += s
                            used[day.date] = (used[day.date] ?: 0) + duration
                            remaining -= credit
                            totalSessions++
                            cursor = s.end + breakSeconds
                        }
                        if (RevisionProblem.COMPUTATION_LIMIT in problems) break
                    }
                }
                if (remaining > 0) unplaced += RevisionUnplaced(plan.exam, topic.id, remaining)
            }
        }
        val value = kept.copy(plans = kept.plans.map { it.copy(sessions = all.getValue(it.exam).toList()) })
        val validation = inspect(value, projection, policy, exams, now)
        check(validation.isEmpty()) { "Constructed revision sessions failed validation" }
        value.plans.firstOrNull()?.let { validateRevision(value, it.exam.accountId) }
        return RevisionDraft(value, unplaced, problems)
    }
}
