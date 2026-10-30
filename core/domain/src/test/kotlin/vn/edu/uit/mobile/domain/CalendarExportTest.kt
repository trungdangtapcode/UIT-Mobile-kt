package vn.edu.uit.mobile.domain

import kotlinx.datetime.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import vn.edu.uit.mobile.common.*
import vn.edu.uit.mobile.model.*

class CalendarExportTest {
    private val export = CalendarFileExport()
    private val day = LocalDate(2026, 10, 26)
    private val stamp = dateAt(day, LocalTime(12, 0))
    private val slot = DaaTimetableSlot("IT004.O21", "IT004", "Database Systems", 3, 1, 1, 3, "A101", "Synthetic lecturer", "2026-10-12", "2026-12-28", 2)
    private val options = CalendarExportOptions(day, day.plus(20, DateTimeUnit.DAY))
    private fun uid(file: String) = file.lineSequence().first { it.startsWith("UID:") }
    private fun logical(file: String) = file.replace("\r\n ", "")

    @Test fun `export expands fortnightly meetings and keeps canceled and makeup instances explicit`() {
        val canceled = ClassScheduleChangeNotice(1, ScheduleChangeType.CANCELED, slot.classCode, "Canceled", "", "$day", 1, 3)
        val makeup = canceled.copy(nodeId = 2, type = ScheduleChangeType.MAKE_UP, targetDate = "2026-10-27", room = "B314")
        val snapshot = PortalSnapshot(timetable = listOf(slot), notices = listOf(canceled, makeup))
        val plan = export.plan(snapshot, options)
        assertEquals(listOf(ScheduleKind.CANCELED, ScheduleKind.MAKE_UP, ScheduleKind.CLASS), plan.entries.map { it.kind })
        val file = export.render(plan, stamp)
        assertEquals(3, Regex("BEGIN:VEVENT").findAll(file).count())
        assertTrue(file.contains("STATUS:CANCELLED\r\n"))
        assertTrue(file.contains("TRANSP:TRANSPARENT\r\n"))
        assertTrue(file.contains("LOCATION:B314\r\n"))
        assertFalse(file.contains("RRULE"))
        assertFalse(file.contains("METHOD:"))
        val fixture = java.io.File("build/test-artifacts/calendar-export.ics")
        fixture.parentFile.mkdirs()
        fixture.writeText(file, Charsets.UTF_8)
    }

    @Test fun `UIT midnight and unknown event duration export exact UTC points without invented ends`() {
        val event = CalendarEvent("account:1", 1, "synthetic", null, "Due", dateAt(day, LocalTime(0, 1)), deadline = true)
        val snapshot = PortalSnapshot(events = listOf(event))
        val plan = export.plan(snapshot, CalendarExportOptions(day, day))
        val file = export.render(plan, stamp)
        assertTrue(file.contains("DTSTART:20261025T170100Z\r\n"))
        assertFalse(file.contains("DTEND:"))
        assertTrue(logical(file).contains("End time not provided."))
        assertEquals(0, export.plan(snapshot, CalendarExportOptions(day.minus(1, DateTimeUnit.DAY), day.minus(1, DateTimeUnit.DAY))).entries.size)
    }

    @Test fun `UIDs survive title changes cancellation and overlapping export ranges`() {
        val snapshot = PortalSnapshot(timetable = listOf(slot))
        val first = export.render(export.plan(snapshot, CalendarExportOptions(day, day)), stamp)
        val cancel = ClassScheduleChangeNotice(1, ScheduleChangeType.CANCELED, slot.classCode, "Canceled", "", "$day", 1, 3)
        val changed = snapshot.copy(timetable = listOf(slot.copy(courseName = "Updated title")), notices = listOf(cancel))
        val second = export.render(export.plan(changed, options), stamp + 1)
        assertEquals(uid(first), uid(second))
        assertFalse(uid(first).contains(slot.classCode))
        val next = export.render(export.plan(snapshot, CalendarExportOptions(day.plus(14, DateTimeUnit.DAY), day.plus(14, DateTimeUnit.DAY))), stamp)
        assertNotEquals(uid(first), uid(next))
    }

    @Test fun `UTF8 folding escaping and CRLF prevent extra properties in source titles`() {
        val title = "Lập trình 🌸; SQL, \\ ".repeat(12) + "\r\nATTENDEE:mailto:synthetic@example.org"
        val event = CalendarEvent("synthetic:1", 1, "synthetic", null, title, stamp, stamp + 900)
        val file = export.render(export.plan(PortalSnapshot(events = listOf(event)), CalendarExportOptions(day, day)), stamp)
        assertTrue(file.endsWith("END:VCALENDAR\r\n"))
        assertFalse(file.replace("\r\n", "").contains('\n'))
        assertTrue(file.split("\r\n").all { it.toByteArray(Charsets.UTF_8).size <= 75 })
        assertFalse(file.contains('\uFFFD'))
        assertFalse(file.contains("\r\nATTENDEE:"))
        assertTrue(logical(file).contains("\\; SQL\\, \\\\"))
        assertTrue(logical(file).contains("\\nATTENDEE:"))
    }

    @Test fun `type selection includes only requested saved events and hides account identifiers`() {
        val exam = DaaExamSlot(slot.classCode, "Exam", 1, "$day", "A204")
        val due = CalendarEvent("account-private:1", 1, "account-private", null, "Deadline", stamp, deadline = true)
        val snapshot = PortalSnapshot(timetable = listOf(slot), exams = listOf(exam), events = listOf(due, due.copy(key = "other:1", deadline = false)))
        val plan = export.plan(snapshot, CalendarExportOptions(day, day, setOf(CalendarExportType.EXAMS, CalendarExportType.DEADLINES)))
        assertEquals(setOf(ScheduleKind.EXAM, ScheduleKind.DEADLINE), plan.entries.map { it.kind }.toSet())
        assertFalse(export.render(plan, stamp).contains("account-private"))
    }

    @Test fun `only valid bounded ranges with selected types and nonempty results can be written`() {
        assertThrows(IllegalArgumentException::class.java) { export.plan(PortalSnapshot(), options.copy(through = day.minus(1, DateTimeUnit.DAY))) }
        assertThrows(IllegalArgumentException::class.java) { export.plan(PortalSnapshot(), options.copy(through = day.plus(90, DateTimeUnit.DAY))) }
        assertThrows(IllegalArgumentException::class.java) { export.plan(PortalSnapshot(), options.copy(types = emptySet())) }
        val empty = export.plan(PortalSnapshot(), options.copy(through = day.plus(89, DateTimeUnit.DAY)))
        assertThrows(IllegalArgumentException::class.java) { export.render(empty, stamp) }
    }
}
