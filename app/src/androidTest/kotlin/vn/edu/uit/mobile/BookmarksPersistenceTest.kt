package vn.edu.uit.mobile

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.KeyStore
import java.util.UUID
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import vn.edu.uit.mobile.common.SessionStore
import vn.edu.uit.mobile.data.*
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.model.*

/** Real Android Keystore persistence, using synthetic identities and no network requests. */
@RunWith(AndroidJUnit4::class)
class BookmarksPersistenceTest {
    @Test fun encryptedBookmarksRestoreAndAccountRemovalPreservesOtherAccounts() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        val preferenceName = "bookmarks-test-$suffix"
        val alias = "uit.bookmarks.test.$suffix"
        val preferences = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
        try {
            val storage = StudyPrivateStorage(context, preferenceName, alias)
            val sessions = object : SessionStore {
                override val sessions = MutableStateFlow(listOf("one", "two").map { id -> PortalSession(Account(id, "https://courses.uit.edu.vn", 1, "Synthetic", PortalType.MOODLE), "synthetic") })
                override suspend fun save(sessions: List<PortalSession>) {
                    this.sessions.value = sessions
                }
                override suspend fun remove(accountId: String) {
                    sessions.value = sessions.value.filterNot { it.account.id == accountId }
                }
            }
            val snapshots = object : SnapshotStore {
                override fun observe(accountId: String) = flowOf(PortalSnapshot())
                override suspend fun read(accountId: String) = PortalSnapshot()
                override suspend fun write(accountId: String, snapshot: PortalSnapshot) = Unit
                override suspend fun delete(accountId: String) = Unit
            }
            val accounts = VaultAccountRepository(sessions, snapshots, onRemove = storage::removeAccount)
            val repository = LocalBookmarkRepository(storage, accounts)
            val original = Bookmark("one", BookmarkKind.COURSE, "course:1", "Native test bookmark", "IT004", 1)
            repository.save(original)
            repository.save(original.copy(accountId = "two"))
            val restored = LocalBookmarkRepository(StudyPrivateStorage(context, preferenceName, alias), accounts).collection.first()
            assertEquals(2, restored.items.size)
            assertEquals(setOf("one", "two"), restored.items.map { it.accountId }.toSet())
            assertEquals(2, preferences.all.size)
            assertTrue(preferences.all.values.none { it.toString().contains(original.title) })
            accounts.remove("one")
            assertEquals(listOf("two"), LocalBookmarkRepository(storage, accounts).collection.first().items.map { it.accountId })
            assertEquals(1, preferences.all.size)
        } finally {
            preferences.edit().clear().commit()
            context.deleteSharedPreferences(preferenceName)
            KeyStore.getInstance("AndroidKeyStore").apply {
                load(null)
                deleteEntry(alias)
            }
        }
    }
}
