package vn.edu.uit.mobile.domain

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import vn.edu.uit.mobile.model.*

class AssignmentReminderPolicyTest {
    private val now = 1800000000L
    private val policy = AssignmentReminderPolicy()
    private val course = Course(4, "test-account", "IT001", "Programming")
    private val assignment = Assignment(12, 4, 120, "Fixture exercise", due = now + 86400, accountId = course.accountId)
    private fun candidates(items: List<Assignment>, events: List<CalendarEvent> = emptyList()) = policy.candidates(course, items, events, now)

    @Test fun `seven day boundary is inclusive but expired and missing due dates are excluded`() {
        val found = candidates(listOf(assignment.copy(due = now + 7 * 86400), assignment.copy(id = 13, due = now + 7 * 86400 + 1), assignment.copy(id = 14, due = now), assignment.copy(id = 15, due = 0)))
        assertEquals(listOf(12L), found.map { it.assignment.id })
    }

    @Test fun `eight hours is a rolling minimum including after clock rollback`() {
        assertTrue(policy.ready(null, now))
        assertFalse(policy.ready(now, now + 8 * 3600 - 1))
        assertTrue(policy.ready(now, now + 8 * 3600))
        assertFalse(policy.ready(now, now - 1))
    }

    @Test fun `only explicit actionable submission states qualify`() {
        listOf("new", "draft", "reopened").forEach { assertTrue(policy.pending(SubmissionStatus(it))) }
        listOf("submitted", "", "unknown", "notavailable").forEach { assertFalse(policy.pending(SubmissionStatus(it))) }
        assertTrue(policy.pending(SubmissionStatus("", requiresSubmission = true)))
        assertFalse(policy.pending(SubmissionStatus("new", requiresSubmission = false)))
    }

    @Test fun `individual due overrides win but close events and other accounts do not`() {
        val event = CalendarEvent("due", 1, course.accountId, course.id, "Fixture", now + 3 * 86400, deadline = true, assignmentId = assignment.id, eventType = "due")
        val close = event.copy(key = "close", start = now + 10 * 86400, eventType = "close")
        assertEquals(event.start, candidates(listOf(assignment), listOf(event, close)).single().due)
        assertEquals(event.start, candidates(listOf(assignment.copy(cutoff = now - 1)), listOf(event)).single().due)
        assertEquals(assignment.due, candidates(listOf(assignment), listOf(close, event.copy(accountId = "other"))).single().due)
        assertTrue(candidates(listOf(assignment), listOf(event.copy(start = now - 1))).isEmpty())
        assertTrue(candidates(listOf(assignment), listOf(event, event.copy(key = "conflict", start = event.start + 1))).isEmpty())
    }

    @Test fun `hidden courses future opening invalid ownership and duplicates are handled`() {
        assertTrue(policy.candidates(course.copy(hidden = true), listOf(assignment), emptyList(), now).isEmpty())
        assertTrue(policy.candidates(course.copy(classification = "past"), listOf(assignment), emptyList(), now).isEmpty())
        assertTrue(candidates(listOf(assignment.copy(opens = now + 1), assignment.copy(id = 99, cutoff = now), assignment.copy(accountId = "other"), assignment.copy(courseId = 999), assignment.copy(cmid = 0))).isEmpty())
        assertEquals(1, candidates(listOf(assignment, assignment)).size)
    }

    @Test fun `policy configuration is validated and account checkpoint identities are opaque`() {
        assertEquals(2 * 86400L, AssignmentReminderPolicy(lookAheadSeconds = 2 * 86400L).lookAheadSeconds)
        assertThrows(IllegalArgumentException::class.java) { AssignmentReminderPolicy(intervalSeconds = 60) }
        assertThrows(IllegalArgumentException::class.java) { AssignmentReminderPolicy(lookAheadSeconds = 0) }
        assertFalse(assignmentReminderKey(course.accountId).contains(course.accountId))
        assertNotEquals(assignmentReminderKey(course.accountId), assignmentReminderKey("other"))
    }
}
