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

/** Synthetic local storage only: no university transport or real account session. */
@RunWith(AndroidJUnit4::class)
class PracticePersistenceTest {
    @Test fun encryptedQuestionsAndProgressRestoreAndRemovalIsOwnerScoped() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        val name = "practice-test-$suffix"
        val alias = "uit.practice.test.$suffix"
        val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        try {
            val storage = StudyPrivateStorage(context, name, alias)
            val sessions = object : SessionStore {
                override val sessions = MutableStateFlow(listOf("one", "two").map { PortalSession(Account(it, "https://example.invalid", 1, "Synthetic", PortalType.MOODLE), "synthetic") })
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
            val owners = VaultAccountRepository(sessions, snapshots, onRemove = storage::removeAccount)
            val source = StudySource("synthetic", "Private synthetic lesson", "Atomicity means all operations complete or none do.")
            val question = PracticeQuestion("q1", PracticeKind.MCQ, "What does atomicity mean?", listOf("All or none", "Always partial", "Only the first", "Only the last"), 0, "All or none", "The operations form a unit.", listOf(StudyCitation(source.id, source.text)))
            val card = PracticeQuestion("q2", PracticeKind.FLASHCARD, "Explain atomicity.", emptyList(), -1, "All operations or none.", "Treat operations as a unit.", question.citations)
            fun set(owner: String) = PracticeSet("native-test", owner, 1, "IT004", "Private synthetic practice", "synthetic", 1793386800, "English", listOf(source), listOf(question, card))
            val repo = LocalPracticeRepository(storage, owners)
            repo.save(set("one"))
            repo.save(set("two"))
            var record = repo.attempt(set("one"), "q1", null, PracticeAttempt("first", selected = 1))
            record = repo.attempt(set("one"), "q1", record.progress.single(), PracticeAttempt("second", selected = 0))
            record = repo.attempt(set("one"), "q2", null, PracticeAttempt("recall", recalled = true))
            val restored = LocalPracticeRepository(StudyPrivateStorage(context, name, alias), owners).collections.first()
            assertEquals(record, restored.single { it.accountId == "one" }.records.single())
            assertEquals(2, record.progress.single { it.questionId == "q1" }.attempts)
            assertEquals(1, record.progress.single { it.questionId == "q1" }.successful)
            assertTrue(preferences.all.values.none { it.toString().contains("Atomicity") || it.toString().contains("Private synthetic practice") })
            owners.remove("one")
            val remaining = LocalPracticeRepository(storage, owners).collections.first()
            assertEquals(listOf("two"), remaining.map { it.accountId })
            assertTrue(remaining.single().records.single().progress.isEmpty())
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
