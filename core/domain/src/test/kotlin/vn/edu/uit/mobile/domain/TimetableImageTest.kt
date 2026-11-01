package vn.edu.uit.mobile.domain

import kotlinx.datetime.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import vn.edu.uit.mobile.common.*
import vn.edu.uit.mobile.model.*

class TimetableImageTest {
    private val day = LocalDate(2026, 10, 26)
    private val slot = DaaTimetableSlot("PRIVATE-CLASS", "IT004", "Private course", 3, 1, 1, 3, "PRIVATE-ROOM", "PRIVATE-LECTURER", "$day", "2026-12-28")
    private val snapshot = PortalSnapshot(timetable = listOf(slot), profile = StudentProfile("PRIVATE-ID", "PRIVATE-NAME", avatarUrl = "https://private.invalid/avatar"), updatedAt = dateAt(day, LocalTime(6, 0)))

    @Test fun privacyProjectionRemovesHiddenFieldsBeforeRendering() {
        val plan = TimetableImageExport().plan(snapshot, TimetableImageOptions(day, day, courseNames = false))
        assertEquals(1, plan.count)
        assertFalse(plan.toString().contains("PRIVATE"))
        assertEquals("", plan.days.single().rows.single().title)
        assertEquals("", plan.identity)
        val full = TimetableImageExport().plan(snapshot, TimetableImageOptions(day, day, rooms = true, identity = true))
        assertEquals("PRIVATE-NAME · PRIVATE-ID", full.identity)
        assertEquals("PRIVATE-ROOM", full.days.single().rows.single().room)
        assertEquals("Private course", full.days.single().rows.single().title)
        assertFalse(full.toString().contains("PRIVATE-LECTURER"))
        assertFalse(full.toString().contains("avatar"))
    }

    @Test fun sharedRecurrenceCancellationAndMakeupRulesArePreservedWithoutExamsOrDeadlines() {
        val canceled = ClassScheduleChangeNotice(1, ScheduleChangeType.CANCELED, slot.classCode, "Canceled", "", "$day", 1, 3)
        val next = day.plus(1, DateTimeUnit.DAY)
        val makeup = ClassScheduleChangeNotice(2, ScheduleChangeType.MAKE_UP, slot.classCode, "Make-up lesson with private reason secret", "B202", "$next", 4, 5)
        val source = snapshot.copy(notices = listOf(canceled, makeup), exams = listOf(DaaExamSlot("exam", "Exam secret", 1, "$day", "Exam room")), events = listOf(CalendarEvent("deadline", 1, "private", null, "Deadline secret", dateAt(day, LocalTime(23, 0)), deadline = true)))
        val plan = TimetableImageExport().plan(source, TimetableImageOptions(day, day.plus(6, DateTimeUnit.DAY)))
        assertEquals(7, plan.days.size)
        assertEquals(listOf(ScheduleKind.CANCELED, ScheduleKind.MAKE_UP), plan.days.flatMap { it.rows }.map { it.kind })
        assertTrue(plan.days.last().rows.isEmpty())
        assertEquals(dateAt(day, LocalTime(7, 30)), plan.days.first().rows.single().start)
        assertFalse(plan.toString().contains("secret"))
        val alternate = snapshot.copy(timetable = listOf(slot.copy(weekInterval = 2)))
        assertEquals(0, TimetableImageExport().plan(alternate, TimetableImageOptions(day.plus(7, DateTimeUnit.DAY), day.plus(7, DateTimeUnit.DAY))).count)
    }

    @Test fun emptySavedDaysAreExplicitAndRangesAreBounded() {
        val empty = TimetableImageExport().plan(snapshot.copy(timetable = emptyList()), TimetableImageOptions(day, day))
        assertEquals(1, empty.days.size)
        assertEquals(0, empty.count)
        assertThrows(IllegalArgumentException::class.java) { TimetableImageExport().plan(snapshot, TimetableImageOptions(day, day.minus(1, DateTimeUnit.DAY))) }
        assertThrows(IllegalArgumentException::class.java) { TimetableImageExport().plan(snapshot, TimetableImageOptions(day, day.plus(7, DateTimeUnit.DAY))) }
        assertThrows(IllegalArgumentException::class.java) { TimetableImageExport().plan(PortalSnapshot(), TimetableImageOptions(day, day)) }
        assertThrows(IllegalArgumentException::class.java) { TimetableImageExport().plan(snapshot.copy(timetable = (1..61).map { slot.copy(classCode = "CLASS$it") }), TimetableImageOptions(day, day)) }
    }

    @Test fun optInTextIsBoundedAndControlCharactersAreRemoved() {
        val source = snapshot.copy(timetable = listOf(slot.copy(courseName = "A".repeat(1000), room = "A\n101\u0000")))
        val row = TimetableImageExport().plan(source, TimetableImageOptions(day, day, rooms = true)).days.single().rows.single()
        assertEquals(300, row.title.length)
        assertTrue(row.title.endsWith("…"))
        assertEquals("A 101", row.room)
    }
}
