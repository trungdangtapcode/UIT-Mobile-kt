package vn.edu.uit.mobile.data

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.model.*

class PracticeRepositoryTest {
    private class Owners : AccountRepository {
        override val accounts = MutableStateFlow(listOf(Account("owner", "https://example.invalid", 1, "Synthetic", PortalType.MOODLE), Account("other", "https://example.invalid", 2, "Synthetic other", PortalType.MOODLE)))
        override suspend fun saveSessions(sessions: List<PortalSession>) = error("Account writes forbidden")
        override suspend fun remove(accountId: String) = error("Account writes forbidden")
    }
    private class Storage : StudyStorage {
        val values = mutableMapOf<String, String>()
        var fail = false
        var afterWrite: (() -> Unit)? = null
        override fun read(key: String) = values[key]
        override fun write(key: String, value: String) {
            check(!fail)
            values[key] = value
            afterWrite?.invoke()
        }
        override fun remove(key: String) {
            values.remove(key)
        }
        override fun removeAccount(accountId: String) {
            values.keys.removeAll { it.startsWith(bookmarkDigest(accountId) + ":") }
        }
    }

    @Test fun sourceCopiesQuestionEditsAndProgressRestoreWithOwnerIsolation() = runTest {
        val storage = Storage()
        val owners = Owners()
        val repo = LocalPracticeRepository(storage, owners) { 100 }
        val set = PracticeFixtures.set()
        repo.save(set)
        repo.save(PracticeFixtures.set(owner = "other"))
        val wrong = repo.attempt(set, "q1", null, PracticeAttempt("attempt-one", selected = 1))
        assertEquals(0, wrong.progress.single().successful)
        val correct = repo.attempt(set, "q1", wrong.progress.single(), PracticeAttempt("attempt-two", selected = 0))
        assertEquals(2, correct.progress.single().attempts)
        assertEquals(1, correct.progress.single().successful)
        val restored = LocalPracticeRepository(storage, owners).collections.first()
        assertEquals(correct, restored.first().records.single())
        assertTrue(restored.last().records.single().progress.isEmpty())
        storage.removeAccount("owner")
        owners.accounts.value = owners.accounts.value.filterNot { it.id == "owner" }
        assertEquals(listOf("other"), repo.collections.first().map { it.accountId })
    }

    @Test fun duplicateSaveAndImmediateAttemptRetriesDoNotDuplicateSetsOrProgress() = runTest {
        val repo = LocalPracticeRepository(Storage(), Owners()) { 100 }
        val set = PracticeFixtures.set()
        assertEquals(repo.save(set), repo.save(set.copy(id = "another-local-id")))
        val value = PracticeAttempt("one", selected = 0)
        val first = repo.attempt(set, "q1", null, value)
        assertEquals(first, repo.attempt(set, "q1", null, value))
        assertEquals(1, repo.collections.first().first().records.size)
        assertTrue(runCatching { repo.attempt(set, "q1", null, PracticeAttempt("one", selected = 1)) }.isFailure)
    }

    @Test fun staleProgressAndDeletionCannotOverwriteNewerAnswers() = runTest {
        val repo = LocalPracticeRepository(Storage(), Owners()) { 100 }
        val set = PracticeFixtures.set()
        val original = repo.save(set)
        val latest = repo.attempt(set, "q1", null, PracticeAttempt("one", selected = 0))
        assertTrue(runCatching { repo.attempt(set, "q1", null, PracticeAttempt("two", selected = 1)) }.isFailure)
        assertTrue(runCatching { repo.remove(original) }.isFailure)
        assertEquals(latest, repo.collections.first().first().records.single())
        repo.remove(latest)
        assertTrue(repo.collections.first().first().records.isEmpty())
    }

    @Test fun failedWriteAfterCommitCanBeRetriedWithoutDoubleCounting() = runTest {
        val storage = Storage()
        val repo = LocalPracticeRepository(storage, Owners()) { 100 }
        val set = PracticeFixtures.set()
        repo.save(set)
        storage.afterWrite = { error("Synthetic uncertain completion") }
        val attempt = PracticeAttempt("one", selected = 0)
        assertTrue(runCatching { repo.attempt(set, "q1", null, attempt) }.isFailure)
        storage.afterWrite = null
        assertEquals(1, repo.attempt(set, "q1", null, attempt).progress.single().attempts)
    }

    @Test fun corruptTypedBucketsRemainIntactWithoutHidingOtherOwners() = runTest {
        val storage = Storage()
        val repo = LocalPracticeRepository(storage, Owners())
        repo.save(PracticeFixtures.set())
        repo.save(PracticeFixtures.set(owner = "other"))
        val key = bookmarkDigest("owner") + ":practice"
        val malformed = storage.values.getValue(key).replace("\"correctIndex\":0", "\"correctIndex\":\"0\"")
        storage.values[key] = malformed
        val data = repo.collections.first()
        assertTrue(data.first().failed)
        assertFalse(data.last().failed)
        assertTrue(runCatching { repo.save(PracticeFixtures.set()) }.isFailure)
        assertEquals(malformed, storage.values[key])
    }

    @Test fun lateAndCanceledWritesCannotRestoreRemovedAccountData() = runTest {
        for (cancel in listOf(false, true)) {
            val storage = Storage()
            val owners = Owners()
            val repo = LocalPracticeRepository(storage, owners)
            storage.afterWrite = {
                owners.accounts.value = owners.accounts.value.filterNot { it.id == "owner" }
                if (cancel)throw CancellationException("Synthetic cancellation")
            }
            assertTrue(runCatching { repo.save(PracticeFixtures.set()) }.isFailure)
            assertTrue(storage.values.isEmpty())
        }
    }

    @Test fun flashcardRecallIsExplicitAndAnAnswerCannotMutateQuestionContent() = runTest {
        val repo = LocalPracticeRepository(Storage(), Owners()) { 100 }
        val set = PracticeFixtures.set()
        repo.save(set)
        assertTrue(runCatching { repo.attempt(set, "q2", null, PracticeAttempt("bad", selected = 0)) }.isFailure)
        val done = repo.attempt(set, "q2", null, PracticeAttempt("recall", recalled = false))
        assertFalse(done.progress.single().lastOutcome)
        assertEquals(-1, done.progress.single().lastChoice)
        assertTrue(runCatching { repo.attempt(set.copy(title = "Stale edited set"), "q1", null, PracticeAttempt("new", selected = 0)) }.isFailure)
        assertEquals(set, done.set)
    }

    @Test fun limitsAndOwnerValidationFailBeforeAnExistingSetIsReplaced() = runTest {
        val owners = Owners()
        val repo = LocalPracticeRepository(Storage(), owners)
        repeat(20) { repo.save(PracticeFixtures.set(id = "set-$it").copy(title = "Set $it")) }
        assertTrue(runCatching { repo.save(PracticeFixtures.set(id = "overflow").copy(title = "Overflow")) }.isFailure)
        assertEquals(20, repo.collections.first().first().records.size)
        assertTrue(runCatching { repo.save(PracticeFixtures.set(owner = "disconnected")) }.isFailure)
    }
}
