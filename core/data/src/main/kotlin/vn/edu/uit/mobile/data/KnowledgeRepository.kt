package vn.edu.uit.mobile.data

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import vn.edu.uit.mobile.domain.*

internal object KnowledgeJson {
    private fun citations(value: JsonObject) = value.getValue("citations").jsonArray.map { c -> c.jsonObject.let { StudyCitation(PracticeJson.text(it, "sourceId"), PracticeJson.text(it, "quote")) } }
    fun graph(value: JsonObject, focus: String, language: String) = KnowledgeGraph(
        PracticeJson.text(value, "title"),
        focus,
        language,
        value.getValue("nodes").jsonArray.map { e -> e.jsonObject.let { KnowledgeNode(PracticeJson.text(it, "id"), PracticeJson.text(it, "label"), PracticeJson.text(it, "definition"), citations(it)) } },
        value.getValue("edges").jsonArray.mapIndexed { index, e -> e.jsonObject.let { KnowledgeEdge(if (it.containsKey("id")) PracticeJson.text(it, "id") else "e${index + 1}", PracticeJson.text(it, "from"), PracticeJson.text(it, "to"), PracticeJson.text(it, "relation"), citations(it)) } }
    )
    private fun evidence(value: List<StudyCitation>) = JsonArray(
        value.map { c ->
            buildJsonObject {
                put("sourceId", c.sourceId)
                put("quote", c.quote)
            }
        }
    )
    fun graph(value: KnowledgeGraph) = buildJsonObject {
        put("title", value.title)
        put(
            "nodes",
            JsonArray(
                value.nodes.map { n ->
                    buildJsonObject {
                        put("id", n.id)
                        put("label", n.label)
                        put("definition", n.definition)
                        put("citations", evidence(n.citations))
                    }
                }
            )
        )
        put(
            "edges",
            JsonArray(
                value.edges.map { e ->
                    buildJsonObject {
                        put("id", e.id)
                        put("from", e.from)
                        put("to", e.to)
                        put("relation", e.relation)
                        put("citations", evidence(e.citations))
                    }
                }
            )
        )
    }
    fun encode(records: List<KnowledgeRecord>) = buildJsonObject {
        put("version", 1)
        put(
            "records",
            JsonArray(
                records.map { r ->
                    buildJsonObject {
                        put("id", r.id)
                        put("course", r.courseId)
                        put("courseName", r.courseName)
                        put("model", r.model)
                        put("at", r.generatedAt)
                        put("revision", r.revision)
                        put("focus", r.graph.focus)
                        put("language", r.graph.language)
                        put("graph", graph(r.graph))
                        put("sourceVersion", r.sourceVersion)
                        put(
                            "sources",
                            JsonArray(
                                r.sources.map { s ->
                                    buildJsonObject {
                                        put("id", s.id)
                                        put("title", s.title)
                                        put("text", s.text)
                                    }
                                }
                            )
                        )
                    }
                }
            )
        )
    }.toString().also { require(it.length <= 1_000_000) }
    fun decode(raw: String, owner: String): List<KnowledgeRecord> {
        require(raw.length <= 1_000_000)
        val root = Json.parseToJsonElement(raw).jsonObject
        require(PracticeJson.int(root, "version") == 1)
        val array = root.getValue("records").jsonArray
        require(array.size <= 12)
        return array.map { element ->
            element.jsonObject.let { r ->
                val sources = r.getValue("sources").jsonArray.map { s -> s.jsonObject.let { StudySource(PracticeJson.text(it, "id"), PracticeJson.text(it, "title"), PracticeJson.text(it, "text")) } }
                KnowledgeRecord(PracticeJson.text(r, "id"), owner, PracticeJson.number(r, "course"), PracticeJson.text(r, "courseName"), sources, graph(r.getValue("graph").jsonObject, PracticeJson.text(r, "focus"), PracticeJson.text(r, "language")), PracticeJson.text(r, "model"), PracticeJson.number(r, "at"), PracticeJson.number(r, "revision")).also {
                    require(it.sourceVersion == PracticeJson.text(r, "sourceVersion"))
                    KnowledgeRules.record(it)
                }
            }
        }.also { require(it.map { r -> r.id }.distinct().size == it.size) }
    }
}

/** Independent encrypted owner buckets; optimistic revisions and exact uncertain-write retry. */
@OptIn(ExperimentalCoroutinesApi::class)
class LocalKnowledgeRepository(private val storage: StudyStorage, private val owners: AccountRepository, private val io: CoroutineDispatcher = Dispatchers.IO) : KnowledgeRepository {
    private val lock = Mutex()
    private val revision = MutableStateFlow(0L)
    private fun key(id: String) = bookmarkDigest(id) + ":knowledge"
    private fun read(id: String) = storage.read(key(id))?.let { KnowledgeJson.decode(it, id) }.orEmpty()
    private suspend fun owned(id: String) = owners.accounts.first().any { it.id == id }
    override val collections = owners.accounts.flatMapLatest { accounts ->
        revision.map {
            withContext(io) {
                lock.withLock {
                    accounts.map { owner ->
                        try {
                            KnowledgeCollection(owner.id, read(owner.id))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            KnowledgeCollection(owner.id, failed = true)
                        }
                    }
                }
            }
        }
    }
    private suspend fun <T> mutate(owner: String, change: (List<KnowledgeRecord>) -> Pair<List<KnowledgeRecord>, T>): T = withContext(io) {
        lock.withLock {
            check(owned(owner))
            val (records, result) = change(read(owner))
            require(records.size <= 12 && records.map { it.id }.distinct().size == records.size)
            records.forEach {
                require(it.accountId == owner)
                KnowledgeRules.record(it)
            }
            val encoded = KnowledgeJson.encode(records)
            try {
                if (records.isEmpty()) storage.remove(key(owner)) else storage.write(key(owner), encoded)
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
    override suspend fun save(expected: KnowledgeRecord?, value: KnowledgeRecord): KnowledgeRecord {
        KnowledgeRules.record(value)
        require(expected == null && value.revision == 0L || expected != null && value.id == expected.id && value.accountId == expected.accountId && value.courseId == expected.courseId && value.revision == expected.revision)
        val next = value.copy(revision = value.revision + 1)
        return mutate(value.accountId) { old ->
            val current = old.singleOrNull { it.id == value.id }
            if (current == next) {
                old to current
            } else {
                check(current == expected)
                (old.filterNot { it.id == value.id } + next) to next
            }
        }
    }
    override suspend fun remove(expected: KnowledgeRecord) {
        mutate(expected.accountId) { old ->
            val current = old.singleOrNull { it.id == expected.id }
            check(current == null || current == expected)
            old.filterNot { it.id == expected.id } to Unit
        }
    }
}
