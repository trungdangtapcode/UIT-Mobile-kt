package vn.edu.uit.mobile.courses

import android.text.Html
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.model.*

enum class KnowledgeStage { LIBRARY, SELECT, MAP }
enum class KnowledgeProblem { LOAD, INPUT, GENERATE, SAVE, REMOVED, CHANGED, CANCELED }
data class KnowledgeState(
    val open: Boolean = false,
    val stage: KnowledgeStage = KnowledgeStage.LIBRARY,
    val ownerNames: Map<String, String> = emptyMap(),
    val courses: List<Course> = emptyList(),
    val collections: List<KnowledgeCollection> = emptyList(),
    val course: Course? = null,
    val sources: List<StudySource> = emptyList(),
    val selected: Set<String> = emptySet(),
    val focus: String = "",
    val language: String = "English",
    val connection: StudyConnection = StudyConnection(),
    val record: KnowledgeRecord? = null,
    val focused: String? = null,
    val dirty: Boolean = false,
    val busy: Boolean = false,
    val pendingSave: Boolean = false,
    val problem: KnowledgeProblem? = null,
    val message: String = "",
    val sourceChanged: Boolean = false,
    val loaded: Boolean = false
)

/** Reads saved snapshots and checked local sources only; cannot write to a university service. */
class KnowledgeViewModel(private val ai: StudyAiRepository, private val library: KnowledgeRepository, private val accounts: AccountRepository, private val portal: PortalRepository, private val offline: OfflineCourseSourceLoader, private val io: CoroutineDispatcher = Dispatchers.IO) : ViewModel() {
    private val state = MutableStateFlow(KnowledgeState())
    val uiState = state.asStateFlow()
    private var knownCollections = emptyList<KnowledgeCollection>()
    private var owners = emptySet<String>()
    private var operation: Job? = null
    private var epoch = 0L
    private var persisted: KnowledgeRecord? = null
    private var pending: Pair<KnowledgeRecord?, KnowledgeRecord>? = null
    private var sourceStamp = ""
    init {
        viewModelScope.launch {
            combine(accounts.accounts, portal.snapshot) { a, s -> a to s }.collect { (a, s) ->
                owners = a.map { it.id }.toSet()
                knownCollections = knownCollections.filter { it.accountId in owners }
                val current = state.value
                val removed = current.course?.accountId?.let { it !in owners } == true || current.record?.accountId?.let { it !in owners } == true
                if (removed) {
                    reset()
                    state.value = state.value.copy(open = current.open, problem = KnowledgeProblem.REMOVED)
                }
                val course = state.value.course
                if (!removed && course != null && state.value.stage == KnowledgeStage.SELECT && sourceStamp.isNotEmpty() && sourceStamp != stamp(s, course)) {
                    cancel()
                    sourceStamp = ""
                    state.value = state.value.copy(sources = emptyList(), selected = emptySet(), loaded = false, sourceChanged = true, problem = KnowledgeProblem.CHANGED)
                }
                state.value = state.value.copy(ownerNames = a.associate { it.id to it.displayName }, courses = s.courses.filter { it.accountId in owners }.distinctBy { it.accountId to it.id }, collections = knownCollections.filter { it.accountId in owners })
            }
        }
        viewModelScope.launch {
            library.collections.collect { data ->
                knownCollections = data.filter { it.accountId in owners }
                state.value = state.value.copy(collections = data.filter { it.accountId in owners })
            }
        }
    }
    private fun stamp(snapshot: PortalSnapshot, course: Course) = practiceDigest(listOf(snapshot.sections["${course.accountId}:${course.id}"].toString(), snapshot.assignments.filter { it.accountId == course.accountId && it.courseId == course.id }.toString()))
    private fun reset() {
        epoch++
        operation?.cancel()
        persisted = null
        pending = null
        sourceStamp = ""
        state.value = KnowledgeState(ownerNames = state.value.ownerNames, courses = state.value.courses, collections = state.value.collections)
    }
    fun open(course: Course? = null) {
        if (!state.value.open) {
            state.value = state.value.copy(open = true)
            if (course != null) start(course) else reloadConnection()
        }
    }
    fun close() {
        reset()
    }
    fun bank() {
        reset()
        state.value = state.value.copy(open = true)
        reloadConnection()
    }
    private fun run(problem: KnowledgeProblem, block: suspend () -> Unit) {
        if (state.value.busy) return
        val token = ++epoch
        state.value = state.value.copy(busy = true, problem = null, message = "")
        operation = viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (token == epoch) state.value = state.value.copy(problem = problem)
            } finally {
                if (token == epoch) state.value = state.value.copy(busy = false)
            }
        }
    }
    private fun reloadConnection() = run(KnowledgeProblem.LOAD) {
        val connection = ai.connection()
        val records = library.collections.first()
        currentCoroutineContext().ensureActive()
        knownCollections = records.filter { it.accountId in owners }
        state.value = state.value.copy(connection = connection, collections = knownCollections)
    }
    fun start(course: Course) {
        if (state.value.busy || course.accountId !in owners) return
        state.value = state.value.copy(course = course, stage = KnowledgeStage.SELECT, sources = emptyList(), selected = emptySet(), record = null, dirty = false, loaded = false)
        reload()
    }
    fun reload() {
        val course = state.value.course ?: return
        run(KnowledgeProblem.LOAD) {
            val snapshot = portal.snapshot.first()
            val stamp = stamp(snapshot, course)
            val sections = snapshot.sections["${course.accountId}:${course.id}"].orEmpty()
            val assignments = snapshot.assignments.filter { it.accountId == course.accountId && it.courseId == course.id }
            val local = withContext(io) {
                sections.flatMap { it.modules }.filter { it.courseId == course.id && !it.description.isNullOrBlank() }.map { StudySource("module:${it.id}", it.name.take(200), Html.fromHtml(it.description.orEmpty(), Html.FROM_HTML_MODE_LEGACY).toString().trim()) }.filter { it.text.isNotBlank() }.take(30) + offline.load(course, sections, assignments)
            }
            val connection = ai.connection()
            currentCoroutineContext().ensureActive()
            check(course.accountId in owners && stamp == stamp(portal.snapshot.first(), course))
            sourceStamp = stamp
            state.value = state.value.copy(sources = local.distinctBy { it.id }.take(60), selected = emptySet(), connection = connection, loaded = true, sourceChanged = false)
        }
    }
    fun select(id: String) {
        if (!state.value.busy && state.value.sources.any { it.id == id && it.text.length <= 12000 }) {
            state.value = state.value.copy(
                selected = if (id in state.value.selected) {
                    state.value.selected - id
                } else if (state.value.selected.size < 5) {
                    state.value.selected + id
                } else {
                    state.value.selected
                }
            )
        }
    }
    fun focus(value: String) {
        if (!state.value.busy) state.value = state.value.copy(focus = value.take(400), problem = null)
    }
    fun language(value: String) {
        if (!state.value.busy && value in setOf("English", "Vietnamese")) state.value = state.value.copy(language = value)
    }
    fun excerpt(title: String, text: String) {
        if (state.value.busy) return
        if (title.isBlank() || title.length > 200 || text.isBlank() || text.length > 12000) {
            state.value = state.value.copy(problem = KnowledgeProblem.INPUT)
            return
        }
        state.value = state.value.copy(sources = (state.value.sources + StudySource("excerpt:${UUID.randomUUID()}", title, text)).takeLast(60))
    }
    fun configure(mode: StudyConnectionMode, endpoint: String, secret: String, model: String, done: () -> Unit) = run(KnowledgeProblem.SAVE) {
        if (mode == StudyConnectionMode.DIRECT) ai.configureDirect(secret, model) else ai.configure(endpoint, secret)
        currentCoroutineContext().ensureActive()
        state.value = state.value.copy(connection = ai.connection())
        done()
    }
    fun disconnect(done: () -> Unit) = run(KnowledgeProblem.SAVE) {
        ai.disconnect()
        currentCoroutineContext().ensureActive()
        state.value = state.value.copy(connection = StudyConnection())
        done()
    }
    fun cancel() {
        epoch++
        operation?.cancel()
        state.value = state.value.copy(busy = false, problem = KnowledgeProblem.CANCELED)
    }
    fun generate() {
        val current = state.value
        val course = current.course ?: return
        val sources = current.sources.filter { it.id in current.selected }
        if (!current.connection.configured || !current.loaded || current.sourceChanged || runCatching { KnowledgeRules.request(sources, current.focus, current.language) }.isFailure) {
            state.value = current.copy(problem = KnowledgeProblem.INPUT)
            return
        }
        run(KnowledgeProblem.GENERATE) {
            val result = ai.knowledge(course, sources, current.focus, current.language)
            currentCoroutineContext().ensureActive()
            check(accounts.accounts.first().any { it.id == course.accountId })
            // A result can arrive before the snapshot observer handles a source change.
            if (sourceStamp != stamp(portal.snapshot.first(), course)) {
                sourceStamp = ""
                state.value = state.value.copy(sources = emptyList(), selected = emptySet(), loaded = false, sourceChanged = true, problem = KnowledgeProblem.CHANGED)
                return@run
            }
            KnowledgeRules.generation(result, sources, current.focus, current.language)
            if (result.status == "insufficient_evidence") {
                state.value = state.value.copy(message = result.message)
                return@run
            }
            persisted = null
            pending = null
            val record = KnowledgeRecord(UUID.randomUUID().toString(), course.accountId, course.id, course.fullname.take(300), sources, result.graph, result.model, result.generatedAt)
            state.value = state.value.copy(record = record, focused = record.graph.nodes.first().id, dirty = true, stage = KnowledgeStage.MAP)
        }
    }
    fun show(record: KnowledgeRecord) {
        if (state.value.busy || record.accountId !in owners || state.value.collections.none { record in it.records }) return
        persisted = record
        pending = null
        state.value = state.value.copy(record = record, focused = record.graph.nodes.first().id, stage = KnowledgeStage.MAP, dirty = false, problem = null)
    }
    fun choose(id: String) {
        if (state.value.record?.graph?.nodes?.any { it.id == id } == true) state.value = state.value.copy(focused = id)
    }
    fun edit(graph: KnowledgeGraph): Boolean {
        val record = state.value.record ?: return false
        if (state.value.busy || state.value.pendingSave || runCatching { KnowledgeRules.graph(graph, record.sources) }.isFailure) {
            state.value = state.value.copy(problem = KnowledgeProblem.INPUT)
            return false
        }
        state.value = state.value.copy(record = record.copy(graph = graph), dirty = persisted?.graph != graph, problem = null)
        return true
    }
    fun save() {
        val record = state.value.record ?: return
        if (state.value.busy || !state.value.dirty || record.accountId !in owners) return
        val write = pending ?: (persisted to record).also { pending = it }
        state.value = state.value.copy(pendingSave = true)
        run(KnowledgeProblem.SAVE) {
            val saved = library.save(write.first, write.second)
            currentCoroutineContext().ensureActive()
            check(saved.accountId in owners)
            persisted = saved
            pending = null
            state.value = state.value.copy(record = saved, dirty = false, pendingSave = false)
        }
    }
    fun remove(record: KnowledgeRecord, done: () -> Unit) = run(KnowledgeProblem.SAVE) {
        library.remove(record)
        currentCoroutineContext().ensureActive()
        if (state.value.record?.id == record.id) {
            persisted = null
            pending = null
            state.value = state.value.copy(record = null, stage = KnowledgeStage.LIBRARY, dirty = false, pendingSave = false)
        }
        done()
    }
}
