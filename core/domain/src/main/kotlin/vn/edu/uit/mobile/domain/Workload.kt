package vn.edu.uit.mobile.domain

import java.security.MessageDigest
import kotlinx.coroutines.flow.Flow
import vn.edu.uit.mobile.model.*

enum class WorkloadKind { ASSIGNMENT, DEADLINE, EXAM }
data class WorkloadRef(val accountId: String, val kind: WorkloadKind, val target: String) {
    val id: String by lazy { MessageDigest.getInstance("SHA-256").digest("$accountId\u0000${kind.name}\u0000$target".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) } }
}
data class WorkloadEstimate(val ref: WorkloadRef, val title: String, val remainingMinutes: Int? = null, val examMinutes: Int? = null)
data class StudyDayBudget(val isoDay: Int, val startMinute: Int = 18 * 60, val endMinute: Int = 22 * 60, val minutes: Int = 120)
data class WorkloadPolicy(
    val horizonDays: Int = 7,
    val days: List<StudyDayBudget> = (1..7).map { StudyDayBudget(it) },
    val minimumSession: Int = 30,
    val travelBuffer: Int = 15,
    val deadlineLead: Int = 60,
    val staleHours: Int = 24,
    val includeOtherEvents: Boolean = true,
    val calendarAccountId: String = ""
)
data class WorkloadAccount(val account: Account, val snapshot: PortalSnapshot = PortalSnapshot(), val policy: WorkloadPolicy = WorkloadPolicy(), val estimates: List<WorkloadEstimate> = emptyList(), val storageFailed: Boolean = false, val sourceFailed: Boolean = false, val configured: Boolean = false)
interface WorkloadRepository {
    val accounts: Flow<List<WorkloadAccount>>
    suspend fun savePolicy(accountId: String, policy: WorkloadPolicy)
    suspend fun saveEstimate(estimate: WorkloadEstimate)
    suspend fun removeEstimate(ref: WorkloadRef)
}

fun validateWorkloadPolicy(policy: WorkloadPolicy) {
    require(policy.horizonDays in setOf(7, 14, 28) && policy.minimumSession in 10..240 && policy.travelBuffer in 0..120 && policy.deadlineLead in 0..1440 && policy.staleHours in 1..168)
    require(policy.days.size == 7 && policy.days.map { it.isoDay }.toSet() == (1..7).toSet())
    require(policy.days.all { it.startMinute in 0..1438 && it.endMinute in 1..1439 && it.startMinute < it.endMinute && (it.minutes == 0 || it.minutes in policy.minimumSession..720) })
    require(policy.calendarAccountId.length <= 256 && policy.calendarAccountId.none { it.isISOControl() })
}
fun validateWorkloadEstimate(estimate: WorkloadEstimate) {
    val ref = estimate.ref
    require(ref.accountId.length in 1..256 && ref.target.length in 1..512 && ref.accountId.none { it.isISOControl() } && ref.target.none { it.isISOControl() })
    require(estimate.title.isNotBlank() && estimate.title.length <= 240)
    require(estimate.remainingMinutes == null || estimate.remainingMinutes in 0..60000)
    require(estimate.examMinutes == null || ref.kind == WorkloadKind.EXAM && estimate.examMinutes in 1..720)
    require(estimate.remainingMinutes != null || estimate.examMinutes != null)
    when (ref.kind) {
        WorkloadKind.ASSIGNMENT -> require(ref.target.matches(Regex("assignment:[1-9][0-9]*")))
        WorkloadKind.DEADLINE -> require(ref.target.startsWith("event:") && ref.target.length > 6)
        WorkloadKind.EXAM -> require(ref.target.startsWith("exam:") && ref.target.length > 5)
    }
}
