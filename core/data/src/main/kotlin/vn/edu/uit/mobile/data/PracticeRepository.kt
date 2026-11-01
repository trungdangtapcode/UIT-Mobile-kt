package vn.edu.uit.mobile.data

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import vn.edu.uit.mobile.domain.*

/** Local records only. This repository has no university transport or account-write API. */
@OptIn(ExperimentalCoroutinesApi::class)
class LocalPracticeRepository(private val storage: StudyStorage, private val owners: AccountRepository, private val io: CoroutineDispatcher = Dispatchers.IO, private val clock: () -> Long = { Clock.System.now().epochSeconds }) : PracticeRepository {
    private val mutex = Mutex()
    private val revision = MutableStateFlow(0L)
    private fun key(id: String) = bookmarkDigest(id) + ":practice"
    private fun read(id: String) = storage.read(key(id))?.let { PracticeJson.decode(it, id) }.orEmpty()
    override val collections: Flow<List<PracticeCollection>> = owners.accounts.flatMapLatest { accounts ->
        revision.map {
            withContext(io) {
                mutex.withLock {
                    accounts.map { account ->
                        try {
                            PracticeCollection(account.id, read(account.id))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            PracticeCollection(account.id, failed = true)
                        }
                    }
                }
            }
        }
    }
    private suspend fun <T> mutate(id: String, change: (List<PracticeRecord>) -> Pair<List<PracticeRecord>, T>): T = withContext(io) {
        mutex.withLock {
            check(owners.accounts.first().any { it.id == id })
            val (next, result) = change(read(id))
            require(next.size <= 20)
            next.forEach {
                require(it.set.accountId == id)
                PracticeRules.record(it)
            }
            try {
                storage.write(key(id), PracticeJson.encode(next))
            } finally {
                withContext(NonCancellable) {
                    if (owners.accounts.first().none { it.id == id }) storage.remove(key(id))
                    revision.value++
                }
            }
            check(owners.accounts.first().any { it.id == id })
            result
        }
    }
    override suspend fun save(set: PracticeSet): PracticeRecord {
        PracticeRules.set(set)
        return mutate(set.accountId) { old ->
            val same = old.firstOrNull { it.set.id == set.id || it.set.version == set.version }
            if (same != null) {
                check(same.set.version == set.version)
                old to same
            } else {
                val record = PracticeRecord(set)
                (old + record) to record
            }
        }
    }
    override suspend fun remove(expected: PracticeRecord) {
        mutate(expected.set.accountId) { old ->
            check(old.singleOrNull { it.set.id == expected.set.id } == expected)
            old.filterNot { it.set.id == expected.set.id } to Unit
        }
    }
    override suspend fun attempt(set: PracticeSet, questionId: String, expected: PracticeProgress?, value: PracticeAttempt): PracticeRecord = mutate(set.accountId) { old ->
        val record = old.single { it.set.id == set.id }
        check(record.set == set)
        val question = record.set.questions.single { it.id == questionId }
        val outcome = PracticeRules.outcome(question, value)
        val prior = record.progress.firstOrNull { it.questionId == questionId }
        if (prior?.lastAttemptId == value.id) {
            check(prior.lastOutcome == outcome && prior.lastChoice == (value.selected ?: -1))
            old to record
        } else {
            check(prior == expected)
            val next = PracticeProgress(question.id, question.version, (prior?.attempts ?: 0) + 1, (prior?.successful ?: 0) + if (outcome)1 else 0, value.id, clock(), outcome, value.selected ?: -1)
            val updated = record.copy(progress = record.progress.filterNot { it.questionId == questionId } + next)
            old.map { if (it.set.id == set.id) updated else it } to updated
        }
    }
}
