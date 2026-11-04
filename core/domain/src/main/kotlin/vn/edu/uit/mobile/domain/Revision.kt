package vn.edu.uit.mobile.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.Instant
import kotlinx.datetime.toLocalDateTime
import vn.edu.uit.mobile.common.UitTimeZone

/** A reusable personal topic, independent of any university completion/submission state. */
data class StudyTopic(val id: String, val title: String, val minutes: Int)
enum class RevisionStatus { PLANNED, COMPLETED, MISSED }
data class RevisionSession(val id: String, val topicId: String, val start: Long, val end: Long, val effort: Int, val status: RevisionStatus = RevisionStatus.PLANNED, val locked: Boolean = false) {
    val minutes: Int get() = ((end - start) / 60).toInt()
}
data class RevisionPlan(val exam: WorkloadRef, val title: String, val due: Long, val topics: List<StudyTopic> = emptyList(), val sessions: List<RevisionSession> = emptyList(), val active: Boolean = true)
data class RevisionOptions(val maximumSession: Int = 90, val breakMinutes: Int = 10, val startDelay: Int = 10)
data class RevisionWorkspace(val plans: List<RevisionPlan> = emptyList(), val options: RevisionOptions = RevisionOptions())
data class RevisionAccount(val accountId: String, val workspace: RevisionWorkspace = RevisionWorkspace(), val failed: Boolean = false)
interface RevisionRepository {
    val accounts: Flow<List<RevisionAccount>>

    /** Compare-and-replace the entire owner workspace so multiple exam plans share one budget. */
    suspend fun replace(accountId: String, expected: RevisionWorkspace, value: RevisionWorkspace)
}

fun validateRevision(workspace: RevisionWorkspace, accountId: String) {
    require(workspace.options.maximumSession in 10..240 && workspace.options.breakMinutes in 0..120 && workspace.options.startDelay in 0..120)
    require(workspace.plans.size <= 20 && workspace.plans.map { it.exam }.distinct().size == workspace.plans.size)
    require(workspace.plans.sumOf { it.sessions.size } <= 2048)
    val sessionIds = workspace.plans.flatMap { it.sessions }.map { it.id }
    require(sessionIds.distinct().size == sessionIds.size)
    workspace.plans.forEach { plan ->
        validateWorkloadEstimate(WorkloadEstimate(plan.exam, plan.title, 0))
        require(plan.exam.accountId == accountId && plan.exam.kind == WorkloadKind.EXAM && plan.due in 0..253402300799L)
        require(plan.topics.size <= 20 && plan.topics.map { it.id }.distinct().size == plan.topics.size)
        plan.topics.forEach { topic ->
            require(topic.id.matches(Regex("[a-zA-Z0-9_-]{1,80}")) && topic.title.isNotBlank() && topic.title.length <= 160 && topic.minutes in 1..60000)
        }
        require(plan.sessions.map { it.id }.distinct().size == plan.sessions.size)
        plan.sessions.forEach { s ->
            require(s.id.matches(Regex("[a-zA-Z0-9_-]{1,100}")) && plan.topics.any { it.id == s.topicId })
            require(s.start in 0..253402300799L && s.end in 0..253402300799L && s.start % 60 == 0L && s.end % 60 == 0L)
            require(s.end > s.start && s.end - s.start <= 720 * 60 && s.effort in 1..s.minutes)
            require(Instant.fromEpochSeconds(s.start).toLocalDateTime(UitTimeZone).date == Instant.fromEpochSeconds(s.end - 1).toLocalDateTime(UitTimeZone).date)
        }
        plan.topics.forEach { topic -> require(plan.sessions.filter { it.topicId == topic.id && it.status != RevisionStatus.MISSED }.sumOf { it.effort.toLong() } <= topic.minutes) }
    }
}
