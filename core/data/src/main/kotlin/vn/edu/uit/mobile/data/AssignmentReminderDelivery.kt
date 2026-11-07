package vn.edu.uit.mobile.data

import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.*
import vn.edu.uit.mobile.common.*
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.model.*
import vn.edu.uit.mobile.moodle.*
import vn.edu.uit.mobile.network.*

data class AssignmentReminderBatch(val items: List<AssignmentReminderItem>, val current: suspend () -> Boolean)
interface AssignmentReminderSource {
    suspend fun accounts(): List<Account>
    suspend fun read(account: Account, now: Long, policy: AssignmentReminderPolicy): AssignmentReminderBatch
}
interface AssignmentReminderLedger {
    suspend fun lastAttempt(accountId: String): Long?
    suspend fun reserve(accountId: String, at: Long)
}
interface AssignmentReminderSink {
    fun available(): Boolean
    fun post(account: Account, items: List<AssignmentReminderItem>, now: Long): Boolean
    fun cancel(accountId: String)
}

/** Only fresh, explicitly pending submissions can reach notification delivery. */
class MoodleAssignmentReminderSource(
    private val vault: SessionStore,
    private val transport: (PortalSession) -> PortalTransport = { SafePortalTransport(it) }
) : AssignmentReminderSource {
    override suspend fun accounts() = vault.sessions.first().map { it.account }.filter { it.portal == PortalType.MOODLE }
    override suspend fun read(account: Account, now: Long, policy: AssignmentReminderPolicy): AssignmentReminderBatch {
        val lease = vault.sessions.first().firstOrNull { it.account == account && it.account.portal == PortalType.MOODLE } ?: throw SessionExpiredException()
        suspend fun current() = lease in vault.sessions.first()
        val items = transport(lease).use { transport ->
            // Background reads must never fall back to pages that mark a module viewed.
            val api = MoodleGateway(transport, AjaxMoodleApiClient(transport), allowSubmissionPageFallback = false)
            val courses = api.courses().filter { it.accountId == account.id && !it.hidden && it.classification !in setOf("past", "hidden", "future") }.distinctBy { it.id }
            val start = Instant.fromEpochSeconds(now).toLocalDateTime(UitTimeZone).date
            val end = Instant.fromEpochSeconds(now + policy.lookAheadSeconds).toLocalDateTime(UitTimeZone).date
            var month = LocalDate(start.year, start.monthNumber, 1)
            val calendar = mutableListOf<CalendarEvent>()
            while (month <= end) {
                calendar += api.calendar(month.year, month.monthNumber)
                month = month.plus(1, DateTimeUnit.MONTH)
            }
            fun calendarReminders(course: Course): List<AssignmentReminderItem> = calendar.filter { it.accountId == account.id && it.courseId == course.id && it.eventType == "due" && it.deadline && it.needsAction && it.assignmentId != null }.mapNotNull { event ->
                val link = event.actionUrl ?: return@mapNotNull null
                val cmid = runCatching {
                    OriginGuardPlugin(account.baseUrl).validate(link)
                    val url = io.ktor.http.Url(link)
                    require(url.encodedPath == "/mod/assign/view.php" && url.parameters.getAll("action") == listOf("editsubmission"))
                    url.parameters.getAll("id")?.singleOrNull()?.toLongOrNull()?.takeIf { it > 0 }
                }.getOrNull() ?: return@mapNotNull null
                val assignment = Assignment(requireNotNull(event.assignmentId), course.id, cmid, event.name, due = event.start, accountId = account.id)
                policy.candidates(course, listOf(assignment), calendar, now).singleOrNull()?.copy(basis = AssignmentReminderBasis.CALENDAR_ACTION)
            }.distinctBy { it.assignment.id }
            buildList {
                var statusApiAvailable = true
                for (course in courses) {
                    check(current()) { "Account changed" }
                    val calendarItems = calendarReminders(course)
                    if (!statusApiAvailable) {
                        addAll(calendarItems)
                        continue
                    }
                    val assignments = try {
                        api.assignments(course.id)
                    } catch (e: PortalException) {
                        if (!MoodleErrorClassifier.isUnavailableSessionMethod(e)) throw e
                        addAll(calendarItems)
                        continue
                    }
                    for (item in policy.candidates(course, assignments, calendar, now)) {
                        currentCoroutineContext().ensureActive()
                        if (!statusApiAvailable) {
                            calendarItems.firstOrNull { it.assignment.id == item.assignment.id }?.let { add(it) }
                            continue
                        }
                        val status = try {
                            api.submission(item.assignment)
                        } catch (e: PortalException) {
                            if (!MoodleErrorClassifier.isUnavailableSessionMethod(e)) throw e
                            statusApiAvailable = false
                            calendarItems.firstOrNull { it.assignment.id == item.assignment.id }?.let { add(it) }
                            continue
                        }
                        val due = status.extensionDue ?: item.due
                        if (policy.pending(status) && due > now && due - now <= policy.lookAheadSeconds) add(item.copy(due = due))
                        check(current()) { "Account changed" }
                    }
                }
            }
        }
        return AssignmentReminderBatch(items) { current() }
    }
}

class AssignmentReminderDelivery(
    private val source: AssignmentReminderSource,
    private val ledger: AssignmentReminderLedger,
    private val sink: AssignmentReminderSink,
    private val policy: AssignmentReminderPolicy = AssignmentReminderPolicy(),
    private val now: () -> Long = { System.currentTimeMillis() / 1000 }
) {
    suspend fun deliver(): Boolean = mutex.withLock {
        if (!sink.available()) return@withLock true
        var complete = true
        for (account in source.accounts().filter { it.portal == PortalType.MOODLE }.distinctBy { it.id }) {
            try {
                val at = now()
                if (!policy.ready(ledger.lastAttempt(account.id), at)) continue
                val batch = withTimeout(120000) { source.read(account, at, policy) }
                if (!batch.current() || account !in source.accounts()) {
                    sink.cancel(account.id)
                    continue
                }
                // Recheck the clock/window after network reads; never post an expired item.
                val ready = batch.items.filter { it.course.accountId == account.id && it.assignment.accountId == account.id && it.due > now() && it.due - now() <= policy.lookAheadSeconds }.sortedBy { it.due }
                if (ready.isEmpty()) {
                    sink.cancel(account.id)
                    // A successful empty scan is also throttled; do not poll Moodle every tick.
                    ledger.reserve(account.id, now())
                    continue
                }
                if (!sink.available()) continue
                // Persist before posting: a storage failure or process retry must not spam.
                val deliveryAt = now()
                ledger.reserve(account.id, deliveryAt)
                val unexpired = ready.filter { it.due > now() }
                if (batch.current() && account in source.accounts() && unexpired.isNotEmpty()) sink.post(account, unexpired, deliveryAt) else sink.cancel(account.id)
            } catch (_: TimeoutCancellationException) {
                complete = false
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                complete = false
            }
        }
        complete
    }
    companion object {
        private val mutex = Mutex()
    }
}
