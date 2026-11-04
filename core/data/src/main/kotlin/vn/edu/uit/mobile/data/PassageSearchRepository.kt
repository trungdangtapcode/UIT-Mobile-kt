package vn.edu.uit.mobile.data

import android.content.Context
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.model.*

fun createPassageSearchRepository(context: Context, accounts: AccountRepository, snapshots: Flow<PortalSnapshot>, ai: StudyAiRepository): PassageSearchRepository = LocalPassageSearchRepository(StudyPrivateStorage(context, "course_passages", "uit.course.passages.v1"), accounts, snapshots, createPassageDocumentLoader(createOfflineDownloadStorage(context)), createOfflineDownloadStorage(context), ai)

/** Derived account-owned index. No university networking, credentials or write capability. */
class LocalPassageSearchRepository(private val storage: StudyStorage, private val accounts: AccountRepository, private val snapshots: Flow<PortalSnapshot>, private val loader: PassageDocumentLoader, private val downloads: OfflineDownloadStorage, private val ai: StudyAiRepository) : PassageSearchRepository {
    private val mutable = MutableStateFlow(PassageLibrary())
    override val library = mutable.asStateFlow()
    private val lock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var initialized = false

    @Volatile private var epoch = 0L
    private var known = emptySet<String>()

    @Volatile private var lexicalCache: Pair<String, PassageBm25>? = null
    init {
        scope.launch { accounts.accounts.collect { if (initialized) reconcile() } }
    }
    private fun key(owner: String) = practiceDigest(listOf(owner)) + ":index"
    private suspend fun owners() = accounts.accounts.first().filter { it.portal == PortalType.MOODLE }.map { it.id }.toSet()
    override suspend fun initialize() = withContext(Dispatchers.IO) {
        lock.withLock {
            if (initialized) return@withLock
            try {
                val current = owners()
                known = storage.read("owners")?.let { raw ->
                    require(raw.length <= 32000)
                    Json.parseToJsonElement(raw).jsonArray.also { require(it.size <= 100) }.map { it.jsonPrimitive.content.also { owner -> require(owner.length in 1..200) } }.toSet()
                }.orEmpty()
                (known - current).forEach { storage.remove(key(it)) }
                known = known intersect current
                val indexes = known.mapNotNull { owner -> storage.read(key(owner))?.let { PassageIndexCodec.decode(it, owner) } }
                saveOwners(known)
                mutable.value = PassageLibrary(indexes)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                mutable.value = PassageLibrary(storageFailed = true)
            }
            initialized = true
        }
    }
    private fun saveOwners(owners: Set<String>) {
        storage.write("owners", JsonArray(owners.sorted().map(::JsonPrimitive)).toString())
    }
    private suspend fun reconcile() = lock.withLock {
        val current = owners()
        if ((known - current).isNotEmpty()) {
            epoch++
            lexicalCache = null
            val kept = mutable.value.indexes.filter { it.owner in current }
            mutable.value = mutable.value.copy(indexes = kept)
            try {
                (known - current).forEach { storage.remove(key(it)) }
                known = known intersect current
                saveOwners(known)
            } catch (_: Exception) {
                mutable.value = mutable.value.copy(storageFailed = true)
            }
        }
    }
    private suspend fun allowed(course: Course): Map<String, OfflineDownloadSpec> {
        check(course.accountId in owners())
        val snapshot = snapshots.first()
        check(snapshot.courses.any { it.id == course.id && it.accountId == course.accountId })
        return savedDownloadSpecs(snapshot, setOf(course.accountId)).filter { it.course.id == course.id }.distinctBy { it.id }.associateBy { it.id }
    }
    override suspend fun candidates(course: Course): List<PassageCandidate> = withContext(Dispatchers.IO) {
        initialize()
        val catalog = allowed(course)
        val saved = downloads.load().items.filter { it.status == OfflineDownloadStatus.SAVED }.map { it.id }.toSet()
        catalog.values.take(PassageRules.CATALOG).map { PassageCandidate(it, it.id in saved) }
    }
    private fun sameSource(current: OfflineDownloadSpec?, saved: OfflineDownloadSpec): Boolean = current != null && current.file == saved.file && current.course.shortname == saved.course.shortname && current.course.fullname == saved.course.fullname
    private suspend fun guard(course: Course, token: Long, selected: List<OfflineDownloadSpec>) {
        currentCoroutineContext().ensureActive()
        check(epoch == token && !mutable.value.storageFailed)
        val current = allowed(course)
        check(selected.all { sameSource(current[it.id], it) }) { "Selected source catalog changed" }
    }
    private fun persist(index: PassageIndex) {
        check(!mutable.value.storageFailed)
        val encoded = PassageIndexCodec.encode(index)
        PassageIndexCodec.decode(encoded, index.owner)
        // Journal ownership before storing content so removal can always discover the namespace.
        val next = known + index.owner
        saveOwners(next)
        known = next
        storage.write(key(index.owner), encoded)
        lexicalCache = null
        mutable.value = PassageLibrary(mutable.value.indexes.filterNot { it.owner == index.owner } + index)
    }
    override suspend fun build(course: Course, ids: Set<String>, config: PassageConfig, embedding: PassageEmbeddingConfig?, progress: (Int, Int) -> Unit) = withContext(Dispatchers.IO) {
        initialize()
        config.validate()
        embedding?.validate()
        require(ids.size in 1..PassageRules.DOCUMENTS)
        val token = lock.withLock {
            check(!mutable.value.storageFailed)
            ++epoch
        }
        val catalog = allowed(course)
        val selected = ids.sorted().map { requireNotNull(catalog[it]) }
        val documents = selected.map { spec ->
            guard(course, token, selected)
            loader.load(spec).also { require(it.spec == spec) }
        }
        val passages = mutableListOf<CoursePassage>()
        var limited = false
        val infos = documents.mapIndexed { i, doc ->
            val chunks = PassageRules.chunks(doc, config)
            val allowance = (PassageRules.PASSAGES - passages.size) / (documents.size - i)
            val kept = chunks.take(allowance)
            if (kept.size < chunks.size) limited = true
            passages += kept
            PassageDocumentInfo(doc.spec, doc.version, doc.partial || kept.size < chunks.size)
        }
        require(passages.isNotEmpty())
        var vectors = emptyList<List<Float>>()
        var tokens = 0L
        var failed = false
        if (embedding != null) {
            val produced = mutableListOf<List<Float>>()
            try {
                val batches = mutableListOf<MutableList<CoursePassage>>()
                passages.forEach { passage ->
                    val last = batches.lastOrNull()
                    if (last == null || last.size >= PassageRules.BATCH || last.sumOf { it.text.toByteArray(Charsets.UTF_8).size } + passage.text.toByteArray(Charsets.UTF_8).size > 64000) batches += mutableListOf(passage) else last += passage
                }
                batches.forEach { batch ->
                    guard(course, token, selected)
                    // No URLs/account identifiers go to the provider; source text is the explicit selected transfer.
                    val result = ai.embed(course, batch.map { it.text }, embedding)
                    require(result.model == embedding.model && result.vectors.size == batch.size && result.tokens >= 0)
                    produced += result.vectors.map { PassageRules.normalize(it, embedding.dimensions) }
                    tokens += result.tokens
                    guard(course, token, selected)
                    progress(produced.size, passages.size)
                }
                vectors = produced
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                failed = true
            }
        }
        guard(course, token, selected)
        // Recheck bytes after potentially slow embedding, not only catalog membership.
        documents.forEach { check(loader.load(it.spec).version == it.version) { "Source changed during indexing" } }
        guard(course, token, selected)
        val index = PassageIndex(course.accountId, course.id, UUID.randomUUID().toString(), System.currentTimeMillis() / 1000, config, infos, passages, embedding, vectors, tokens, failed, limited)
        lock.withLock {
            guard(course, token, selected)
            persist(index)
        }
    }
    private suspend fun validIndex(course: Course): Pair<PassageIndex?, Int> {
        initialize()
        val original = lock.withLock { mutable.value.indexes.firstOrNull { it.owner == course.accountId && it.courseId == course.id } } ?: return null to 0
        val catalog = allowed(course)
        val valid = original.documents.filter { doc ->
            currentCoroutineContext().ensureActive()
            try {
                sameSource(catalog[doc.spec.id], doc.spec) && loader.load(doc.spec).version == doc.version
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
        }
        if (valid.size == original.documents.size) return original to 0
        val positions = original.passages.indices.filter { i -> original.passages[i].documentId in valid.map { it.spec.id } }
        val updated = original.copy(revision = UUID.randomUUID().toString(), documents = valid, passages = positions.map { original.passages[it] }, vectors = if (original.vectors.isEmpty()) emptyList() else positions.map { original.vectors[it] })
        lock.withLock {
            check(mutable.value.indexes.any { it.revision == original.revision })
            check(course.accountId in owners())
            if (updated.passages.isEmpty()) {
                lexicalCache = null
                storage.remove(key(course.accountId))
                known = known - course.accountId
                saveOwners(known)
                mutable.value = mutable.value.copy(indexes = mutable.value.indexes.filterNot { it.owner == course.accountId })
            } else {
                persist(updated)
            }
        }
        return updated.takeIf { it.passages.isNotEmpty() } to (original.documents.size - valid.size)
    }
    override suspend fun search(course: Course, query: String, mode: PassageSearchMode): PassageSearchResult = withContext(Dispatchers.IO) {
        PassageRules.query(query)
        val (index, invalidated) = validIndex(course)
        if (index == null) return@withContext PassageSearchResult(emptyList(), mode, PassageSearchMode.BM25, invalidated)
        val token = epoch
        val titles = index.documents.associate { it.spec.id to "${it.spec.course.shortname} ${it.spec.file.name}" }
        val engine = lock.withLock {
            guard(course, token, index.documents.map { it.spec })
            check(mutable.value.indexes.any { it.revision == index.revision })
            lexicalCache?.takeIf { it.first == index.revision }?.second ?: PassageBm25(index.passages, index.config, titles).also { lexicalCache = index.revision to it }
        }
        val lexical = engine.search(query)
        var actual = PassageSearchMode.BM25
        var tokens = 0L
        var ranking = lexical
        val embedding = index.embedding
        if (mode != PassageSearchMode.BM25 && embedding != null && index.vectors.size == index.passages.size) {
            try {
                guard(course, token, index.documents.map { it.spec })
                val result = ai.embed(course, listOf(query), embedding)
                require(result.model == embedding.model && result.vectors.size == 1)
                tokens = result.tokens
                val dense = PassageRanking.dense(index.passages, index.vectors, PassageRules.normalize(result.vectors.single(), embedding.dimensions), index.config.candidates)
                ranking = if (mode == PassageSearchMode.HYBRID) PassageRanking.fusion(listOf(lexical, dense), index.config.fusionK) else dense
                actual = mode
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) { /* Explicit BM25 fallback retains usable local evidence. */ }
        }
        guard(course, token, index.documents.map { it.spec })
        check(mutable.value.indexes.any { it.revision == index.revision })
        // Detect a selected file removed/replaced while an embedding request was in flight.
        index.documents.forEach { check(loader.load(it.spec).version == it.version) }
        val byId = index.passages.associateBy { it.id }
        val hits = ranking.take(20).map { (id, score) ->
            val p = byId.getValue(id)
            PassageHit(p, index.documents.first { it.spec.id == p.documentId }, score)
        }
        PassageSearchResult(hits, mode, actual, invalidated, tokens)
    }
    override suspend fun inspect(course: Course, hit: PassageHit): PassageInspection = withContext(Dispatchers.IO) {
        val index = mutable.value.indexes.first { it.owner == course.accountId && it.courseId == course.id && hit.passage in it.passages }
        val token = epoch
        require(hit.document in index.documents && hit.passage.documentId == hit.document.spec.id) { "Source inspection escaped the selected index" }
        guard(course, token, index.documents.map { it.spec })
        val source = loader.load(hit.document.spec)
        require(source.version == hit.document.version)
        val part = source.parts.first { it.ordinal == hit.passage.ordinal && it.kind == hit.passage.kind }
        require(part.text.substring(hit.passage.start, hit.passage.end) == hit.passage.text)
        guard(course, token, index.documents.map { it.spec })
        PassageInspection(hit, part)
    }
    override suspend fun clear(owner: String) = withContext(Dispatchers.IO) {
        initialize()
        lock.withLock {
            check(owner in owners() && !mutable.value.storageFailed)
            epoch++
            lexicalCache = null
            storage.remove(key(owner))
            known = known - owner
            saveOwners(known)
            mutable.value = PassageLibrary(mutable.value.indexes.filterNot { it.owner == owner })
        }
    }
    override suspend fun reset() = withContext(Dispatchers.IO) {
        lock.withLock {
            epoch++
            lexicalCache = null
            storage.clear()
            known = emptySet()
            initialized = true
            mutable.value = PassageLibrary()
        }
    }
    override fun close() {
        scope.cancel()
    }
}
