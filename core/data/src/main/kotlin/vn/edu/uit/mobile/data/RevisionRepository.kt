package vn.edu.uit.mobile.data

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.model.*

@OptIn(ExperimentalCoroutinesApi::class)
class LocalRevisionRepository(private val storage: StudyStorage, private val owners: AccountRepository) : RevisionRepository {
    private val mutex = Mutex()
    private val revision = MutableStateFlow(0L)
    private fun key(id: String) = bookmarkDigest(id) + ":revision"
    override val accounts: Flow<List<RevisionAccount>> = owners.accounts.flatMapLatest { connected ->
        revision.map {
            withContext(Dispatchers.IO) {
                mutex.withLock {
                    connected.filter { it.portal != PortalType.MOODLE }.map { owner ->
                        try {
                            RevisionAccount(owner.id, read(owner.id))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            RevisionAccount(owner.id, failed = true)
                        }
                    }
                }
            }
        }
    }
    private fun read(id: String): RevisionWorkspace {
        val raw = storage.read(key(id)) ?: return RevisionWorkspace()
        require(raw.length <= 2_000_000)
        val doc = Json.parseToJsonElement(raw).jsonObject
        require(doc.getValue("version").jsonPrimitive.int == 1)
        val options = doc.getValue("options").jsonObject
        return RevisionWorkspace(
            doc.getValue("plans").jsonArray.map { element ->
                val p = element.jsonObject
                RevisionPlan(
                    WorkloadRef(id, WorkloadKind.EXAM, p.getValue("target").jsonPrimitive.content),
                    p.getValue("title").jsonPrimitive.content,
                    p.getValue("due").jsonPrimitive.long,
                    p.getValue("topics").jsonArray.map { value ->
                        val t = value.jsonObject
                        StudyTopic(t.getValue("id").jsonPrimitive.content, t.getValue("title").jsonPrimitive.content, t.getValue("minutes").jsonPrimitive.int)
                    },
                    p.getValue("sessions").jsonArray.map { value ->
                        val s = value.jsonObject
                        RevisionSession(s.getValue("id").jsonPrimitive.content, s.getValue("topic").jsonPrimitive.content, s.getValue("start").jsonPrimitive.long, s.getValue("end").jsonPrimitive.long, s.getValue("effort").jsonPrimitive.int, RevisionStatus.valueOf(s.getValue("status").jsonPrimitive.content), s.getValue("locked").jsonPrimitive.boolean)
                    },
                    p.getValue("active").jsonPrimitive.boolean
                )
            },
            RevisionOptions(options.getValue("maximum").jsonPrimitive.int, options.getValue("break").jsonPrimitive.int, options.getValue("delay").jsonPrimitive.int)
        ).also { validateRevision(it, id) }
    }
    private fun encode(value: RevisionWorkspace): String = buildJsonObject {
        put("version", 1)
        put(
            "options",
            buildJsonObject {
                put("maximum", value.options.maximumSession)
                put("break", value.options.breakMinutes)
                put("delay", value.options.startDelay)
            }
        )
        put(
            "plans",
            JsonArray(
                value.plans.map { p ->
                    buildJsonObject {
                        put("target", p.exam.target)
                        put("title", p.title)
                        put("due", p.due)
                        put("active", p.active)
                        put(
                            "topics",
                            JsonArray(
                                p.topics.map { t ->
                                    buildJsonObject {
                                        put("id", t.id)
                                        put("title", t.title)
                                        put("minutes", t.minutes)
                                    }
                                }
                            )
                        )
                        put(
                            "sessions",
                            JsonArray(
                                p.sessions.map { s ->
                                    buildJsonObject {
                                        put("id", s.id)
                                        put("topic", s.topicId)
                                        put("start", s.start)
                                        put("end", s.end)
                                        put("effort", s.effort)
                                        put("status", s.status.name)
                                        put("locked", s.locked)
                                    }
                                }
                            )
                        )
                    }
                }
            )
        )
    }.toString().also { require(it.length <= 2_000_000) }
    override suspend fun replace(accountId: String, expected: RevisionWorkspace, value: RevisionWorkspace) = withContext(Dispatchers.IO) {
        validateRevision(value, accountId)
        mutex.withLock {
            check(owners.accounts.first().any { it.id == accountId && it.portal != PortalType.MOODLE })
            check(read(accountId) == expected) { "Planning changed; reload before saving" }
            try {
                storage.write(key(accountId), encode(value))
            } finally {
                withContext(NonCancellable) {
                    if (owners.accounts.first().none { it.id == accountId }) storage.remove(key(accountId))
                    revision.value++
                }
            }
            check(owners.accounts.first().any { it.id == accountId })
        }
    }
}
