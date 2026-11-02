package vn.edu.uit.mobile.data

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import vn.edu.uit.mobile.domain.*

/** Account-partitioned local records with optimistic concurrency and uncertain-write retry. */
@OptIn(ExperimentalCoroutinesApi::class)
class LocalReadingRepository(private val storage: StudyStorage, private val owners: AccountRepository, private val io: CoroutineDispatcher = Dispatchers.IO) : ReadingRepository {
    private val mutex = Mutex()
    private val revision = MutableStateFlow(0L)
    private fun key(owner: String) = bookmarkDigest(owner) + ":reading"
    private fun read(owner: String) = storage.read(key(owner))?.let { ReadingJson.decode(it, owner) }.orEmpty()
    private suspend fun owned(owner: String) = owners.accounts.first().any { it.id == owner }
    override val collections = owners.accounts.flatMapLatest { accounts ->
        revision.map {
            withContext(io) {
                mutex.withLock {
                    accounts.map { owner ->
                        try {
                            ReadingCollection(owner.id, read(owner.id))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            ReadingCollection(owner.id, failed = true)
                        }
                    }
                }
            }
        }
    }
    override suspend fun load(identity: ReadingIdentity, title: String, courseId: Long, pageCount: Int): ReadingRecord = withContext(io) {
        ReadingRules.identity(identity)
        mutex.withLock {
            check(owned(identity.accountId))
            val result = read(identity.accountId).singleOrNull { it.identity == identity } ?: ReadingRecord(identity, title, courseId, pageCount)
            require(result.pageCount == pageCount && result.courseId == courseId)
            ReadingRules.record(result)
            check(owned(identity.accountId))
            result
        }
    }
    private suspend fun <T> mutate(owner: String, change: (List<ReadingRecord>) -> Pair<List<ReadingRecord>, T>): T = withContext(io) {
        mutex.withLock {
            check(owned(owner))
            val (next, result) = change(read(owner))
            val encoded = try {
                ReadingRules.collection(owner, next)
                ReadingJson.encode(owner, next)
            } catch (_: IllegalArgumentException) {
                throw ReadingLimitException()
            }
            try {
                if (next.isEmpty()) storage.remove(key(owner)) else storage.write(key(owner), encoded)
            } finally {
                withContext(NonCancellable) {
                    if (!owned(owner)) storage.remove(key(owner))
                    revision.value++
                }
            }
            check(owned(owner))
            result
        }
    }
    override suspend fun save(expected: ReadingRecord, value: ReadingRecord): ReadingRecord {
        ReadingRules.record(expected)
        ReadingRules.record(value)
        require(expected.identity == value.identity && expected.title == value.title && expected.courseId == value.courseId && expected.pageCount == value.pageCount && expected.revision == value.revision)
        val updated = value.copy(revision = expected.revision + 1)
        return mutate(expected.identity.accountId) { old ->
            val prior = old.singleOrNull { it.identity == expected.identity }
            if (prior == updated) {
                old to prior
            } else {
                check(prior == expected || prior == null && expected.revision == 0L)
                (old.filterNot { it.identity == expected.identity } + updated) to updated
            }
        }
    }
    override suspend fun export(record: ReadingRecord): String = withContext(io) {
        ReadingRules.record(record)
        mutex.withLock {
            check(owned(record.identity.accountId))
            val current = read(record.identity.accountId).singleOrNull { it.identity == record.identity }
            check(current == record || current == null && record.revision == 0L)
            val result = ReadingJson.export(record)
            check(owned(record.identity.accountId))
            result
        }
    }
    override suspend fun remove(expected: ReadingRecord) {
        ReadingRules.record(expected)
        mutate(expected.identity.accountId) { old ->
            val current = old.singleOrNull { it.identity == expected.identity }
            check(current == null || current == expected)
            old.filterNot { it.identity == expected.identity } to Unit
        }
    }
}
