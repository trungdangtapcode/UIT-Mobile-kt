package vn.edu.uit.mobile.data

import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.model.*

class KnowledgeRepositoryTest {
    private class Storage : StudyStorage {
        val values = mutableMapOf<String, String>()
        var committedFailure = false
        var afterWrite: (() -> Unit)? = null
        override fun read(key: String) = values[key]
        override fun write(key: String, value: String) {
            values[key] = value
            afterWrite?.invoke()
            check(!committedFailure)
        }
        override fun remove(key: String) {
            values.remove(key)
        }
        override fun removeAccount(accountId: String) = error("No university mutations")
    }
    private class Owners : AccountRepository {
        override val accounts = MutableStateFlow(listOf("owner", "other").map { Account(it, "https://example.invalid", 1, "Synthetic", PortalType.MOODLE) })
        override suspend fun remove(accountId: String) = error("University mutation forbidden")
        override suspend fun saveSessions(sessions: List<PortalSession>) = error("University mutation forbidden")
    }

    @Test fun sourceCopiesAndCustomEdgeIdentitiesRoundTripOffline() = runTest {
        val storage = Storage()
        val owners = Owners()
        val io = StandardTestDispatcher(testScheduler)
        val repo = LocalKnowledgeRepository(storage, owners, io)
        val value = KnowledgeFixtures.record.copy(graph = KnowledgeFixtures.graph.copy(edges = KnowledgeFixtures.graph.edges.map { it.copy(id = "custom-${it.id}") }))
        val saved = repo.save(null, value)
        val restored = LocalKnowledgeRepository(storage, owners, io).collections.first().first { it.accountId == "owner" }.records.single()
        assertEquals(saved, restored)
        assertEquals(1L, restored.revision)
        assertEquals(KnowledgeFixtures.TEXT, restored.sources.single().text)
    }

    @Test fun staleRevisionsCannotOverwriteOrDeleteANewerMap() = runTest {
        val repo = LocalKnowledgeRepository(Storage(), Owners(), StandardTestDispatcher(testScheduler))
        val first = repo.save(null, KnowledgeFixtures.record)
        val second = repo.save(first, first.copy(graph = first.graph.copy(title = "Reviewed graph")))
        assertTrue(runCatching { repo.save(first, first.copy(graph = first.graph.copy(title = "Stale title"))) }.isFailure)
        assertTrue(runCatching { repo.remove(first) }.isFailure)
        assertEquals(second, repo.collections.first().first().records.single())
    }

    @Test fun anUncertainCommittedWriteIsExactlyIdempotent() = runTest {
        val storage = Storage()
        val repo = LocalKnowledgeRepository(storage, Owners(), StandardTestDispatcher(testScheduler))
        storage.committedFailure = true
        assertTrue(runCatching { repo.save(null, KnowledgeFixtures.record) }.isFailure)
        storage.committedFailure = false
        assertEquals(1L, repo.save(null, KnowledgeFixtures.record).revision)
        assertEquals(1, repo.collections.first().first().records.size)
    }

    @Test fun removedOwnerLateWritesArePurgedAndOtherOwnerSurvives() = runTest {
        val storage = Storage()
        val owners = Owners()
        val repo = LocalKnowledgeRepository(storage, owners, StandardTestDispatcher(testScheduler))
        repo.save(null, KnowledgeFixtures.record.copy(accountId = "other"))
        storage.afterWrite = { owners.accounts.value = owners.accounts.value.filter { it.id != "owner" } }
        assertTrue(runCatching { repo.save(null, KnowledgeFixtures.record) }.isFailure)
        assertEquals(1, storage.values.size)
        assertEquals("other", repo.collections.first().single().accountId)
        assertTrue(runCatching { repo.save(null, KnowledgeFixtures.record) }.isFailure)
    }

    @Test fun corruptBucketsAreIsolatedAndCapacityIsCheckedBeforeWriting() = runTest {
        val storage = Storage()
        val repo = LocalKnowledgeRepository(storage, Owners(), StandardTestDispatcher(testScheduler))
        repeat(12) { repo.save(null, KnowledgeFixtures.record.copy(id = "map-$it")) }
        val before = storage.values.toMap()
        assertTrue(runCatching { repo.save(null, KnowledgeFixtures.record.copy(id = "too-many")) }.isFailure)
        assertEquals(before, storage.values)
        storage.values[bookmarkDigest("other") + ":knowledge"] = "corrupt"
        val data = repo.collections.first()
        assertEquals(12, data.first { it.accountId == "owner" }.records.size)
        assertTrue(data.first { it.accountId == "other" }.failed)
    }
}
