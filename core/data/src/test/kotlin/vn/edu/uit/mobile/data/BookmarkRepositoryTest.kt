package vn.edu.uit.mobile.data

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.model.*

class BookmarkRepositoryTest {
    private class Accounts : AccountRepository {
        override val accounts = MutableStateFlow(listOf(Account("one", "https://courses.uit.edu.vn", 1, "First", PortalType.MOODLE), Account("two", "https://courses.uit.edu.vn", 2, "Second", PortalType.MOODLE)))
        override suspend fun remove(accountId: String) {
            accounts.value = accounts.value.filterNot { it.id == accountId }
        }
        override suspend fun saveSessions(sessions: List<PortalSession>) = Unit
    }
    private class Storage : StudyStorage {
        val values = mutableMapOf<String, String>()
        var beforeWrite: (() -> Unit)? = null
        var cancelWrite = false
        override fun read(key: String) = values[key]
        override fun write(key: String, value: String) {
            beforeWrite?.invoke()
            values[key] = value
            if (cancelWrite) throw CancellationException("Synthetic cancellation")
        }
        override fun remove(key: String) {
            values.remove(key)
        }
        override fun removeAccount(accountId: String) {
            values.keys.removeAll { it.startsWith(bookmarkDigest(accountId) + ":") }
        }
    }
    private val item = Bookmark("one", BookmarkKind.COURSE, "course:1", "Databases", "IT004", 1)

    @Test fun bookmarksSurviveRepositoryRecreationWithoutDuplicatingOrMixingAccounts() = runTest {
        val storage = Storage()
        val accounts = Accounts()
        val repository = LocalBookmarkRepository(storage, accounts)
        repository.save(item)
        repository.save(item.copy(accountId = "two"))
        val old = repository.collection.first().items.first { it.accountId == "one" }
        repository.save(item.copy(title = "Renamed course"))
        val restored = LocalBookmarkRepository(storage, accounts).collection.first()
        assertEquals(2, restored.items.size)
        assertEquals(old.savedAt, restored.items.first { it.accountId == "one" }.savedAt)
        assertEquals("Renamed course", restored.items.first { it.accountId == "one" }.title)
        repository.remove("one", item.id)
        assertEquals(listOf("two"), repository.collection.first().items.map { it.accountId })
    }

    @Test fun corruptAccountBucketIsRetainedAndDoesNotHideAnotherAccount() = runTest {
        val storage = Storage()
        val accounts = Accounts()
        val repository = LocalBookmarkRepository(storage, accounts)
        repository.save(item)
        repository.save(item.copy(accountId = "two"))
        val key = bookmarkDigest("one") + ":items"
        storage.values[key] = "corrupted metadata"
        val result = repository.collection.first()
        assertEquals(setOf("one"), result.failedAccounts)
        assertEquals(listOf("two"), result.items.map { it.accountId })
        assertTrue(runCatching { repository.save(item) }.isFailure)
        assertEquals("corrupted metadata", storage.values[key])
    }

    @Test fun removingAnAccountDuringALateWriteCannotLeaveItsBucket() = runTest {
        for (canceled in listOf(false, true)) {
            val accounts = Accounts()
            val storage = Storage().apply {
                cancelWrite = canceled
                beforeWrite = {
                    accounts.accounts.value = accounts.accounts.value.filterNot { it.id == "one" }
                    removeAccount("one")
                }
            }
            val repository = LocalBookmarkRepository(storage, accounts)
            assertTrue(runCatching { repository.save(item) }.isFailure)
            assertTrue(storage.values.isEmpty())
            assertTrue(repository.collection.first().items.isEmpty())
        }
    }

    @Test fun limitIsPerAccountAndExistingBookmarksCanStillBeUpdatedOrRemoved() = runTest {
        val repository = LocalBookmarkRepository(Storage(), Accounts())
        repeat(200) { index -> repository.save(item.copy(target = "course:${index + 1}", courseId = index + 1L)) }
        assertTrue(runCatching { repository.save(item.copy(target = "course:201", courseId = 201)) }.isFailure)
        repository.save(item.copy(title = "Updated"))
        repository.save(item.copy(accountId = "two"))
        assertEquals(201, repository.collection.first().items.size)
        repository.remove("one", item.id)
        repository.save(item.copy(target = "course:201", courseId = 201))
        assertEquals(201, repository.collection.first().items.size)
    }

    @Test fun sourceTypesAndDiscussionOriginsMustMatchTheOwningService() = runTest {
        val accounts = Accounts()
        accounts.accounts.value += Account("portal", "https://portal.uit.edu.vn", 3, "Synthetic", PortalType.UIT_PORTAL)
        val repository = LocalBookmarkRepository(Storage(), accounts)
        assertTrue(runCatching { repository.save(item.copy(accountId = "portal")) }.isFailure)
        val thread = Bookmark("one", BookmarkKind.DISCUSSION, "discussion:1:8", "Thread", "IT004", 1, "https://coursesold.uit.edu.vn/mod/forum/discuss.php?d=8")
        assertTrue(runCatching { repository.save(thread) }.isFailure)
        repository.save(thread.copy(url = "https://courses.uit.edu.vn/mod/forum/discuss.php?d=8"))
        assertEquals(1, repository.collection.first().items.size)
    }
}
