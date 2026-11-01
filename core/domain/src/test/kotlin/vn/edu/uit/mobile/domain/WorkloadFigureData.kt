package vn.edu.uit.mobile.domain

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.datetime.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import vn.edu.uit.mobile.common.*
import vn.edu.uit.mobile.model.*

/** Synthetic executable examples shared by the renderer and the figure-contract tests. */
object WorkloadFigureData {
    private val day = LocalDate(2026, 10, 26)
    private val now = dateAt(day, LocalTime(8, 0))
    private val study = Account("figure-study", "https://courses.uit.edu.vn", 1, "Example study source", PortalType.MOODLE)
    private val teaching = Account("figure-teaching", "https://portal.uit.edu.vn", 1, "Example teaching source", PortalType.UIT_PORTAL)
    private val policy = WorkloadPolicy(days = (1..7).map { StudyDayBudget(it, 8 * 60, 18 * 60, 240) }, travelBuffer = 0, deadlineLead = 0, calendarAccountId = teaching.id)
    private fun at(minute: Int) = now + minute * 60L
    private fun task(id: Long, minute: Int) = Assignment(id, 1, id + 100, "Example task $id", "", at(minute), accountId = study.id)
    private fun estimate(id: Long, minutes: Int) = WorkloadEstimate(WorkloadRef(study.id, WorkloadKind.ASSIGNMENT, "assignment:$id"), "Example task $id", minutes)
    private fun source(assignments: List<Assignment>, estimates: List<WorkloadEstimate>, events: List<CalendarEvent> = emptyList()) = WorkloadAccount(study, PortalSnapshot(assignments = assignments, events = events, updatedAt = now), policy, estimates, configured = true)
    private fun calendar(unknownExam: Boolean = false): WorkloadAccount {
        val exams = if (unknownExam) listOf(DaaExamSlot("EXAMPLE", "Example exam", 0, "$day", "", startTime = "11:00")) else emptyList()
        val owner = WorkloadAccount(teaching, PortalSnapshot(exams = exams, updatedAt = now), configured = true)
        val ref = WorkloadSourceIndex().items(owner).singleOrNull()?.ref
        return if (ref == null) owner else owner.copy(estimates = listOf(WorkloadEstimate(ref, "Example exam", remainingMinutes = 0)))
    }
    private fun analyze(primary: WorkloadAccount, config: WorkloadPolicy = policy, blocks: List<PersonalTimeBlock> = emptyList(), unknownExam: Boolean = false) = WorkloadEngine().analyze(study.id, listOf(primary, calendar(unknownExam)), config, now, blocks)

    fun point(minute: Int, unknownExam: Boolean = false): WorkloadCheckpoint {
        val primary = source(listOf(task(1, 240), task(2, 420), task(9999, minute)), listOf(estimate(1, 180), estimate(2, 60), estimate(9999, 0)), listOf(CalendarEvent("lab", 1, study.id, null, "Example lab", at(0), at(120))))
        return analyze(primary, unknownExam = unknownExam).checkpoints.single { it.cutoff == at(minute) }
    }
    fun union(): WorkloadCheckpoint {
        val config = policy.copy(days = policy.days.map { it.copy(endMinute = 12 * 60, minutes = 120) }, travelBuffer = 15)
        val events = listOf(CalendarEvent("a", 1, study.id, null, "Busy A", at(60), at(150)), CalendarEvent("b", 2, study.id, null, "Busy B", at(120), at(180)))
        return analyze(source(listOf(task(1, 240)), listOf(estimate(1, 90)), events), config).checkpoints.single()
    }
    fun fragmented(budget: Int): WorkloadCheckpoint {
        val config = policy.copy(days = policy.days.map { it.copy(endMinute = 12 * 60, minutes = budget) })
        val blocks = listOf(PersonalTimeBlock("a", "Example busy", at(30), at(60)), PersonalTimeBlock("b", "Example busy", at(90), at(240)))
        return analyze(source(listOf(task(1, 240)), listOf(estimate(1, 0))), config, blocks).checkpoints.single()
    }
    fun counterexample(): List<WorkloadCheckpoint> {
        val config = policy.copy(days = policy.days.map { it.copy(endMinute = 12 * 60, minutes = 50) })
        val blocks = listOf(PersonalTimeBlock("a", "Example busy", at(30), at(120)), PersonalTimeBlock("b", "Example busy", at(170), at(240)))
        return analyze(source(listOf(task(1, 30), task(2, 170)), listOf(estimate(1, 30), estimate(2, 20))), config, blocks).checkpoints
    }

    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 1)
        val root = Path.of(args.single())
        Files.createDirectories(root)
        val curves = buildString {
            append("scenario\tminute\tdemand\tlower\tupper\tmissing\tstatus\n")
            for (unknown in listOf(false, true)) {
                for (minute in 0..600) {
                    val p = point(minute, unknown)
                    append("${if (unknown) "unknown" else "known"}\t$minute\t${p.demand}\t${p.capacity.conservative}\t${p.capacity.optimistic}\t${p.missingEstimates}\t${p.pressure}\n")
                }
            }
        }
        Files.writeString(root.resolve("engine-curves.tsv"), curves)
        val checkpoints = counterexample()
        val values = linkedMapOf(
            "meta" to linkedMapOf("origin_hour" to 8, "horizon_days" to policy.horizonDays, "minimum" to policy.minimumSession, "budget" to 240, "lead" to policy.deadlineLead, "buffer" to policy.travelBuffer),
            "union" to linkedMapOf("window_start" to 0, "window_end" to 240, "a_start" to 60, "a_end" to 150, "b_start" to 120, "b_end" to 180, "buffer" to 15, "union_start" to 45, "union_end" to 195, "minimum" to 30, "budget" to 120, "capacity" to union().capacity.optimistic.toInt()),
            "fragments" to linkedMapOf("first_start" to 0, "first_end" to 30, "second_start" to 60, "second_end" to 90, "minimum" to 30, "budget_a" to 50, "budget_b" to 60, "capacity_a" to fragmented(50).capacity.optimistic.toInt(), "capacity_b" to fragmented(60).capacity.optimistic.toInt()),
            "counterexample" to linkedMapOf("first_start" to 0, "first_end" to 30, "second_start" to 120, "second_end" to 170, "budget" to 50, "minimum" to 30, "first_demand" to checkpoints.first().demand.toInt(), "second_demand" to checkpoints.last().demand.toInt(), "first_capacity" to checkpoints.first().capacity.optimistic.toInt(), "second_capacity" to checkpoints.last().capacity.optimistic.toInt())
        )
        Files.writeString(
            root.resolve("engine-examples.tsv"),
            buildString {
                append("case\tkey\tvalue\n")
                values.forEach { (case, fields) -> fields.forEach { (key, value) -> append("$case\t$key\t$value\n") } }
            }
        )
        println("Exported synthetic workload figure inputs from the production engine.")
    }
}

class WorkloadFigureDataTest {
    @Test fun `cutoff curves distinguish a known deficit from unknown attendance duration`() {
        val noon = WorkloadFigureData.point(240)
        assertEquals(180, noon.demand)
        assertEquals(120, noon.capacity.optimistic)
        assertEquals(WorkloadPressure.SHORTFALL, noon.pressure)
        val later = WorkloadFigureData.point(420)
        assertEquals(240, later.demand)
        assertEquals(240, later.capacity.optimistic)
        assertEquals(WorkloadPressure.WITHIN_ENTERED_BUDGET, later.pressure)
        val unknown = WorkloadFigureData.point(420, true)
        assertEquals(60, unknown.capacity.conservative)
        assertEquals(240, unknown.capacity.optimistic)
        assertEquals(WorkloadPressure.UNCERTAIN, unknown.pressure)
    }

    @Test fun `union and fragmented-budget illustrations match executable capacity`() {
        assertEquals(90, WorkloadFigureData.union().capacity.optimistic)
        assertEquals(30, WorkloadFigureData.fragmented(50).capacity.optimistic)
        assertEquals(60, WorkloadFigureData.fragmented(60).capacity.optimistic)
    }

    @Test fun `independent checkpoints pass in the illustrated joint-session counterexample`() {
        val points = WorkloadFigureData.counterexample()
        assertTrue(points.all { it.pressure == WorkloadPressure.WITHIN_ENTERED_BUDGET })
        assertEquals(30, points.first().demand)
        assertEquals(50, points.last().demand)
        assertTrue(50 - points.first().demand < 30)
        assertTrue(points.last().demand > points.first().demand)
    }
}
