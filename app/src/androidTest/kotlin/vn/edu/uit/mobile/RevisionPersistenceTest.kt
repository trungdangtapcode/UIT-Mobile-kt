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

@RunWith(AndroidJUnit4::class)
class RevisionPersistenceTest {
    @Test fun encryptedRevisionHistoryRestoresAndRemovalIsOwnerScoped() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        val name = "revision-test-$suffix"
        val alias = "uit.revision.test.$suffix"
        val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        try {
            val storage = StudyPrivateStorage(context, name, alias)
            val sessions = object : SessionStore {
                override val sessions = MutableStateFlow(listOf("one", "two").map { PortalSession(Account(it, "https://portal.uit.edu.vn", 1, "Synthetic", PortalType.UIT_PORTAL), "synthetic") })
                override suspend fun save(sessions: List<PortalSession>) {
                    this.sessions.value = sessions
                }
                override suspend fun remove(accountId: String) {
                    sessions.value = sessions.value.filterNot { it.account.id == accountId }
                }
            }
            val snapshots = object : SnapshotStore {
                override fun observe(accountId: String) = flowOf(PortalSnapshot(updatedAt = 100))
                override suspend fun read(accountId: String) = PortalSnapshot(updatedAt = 100)
                override suspend fun write(accountId: String, snapshot: PortalSnapshot) = Unit
                override suspend fun delete(accountId: String) = Unit
            }
            val owners = VaultAccountRepository(sessions, snapshots, onRemove = storage::removeAccount)
            val repo = LocalRevisionRepository(storage, owners)
            fun workspace(id: String) = RevisionWorkspace(listOf(RevisionPlan(WorkloadRef(id, WorkloadKind.EXAM, "exam:synthetic"), "Native private revision", 200000, listOf(StudyTopic("topic", "Synthetic preparation", 30)), listOf(RevisionSession("completed", "topic", 36000, 37800, 30, RevisionStatus.COMPLETED)))), RevisionOptions(120, 20, 5))
            repo.replace("one", RevisionWorkspace(), workspace("one"))
            repo.replace("two", RevisionWorkspace(), workspace("two"))
            val restored = LocalRevisionRepository(StudyPrivateStorage(context, name, alias), owners).accounts.first()
            assertEquals(workspace("one"), restored.first { it.accountId == "one" }.workspace)
            assertTrue(preferences.all.values.none { it.toString().contains("Native private revision") })
            owners.remove("one")
            val remaining = LocalRevisionRepository(storage, owners).accounts.first()
            assertEquals(listOf("two"), remaining.map { it.accountId })
            assertEquals(workspace("two"), remaining.single().workspace)
            assertEquals(1, preferences.all.size)
        } finally {
            preferences.edit().clear().commit()
            context.deleteSharedPreferences(name)
            KeyStore.getInstance("AndroidKeyStore").apply {
                load(null)
                deleteEntry(alias)
            }
        }
    }
}
