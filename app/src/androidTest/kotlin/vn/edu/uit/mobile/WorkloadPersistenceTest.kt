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
class WorkloadPersistenceTest {
    @Test fun encryptedSettingsAndEstimatesRestoreAndAccountRemovalIsScoped() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        val name = "workload-test-$suffix"
        val alias = "uit.workload.test.$suffix"
        val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        try {
            val storage = StudyPrivateStorage(context, name, alias)
            val sessions = object : SessionStore {
                override val sessions = MutableStateFlow(listOf("one", "two").map { PortalSession(Account(it, "https://courses.uit.edu.vn", 1, "Synthetic", PortalType.MOODLE), "synthetic") })
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
            val repo = LocalWorkloadRepository(storage, owners, snapshots)
            val policy = WorkloadPolicy(horizonDays = 14, deadlineLead = 90)
            repo.savePolicy("one", policy)
            val estimate = WorkloadEstimate(WorkloadRef("one", WorkloadKind.ASSIGNMENT, "assignment:1"), "Native private workload estimate", 90)
            repo.saveEstimate(estimate)
            repo.saveEstimate(estimate.copy(ref = estimate.ref.copy(accountId = "two")))
            val restored = LocalWorkloadRepository(StudyPrivateStorage(context, name, alias), owners, snapshots).accounts.first()
            assertEquals(policy, restored.first { it.account.id == "one" }.policy)
            assertTrue(restored.first { it.account.id == "one" }.configured)
            assertEquals(estimate, restored.first { it.account.id == "one" }.estimates.single())
            assertTrue(preferences.all.values.none { it.toString().contains(estimate.title) })
            owners.remove("one")
            val remaining = LocalWorkloadRepository(storage, owners, snapshots).accounts.first()
            assertEquals(listOf("two"), remaining.map { it.account.id })
            assertEquals(1, remaining.single().estimates.size)
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
