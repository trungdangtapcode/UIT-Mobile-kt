package vn.edu.uit.mobile.data

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.model.*

class WorkloadRepositoryTest {
    private class Owners : AccountRepository {
        override val accounts = MutableStateFlow(listOf(Account("one", "https://courses.uit.edu.vn", 1, "Study", PortalType.MOODLE), Account("two", "https://portal.uit.edu.vn", 1, "Calendar", PortalType.UIT_PORTAL)))
        override suspend fun remove(accountId: String) {
            accounts.value = accounts.value.filterNot { it.id == accountId }
        }
        override suspend fun saveSessions(sessions: List<PortalSession>) = Unit
    }
    private class Storage : StudyStorage {
        val values = mutableMapOf<String, String>()
        var beforeWrite: (() -> Unit)? = null
        var cancel = false
        override fun read(key: String) = values[key]
        override fun write(key: String, value: String) {
            beforeWrite?.invoke()
            values[key] = value
            if (cancel) throw CancellationException("Synthetic cancellation")
        }
        override fun remove(key: String) {
            values.remove(key)
        }
        override fun removeAccount(accountId: String) {
            values.keys.removeAll { it.startsWith(bookmarkDigest(accountId) + ":") }
        }
    }
    private class Snapshots : SnapshotStore {
        var fail = false
        val entries = MutableStateFlow(mapOf("one" to PortalSnapshot(updatedAt = 100), "two" to PortalSnapshot(updatedAt = 200)))
        override fun observe(accountId: String): Flow<PortalSnapshot> = if (fail && accountId == "one") flow { error("Synthetic source failure") } else entries.map { it[accountId] ?: PortalSnapshot() }
        override suspend fun read(accountId: String) = entries.value[accountId] ?: PortalSnapshot()
        override suspend fun write(accountId: String, snapshot: PortalSnapshot) {
            entries.value = entries.value + (accountId to snapshot)
        }
        override suspend fun delete(accountId: String) {
            entries.value = entries.value - accountId
        }
    }
    private val estimate = WorkloadEstimate(WorkloadRef("one", WorkloadKind.ASSIGNMENT, "assignment:1"), "Personal task", 90)

    @Test fun settingsAndEstimatesRestoreWithExplicitConfirmationAndPerOwnerSnapshots() = runTest {
        val storage = Storage()
        val owners = Owners()
        val snapshots = Snapshots()
        val repo = LocalWorkloadRepository(storage, owners, snapshots)
        repo.saveEstimate(estimate)
        assertFalse(repo.accounts.first().first { it.account.id == "one" }.configured)
        val policy = WorkloadPolicy(horizonDays = 14, calendarAccountId = "two", travelBuffer = 25)
        repo.savePolicy("one", policy)
        val exam = WorkloadEstimate(WorkloadRef("two", WorkloadKind.EXAM, "exam:EXAM:2026-10-26:1"), "Exam", 120, 90)
        repo.saveEstimate(exam)
        val restored = LocalWorkloadRepository(storage, owners, snapshots).accounts.first()
        assertEquals(policy, restored.first { it.account.id == "one" }.policy)
        assertTrue(restored.first { it.account.id == "one" }.configured)
        assertEquals(listOf(estimate), restored.first { it.account.id == "one" }.estimates)
        assertEquals(listOf(exam), restored.first { it.account.id == "two" }.estimates)
        assertEquals(200, restored.first { it.account.id == "two" }.snapshot.updatedAt)
        repo.saveEstimate(estimate.copy(remainingMinutes = 30))
        assertEquals(1, repo.accounts.first().first().estimates.size)
        repo.removeEstimate(estimate.ref)
        assertTrue(repo.accounts.first().first().estimates.isEmpty())
    }

    @Test fun corruptPlanningBucketsAndFailingSourcesStayDistinctAndNeverOverwriteData() = runTest {
        val storage = Storage()
        val owners = Owners()
        val snapshots = Snapshots()
        val repo = LocalWorkloadRepository(storage, owners, snapshots)
        repo.saveEstimate(estimate)
        val key = bookmarkDigest("one") + ":workload"
        val malformed = storage.values.getValue(key).replace("\"remaining\":90", "\"remaining\":\"invalid\"")
        storage.values[key] = malformed
        val data = repo.accounts.first()
        assertTrue(data.first { it.account.id == "one" }.storageFailed)
        assertFalse(data.first { it.account.id == "two" }.storageFailed)
        assertTrue(runCatching { repo.savePolicy("one", WorkloadPolicy()) }.isFailure)
        assertEquals(malformed, storage.values[key])
        snapshots.fail = true
        assertTrue(repo.accounts.first().first { it.account.id == "one" }.sourceFailed)
        assertEquals(200, repo.accounts.first().first { it.account.id == "two" }.snapshot.updatedAt)
    }

    @Test fun lateWritesCannotRestoreRemovedOwnerDataEvenUnderCancellation() = runTest {
        for (cancel in listOf(false, true)) {
            val owners = Owners()
            val storage = Storage()
            val repo = LocalWorkloadRepository(storage, owners, Snapshots())
            storage.beforeWrite = { owners.accounts.value = owners.accounts.value.filterNot { it.id == "one" } }
            storage.cancel = cancel
            assertTrue(runCatching { repo.saveEstimate(estimate) }.isFailure)
            assertTrue(storage.values.isEmpty())
            assertEquals(listOf("two"), repo.accounts.first().map { it.account.id })
        }
    }

    @Test fun ownersServiceKindsAndCalendarSelectionsAreValidated() = runTest {
        val owners = Owners()
        val repo = LocalWorkloadRepository(Storage(), owners, Snapshots())
        assertTrue(runCatching { repo.saveEstimate(estimate.copy(ref = estimate.ref.copy(accountId = "two"))) }.isFailure)
        assertTrue(runCatching { repo.saveEstimate(WorkloadEstimate(WorkloadRef("one", WorkloadKind.EXAM, "exam:1"), "Wrong service", 20)) }.isFailure)
        assertTrue(runCatching { repo.savePolicy("one", WorkloadPolicy(calendarAccountId = "one")) }.isFailure)
        assertTrue(runCatching { repo.savePolicy("one", WorkloadPolicy(calendarAccountId = "removed")) }.isFailure)
        owners.remove("one")
        assertTrue(runCatching { repo.saveEstimate(estimate) }.isFailure)
    }

    @Test fun strictPersonalBlockReadsReportPartialCorruptionWhileLegacyReadsRemainCompatible() {
        val storage = Storage()
        val blocks = PrivateFreeTimeBlockStorage(storage)
        blocks.save(setOf("one"), listOf(PersonalTimeBlock("valid", "Personal", 100, 200)))
        val key = storage.values.keys.single()
        storage.values[key] = storage.values.getValue(key).replace("\"blocks\":[", "\"blocks\":[{\"id\":\"broken\"},")
        assertEquals(1, blocks.load(setOf("one")).size)
        assertTrue(runCatching { blocks.loadStrict(setOf("one")) }.isFailure)
        assertTrue(storage.values.containsKey(key))
    }
}
