package vn.edu.uit.mobile.data

import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import vn.edu.uit.mobile.common.SessionStore
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.model.*

class ReadingAccountCleanupTest {
    @get:Rule val folder = TemporaryFolder()
    private class Sessions(private val cancelAfterRemoval: Boolean = false) : SessionStore {
        override val sessions = MutableStateFlow(listOf("one", "two").map { PortalSession(Account(it, "https://example.invalid", 1, "Synthetic", PortalType.MOODLE), "synthetic") })
        override suspend fun save(sessions: List<PortalSession>) = error("Session writes are not exercised")
        override suspend fun remove(accountId: String) {
            sessions.value = sessions.value.filterNot { it.account.id == accountId }
            if (cancelAfterRemoval) currentCoroutineContext().cancel()
        }
    }
    private class Snapshots : SnapshotStore {
        val deleted = mutableListOf<String>()
        override fun observe(accountId: String) = flowOf(PortalSnapshot())
        override suspend fun read(accountId: String) = PortalSnapshot()
        override suspend fun write(accountId: String, snapshot: PortalSnapshot) = error("Snapshot writes forbidden")
        override suspend fun delete(accountId: String) {
            deleted += accountId
        }
    }
    private fun material(owner: String) = File(folder.root, "${accountFolder(owner)}/synthetic.pdf").apply {
        parentFile!!.mkdirs()
        writeText("synthetic")
    }

    @Test fun localReadingCleanupStillRunsWhenTheRemovalCoroutineIsCanceled() = runTest {
        val sessions = Sessions(true)
        val snapshots = Snapshots()
        val callbacks = mutableListOf<String>()
        val removed = material("one")
        val kept = material("two")
        val repo = VaultAccountRepository(sessions, snapshots, folder.root) { callbacks += it }
        val job = launch { repo.remove("one") }
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(listOf("one"), callbacks)
        assertEquals(listOf("one"), snapshots.deleted)
        assertFalse(removed.exists())
        assertTrue(kept.exists())
    }

    @Test fun failedReadingCleanupDoesNotPreventOtherOwnedCachesBeingPurged() = runTest {
        val sessions = Sessions()
        val snapshots = Snapshots()
        val removed = material("one")
        val kept = material("two")
        val repo = VaultAccountRepository(sessions, snapshots, folder.root) { error("Synthetic encrypted-store failure") }
        assertTrue(runCatching { repo.remove("one") }.isFailure)
        assertEquals(listOf("one"), snapshots.deleted)
        assertFalse(removed.exists())
        assertTrue(kept.exists())
        assertEquals(listOf("two"), sessions.sessions.value.map { it.account.id })
    }
}
