package vn.edu.uit.mobile.data

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.model.*

/** Read per-account snapshots; never reconstruct ownership from the flattened workspace. */
@OptIn(ExperimentalCoroutinesApi::class)
class LocalWorkloadRepository(private val storage: StudyStorage, private val owners: AccountRepository, private val snapshots: SnapshotStore) : WorkloadRepository {
    private val mutex = Mutex()
    private val revision = MutableStateFlow(0L)
    private data class Saved(val policy: WorkloadPolicy = WorkloadPolicy(), val estimates: List<WorkloadEstimate> = emptyList(), val configured: Boolean = false)
    private data class Source(val snapshot: PortalSnapshot, val failed: Boolean = false)
    override val accounts: Flow<List<WorkloadAccount>> = owners.accounts.flatMapLatest { accounts ->
        require(accounts.size <= 30)
        if (accounts.isEmpty()) {
            flowOf(emptyList())
        } else {
            combine(
                accounts.map { owner ->
                    combine(
                        snapshots.observe(owner.id).map { Source(it) }.catch {
                            if (it is CancellationException) throw it
                            emit(Source(PortalSnapshot(), true))
                        },
                        revision
                    ) { source, _ ->
                        withContext(Dispatchers.IO) {
                            mutex.withLock {
                                try {
                                    val saved = read(owner.id)
                                    WorkloadAccount(owner, source.snapshot, saved.policy, saved.estimates, sourceFailed = source.failed, configured = saved.configured)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (_: Exception) {
                                    WorkloadAccount(owner, source.snapshot, storageFailed = true, sourceFailed = source.failed)
                                }
                            }
                        }
                    }
                }
            ) { it.toList() }
        }
    }
    private fun key(id: String) = bookmarkDigest(id) + ":workload"
    private fun read(id: String): Saved {
        val raw = storage.read(key(id)) ?: return Saved()
        require(raw.length <= 1_000_000)
        val doc = Json.parseToJsonElement(raw).jsonObject
        require(doc.getValue("version").jsonPrimitive.int == 1)
        val config = doc.getValue("policy").jsonObject
        fun number(name: String) = config.getValue(name).jsonPrimitive.int
        val policy = WorkloadPolicy(
            number("horizon"),
            config.getValue("days").jsonArray.map { element ->
                val day = element.jsonObject
                StudyDayBudget(day.getValue("day").jsonPrimitive.int, day.getValue("start").jsonPrimitive.int, day.getValue("end").jsonPrimitive.int, day.getValue("minutes").jsonPrimitive.int)
            },
            number("minimum"),
            number("buffer"),
            number("lead"),
            number("stale"),
            config.getValue("otherEvents").jsonPrimitive.boolean,
            config.getValue("calendar").jsonPrimitive.content
        )
        validateWorkloadPolicy(policy)
        val estimates = doc.getValue("estimates").jsonArray
        require(estimates.size <= 500)
        val values = estimates.map { element ->
            val item = element.jsonObject
            WorkloadEstimate(WorkloadRef(id, WorkloadKind.valueOf(item.getValue("kind").jsonPrimitive.content), item.getValue("target").jsonPrimitive.content), item.getValue("title").jsonPrimitive.content, item["remaining"]?.jsonPrimitive?.int, item["duration"]?.jsonPrimitive?.int).also(::validateWorkloadEstimate)
        }
        require(values.map { it.ref.id }.distinct().size == values.size)
        return Saved(policy, values, doc.getValue("configured").jsonPrimitive.boolean)
    }
    private fun write(id: String, saved: Saved) = storage.write(
        key(id),
        buildJsonObject {
            put("version", 1)
            put("configured", saved.configured)
            put(
                "policy",
                buildJsonObject {
                    val p = saved.policy
                    put("horizon", p.horizonDays)
                    put("minimum", p.minimumSession)
                    put("buffer", p.travelBuffer)
                    put("lead", p.deadlineLead)
                    put("stale", p.staleHours)
                    put("otherEvents", p.includeOtherEvents)
                    put("calendar", p.calendarAccountId)
                    put(
                        "days",
                        JsonArray(
                            p.days.sortedBy { it.isoDay }.map { day ->
                                buildJsonObject {
                                    put("day", day.isoDay)
                                    put("start", day.startMinute)
                                    put("end", day.endMinute)
                                    put("minutes", day.minutes)
                                }
                            }
                        )
                    )
                }
            )
            put(
                "estimates",
                JsonArray(
                    saved.estimates.map { e ->
                        buildJsonObject {
                            put("kind", e.ref.kind.name)
                            put("target", e.ref.target)
                            put("title", e.title)
                            e.remainingMinutes?.let { put("remaining", it) }
                            e.examMinutes?.let { put("duration", it) }
                        }
                    }
                )
            )
        }.toString()
    )
    private suspend fun mutate(id: String, change: (Saved, List<Account>) -> Saved) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val connected = owners.accounts.first()
            check(connected.any { it.id == id })
            val next = change(read(id), connected)
            try {
                write(id, next)
            } finally {
                withContext(NonCancellable) {
                    if (owners.accounts.first().none { it.id == id }) storage.remove(key(id))
                    revision.value++
                }
            }
            check(owners.accounts.first().any { it.id == id })
        }
    }
    override suspend fun savePolicy(accountId: String, policy: WorkloadPolicy) {
        validateWorkloadPolicy(policy)
        mutate(accountId) { old, connected ->
            require(policy.calendarAccountId.isEmpty() || connected.any { it.id == policy.calendarAccountId && it.portal != PortalType.MOODLE })
            old.copy(policy = policy, configured = true)
        }
    }
    override suspend fun saveEstimate(estimate: WorkloadEstimate) {
        validateWorkloadEstimate(estimate)
        mutate(estimate.ref.accountId) { old, connected ->
            val owner = connected.single { it.id == estimate.ref.accountId }
            require((estimate.ref.kind == WorkloadKind.EXAM) == (owner.portal != PortalType.MOODLE))
            require(old.estimates.any { it.ref == estimate.ref } || old.estimates.size < 500)
            old.copy(estimates = old.estimates.filterNot { it.ref == estimate.ref } + estimate)
        }
    }
    override suspend fun removeEstimate(ref: WorkloadRef) {
        mutate(ref.accountId) { old, _ -> old.copy(estimates = old.estimates.filterNot { it.ref == ref }) }
    }
}
