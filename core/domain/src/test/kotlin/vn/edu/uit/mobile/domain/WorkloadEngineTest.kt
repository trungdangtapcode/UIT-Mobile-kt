package vn.edu.uit.mobile.domain

import kotlin.random.Random
import kotlinx.datetime.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import vn.edu.uit.mobile.common.*
import vn.edu.uit.mobile.model.*

class WorkloadEngineTest {
    private val day = LocalDate(2026, 10, 26)
    private val now = at(8)
    private val moodle = Account("moodle", "https://courses.uit.edu.vn", 1, "Study", PortalType.MOODLE)
    private val portal = Account("portal", "https://portal.uit.edu.vn", 1, "Teaching", PortalType.UIT_PORTAL)
    private val policy = WorkloadPolicy(days = (1..7).map { StudyDayBudget(it, 8 * 60, 18 * 60, 600) }, travelBuffer = 0, deadlineLead = 0, calendarAccountId = portal.id)
    private fun at(hour: Int, minute: Int = 0, date: LocalDate = day) = dateAt(date, LocalTime(hour, minute))
    private fun assignment(id: Long, due: Long) = Assignment(id, 1, id + 100, "Assignment $id", "", due, accountId = moodle.id)
    private fun sources(assignments: List<Assignment> = listOf(assignment(1, at(12))), events: List<CalendarEvent> = emptyList(), exams: List<DaaExamSlot> = emptyList(), timetable: List<DaaTimetableSlot> = emptyList(), estimates: List<WorkloadEstimate> = emptyList(), notices: List<ClassScheduleChangeNotice> = emptyList()) = listOf(
        WorkloadAccount(moodle, PortalSnapshot(assignments = assignments, events = events, updatedAt = now), policy, estimates.filter { it.ref.accountId == moodle.id }, configured = true),
        WorkloadAccount(portal, PortalSnapshot(exams = exams, timetable = timetable, notices = notices, updatedAt = now), estimates = estimates.filter { it.ref.accountId == portal.id }, configured = true)
    )
    private fun estimate(id: Long, minutes: Int?) = WorkloadEstimate(WorkloadRef(moodle.id, WorkloadKind.ASSIGNMENT, "assignment:$id"), "Personal estimate", minutes)
    private fun analyze(input: List<WorkloadAccount>, config: WorkloadPolicy = policy, personal: List<PersonalTimeBlock> = emptyList()) = WorkloadEngine().analyze(moodle.id, input, config, now, personal)

    @Test fun `canonical IDs deduplicate explicit and exact URL references without title matching or owner mixing`() {
        val task = assignment(1, at(12))
        val calendar = listOf(
            CalendarEvent("one", 1, moodle.id, 1, task.name, task.due, deadline = true, assignmentId = 1),
            CalendarEvent("two", 2, moodle.id, 1, "Calendar title", task.due, deadline = true, url = "https://courses.uit.edu.vn:443/mod/assign/view.php?id=101"),
            CalendarEvent("foreign", 3, "another", 1, task.name, task.due, deadline = true, assignmentId = 1),
            CalendarEvent("unlinked", 4, moodle.id, 1, task.name, task.due, deadline = true),
            CalendarEvent("prefix", 5, moodle.id, 1, task.name, task.due, deadline = true, url = "https://courses.uit.edu.vn/mod/assign/view.php?id=1011"),
            CalendarEvent("external", 6, moodle.id, 1, task.name, task.due, deadline = true, url = "https://outside.invalid/mod/assign/view.php?id=101")
        )
        val result = analyze(sources(events = calendar))
        assertEquals(4, result.tasks.size)
        assertEquals(1, result.tasks.count { it.item.ref.kind == WorkloadKind.ASSIGNMENT })
        assertEquals(4, result.checkpoints.single().missingEstimates)
        assertEquals(WorkloadPressure.UNCERTAIN, result.checkpoints.single().pressure)
        val other = WorkloadSourceIndex().items(sources().first().copy(account = moodle.copy(id = "another"), snapshot = PortalSnapshot(assignments = listOf(task.copy(accountId = "another"))))).single()
        assertNotEquals(result.tasks.first { it.item.ref.kind == WorkloadKind.ASSIGNMENT }.item.ref.id, other.ref.id)
    }

    @Test fun `deadline checkpoints use exact time and cumulative demand without spreading hours into days`() {
        val result = analyze(sources(assignments = listOf(assignment(1, at(10)), assignment(2, at(12))), estimates = listOf(estimate(1, 180), estimate(2, 60))))
        assertEquals(listOf(180L, 240L), result.checkpoints.map { it.demand })
        assertEquals(listOf(120L, 240L), result.checkpoints.map { it.capacity.optimistic })
        assertEquals(WorkloadPressure.SHORTFALL, result.checkpoints.first().pressure)
        assertEquals(WorkloadPressure.WITHIN_ENTERED_BUDGET, result.checkpoints.last().pressure)
        assertEquals(WorkloadPressure.SHORTFALL, result.checkpoints.minBy { it.pressure.ordinal }.pressure)
        val lead = analyze(sources(estimates = listOf(estimate(1, 240))), policy.copy(deadlineLead = 60)).checkpoints.single()
        assertEquals(at(11), lead.cutoff)
        assertEquals(180, lead.capacity.optimistic)
        assertEquals(WorkloadPressure.SHORTFALL, lead.pressure)
    }

    @Test fun `overlap union and minimum sessions respect fragmented daily budgets`() {
        val blocks = listOf(PersonalTimeBlock("1", "Busy", at(8, 30), at(9)), PersonalTimeBlock("2", "Also busy", at(8, 45), at(9)), PersonalTimeBlock("3", "Busy later", at(9, 30), at(18)))
        val config = policy.copy(days = policy.days.map { it.copy(minutes = 50) })
        val result = analyze(sources(estimates = listOf(estimate(1, 40))), config, blocks)
        assertEquals(30, result.checkpoints.single().capacity.optimistic)
        assertEquals(WorkloadPressure.SHORTFALL, result.checkpoints.single().pressure)
        val withoutOverlap = analyze(sources(), config, blocks.filterNot { it.id == "2" })
        assertEquals(result.days.map { it.capacity }, withoutOverlap.days.map { it.capacity })
    }

    @Test fun `partial cancellations retain teaching periods and make-up lessons remain busy`() {
        val slot = DaaTimetableSlot("IT004", "IT004", "Course", 3, 1, 1, 3, "A101", "Teacher", "$day", "$day")
        val cancellation = ClassScheduleChangeNotice(1, ScheduleChangeType.CANCELED, "IT004", "Canceled", "", "$day", 2, 2)
        val makeup = ClassScheduleChangeNotice(2, ScheduleChangeType.MAKE_UP, "IT004", "Make up", "A101", "$day", 4, 4)
        val source = sources(timetable = listOf(slot), notices = listOf(cancellation, makeup))
        val result = analyze(source)
        // 08:00-08:15 (15), 09:00-09:45 (45), 10:00-10:45 (45) are busy.
        // Gaps under 30 minutes are excluded, leaving 45 + 75 = 120 before noon.
        assertEquals(120, result.checkpoints.single().capacity.optimistic)
    }

    @Test fun `missing estimates are not zero and entered zero never means officially submitted`() {
        val zero = analyze(sources(estimates = listOf(estimate(1, 0))))
        assertEquals(1, zero.tasks.size)
        assertEquals(0, zero.submittedCount)
        assertEquals(0, zero.checkpoints.single().missingEstimates)
        val unknown = analyze(sources())
        assertEquals(1, unknown.checkpoints.single().missingEstimates)
        assertEquals(WorkloadPressure.UNCERTAIN, unknown.checkpoints.single().pressure)
        val submitted = CalendarEvent("submitted", 1, moodle.id, 1, "Assignment", at(12), deadline = true, assignmentId = 1, submitted = true)
        assertEquals(1, analyze(sources(events = listOf(submitted))).submittedCount)
        assertTrue(analyze(sources(events = listOf(submitted))).tasks.isEmpty())
    }

    @Test fun `unknown exam durations create scenario bounds and only a user assumption narrows them`() {
        val exam = DaaExamSlot("EXAM", "Exam", 1, "$day", "Room", startTime = "10:00")
        val source = sources(exams = listOf(exam), estimates = listOf(estimate(1, 180)))
        val result = analyze(source)
        val noon = result.checkpoints.first { it.cutoff == at(12) }
        assertEquals(120, noon.capacity.conservative)
        assertEquals(240, noon.capacity.optimistic)
        assertEquals(WorkloadPressure.UNCERTAIN, noon.pressure)
        val ref = result.tasks.single { it.item.ref.kind == WorkloadKind.EXAM }.item.ref
        val revised = source.map { if (it.account.id == portal.id) it.copy(estimates = listOf(WorkloadEstimate(ref, "Exam", remainingMinutes = 0, examMinutes = 60))) else it }
        val known = analyze(revised)
        assertEquals(1, known.assumedExamEnds)
        assertFalse(WorkloadIssue.UNKNOWN_END in known.issues)
        assertEquals(180, known.checkpoints.first { it.cutoff == at(12) }.capacity.optimistic)
    }

    @Test fun `source changes preserve identity while absent items retain unavailable estimates`() {
        val estimate = estimate(1, 60)
        val first = analyze(sources(estimates = listOf(estimate)))
        val renamed = analyze(sources(assignments = listOf(assignment(1, at(13)).copy(name = "Changed")), estimates = listOf(estimate)))
        assertEquals(first.tasks.single().item.ref, renamed.tasks.single().item.ref)
        assertEquals(60, renamed.tasks.single().estimate!!.remainingMinutes)
        val absent = analyze(sources(assignments = emptyList(), estimates = listOf(estimate)))
        assertEquals(listOf(estimate), absent.orphanEstimates)
        assertEquals(0, absent.submittedCount)
    }

    @Test fun `overdue is separate and conflicts staleness and unconfirmed settings are explicit`() {
        val past = assignment(1, at(7))
        val result = analyze(sources(assignments = listOf(past), estimates = listOf(estimate(1, 600))))
        assertEquals(1, result.overdue.size)
        assertTrue(result.checkpoints.isEmpty())
        val event = CalendarEvent("conflict", 1, moodle.id, 1, "Old deadline", at(14), deadline = true, assignmentId = 1)
        val conflict = analyze(sources(events = listOf(event)))
        assertEquals(at(12), conflict.tasks.single().item.due)
        assertTrue(WorkloadIssue.CONFLICT in conflict.issues)
        val inputs = sources(estimates = listOf(estimate(1, 0))).map { it.copy(snapshot = it.snapshot.copy(updatedAt = now - 2 * 86400), configured = false) }
        val stale = analyze(inputs)
        assertTrue(WorkloadIssue.STALE in stale.issues)
        assertTrue(WorkloadIssue.UNCONFIRMED_BUDGET in stale.issues)
        assertEquals(WorkloadPressure.UNCERTAIN, stale.checkpoints.single().pressure)
        val missingCalendar = analyze(sources(), policy.copy(calendarAccountId = "disconnected"))
        assertTrue(WorkloadIssue.MISSING_CALENDAR in missingCalendar.issues)
        assertEquals(0, missingCalendar.checkpoints.single().capacity.conservative)
    }

    @Test fun `configuration changes affect real calculation and malformed policies are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { validateWorkloadPolicy(policy.copy(days = policy.days.dropLast(1))) }
        assertThrows(IllegalArgumentException::class.java) { validateWorkloadPolicy(policy.copy(horizonDays = 100)) }
        assertThrows(IllegalArgumentException::class.java) { validateWorkloadPolicy(policy.copy(minimumSession = 90, days = policy.days.map { it.copy(minutes = 60) })) }
        val input = sources(estimates = listOf(estimate(1, 90)))
        val dayOff = analyze(input, policy.copy(days = policy.days.map { it.copy(minutes = 0) }))
        assertEquals(0, dayOff.checkpoints.single().capacity.optimistic)
        assertEquals(WorkloadPressure.SHORTFALL, dayOff.checkpoints.single().pressure)
        val tomorrow = assignment(1, at(12, date = day.plus(7, DateTimeUnit.DAY)))
        assertTrue(analyze(sources(assignments = listOf(tomorrow))).tasks.isEmpty())
        assertEquals(1, analyze(sources(assignments = listOf(tomorrow)), policy.copy(horizonDays = 14)).tasks.size)
    }

    @Test fun `capacity is deterministic monotone and union safe across randomized overlapping blocks`() {
        val random = Random(401)
        repeat(100) {
            val blocks = (0..5).map { i ->
                val start = now + random.nextInt(0, 180) * 60
                PersonalTimeBlock("$i", "Busy", start, start + random.nextInt(1, 90) * 60)
            }
            val source = sources(estimates = listOf(estimate(1, 100)))
            val config = policy.copy(days = policy.days.map { it.copy(minutes = 90) })
            val base = analyze(source, config, blocks)
            val shuffled = analyze(source.reversed(), config, blocks.shuffled(random))
            assertEquals(base.checkpoints, shuffled.checkpoints)
            val withMoreBusy = analyze(source, config, blocks + PersonalTimeBlock("extra", "Busy", at(9), at(10)))
            assertTrue(withMoreBusy.checkpoints.single().capacity.optimistic <= base.checkpoints.single().capacity.optimistic)
            val largerBudget = analyze(source, config.copy(days = policy.days.map { it.copy(minutes = 180) }), blocks)
            assertTrue(largerBudget.checkpoints.single().capacity.optimistic >= base.checkpoints.single().capacity.optimistic)
            val longerMinimum = analyze(source, config.copy(minimumSession = 60), blocks)
            assertTrue(longerMinimum.checkpoints.single().capacity.optimistic <= base.checkpoints.single().capacity.optimistic)
            assertTrue(base.checkpoints.single().capacity.conservative <= base.checkpoints.single().capacity.optimistic)
        }
    }

    @Test fun `session capacity agrees with independent exhaustive subset allocation`() {
        val random = Random(710)
        repeat(100) {
            val lengths = (1..4).map { random.nextInt(15, 46) }
            val minimum = listOf(10, 20, 30, 45).random(random)
            val budget = random.nextInt(minimum, 181)
            var cursor = now
            val busy = mutableListOf<PersonalTimeBlock>()
            lengths.forEachIndexed { index, length ->
                cursor += length * 60L
                if (index < 3) {
                    busy += PersonalTimeBlock("gap$index", "Busy", cursor, cursor + 10 * 60)
                    cursor += 10 * 60
                }
            }
            if (cursor < at(12)) busy += PersonalTimeBlock("end", "Busy", cursor, at(12))
            val eligible = lengths.filter { it >= minimum }
            val expected = (0 until (1 shl eligible.size)).maxOf { mask ->
                val chosen = eligible.filterIndexed { index, _ -> mask and (1 shl index) != 0 }
                if (chosen.size * minimum > budget) 0 else minOf(budget, chosen.sum())
            }
            val config = policy.copy(minimumSession = minimum, days = policy.days.map { it.copy(endMinute = 12 * 60, minutes = budget) })
            assertEquals(expected.toLong(), analyze(sources(), config, busy).checkpoints.single().capacity.optimistic)
        }
    }

    @Test fun `second precision rounds inward and displayed dates follow UIT midnight`() {
        val seconds = sources(assignments = listOf(assignment(1, at(8, 30) + 59)), estimates = listOf(estimate(1, 30)))
        val result = WorkloadEngine().analyze(moodle.id, seconds, policy.copy(minimumSession = 10), now + 31)
        assertEquals(29, result.checkpoints.single().capacity.optimistic)
        assertEquals(WorkloadPressure.SHORTFALL, result.checkpoints.single().pressure)
        val tomorrow = day.plus(1, DateTimeUnit.DAY)
        val midnight = WorkloadEngine().analyze(moodle.id, sources(assignments = listOf(assignment(1, dateAt(tomorrow, LocalTime(0, 0))))), policy, at(23, 50))
        assertEquals(tomorrow, midnight.days.single { it.tasks.isNotEmpty() }.date)
        assertEquals(0, midnight.checkpoints.single().capacity.optimistic)
    }

    @Test fun `contradictory submission flags never drop work and only one teaching service supplies exams`() {
        val events = listOf(true, false).mapIndexed { index, submitted -> CalendarEvent("state$index", index.toLong(), moodle.id, 1, "Assignment", at(12), deadline = true, assignmentId = 1, submitted = submitted) }
        val result = analyze(sources(events = events))
        assertEquals(1, result.tasks.size)
        assertNull(result.tasks.single().item.officialSubmitted)
        assertTrue(WorkloadIssue.CONFLICT in result.issues)
        val exam = DaaExamSlot("EXAM", "Exam", 1, "2026-10-27", "A101")
        val source = sources(exams = listOf(exam))
        val another = source.last().copy(account = portal.copy(id = "daa", portal = PortalType.DAA))
        assertEquals(1, analyze(source + another).tasks.count { it.item.ref.kind == WorkloadKind.EXAM })
        val switched = analyze(source + another, policy.copy(calendarAccountId = "daa"))
        assertEquals("daa", switched.tasks.single { it.item.ref.kind == WorkloadKind.EXAM }.item.ref.accountId)
    }

    @Test fun `explicit exam times preserve separate events when shifts are missing`() {
        val exam = DaaExamSlot("EXAM", "Exam", 0, "2026-10-27", "A101", startTime = "09:00")
        val result = analyze(sources(exams = listOf(exam, exam.copy(startTime = "13:00"))))
        val exams = result.tasks.filter { it.item.ref.kind == WorkloadKind.EXAM }
        assertEquals(2, exams.size)
        assertNotEquals(exams[0].item.ref, exams[1].item.ref)
    }
}
