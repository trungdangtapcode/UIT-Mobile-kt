package vn.edu.uit.mobile.domain

import kotlinx.datetime.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import vn.edu.uit.mobile.common.*
import vn.edu.uit.mobile.model.*

class ScheduleTest {
    private val course = Course(42, "moodle:one", "IT004.O21", "Databases")
    private val slot = DaaTimetableSlot("IT004.O21", "IT004", "Databases", 3, 1, 1, 3, "A101", "Lecturer", "2026-09-07", "2026-12-28")

    @Test fun `unified schedule applies date specific cancellations makeup exams and deadlines`() {
        val day = LocalDate(2026, 10, 26)
        val change = ClassScheduleChangeNotice(1, ScheduleChangeType.CANCELED, "IT004.O21", "Nghỉ", "", "$day", 1, 3)
        val makeup = change.copy(nodeId = 2, type = ScheduleChangeType.MAKE_UP, title = "Bù", targetDate = "2026-10-27", room = "B3.14")
        val exam = DaaExamSlot("IT004.O21", "Databases exam", 2, "2026-10-28", "A204")
        val due = CalendarEvent("account:1", 1, course.accountId, 42, "Assignment", dateAt(day, LocalTime(23, 59)), deadline = true, needsAction = true)
        val snapshot = PortalSnapshot(courses = listOf(course), timetable = listOf(slot), notices = listOf(change, makeup), exams = listOf(exam), events = listOf(due))
        val entries = GetUnifiedScheduleUseCase()(snapshot, day, day.plus(6, DateTimeUnit.DAY))
        assertEquals(setOf(ScheduleKind.CANCELED, ScheduleKind.MAKE_UP, ScheduleKind.EXAM, ScheduleKind.DEADLINE), entries.map { it.kind }.toSet())
        assertTrue(entries.all { it.course == course })
        assertEquals("B3.14", entries.first { it.kind == ScheduleKind.MAKE_UP }.room)
        assertEquals(ScheduleKind.CLASS, GetUnifiedScheduleUseCase()(snapshot, day.plus(7, DateTimeUnit.DAY), day.plus(7, DateTimeUnit.DAY)).single().kind)
    }

    @Test fun `cancellation requires matching section and overlapping periods`() {
        val notice = ClassScheduleChangeNotice(1, ScheduleChangeType.CANCELED, "IT004.O21.1", "Lab off", "", "2026-10-26", 6, 8)
        val entries = GetUnifiedScheduleUseCase()(PortalSnapshot(timetable = listOf(slot), notices = listOf(notice)), LocalDate(2026, 10, 26), LocalDate(2026, 10, 26))
        assertEquals(ScheduleKind.CLASS, entries.single().kind)
    }

    @Test fun `cross linking uses complete tokens and rejects ambiguous accounts`() {
        val linker = CourseCrossLinker()
        assertEquals(course, linker.match("it004.o21", listOf(course)))
        assertNull(linker.match("IT004.O2", listOf(course)))
        assertNull(linker.match("IT004.O21.1", listOf(course)))
        assertNull(linker.match("IT004.O21", listOf(course, course.copy(accountId = "other"))))
    }

    @Test fun `reminder windows deduplicate and prefer last hour on startup`() {
        val now = 1700000000L
        val event = CalendarEvent("account:1", 1, "one", null, "Due", now + 3600, deadline = true, needsAction = true)
        val useCase = CalculateDeadlineRemindersUseCase()
        val reminder = useCase(listOf(event), emptySet(), now).single()
        assertEquals(1, reminder.hours)
        assertTrue(useCase(listOf(event), setOf(reminder.key), now).isEmpty())
        assertEquals(24, useCase(listOf(event.copy(start = now + 86400)), emptySet(), now).single().hours)
        assertTrue(useCase(listOf(event.copy(needsAction = false), event.copy(start = now), event.copy(start = now + 86401)), emptySet(), now).isEmpty())
    }

    @Test fun `timezone boundaries include late night deadlines on their UIT day`() {
        val day = LocalDate(2026, 10, 26)
        val due = dateAt(day, LocalTime(0, 1))
        assertEquals("2026-10-25T17:01:00Z", Instant.fromEpochSeconds(due).toString())
        val event = CalendarEvent("1", 1, "one", null, "Due", due)
        assertEquals(1, GetUnifiedScheduleUseCase()(PortalSnapshot(events = listOf(event)), day, day).size)
    }

    @Test fun `calendar individual overrides take precedence over assignment dates`() {
        val date = LocalDate(2026, 10, 26)
        val due = dateAt(date, LocalTime(23, 0))
        val assignment = Assignment(2, course.id, 3, "Exercise", due = due, accountId = course.accountId)
        val event = CalendarEvent("calendar:2", 2, course.accountId, course.id, "Exercise override", due + 86400, deadline = true, needsAction = true, assignmentId = 2)
        val snapshot = PortalSnapshot(courses = listOf(course), assignments = listOf(assignment), events = listOf(event))
        assertTrue(GetUnifiedScheduleUseCase()(snapshot, date, date).isEmpty())
        assertEquals("Exercise", GetUnifiedScheduleUseCase()(snapshot.copy(events = emptyList()), date, date).single().title)
    }

    @Test fun `GPA projection weights credits and rejects invalid scores`() {
        assertEquals(8.1, ProjectGpaUseCase()(8.0, 27, listOf(3 to 9.0)), 0.0001)
        assertThrows(IllegalArgumentException::class.java) { ProjectGpaUseCase()(8.0, 27, listOf(3 to 11.0)) }
    }
}
