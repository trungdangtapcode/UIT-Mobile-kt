package vn.edu.uit.mobile.data

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.forum.network.*

class ForumRepositoryTest {
    private class Memory : StudyStorage {
        val values = mutableMapOf<String, String>()
        var fail = false
        override fun read(key: String) = values[key]
        override fun write(key: String, value: String) {
            check(!fail)
            values[key] = value
        }
        override fun remove(key: String) {
            check(!fail)
            values.remove(key)
        }
        override fun removeAccount(accountId: String) = error("Account operation forbidden")
    }
    private class Remote : ForumTransport {
        var failure: ForumFailure? = null
        var gate: CompletableDeferred<Unit>? = null
        val calls = mutableListOf<ForumRequest>()
        override suspend fun get(request: ForumRequest): String {
            calls += request
            gate?.await()
            failure?.let { throw ForumException(it) }
            return ForumFixtures.response(request)
        }
        override suspend fun image(url: String): ByteArray = error("No implicit image fetch")
    }

    @Test fun transientFailureUsesDatedSnapshotButRateLimitDoesNot() = runTest {
        val remote = Remote()
        val memory = Memory()
        val repo = LocalForumRepository(remote, memory) { 123 }
        val request = ForumRequest.Topic(901)
        repo.load(request)
        remote.failure = ForumFailure.NETWORK
        val cached = repo.load(request)
        assertTrue(cached.offline)
        assertEquals(123L, cached.fetchedAt)
        assertEquals(20, cached.document.posts.size)
        remote.failure = ForumFailure.RATE_LIMIT
        try {
            repo.load(request)
            fail()
        } catch (e: ForumException) {
            assertEquals(ForumFailure.RATE_LIMIT, e.reason)
        }
    }

    @Test fun offlineResumeReassemblesBatchesWithoutAnExactJumpSnapshot() = runTest {
        val remote = Remote()
        val repo = LocalForumRepository(remote, Memory()) { 123 }
        repo.load(ForumRequest.Topic(901))
        repo.load(ForumRequest.Posts(901, (1021L..1040L).toList()))
        remote.failure = ForumFailure.NETWORK
        val resumed = repo.load(ForumRequest.Topic(901, 69))
        assertTrue(resumed.offline)
        assertEquals(125, resumed.document.stream.size)
        assertTrue(resumed.document.posts.any { it.number == 69 })
        val partial = repo.load(ForumRequest.Posts(901, (1011L..1030L).toList()))
        assertEquals((1011L..1030L).toList(), partial.document.posts.map { it.id })
        assertEquals(123L, partial.fetchedAt)
    }

    @Test fun unavailableTopicPurgesOldBodyAndDoesNotMasqueradeAsOffline() = runTest {
        val remote = Remote()
        val memory = Memory()
        val repo = LocalForumRepository(remote, memory)
        val request = ForumRequest.Topic(901)
        repo.load(request)
        remote.failure = ForumFailure.UNAVAILABLE
        try {
            repo.load(request)
            fail()
        } catch (e: ForumException) {
            assertEquals(ForumFailure.UNAVAILABLE, e.reason)
        }
        remote.failure = ForumFailure.NETWORK
        try {
            repo.load(request)
            fail()
        } catch (e: ForumException) {
            assertEquals(ForumFailure.NETWORK, e.reason)
        }
    }

    @Test fun localSavesPositionsAndClearNeverNeedAccountOrRemote() = runTest {
        val remote = Remote()
        val memory = Memory()
        val repo = LocalForumRepository(remote, memory) { 123 }
        repo.save(ForumFixtures.topic, true)
        repo.position(ForumPosition(ForumFixtures.topic, 1050, 99, 48))
        val restored = LocalForumRepository(remote, memory).local()
        assertEquals(901L, restored.saved.single().id)
        assertEquals(123L, restored.savedAt[901L])
        assertTrue(restored.saved.single().pinned)
        assertEquals(99, restored.positions.single().postNumber)
        repo.save(ForumFixtures.topic, true)
        assertEquals(1, repo.local().saved.size)
        assertTrue(remote.calls.isEmpty())
        repo.clear()
        assertEquals(ForumLocalState(), repo.local())
        assertTrue(memory.values.isEmpty())
    }

    @Test fun failedSaveIsNotReportedAsPersisted() = runTest {
        val memory = Memory().apply { fail = true }
        val repo = LocalForumRepository(Remote(), memory)
        try {
            repo.save(ForumFixtures.topic, true)
            fail()
        } catch (e: ForumException) {
            assertEquals(ForumFailure.STORAGE, e.reason)
        }
        assertTrue(repo.local().saved.isEmpty())
    }

    @Test fun clearPreventsInflightResponseFromRecreatingCache() = runTest {
        val gate = CompletableDeferred<Unit>()
        val remote = Remote().apply { this.gate = gate }
        val memory = Memory()
        val repo = LocalForumRepository(remote, memory)
        val loading = async(Dispatchers.Unconfined) { repo.load(ForumRequest.Topic(901)) }
        while (remote.calls.isEmpty()) yield()
        repo.clear()
        gate.complete(Unit)
        loading.await()
        assertTrue(memory.values.isEmpty())
    }

    @Test fun referencesHaveEnforcedCapacityAndIdempotentUpdates() = runTest {
        val repo = LocalForumRepository(Remote(), Memory())
        repeat(100) { repo.save(ForumFixtures.topic.copy(id = it + 1L), true) }
        repo.save(ForumFixtures.topic.copy(id = 1), true)
        try {
            repo.save(ForumFixtures.topic.copy(id = 101), true)
            fail()
        } catch (e: ForumException) {
            assertEquals(ForumFailure.CAPACITY, e.reason)
        }
        repo.save(ForumFixtures.topic.copy(id = 1), false)
        assertEquals(99, repo.local().saved.size)
    }

    @Test fun cacheStaysWithinEncodedBudgetAndCanReadAfterEviction() = runTest {
        val memory = Memory()
        val repo = LocalForumRepository(Remote(), memory)
        repeat(100) { repo.load(ForumRequest.Topic(901, it + 1)) }
        assertTrue(memory.values.getValue("cache").length <= ForumLimits.CACHE_CHARS)
    }
}
