package vn.edu.uit.mobile.data

import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.model.*

class AssignmentReminderDeliveryTest {
    private val account = Account("fixture-account", "https://courses.uit.edu.vn", 1, "Fixture student", PortalType.MOODLE)
    private val course = Course(4, account.id, "IT001", "Programming")
    private var clock = 1800000000L
    private val item get() = AssignmentReminderItem(course, Assignment(12, course.id, 120, "Fixture exercise", due = clock + 86400, accountId = account.id), clock + 86400)
    private class Ledger : AssignmentReminderLedger {
        val times = mutableMapOf<String, Long>()
        var broken = false
        override suspend fun lastAttempt(accountId: String) = times[accountId]
        override suspend fun reserve(accountId: String, at: Long) {
            if (broken) error("Storage unavailable") else times[accountId] = at
        }
    }
    private class Sink : AssignmentReminderSink {
        var enabled = true
        var broken = false
        val posted = mutableListOf<Pair<String, List<AssignmentReminderItem>>>()
        val canceled = mutableListOf<String>()
        override fun available() = enabled
        override fun post(account: Account, items: List<AssignmentReminderItem>, now: Long): Boolean {
            if (broken) error("Notification outcome unknown")
            posted += account.id to items
            return true
        }
        override fun cancel(accountId: String) {
            canceled += accountId
        }
    }
    private inner class Source : AssignmentReminderSource {
        var owners = listOf(account)
        var reads = 0
        var read: suspend (Account) -> AssignmentReminderBatch = { AssignmentReminderBatch(listOf(item)) { true } }
        override suspend fun accounts() = owners
        override suspend fun read(account: Account, now: Long, policy: AssignmentReminderPolicy): AssignmentReminderBatch {
            reads++
            return read(account)
        }
    }

    @Test fun repeatBoundaryAndRestartUsePersistentRollingCheckpoint() = runTest {
        val source = Source()
        val ledger = Ledger()
        val sink = Sink()
        fun delivery() = AssignmentReminderDelivery(source, ledger, sink, now = { clock })
        assertTrue(delivery().deliver())
        assertEquals(1, sink.posted.size)
        clock += 8 * 3600 - 1
        delivery().deliver()
        assertEquals(1, source.reads)
        clock++
        delivery().deliver()
        assertEquals(2, sink.posted.size)
        clock -= 100
        delivery().deliver()
        assertEquals(2, source.reads)
    }

    @Test fun concurrentWorkersSerializeAndDoNotDoubleDeliver() = runTest {
        val source = Source()
        val ledger = Ledger()
        val sink = Sink()
        coroutineScope { repeat(4) { launch { AssignmentReminderDelivery(source, ledger, sink, now = { clock }).deliver() } } }
        assertEquals(1, source.reads)
        assertEquals(1, sink.posted.size)
    }

    @Test fun permissionDenialDoesNotReadTheUniversityOrConsumeTheCheckpoint() = runTest {
        val source = Source()
        val ledger = Ledger()
        val sink = Sink().apply { enabled = false }
        val delivery = AssignmentReminderDelivery(source, ledger, sink, now = { clock })
        delivery.deliver()
        assertEquals(0, source.reads)
        assertTrue(ledger.times.isEmpty())
        sink.enabled = true
        delivery.deliver()
        assertEquals(1, sink.posted.size)
    }

    @Test fun accountRevocationAndExpiredItemsNeverReachNotification() = runTest {
        val source = Source()
        val ledger = Ledger()
        val sink = Sink()
        source.read = { AssignmentReminderBatch(listOf(item)) { false } }
        AssignmentReminderDelivery(source, ledger, sink, now = { clock }).deliver()
        assertTrue(sink.posted.isEmpty())
        assertTrue(ledger.times.isEmpty())
        source.read = { AssignmentReminderBatch(listOf(item.copy(due = clock), item.copy(course = course.copy(accountId = "other")))) { true } }
        AssignmentReminderDelivery(source, ledger, sink, now = { clock }).deliver()
        assertTrue(sink.posted.isEmpty())
        assertEquals(2, sink.canceled.size)
    }

    @Test fun aFreshEmptyResultCancelsThePreviousNotificationAfterSubmission() = runTest {
        val source = Source()
        val ledger = Ledger()
        val sink = Sink()
        val delivery = AssignmentReminderDelivery(source, ledger, sink, now = { clock })
        delivery.deliver()
        clock += 8 * 3600
        source.read = { AssignmentReminderBatch(emptyList()) { true } }
        delivery.deliver()
        assertEquals(1, sink.posted.size)
        assertEquals(listOf(account.id), sink.canceled)
    }

    @Test fun networkFailureKeepsEligibilityForRetryAndDoesNotBlockAnotherAccount() = runTest {
        val source = Source()
        val ledger = Ledger()
        val sink = Sink()
        val other = account.copy(id = "second")
        source.owners += other
        source.read = { owner ->
            if (owner.id == account.id) throw IOException("Fixture network failure")
            AssignmentReminderBatch(listOf(item.copy(course = course.copy(accountId = owner.id), assignment = item.assignment.copy(accountId = owner.id)))) { true }
        }
        assertFalse(AssignmentReminderDelivery(source, ledger, sink, now = { clock }).deliver())
        assertEquals(listOf(other.id), sink.posted.map { it.first })
        assertNull(ledger.times[account.id])
    }

    @Test fun persistenceFailurePreventsPostingAndUncertainPostCannotSpamOnRetry() = runTest {
        val source = Source()
        val ledger = Ledger().apply { broken = true }
        val sink = Sink()
        val delivery = AssignmentReminderDelivery(source, ledger, sink, now = { clock })
        assertFalse(delivery.deliver())
        assertTrue(sink.posted.isEmpty())
        ledger.broken = false
        sink.broken = true
        assertFalse(delivery.deliver())
        assertEquals(clock, ledger.times[account.id])
        sink.broken = false
        delivery.deliver()
        assertTrue(sink.posted.isEmpty())
    }

    @Test fun oneWeekSimulationProducesTwentyOneGroupedAlertsThenStopsAtTheDeadline() = runTest {
        val source = Source()
        val ledger = Ledger()
        val sink = Sink()
        val due = clock + 7 * 86400
        val pending = item.copy(due = due, assignment = item.assignment.copy(due = due))
        source.read = { AssignmentReminderBatch(listOf(pending)) { true } }
        val delivery = AssignmentReminderDelivery(source, ledger, sink, now = { clock })
        repeat(7 * 24 * 4 + 1) {
            delivery.deliver()
            clock += 15 * 60
        }
        assertEquals(21, sink.posted.size)
        assertEquals(22, source.reads)
        assertEquals(listOf(account.id), sink.canceled)
    }

    @Test fun emptyScansAreThrottledAndAnEarlyTickDoesNotLoseAnotherEightHours() = runTest {
        val source = Source()
        val ledger = Ledger()
        val sink = Sink()
        source.read = { AssignmentReminderBatch(emptyList()) { true } }
        val delivery = AssignmentReminderDelivery(source, ledger, sink, now = { clock })
        delivery.deliver()
        val start = clock
        repeat(31) {
            clock += 15 * 60
            delivery.deliver()
        }
        assertEquals(1, source.reads)
        clock = start + 8 * 3600 - 1
        delivery.deliver()
        assertEquals(1, source.reads)
        source.read = { AssignmentReminderBatch(listOf(item)) { true } }
        clock += 15 * 60
        delivery.deliver()
        assertEquals(2, source.reads)
        assertEquals(1, sink.posted.size)
    }

    @Test fun cancellationIsNotTurnedIntoASuccessfulOrRetriableDelivery() = runTest {
        val source = Source()
        val ledger = Ledger()
        val sink = Sink()
        source.read = { throw CancellationException("Fixture cancellation") }
        val job = async { AssignmentReminderDelivery(source, ledger, sink, now = { clock }).deliver() }
        runCatching { job.await() }
        assertTrue(job.isCancelled)
        assertTrue(ledger.times.isEmpty())
        assertTrue(sink.posted.isEmpty())
    }
}
