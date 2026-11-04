package vn.edu.uit.mobile.domain

import kotlin.random.Random
import kotlinx.datetime.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import vn.edu.uit.mobile.common.*
import vn.edu.uit.mobile.model.*

class RevisionPlannerTest {
    private val day = LocalDate(2026, 10, 26)
    private val now = dateAt(day, LocalTime(8, 0))
    private fun at(minute: Int) = now + minute * 60L
    private val policy = WorkloadPolicy(days = (1..7).map { StudyDayBudget(it, 8 * 60, 18 * 60, 120) }, travelBuffer = 0, deadlineLead = 0)
    private fun plan(id: String = "one", due: Int = 600, minutes: Int = 120) = RevisionPlan(WorkloadRef("owner", WorkloadKind.EXAM, "exam:$id"), "Exam $id", at(due), listOf(StudyTopic("topic-$id", "Topic $id", minutes)))
    private fun source(plans: List<RevisionPlan>) = plans.map { WorkloadItem(it.exam, it.title, it.due) }
    private fun projection(gaps: List<Pair<Int, Int>> = listOf(0 to 600), issues: Set<WorkloadIssue> = emptySet()): WorkloadResult = WorkloadResult(day, day, emptyList(), emptyList(), 0, emptyList(), listOf(WorkloadDay(day, emptyList(), WorkloadCapacity(120, 120), gaps.map { StudyInterval(at(it.first), at(it.second)) })), issues, 0, emptyList(), emptyList())
    private val planner = RevisionPlanner()
    private val options = RevisionOptions(90, 10, 0)
    private fun generate(plans: List<RevisionPlan>, gaps: List<Pair<Int, Int>> = listOf(0 to 600), config: WorkloadPolicy = policy, opts: RevisionOptions = options, issues: Set<WorkloadIssue> = emptySet()) = planner.generate(RevisionWorkspace(plans, opts), projection(gaps, issues), config, source(plans), now)

    @Test fun `all exams share one budget with deadline priority and deterministic results`() {
        val early = plan("early", 90, 90)
        val late = plan("late", 600, 90)
        val result = generate(listOf(late, early))
        assertTrue(result.problems.isEmpty())
        val sessions = result.workspace.plans.flatMap { it.sessions }
        assertEquals(120, sessions.sumOf { it.minutes })
        assertEquals(90, result.workspace.plans.last().sessions.sumOf { it.effort })
        assertEquals(60, result.unplaced.sumOf { it.minutes })
        assertEquals(result, generate(listOf(late, early)))
        assertEquals(100, result.workspace.plans.first().sessions.single().start.minus(now).div(60).toInt())
    }

    @Test fun `short final effort reserves a full minimum session without inventing effort`() {
        val result = generate(listOf(plan(minutes = 20)))
        val session = result.workspace.plans.single().sessions.single()
        assertEquals(30, session.minutes)
        assertEquals(20, session.effort)
        assertTrue(result.unplaced.isEmpty())
        assertTrue(generate(listOf(plan(minutes = 20)), listOf(0 to 29)).unplaced.isNotEmpty())
    }

    @Test fun `prefix counterexample stays jointly feasible and leaves work explicitly unplaced`() {
        val plans = listOf(plan("early", 30, 30), plan("late", 170, 20))
        val result = generate(plans, listOf(0 to 30, 120 to 170), policy.copy(days = policy.days.map { it.copy(minutes = 50) }))
        assertEquals(1, result.workspace.plans.sumOf { it.sessions.size })
        assertEquals(20, result.unplaced.single().minutes)
        assertTrue(result.problems.isEmpty())
    }

    @Test fun `completed time consumes today's budget and completed and locked sessions survive replan`() {
        val original = plan(minutes = 120)
        val done = RevisionSession("done", original.topics.single().id, at(-60), at(-30), 30, RevisionStatus.COMPLETED)
        val locked = RevisionSession("locked", done.topicId, at(60), at(90), 30, locked = true)
        val old = RevisionSession("replace", done.topicId, at(200), at(260), 60)
        val result = generate(listOf(original.copy(sessions = listOf(done, locked, old))))
        val sessions = result.workspace.plans.single().sessions
        assertTrue(done in sessions && locked in sessions)
        assertFalse(old in sessions)
        assertEquals(120, sessions.sumOf { it.minutes })
        assertTrue(result.unplaced.isEmpty())
    }

    @Test fun `missed unlocked sessions become history without credit or automatic completion`() {
        val p = plan(minutes = 30)
        val past = RevisionSession("past", p.topics.single().id, at(-60), at(-30), 30)
        val result = generate(listOf(p.copy(sessions = listOf(past))))
        assertEquals(RevisionStatus.MISSED, result.workspace.plans.single().sessions.first().status)
        assertEquals(30, result.workspace.plans.single().sessions.filter { it.status == RevisionStatus.PLANNED }.sumOf { it.effort })
    }

    @Test fun `locked conflicts reject generation and never overwrite original history`() {
        val p = plan(minutes = 30)
        val original = RevisionWorkspace(listOf(p.copy(sessions = listOf(RevisionSession("lock", p.topics.single().id, at(60), at(90), 30, locked = true)))), options)
        val result = planner.generate(original, projection(listOf(120 to 600)), policy, source(listOf(p)), now)
        assertTrue(RevisionProblem.SESSION_CONFLICT in result.problems)
        assertEquals(original, result.workspace)
    }

    @Test fun `changed exams require explicit relinking while archives retain completed effort`() {
        val p = plan(minutes = 30)
        val completed = RevisionSession("done", p.topics.single().id, at(-60), at(-30), 30, RevisionStatus.COMPLETED)
        val original = RevisionWorkspace(listOf(p.copy(sessions = listOf(completed))), options)
        val result = planner.generate(original, projection(), policy, emptyList(), now)
        assertEquals(original, result.workspace)
        assertTrue(RevisionProblem.SOURCE_CHANGED in result.problems)
        val archived = original.copy(plans = original.plans.map { it.copy(active = false) })
        assertTrue(planner.generate(archived, projection(), policy, emptyList(), now).problems.isEmpty())
        assertEquals(completed, archived.plans.single().sessions.single())
    }

    @Test fun `unknown exam ends use conservative windows and other quality failures block planning`() {
        assertTrue(generate(listOf(plan()), issues = setOf(WorkloadIssue.UNKNOWN_END)).problems.isEmpty())
        assertTrue(RevisionProblem.INPUT_QUALITY in generate(listOf(plan()), issues = setOf(WorkloadIssue.STALE)).problems)
        assertTrue(generate(listOf(plan()), gaps = emptyList(), issues = setOf(WorkloadIssue.UNKNOWN_END)).unplaced.isNotEmpty())
    }

    @Test fun `lead delay minimum maximum breaks and topic order are applied together`() {
        val p = plan(due = 160, minutes = 20).copy(topics = listOf(StudyTopic("first", "First", 20), StudyTopic("second", "Second", 20)))
        val result = generate(listOf(p), config = policy.copy(deadlineLead = 20), opts = RevisionOptions(30, 15, 10))
        val sessions = result.workspace.plans.single().sessions
        assertEquals(listOf("first", "second"), sessions.map { it.topicId })
        assertEquals(at(10), sessions.first().start)
        assertEquals(at(55), sessions.last().start)
        assertTrue(sessions.all { it.minutes == 30 && it.end <= at(140) })
    }

    @Test fun `overallocated progress wrong ownership and malformed sessions are rejected`() {
        val p = plan(minutes = 20)
        assertThrows(IllegalArgumentException::class.java) { validateRevision(RevisionWorkspace(listOf(p)), "another") }
        assertThrows(IllegalArgumentException::class.java) { validateRevision(RevisionWorkspace(listOf(p.copy(sessions = listOf(RevisionSession("too-much", p.topics.single().id, at(0), at(30), 30))))), "owner") }
        assertThrows(IllegalArgumentException::class.java) { validateRevision(RevisionWorkspace(listOf(p.copy(sessions = listOf(RevisionSession("seconds", p.topics.single().id, at(0) + 1, at(30), 20))))), "owner") }
    }

    @Test fun `independent randomized oracle verifies constructed intervals budgets credit and deadlines`() {
        val random = Random(120026)
        repeat(150) {
            val minimum = listOf(10, 20, 30, 45)[random.nextInt(4)]
            val budget = random.nextInt(minimum, 241)
            val max = random.nextInt(minimum, 121)
            val rest = random.nextInt(0, 21)
            val gaps = listOf(0 to random.nextInt(30, 100), 120 to random.nextInt(180, 240), 300 to 500)
            val plans = (1..3).map { plan("$it", random.nextInt(100, 601), random.nextInt(1, 301)) }
            val config = policy.copy(minimumSession = minimum, days = policy.days.map { it.copy(minutes = budget) })
            val result = generate(plans, gaps, config, RevisionOptions(max, rest, 0))
            assertTrue(result.problems.isEmpty())
            val sessions = result.workspace.plans.flatMap { p -> p.sessions.map { p to it } }.sortedBy { it.second.start }
            assertTrue(sessions.sumOf { it.second.minutes } <= budget)
            sessions.forEach { (p, s) ->
                assertTrue(s.start >= now && s.end <= p.due)
                assertTrue(s.minutes in minimum..max && s.effort in 1..s.minutes)
                assertTrue(gaps.any { s.start >= at(it.first) && s.end <= at(it.second) })
            }
            sessions.zipWithNext().forEach { (a, b) -> assertTrue(a.second.end + rest * 60 <= b.second.start) }
            plans.forEach { p ->
                val credit = sessions.filter { it.first.exam == p.exam }.sumOf { it.second.effort }
                val unplaced = result.unplaced.filter { it.exam == p.exam }.sumOf { it.minutes }
                assertEquals(p.topics.single().minutes, credit + unplaced)
            }
        }
    }

    @Test fun `in progress reservations survive without being marked completed`() {
        val p = plan(minutes = 60)
        val running = RevisionSession("running", p.topics.single().id, at(-10), at(20), 30)
        val result = generate(listOf(p.copy(sessions = listOf(running))), config = policy.copy(days = policy.days.map { it.copy(minutes = 60) }))
        assertTrue(result.problems.isEmpty())
        assertTrue(running in result.workspace.plans.single().sessions)
        assertTrue(result.workspace.plans.single().sessions.none { it.status == RevisionStatus.COMPLETED })
        assertTrue(result.unplaced.isEmpty())
    }

    @Test fun `multiple UIT dates share separate day budgets and older completed effort stays credited`() {
        val p = plan(due = 2040, minutes = 300)
        val done = RevisionSession("yesterday", p.topics.single().id, at(-1440), at(-1380), 60, RevisionStatus.COMPLETED)
        val tomorrow = day.plus(1, DateTimeUnit.DAY)
        val input = projection().copy(through = tomorrow, days = projection().days + WorkloadDay(tomorrow, emptyList(), WorkloadCapacity(120, 120), listOf(StudyInterval(at(1440), at(2040)))))
        val result = planner.generate(RevisionWorkspace(listOf(p.copy(sessions = listOf(done))), options.copy(breakMinutes = 0)), input, policy, source(listOf(p)), now)
        assertTrue(result.problems.isEmpty())
        assertTrue(result.unplaced.isEmpty())
        assertEquals(300, result.workspace.plans.single().sessions.sumOf { it.effort })
        result.workspace.plans.single().sessions.filter { it.status == RevisionStatus.PLANNED }.groupBy { Instant.fromEpochSeconds(it.start).toLocalDateTime(UitTimeZone).date }.forEach { (_, sessions) -> assertEquals(120, sessions.sumOf { it.minutes }) }
    }

    @Test fun `search guards block saving and obsolete calculations can be canceled cooperatively`() {
        val p = plan(minutes = 180)
        val result = RevisionPlanner(1).generate(RevisionWorkspace(listOf(p), options), projection(), policy, source(listOf(p)), now)
        assertTrue(RevisionProblem.COMPUTATION_LIMIT in result.problems)
        assertTrue(result.unplaced.isNotEmpty())
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            planner.generate(RevisionWorkspace(listOf(p), options), projection(), policy, source(listOf(p)), now) { throw java.util.concurrent.CancellationException("Obsolete draft") }
        }
    }

    @Test fun `production workload exports conservatively clipped and rounded source intervals`() {
        val owner = Account("owner", "https://portal.uit.edu.vn", 1, "Example", PortalType.UIT_PORTAL)
        val source = WorkloadAccount(owner, PortalSnapshot(updatedAt = now), policy, configured = true)
        val result = WorkloadEngine().analyze(owner.id, listOf(source), policy, now + 1, listOf(PersonalTimeBlock("busy", "Busy", at(30), at(90))))
        assertEquals(listOf(StudyInterval(at(1), at(30)), StudyInterval(at(90), at(600))), result.days.first().available)
        assertTrue(WorkloadEngine().analyze(owner.id, listOf(source), policy, now, personalFailed = true).days.all { it.available.isEmpty() })
    }
}
