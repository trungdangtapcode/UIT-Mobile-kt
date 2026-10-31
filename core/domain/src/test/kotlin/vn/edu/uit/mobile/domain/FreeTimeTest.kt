package vn.edu.uit.mobile.domain

import kotlinx.datetime.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import vn.edu.uit.mobile.common.*
import vn.edu.uit.mobile.model.*

class FreeTimeTest {
    private val day = LocalDate(2026, 10, 26)
    private val options = FreeTimeOptions(day, LocalTime(8, 0), LocalTime(18, 0), 30, 15)
    private val find = FindFreeTimeUseCase()
    private fun at(time: String, date: LocalDate = day) = dateAt(date, LocalTime.parse(time))
    private fun event(key: String, start: String, end: String) = CalendarEvent(key, 1, "synthetic", null, "Synthetic meeting", at(start), at(end))
    private fun times(result: FreeTimeResult) = result.slots.map { clock(it.start) to clock(it.end) }
    private fun clock(seconds: Long) = Instant.fromEpochSeconds(seconds).toLocalDateTime(UitTimeZone).time.toString()
    private val slot = DaaTimetableSlot("IT004.O21", "IT004", "Database Systems", 3, 1, 1, 3, "A101", "Synthetic lecturer", "2026-10-12", "2026-12-28", 2)

    @Test fun `overlaps nested events and touching buffers are merged before minimum filtering`() {
        val events = listOf(event("a", "09:00", "11:00"), event("b", "10:00", "10:30"), event("c", "11:30", "12:00"))
        assertEquals(listOf("08:00" to "08:45", "12:15" to "18:00"), times(find(PortalSnapshot(events = events), options)))
        assertEquals(listOf("08:00" to "09:00", "11:00" to "11:30", "12:00" to "18:00"), times(find(PortalSnapshot(events = events), options.copy(bufferMinutes = 0))))
    }

    @Test fun `cancellations free their occurrence and fortnightly makeup meetings still block`() {
        val cancel = ClassScheduleChangeNotice(1, ScheduleChangeType.CANCELED, slot.classCode, "Canceled", "", "$day", 1, 3)
        val makeup = cancel.copy(nodeId = 2, type = ScheduleChangeType.MAKE_UP, lessonStart = 6, lessonEnd = 8, room = "B314")
        val snapshot = PortalSnapshot(timetable = listOf(slot), notices = listOf(cancel, makeup))
        val result = find(snapshot, options)
        assertEquals(1, result.canceledCount)
        assertEquals(listOf("08:00" to "12:45", "15:30" to "18:00"), times(result))
        val skipped = day.plus(7, DateTimeUnit.DAY)
        assertEquals(listOf("08:00" to "18:00"), times(find(snapshot, options.copy(date = skipped))))
    }

    @Test fun `partial cancellation preserves the periods that still meet`() {
        val longer = slot.copy(lessonEnd = 5)
        val cancel = ClassScheduleChangeNotice(1, ScheduleChangeType.CANCELED, slot.classCode, "Partly canceled", "", "$day", 4, 5)
        val snapshot = PortalSnapshot(timetable = listOf(longer), notices = listOf(cancel))
        val result = find(snapshot, options)
        assertEquals(listOf("10:00" to "18:00"), times(result))
        assertEquals(0, result.canceledCount)
        assertEquals(at("09:45"), result.busy.single().end)
        val middle = snapshot.copy(notices = listOf(cancel.copy(lessonStart = 3, lessonEnd = 3)))
        assertEquals(listOf("09:15" to "09:45", "11:45" to "18:00"), times(find(middle, options)))
    }

    @Test fun `unknown exam end blocks the rest of its UIT day without inventing a duration`() {
        val exam = DaaExamSlot(slot.classCode, "Database exam", 2, "$day", "A204")
        val result = find(PortalSnapshot(exams = listOf(exam)), options)
        assertEquals(listOf("08:00" to "09:15"), times(result))
        assertTrue(result.busy.single().unknownEnd)
        assertEquals(at("18:00"), result.busy.single().bufferedEnd)
    }

    @Test fun `events beginning several days earlier and next-day buffers intersect the window`() {
        val spanning = event("multi-day", "08:00", "09:00").copy(start = at("20:00", day.minus(4, DateTimeUnit.DAY)))
        assertEquals(listOf("09:15" to "18:00"), times(find(PortalSnapshot(events = listOf(spanning)), options)))
        val midnight = event("next-day", "00:05", "01:00").copy(start = at("00:05", day.plus(1, DateTimeUnit.DAY)), end = at("01:00", day.plus(1, DateTimeUnit.DAY)))
        val evening = options.copy(start = LocalTime(22, 0), end = LocalTime(23, 59), minimumMinutes = 10)
        assertEquals(listOf("22:00" to "23:50"), times(find(PortalSnapshot(events = listOf(midnight)), evening)))
    }

    @Test fun `UIT boundaries and seconds are rounded inward to avoid offering occupied time`() {
        val event = event("seconds", "09:00", "10:00").copy(end = at("10:00") + 1)
        val result = find(PortalSnapshot(events = listOf(event)), options.copy(bufferMinutes = 0))
        assertEquals(listOf("08:00" to "09:00", "10:01" to "18:00"), times(result))
        assertEquals("2026-10-26T01:00:00Z", Instant.fromEpochSeconds(result.slots.first().start).toString())
    }

    @Test fun `deadlines remain reminders and personal blocks reserve time only locally`() {
        val due = event("due", "07:00", "18:00").copy(deadline = true)
        val block = PersonalTimeBlock("one", "Lunch and travel", at("12:00"), at("13:00"))
        val result = find(PortalSnapshot(events = listOf(due)), options, listOf(block))
        assertEquals(listOf("08:00" to "11:45", "13:15" to "18:00"), times(result))
        assertEquals(1, result.deadlines.size)
        assertEquals("PERSONAL", result.busy.single().kind)
    }

    @Test fun `unknown event ends stop at its own UIT midnight and buffers can reach the next day`() {
        val prior = event("prior", "23:50", "23:50").copy(start = at("23:50", day.minus(1, DateTimeUnit.DAY)), end = 0)
        val early = options.copy(start = LocalTime(0, 0), end = LocalTime(2, 0))
        assertEquals(listOf("00:15" to "02:00"), times(find(PortalSnapshot(events = listOf(prior)), early)))
    }

    @Test fun `duplicate cached calendar keys do not produce duplicate commitments`() {
        val event = event("one", "09:00", "10:00")
        assertEquals(1, find(PortalSnapshot(events = listOf(event, event)), options).busy.size)
    }

    @Test fun `cached versions with different ends reserve their combined occupied interval`() {
        val earlier = event("same", "09:00", "10:00")
        val later = earlier.copy(end = at("11:00"))
        val result = find(PortalSnapshot(events = listOf(earlier, later)), options)
        assertEquals(2, result.busy.size)
        assertEquals(listOf("08:00" to "08:45", "11:15" to "18:00"), times(result))
    }

    @Test fun `empty days and fully occupied windows return distinct results`() {
        assertEquals(listOf("08:00" to "18:00"), times(find(PortalSnapshot(), options)))
        assertTrue(find(PortalSnapshot(events = listOf(event("whole", "07:00", "19:00"))), options).slots.isEmpty())
    }

    @Test fun `invalid ranges buffers personal blocks and timestamps fail explicitly`() {
        for (invalid in listOf(options.copy(end = options.start), options.copy(bufferMinutes = -1), options.copy(bufferMinutes = 121), options.copy(minimumMinutes = 0), options.copy(minimumMinutes = 721))) {
            assertThrows(IllegalArgumentException::class.java) { find(PortalSnapshot(), invalid) }
        }
        assertThrows(IllegalArgumentException::class.java) { find(PortalSnapshot(), options, listOf(PersonalTimeBlock("one", "Invalid", at("13:00"), at("12:00")))) }
        assertThrows(IllegalArgumentException::class.java) { find(PortalSnapshot(events = listOf(event("bad", "09:00", "10:00").copy(start = Long.MAX_VALUE))), options) }
    }
}
