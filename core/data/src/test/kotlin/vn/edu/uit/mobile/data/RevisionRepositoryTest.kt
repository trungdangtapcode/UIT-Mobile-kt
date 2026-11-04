package vn.edu.uit.mobile.data

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.model.*

class RevisionRepositoryTest {
    private class Owners : AccountRepository {
        override val accounts = MutableStateFlow(listOf(Account("one", "https://portal.uit.edu.vn", 1, "One", PortalType.UIT_PORTAL), Account("two", "https://daa.uit.edu.vn", 1, "Two", PortalType.DAA), Account("study", "https://courses.uit.edu.vn", 1, "Study", PortalType.MOODLE)))
        override suspend fun remove(accountId: String) {
            accounts.value = accounts.value.filterNot { it.id == accountId }
        }
        override suspend fun saveSessions(sessions: List<PortalSession>) = Unit
    }
    private class Storage : StudyStorage {
        val values = mutableMapOf<String, String>()
        var onWrite: (() -> Unit)? = null
        var cancel = false
        override fun read(key: String) = values[key]
        override fun write(key: String, value: String) {
            onWrite?.invoke()
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
    private fun value(id: String = "one") = RevisionWorkspace(listOf(RevisionPlan(WorkloadRef(id, WorkloadKind.EXAM, "exam:CS:2026-11-02:1:07:30"), "Example exam", 200000, listOf(StudyTopic("topic", "Transactions", 90)), listOf(RevisionSession("done", "topic", 36000, 37800, 30, RevisionStatus.COMPLETED), RevisionSession("lock", "topic", 90000, 93600, 60, locked = true)))), RevisionOptions(90, 15, 20))

    @Test fun settingsTopicOrderHistoryAndLocksRestoreWithinTheirOwner() = runTest {
        val storage = Storage()
        val owners = Owners()
        val repo = LocalRevisionRepository(storage, owners)
        repo.replace("one", RevisionWorkspace(), value())
        repo.replace("two", RevisionWorkspace(), value("two"))
        val restored = LocalRevisionRepository(storage, owners).accounts.first()
        assertEquals(listOf("one", "two"), restored.map { it.accountId })
        assertEquals(value(), restored.first().workspace)
        assertEquals(value("two"), restored.last().workspace)
        storage.removeAccount("one")
        owners.remove("one")
        assertEquals(listOf("two"), repo.accounts.first().map { it.accountId })
        assertEquals(1, storage.values.size)
    }

    @Test fun optimisticCompareAndReplaceRejectsLostUpdatesInsteadOfOverwritingProgress() = runTest {
        val repo = LocalRevisionRepository(Storage(), Owners())
        repo.replace("one", RevisionWorkspace(), value())
        assertTrue(runCatching { repo.replace("one", RevisionWorkspace(), RevisionWorkspace()) }.isFailure)
        assertEquals(value(), repo.accounts.first().first().workspace)
        val next = value().copy(options = RevisionOptions(120, 20, 30))
        repo.replace("one", value(), next)
        assertEquals(next, repo.accounts.first().first().workspace)
    }

    @Test fun malformedBucketsRemainIntactAndOtherOwnersStayAvailable() = runTest {
        val storage = Storage()
        val repo = LocalRevisionRepository(storage, Owners())
        repo.replace("one", RevisionWorkspace(), value())
        repo.replace("two", RevisionWorkspace(), value("two"))
        val key = bookmarkDigest("one") + ":revision"
        val broken = storage.values.getValue(key).replace("\"effort\":30", "\"effort\":\"bad\"")
        storage.values[key] = broken
        val restored = repo.accounts.first()
        assertTrue(restored.first().failed)
        assertFalse(restored.last().failed)
        assertTrue(runCatching { repo.replace("one", RevisionWorkspace(), value()) }.isFailure)
        assertEquals(broken, storage.values[key])
    }

    @Test fun disconnectedCrossOwnerAndMoodleWritesAreRejected() = runTest {
        val owners = Owners()
        val repo = LocalRevisionRepository(Storage(), owners)
        assertTrue(runCatching { repo.replace("two", RevisionWorkspace(), value()) }.isFailure)
        assertTrue(runCatching { repo.replace("study", RevisionWorkspace(), value("study")) }.isFailure)
        owners.remove("one")
        assertTrue(runCatching { repo.replace("one", RevisionWorkspace(), value()) }.isFailure)
    }

    @Test fun lateCanceledWritesCannotRestoreRemovedOwnerBuckets() = runTest {
        for (cancel in listOf(false, true)) {
            val storage = Storage()
            val owners = Owners()
            val repo = LocalRevisionRepository(storage, owners)
            storage.onWrite = { owners.accounts.value = owners.accounts.value.filterNot { it.id == "one" } }
            storage.cancel = cancel
            assertTrue(runCatching { repo.replace("one", RevisionWorkspace(), value()) }.isFailure)
            assertTrue(storage.values.isEmpty())
        }
    }
}
