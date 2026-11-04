package vn.edu.uit.mobile.courses

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.model.*

enum class PassageWork { CATALOG, BUILD, SEARCH, INSPECT, CONNECTION, DELETE }
data class PassageSearchState(
    val courses: List<Course> = emptyList(),
    val course: Course? = null,
    val candidates: List<PassageCandidate> = emptyList(),
    val selected: Set<String> = emptySet(),
    val query: String = "",
    val mode: PassageSearchMode = PassageSearchMode.BM25,
    val library: PassageLibrary = PassageLibrary(),
    val result: PassageSearchResult? = null,
    val inspection: PassageInspection? = null,
    val connection: StudyConnection = StudyConnection(),
    val work: PassageWork? = null,
    val problem: PassageWork? = null,
    val completed: Int = 0,
    val total: Int = 0
) {
    val index: PassageIndex? get() = library.indexes.firstOrNull { it.owner == course?.accountId && it.courseId == course?.id }
}
class PassageSearchViewModel(private val repository: PassageSearchRepository, private val snapshots: Flow<PortalSnapshot>, private val owners: AccountRepository, private val ai: StudyAiRepository) : ViewModel() {
    private val mutable = MutableStateFlow(PassageSearchState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var epoch = 0L
    init {
        viewModelScope.launch { repository.library.collect { library -> mutable.update { it.copy(library = library) } } }
        viewModelScope.launch {
            combine(snapshots, owners.accounts) { snapshot, accounts -> snapshot.courses.filter { c -> accounts.any { it.id == c.accountId && it.portal == PortalType.MOODLE } }.distinctBy { it.accountId to it.id } }.collect { courses ->
                val current = state.value.course
                if (current != null && courses.none { it.id == current.id && it.accountId == current.accountId }) select(null)
                mutable.update { it.copy(courses = courses, course = current?.let { old -> courses.firstOrNull { c -> c.id == old.id && c.accountId == old.accountId } }) }
            }
        }
        viewModelScope.launch {
            try {
                repository.initialize()
                mutable.update { it.copy(connection = ai.connection()) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                mutable.update { it.copy(problem = PassageWork.CATALOG) }
            }
        }
    }
    fun stop() {
        epoch++
        job?.cancel()
        job = null
        mutable.update { it.copy(work = null) }
    }
    fun close() {
        stop()
        mutable.update { it.copy(query = "", result = null, inspection = null, problem = null) }
    }
    private fun operation(work: PassageWork, body: suspend (Long) -> Unit) {
        stop()
        val token = epoch
        mutable.update { it.copy(work = work, problem = null) }
        job = viewModelScope.launch {
            try {
                body(token)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (token == epoch) mutable.update { it.copy(problem = work) }
            } finally {
                if (token == epoch) mutable.update { it.copy(work = null) }
            }
        }
    }
    fun select(course: Course?) {
        stop()
        mutable.update { it.copy(course = course, mode = PassageSearchMode.BM25, candidates = emptyList(), selected = emptySet(), query = "", result = null, inspection = null, problem = null) }
        if (course != null) {
            operation(PassageWork.CATALOG) { token ->
                val candidates = repository.candidates(course)
                if (token == epoch) mutable.update { it.copy(candidates = candidates) }
            }
        }
    }
    fun refresh() {
        val course = state.value.course ?: return
        select(course)
    }
    fun toggle(id: String) {
        if (state.value.work != null || state.value.candidates.none { it.spec.id == id && it.downloaded }) return
        mutable.update {
            it.copy(
                selected = if (id in it.selected) {
                    it.selected - id
                } else if (it.selected.size < PassageRules.DOCUMENTS) {
                    it.selected + id
                } else {
                    it.selected
                }
            )
        }
    }
    fun query(value: String) {
        if (state.value.work == null) mutable.update { it.copy(query = value.take(400), result = null) }
    }
    fun mode(mode: PassageSearchMode) {
        if (state.value.work == null) mutable.update { it.copy(mode = mode, result = null) }
    }
    fun build(config: PassageConfig, embedding: PassageEmbeddingConfig?) {
        val current = state.value
        val course = current.course ?: return
        if (current.selected.isEmpty() || current.work != null) return
        operation(PassageWork.BUILD) { token ->
            repository.build(course, current.selected, config, embedding) { completed, total -> if (token == epoch) mutable.update { it.copy(completed = completed, total = total) } }
            if (token == epoch) mutable.update { it.copy(result = null, inspection = null) }
        }
    }
    fun search() {
        val current = state.value
        val course = current.course ?: return
        if (current.work != null || current.index == null || runCatching { PassageRules.query(current.query) }.isFailure) return
        operation(PassageWork.SEARCH) { token ->
            val result = repository.search(course, current.query, current.mode)
            if (token == epoch) mutable.update { it.copy(result = result) }
        }
    }
    fun inspect(hit: PassageHit) {
        val current = state.value
        val course = current.course ?: return
        if (current.work != null || hit !in current.result?.hits.orEmpty()) return
        operation(PassageWork.INSPECT) { token ->
            val source = repository.inspect(course, hit)
            if (token == epoch) mutable.update { it.copy(inspection = source) }
        }
    }
    fun dismissInspection() {
        mutable.update { it.copy(inspection = null) }
    }
    fun clear() {
        val course = state.value.course ?: return
        operation(PassageWork.DELETE) { token ->
            repository.clear(course.accountId)
            if (token == epoch) mutable.update { it.copy(result = null, inspection = null) }
        }
    }
    fun reset() = operation(PassageWork.DELETE) { token ->
        repository.reset()
        if (token == epoch) mutable.update { it.copy(result = null, inspection = null) }
    }
    fun configure(mode: StudyConnectionMode, endpoint: String, token: String, model: String) = operation(PassageWork.CONNECTION) { generation ->
        if (mode == StudyConnectionMode.DIRECT) ai.configureDirect(token, model) else ai.configure(endpoint, token)
        val connection = ai.connection()
        if (generation == epoch) mutable.update { it.copy(connection = connection) }
    }
    fun disconnect() = operation(PassageWork.CONNECTION) { generation ->
        ai.disconnect()
        if (generation == epoch) mutable.update { it.copy(connection = StudyConnection()) }
    }
    override fun onCleared() {
        stop()
        repository.close()
    }
}
