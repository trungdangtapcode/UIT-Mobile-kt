package vn.edu.uit.mobile.domain

import java.security.MessageDigest
import vn.edu.uit.mobile.model.*

data class AssignmentReminderPolicy(val lookAheadSeconds: Long = 7 * 86400L, val intervalSeconds: Long = 8 * 3600L) {
    init {
        require(lookAheadSeconds in 1..31 * 86400L)
        require(intervalSeconds in 900..7 * 86400L)
    }
    fun ready(lastAttempt: Long?, now: Long): Boolean = lastAttempt == null || now >= lastAttempt && now - lastAttempt >= intervalSeconds
    fun pending(status: SubmissionStatus): Boolean = status.requiresSubmission ?: (status.status in setOf("new", "draft", "reopened"))
    fun candidates(course: Course, assignments: List<Assignment>, calendar: List<CalendarEvent>, now: Long): List<AssignmentReminderItem> {
        if (course.hidden || course.classification in setOf("past", "hidden", "future")) return emptyList()
        return assignments.filter { it.accountId == course.accountId && it.courseId == course.id && it.id > 0 && it.cmid > 0 }.distinctBy { it.id }.mapNotNull { assignment ->
            val overrides = calendar.filter { it.accountId == course.accountId && it.courseId == course.id && it.assignmentId == assignment.id && it.deadline && it.eventType == "due" }.map { it.start }.distinct()
            // A close event is not a due-date override; conflicting due records are unresolved.
            if (overrides.size > 1) return@mapNotNull null
            val due = overrides.singleOrNull() ?: assignment.due
            if (due <= now || due - now > lookAheadSeconds || assignment.opens > now || overrides.isEmpty() && assignment.cutoff in 1..now) return@mapNotNull null
            AssignmentReminderItem(course, assignment, due)
        }.sortedWith(compareBy({ it.due }, { it.assignment.id }))
    }
}

enum class AssignmentReminderBasis { SUBMISSION_STATUS, CALENDAR_ACTION }
data class AssignmentReminderItem(val course: Course, val assignment: Assignment, val due: Long, val basis: AssignmentReminderBasis = AssignmentReminderBasis.SUBMISSION_STATUS)

/** One opaque account-bound checkpoint; no assignment titles or university credentials. */
fun assignmentReminderKey(accountId: String): String = "assignment-reminder:" + MessageDigest.getInstance("SHA-256").digest(accountId.toByteArray()).joinToString("") { "%02x".format(it) }
