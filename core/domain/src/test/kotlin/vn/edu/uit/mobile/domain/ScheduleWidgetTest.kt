package vn.edu.uit.mobile.domain

import kotlinx.datetime.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import vn.edu.uit.mobile.common.*
import vn.edu.uit.mobile.model.*

class ScheduleWidgetTest {
    private val day = LocalDate(2026, 10, 26)
    private val course = Course(1, "test-account", "IT004.O21", "Database systems")
    private val slot = DaaTimetableSlot("IT004.O21", "IT004", course.fullname, 4, 1, 1, 3, "B3.14", "Test Lecturer", "2026-09-07", "2026-12-28")
    private fun at(time: String) = dateAt(day, LocalTime.parse(time))
    private fun snapshot() = PortalSnapshot(courses = listOf(course), timetable = listOf(slot), updatedAt = at("07:00"))
    private val build = BuildScheduleWidgetUseCase()

    @Test fun `current class remains visible until its end then advances`() {
        val data = snapshot().copy(timetable = listOf(slot, slot.copy(classCode = "SE104.O22", courseName = "Software design", lessonStart = 6, lessonEnd = 8)))
        val current = build(data, true, at("08:00"))
        assertEquals("IT004.O21", current.next?.classCode)
        assertEquals(at("09:45") + 1, current.nextUpdateAt)
        val later = build(data, true, at("09:45"))
        assertEquals("SE104.O22", later.next?.classCode)
        assertEquals(1, later.today.size)
    }

    @Test fun `canceled classes do not become the next meeting`() {
        val data = snapshot().copy(notices = listOf(ClassScheduleChangeNotice(1, ScheduleChangeType.CANCELED, slot.classCode, "Canceled", "", day.toString(), 1, 3)))
        val result = build(data, true, at("07:00"))
        assertTrue(result.today.isEmpty())
        assertNotNull(result.next)
        assertEquals(day.plus(7, DateTimeUnit.DAY), Instant.fromEpochSeconds(result.next!!.start).toLocalDateTime(UitTimeZone).date)
    }

    @Test fun `fortnightly schedules still find the next meeting after the current class ends`() {
        val result = build(snapshot().copy(timetable = listOf(slot.copy(weekInterval = 2, startDate = day.toString()))), true, at("10:00"))
        assertEquals(day.plus(14, DateTimeUnit.DAY), Instant.fromEpochSeconds(result.next!!.start).toLocalDateTime(UitTimeZone).date)
    }

    @Test fun `submitted deadlines stay hidden even when calendar actionability is inconsistent`() {
        val event = CalendarEvent("submitted", 1, course.accountId, course.id, "Already submitted", at("23:59"), deadline = true, needsAction = true, assignmentId = 10, submitted = true)
        val assignment = Assignment(10, course.id, 20, "Already submitted", due = event.start, accountId = course.accountId)
        assertNull(build(snapshot().copy(events = listOf(event), assignments = listOf(assignment)), true, at("07:00")).deadline)
    }

    @Test fun `assignment-only deadline is visible without claiming submission status`() {
        val assignment = Assignment(10, course.id, 20, "Coursework", due = at("23:59"), accountId = course.accountId)
        val result = build(snapshot().copy(assignments = listOf(assignment)), true, at("07:00"))
        assertEquals("Coursework", result.deadline?.title)
        assertFalse(result.deadline!!.needsAction)
        assertEquals(course, result.deadline!!.course)
    }

    @Test fun `upcoming deadline is preferred to overdue work and can be beyond the meeting horizon`() {
        val overdue = CalendarEvent("old", 1, course.accountId, course.id, "Overdue", at("06:00"), deadline = true, needsAction = true)
        val future = overdue.copy(key = "future", id = 2, name = "Future coursework", start = dateAt(day.plus(21, DateTimeUnit.DAY), LocalTime(23, 59)))
        assertEquals("future", build(snapshot().copy(events = listOf(overdue, future)), true, at("07:00")).deadline?.key)
        assertEquals("old", build(snapshot().copy(events = listOf(overdue)), true, at("07:00")).deadline?.key)
    }

    @Test fun `disconnected or never-synced widgets cannot expose cached course records`() {
        val disconnected = build(snapshot(), false, at("07:00"))
        assertFalse(disconnected.connected)
        assertNull(disconnected.next)
        assertEquals(0L, disconnected.updatedAt)
        assertNull(build(snapshot().copy(updatedAt = 0), true, at("07:00")).next)
    }

    @Test fun `day rollover and exam times always use UIT time`() {
        val tomorrow = day.plus(1, DateTimeUnit.DAY)
        val exam = DaaExamSlot(slot.classCode, "Database exam", 1, tomorrow.toString(), "A2.03", startTime = "13:30")
        val result = build(snapshot().copy(timetable = emptyList(), exams = listOf(exam)), true, at("23:59"))
        assertEquals(day, result.date)
        assertEquals(dateAt(tomorrow, LocalTime(0, 0)) + 1, result.nextUpdateAt)
        assertEquals(dateAt(tomorrow, LocalTime(13, 30)), result.next?.start)
        assertEquals(ScheduleKind.EXAM, result.next?.kind)
    }
}
