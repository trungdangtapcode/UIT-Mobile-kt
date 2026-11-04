package vn.edu.uit.mobile.courses

import android.text.Html
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.model.*

enum class TutorTab { EXPLAIN, HINTS, TESTS }
enum class TutorProblem { INPUT, LOAD, GENERATE, CONNECTION, CHANGED, REMOVED, CANCELED }
data class CodeTutorState(
    val open: Boolean = false,
    val course: Course? = null,
    val code: String = "",
    val from: String = "1",
    val to: String = "1",
    val codeLanguage: String = "Python",
    val language: String = "English",
    val question: String = "",
    val environment: String = "",
    val catalog: TutorCodeCatalog = TutorCodeCatalog(),
    val imported: TutorCodeSource? = null,
    val sources: List<StudySource> = emptyList(),
    val selected: Set<String> = emptySet(),
    val connection: StudyConnection = StudyConnection(),
    val submitted: TutorRequest? = null,
    val result: TutorResult? = null,
    val tab: TutorTab = TutorTab.EXPLAIN,
    val revealedHints: Int = 0,
    val busy: Boolean = false,
    val sourceChanged: Boolean = false,
    val catalogChecked: Boolean = false,
    val problem: TutorProblem? = null,
    val session: Long = 0
) {
    val request get() = runCatching {
        TutorRequest(TutorRules.selection(code, from.toInt(), to.toInt(), codeLanguage), question, language, environment, sources.filter { it.id in selected }).also(TutorRules::request)
    }.getOrNull()
    val canAnalyze get() = !busy && !sourceChanged && connection.configured && request != null
}
interface CodeTutorActions {
    fun close() {}
    fun code(value: String) {}
    fun range(from: String, to: String) {}
    fun codeLanguage(value: String) {}
    fun language(value: String) {}
    fun question(value: String) {}
    fun environment(value: String) {}
    fun use(source: TutorCodeSource) {}
    fun select(id: String) {}
    fun reload() {}
    fun analyze() {}
    fun cancel() {}
    fun reveal() {}
    fun tab(value: TutorTab) {}
    fun edit() {}
    fun configure(mode: StudyConnectionMode, endpoint: String, secret: String, model: String, done: () -> Unit) {}
    fun disconnect(done: () -> Unit) {}
}

/** Reads saved snapshots and verified files only. No university requests or code-execution capability. */
class CodeTutorViewModel(
    private val ai: StudyAiRepository,
    private val accounts: AccountRepository,
    private val portal: PortalRepository,
    private val contextLoader: OfflineCourseSourceLoader = OfflineCourseSourceLoader { _, _, _ -> emptyList() },
    private val codeLoader: OfflineTutorCodeLoader = OfflineTutorCodeLoader { _, _, _ -> TutorCodeCatalog() },
    private val io: CoroutineDispatcher = Dispatchers.IO
) : ViewModel(),
    CodeTutorActions {
    private val state = MutableStateFlow(CodeTutorState())
    val uiState = state.asStateFlow()
    private var operation: Job? = null
    private var epoch = 0L
    private var fingerprint = ""
    private var sections = emptyList<CourseSection>()
    private var assignments = emptyList<Assignment>()
    init {
        viewModelScope.launch {
            accounts.accounts.collect { owners ->
                val current = state.value
                if (current.course?.let { c -> owners.none { it.id == c.accountId } } == true) {
                    reset(current.open)
                    state.value = state.value.copy(problem = TutorProblem.REMOVED)
                }
            }
        }
        viewModelScope.launch {
            portal.snapshot.collect { snapshot ->
                val current = state.value
                val course = current.course
                if (current.open && course != null && current.catalogChecked && fingerprint != stamp(snapshot, course)) {
                    operation?.cancel()
                    epoch++
                    state.value = current.copy(result = null, submitted = null, sources = emptyList(), selected = emptySet(), catalog = TutorCodeCatalog(), sourceChanged = true, catalogChecked = false, busy = false, revealedHints = 0, problem = TutorProblem.CHANGED, session = current.session + 1)
                }
            }
        }
    }
    private fun metadata(course: Course, sections: List<CourseSection>, assignments: List<Assignment>) = buildList {
        assignments.filter { it.accountId == course.accountId && it.courseId == course.id }.forEach {
            val text = Html.fromHtml(it.intro, Html.FROM_HTML_MODE_LEGACY).toString().trim()
            if (text.isNotBlank()) add(StudySource("assignment:${it.id}", it.name.take(200), text))
        }
        sections.flatMap { it.modules }.filter { it.courseId == course.id }.forEach {
            val text = Html.fromHtml(it.description, Html.FROM_HTML_MODE_LEGACY).toString().trim()
            if (text.isNotBlank()) add(StudySource("module:${it.id}", it.name.take(200), text))
        }
    }.distinctBy { it.id }
    private fun stamp(snapshot: PortalSnapshot, course: Course) = practiceDigest(listOf(snapshot.courses.any { it.id == course.id && it.accountId == course.accountId }.toString()) + metadata(course, snapshot.sections["${course.accountId}:${course.id}"].orEmpty(), snapshot.assignments).flatMap { listOf(it.id, it.title, it.text) } + (snapshot.sections["${course.accountId}:${course.id}"].orEmpty().flatMap { it.modules }.flatMap { it.files } + snapshot.assignments.filter { it.courseId == course.id && it.accountId == course.accountId }.flatMap { it.files }).flatMap { listOf(it.name, it.url, it.size.toString()) })
    private fun reset(open: Boolean, course: Course? = null) {
        operation?.cancel()
        epoch++
        val session = state.value.session + 1
        fingerprint = ""
        sections = emptyList()
        assignments = emptyList()
        state.value = CodeTutorState(open = open, course = course, session = session)
    }
    fun open(course: Course? = null, sections: List<CourseSection> = emptyList(), assignments: List<Assignment> = emptyList()) {
        reset(true, course)
        this.sections = sections
        this.assignments = assignments
        reload()
    }
    override fun close() = reset(false)
    private fun launch(problem: TutorProblem, block: suspend () -> Unit) {
        if (state.value.busy) return
        val token = ++epoch
        state.value = state.value.copy(busy = true, problem = null)
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
    override fun reload() = launch(TutorProblem.LOAD) {
        val course = state.value.course
        if (course == null) {
            val connection = ai.connection()
            currentCoroutineContext().ensureActive()
            state.value = state.value.copy(connection = connection)
        } else {
            check(accounts.accounts.first().any { it.id == course.accountId })
            state.value = state.value.copy(sourceChanged = true, catalogChecked = false, result = null, submitted = null, revealedHints = 0)
            val snapshot = portal.snapshot.first()
            val localSections = snapshot.sections["${course.accountId}:${course.id}"] ?: sections
            val localAssignments = snapshot.assignments.filter { it.accountId == course.accountId && it.courseId == course.id }.ifEmpty { assignments.filter { it.accountId == course.accountId && it.courseId == course.id } }
            val loaded = withContext(io) { Triple(metadata(course, localSections, localAssignments) + contextLoader.load(course, localSections, localAssignments), codeLoader.load(course, localSections, localAssignments), stamp(snapshot, course)) }
            val connection = ai.connection()
            val latest = portal.snapshot.first()
            currentCoroutineContext().ensureActive()
            if (loaded.third != stamp(latest, course)) {
                state.value = state.value.copy(sources = emptyList(), selected = emptySet(), catalog = TutorCodeCatalog(), sourceChanged = true, problem = TutorProblem.CHANGED)
            } else {
                fingerprint = loaded.third
                state.value = state.value.copy(sources = loaded.first.distinctBy { it.id }, catalog = loaded.second, selected = emptySet(), connection = connection, sourceChanged = false, catalogChecked = true)
            }
        }
    }
    private fun input(change: (CodeTutorState) -> CodeTutorState) {
        if (state.value.busy) return
        state.value = change(state.value).copy(result = null, submitted = null, revealedHints = 0, problem = null)
    }
    override fun code(value: String) {
        if (value.length > 48_000) {
            if (!state.value.busy) state.value = state.value.copy(problem = TutorProblem.INPUT)
            return
        }
        input { old -> old.copy(code = value, to = if (old.to.toIntOrNull() == TutorRules.lines(old.code).size) TutorRules.lines(value).size.toString() else old.to) }
    }
    override fun range(from: String, to: String) = input { it.copy(from = from.take(7), to = to.take(7)) }
    override fun codeLanguage(value: String) = input { it.copy(codeLanguage = value) }
    override fun language(value: String) = input { it.copy(language = value) }
    override fun question(value: String) = input { it.copy(question = value.take(1200)) }
    override fun environment(value: String) = input { it.copy(environment = value.take(300)) }
    override fun use(source: TutorCodeSource) {
        if (state.value.catalog.sources.none { it == source } || source.code.length > 48_000) return
        input { it.copy(code = source.code, from = "1", to = minOf(300, TutorRules.lines(source.code).size).toString(), codeLanguage = source.codeLanguage, imported = source) }
    }
    override fun select(id: String) = input { old ->
        val source = old.sources.firstOrNull { it.id == id }
        if (source == null || source.text.length > 12_000 || id !in old.selected && old.selected.size >= 4) old else old.copy(selected = if (id in old.selected) old.selected - id else old.selected + id)
    }
    override fun analyze() {
        val current = state.value
        val request = current.request
        if (!current.canAnalyze || request == null) {
            state.value = current.copy(problem = TutorProblem.INPUT)
            return
        }
        state.value = current.copy(result = null, submitted = request, revealedHints = 0, tab = current.tab)
        launch(TutorProblem.GENERATE) {
            val result = ai.tutor(current.course, request)
            currentCoroutineContext().ensureActive()
            TutorRules.result(result, request)
            check(state.value.request?.version == request.version)
            state.value = state.value.copy(result = result)
        }
    }
    override fun cancel() {
        epoch++
        operation?.cancel()
        state.value = state.value.copy(busy = false, problem = TutorProblem.CANCELED)
    }
    override fun reveal() {
        val current = state.value
        if (!current.busy && current.result != null) state.value = current.copy(revealedHints = minOf(current.revealedHints + 1, current.result.hints.size))
    }
    override fun tab(value: TutorTab) {
        if (!state.value.busy) state.value = state.value.copy(tab = value)
    }
    override fun edit() = input { it.copy(tab = TutorTab.EXPLAIN, session = it.session + 1) }
    override fun configure(mode: StudyConnectionMode, endpoint: String, secret: String, model: String, done: () -> Unit) = launch(TutorProblem.CONNECTION) {
        if (mode == StudyConnectionMode.DIRECT) ai.configureDirect(secret, model) else ai.configure(endpoint, secret)
        val connection = ai.connection()
        currentCoroutineContext().ensureActive()
        state.value = state.value.copy(connection = connection)
        done()
    }
    override fun disconnect(done: () -> Unit) = launch(TutorProblem.CONNECTION) {
        ai.disconnect()
        currentCoroutineContext().ensureActive()
        state.value = state.value.copy(connection = StudyConnection())
        done()
    }
}
