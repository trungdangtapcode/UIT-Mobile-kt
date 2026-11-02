package vn.edu.uit.mobile.courses

import android.text.Html
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.model.*

enum class PracticeStage { LIBRARY, SELECT, PREVIEW, PRACTICE, RESULTS }
enum class PracticeProblem { LOAD, INPUT, GENERATE, SAVE, CHANGED, REMOVED, CANCELED, SOURCE }
data class PracticeRun(val accountId: String, val setId: String, val ids: List<String>, val index: Int = 0, val attemptId: String = UUID.randomUUID().toString(), val selected: Int? = null, val revealed: Boolean = false, val pending: PracticeAttempt? = null, val recorded: Boolean = false, val outcomes: Map<String, Boolean> = emptyMap())
data class PracticeState(
    val open: Boolean = false,
    val course: Course? = null,
    val owners: List<Account> = emptyList(),
    val collections: List<PracticeCollection> = emptyList(),
    val stage: PracticeStage = PracticeStage.LIBRARY,
    val sources: List<StudySource> = emptyList(),
    val selected: Set<String> = emptySet(),
    val connection: StudyConnection = StudyConnection(),
    val options: PracticeOptions = PracticeOptions(),
    val draft: PracticeSet? = null,
    val generationMessage: String = "",
    val run: PracticeRun? = null,
    val busy: Boolean = false,
    val problem: PracticeProblem? = null,
    val sourceChanged: Boolean = false,
    val catalogChecked: Boolean = false,
    val session: Long = 0
) {
    val records get() = collections.filter { !it.failed && owners.any { a -> a.id == it.accountId } }.flatMap { it.records }.filter { course == null || it.set.accountId == course.accountId && it.set.courseId == course.id }
    val record get() = records.firstOrNull { it.set.accountId == run?.accountId && it.set.id == run?.setId }
    val question get() = run?.let { r -> record?.set?.questions?.firstOrNull { it.id == r.ids.getOrNull(r.index) } }
    val storageFailed get() = course?.let { c -> collections.any { it.accountId == c.accountId && it.failed } } ?: collections.any { it.failed }
}

/** Only reads cached portal snapshots. It has no university transport, login or mutation calls. */
class PracticeViewModel(private val ai: StudyAiRepository, private val library: PracticeRepository, private val accounts: AccountRepository, private val portal: PortalRepository, private val offline: OfflineCourseSourceLoader = OfflineCourseSourceLoader { _, _, _ -> emptyList() }, private val io: CoroutineDispatcher = Dispatchers.IO) :
    ViewModel(),
    PracticeActions {
    private val state = MutableStateFlow(PracticeState())
    val uiState = state.asStateFlow()
    private var operation: Job? = null
    private var epoch = 0L
    private var sections = emptyList<CourseSection>()
    private var assignments = emptyList<Assignment>()
    private var metadata = ""
    private var knownCollections = emptyList<PracticeCollection>()
    init {
        viewModelScope.launch {
            accounts.accounts.collect { owners ->
                val old = state.value
                val removedIds = old.owners.map { it.id }.toSet() - owners.map { it.id }.toSet()
                knownCollections = knownCollections.filterNot { it.accountId in removedIds }
                val removed = old.course?.accountId?.let { id -> owners.none { it.id == id } } == true || old.record?.set?.accountId?.let { id -> owners.none { it.id == id } } == true
                if (removed) {
                    operation?.cancel()
                    epoch++
                    sections = emptyList()
                    assignments = emptyList()
                    metadata = ""
                    state.value = PracticeState(open = old.open, owners = owners, collections = knownCollections.filter { c -> owners.any { it.id == c.accountId } }, problem = PracticeProblem.REMOVED, session = old.session + 1)
                } else {
                    state.value = old.copy(owners = owners, collections = knownCollections.filter { c -> owners.any { it.id == c.accountId } })
                }
            }
        }
        viewModelScope.launch {
            try {
                library.collections.collect { data ->
                    knownCollections = data
                    val old = state.value
                    val visible = data.filter { c -> old.owners.any { it.id == c.accountId } }
                    val current = old.record
                    val next = visible.flatMap { it.records }.firstOrNull { it.set.accountId == old.run?.accountId && it.set.id == old.run?.setId }
                    if (old.run != null && current != null && (next == null || next.set != current.set)) {
                        operation?.cancel()
                        epoch++
                        state.value = old.copy(collections = visible, stage = PracticeStage.LIBRARY, run = null, busy = false, problem = PracticeProblem.CHANGED, session = old.session + 1)
                    } else {
                        state.value = old.copy(collections = visible)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                state.value = state.value.copy(problem = PracticeProblem.LOAD, busy = false)
            }
        }
        viewModelScope.launch {
            portal.snapshot.collect { snapshot ->
                val current = state.value
                val course = current.course
                if (current.open && course != null && current.catalogChecked && metadata != stamp(snapshot, course)) {
                    if (current.stage in setOf(PracticeStage.SELECT, PracticeStage.PREVIEW)) {
                        operation?.cancel()
                        epoch++
                    }
                    state.value = current.copy(sourceChanged = true, draft = null, busy = if (current.stage in setOf(PracticeStage.SELECT, PracticeStage.PREVIEW)) false else current.busy, problem = PracticeProblem.CHANGED, stage = if (current.stage == PracticeStage.PREVIEW) PracticeStage.SELECT else current.stage)
                }
            }
        }
    }
    private fun stamp(snapshot: PortalSnapshot, course: Course) = practiceDigest(listOf(snapshot.courses.any { it.id == course.id && it.accountId == course.accountId }.toString()) + catalog(course, snapshot.sections["${course.accountId}:${course.id}"].orEmpty(), snapshot.assignments).flatMap { listOf(it.id, it.title, it.text) })
    private fun catalog(course: Course, sections: List<CourseSection>, assignments: List<Assignment>) = buildList {
        assignments.filter { it.accountId == course.accountId && it.courseId == course.id }.forEach { a ->
            val text = Html.fromHtml(a.intro, Html.FROM_HTML_MODE_LEGACY).toString().trim()
            if (text.isNotBlank())add(StudySource("assignment:${a.id}", a.name.take(200), text))
        }
        sections.flatMap { it.modules }.filter { it.courseId == course.id }.forEach { m ->
            val text = Html.fromHtml(m.description, Html.FROM_HTML_MODE_LEGACY).toString().trim()
            if (text.isNotBlank())add(StudySource("module:${m.id}", m.name.take(200), text))
        }
    }.distinctBy { it.id }
    private fun reset(open: Boolean, course: Course? = null, stage: PracticeStage = PracticeStage.LIBRARY) {
        operation?.cancel()
        epoch++
        val old = state.value
        state.value = PracticeState(open, course, old.owners, old.collections, stage, session = old.session + 1)
    }
    fun openSaved() {
        reset(true)
        reload()
    }
    override fun close() {
        reset(false)
        sections = emptyList()
        assignments = emptyList()
        metadata = ""
    }
    fun open(course: Course, sections: List<CourseSection>, assignments: List<Assignment>) {
        reset(true, course, PracticeStage.SELECT)
        this.sections = sections
        this.assignments = assignments
        reload()
    }
    private fun fail(problem: PracticeProblem) {
        state.value = state.value.copy(problem = problem, busy = false)
    }
    private fun launch(problem: PracticeProblem, block: suspend () -> Unit) {
        if (state.value.busy)return
        val token = ++epoch
        state.value = state.value.copy(busy = true, problem = null)
        operation = viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (token == epoch)fail(problem)
            } finally {
                if (token == epoch)state.value = state.value.copy(busy = false)
            }
        }
    }
    override fun reload() {
        if (state.value.busy)return
        val course = state.value.course
        if (course == null || state.value.stage == PracticeStage.LIBRARY) {
            launch(PracticeProblem.LOAD) {
                val data = library.collections.first()
                val connection = ai.connection()
                currentCoroutineContext().ensureActive()
                knownCollections = data
                state.value = state.value.copy(collections = data.filter { c -> state.value.owners.any { it.id == c.accountId } }, connection = connection)
            }
            return
        }
        state.value = state.value.copy(sourceChanged = true, catalogChecked = false)
        launch(PracticeProblem.SOURCE) {
            check(accounts.accounts.first().any { it.id == course.accountId })
            val snapshot = portal.snapshot.first()
            val localSections = snapshot.sections["${course.accountId}:${course.id}"] ?: sections
            val localAssignments = snapshot.assignments.filter { it.accountId == course.accountId && it.courseId == course.id }.ifEmpty { assignments.filter { it.accountId == course.accountId && it.courseId == course.id } }
            val (sources, sourceStamp) = withContext(io) {
                val stamp = stamp(snapshot, course)
                (catalog(course, localSections, localAssignments) + offline.load(course, localSections, localAssignments)) to stamp
            }
            val connection = ai.connection()
            val latest = portal.snapshot.first()
            currentCoroutineContext().ensureActive()
            if (sourceStamp != stamp(latest, course)) {
                metadata = ""
                state.value = state.value.copy(sources = emptyList(), selected = emptySet(), draft = null, sourceChanged = true, catalogChecked = false, problem = PracticeProblem.CHANGED, stage = PracticeStage.SELECT)
                return@launch
            }
            metadata = sourceStamp
            state.value = state.value.copy(sources = sources.distinctBy { it.id }, selected = emptySet(), draft = null, generationMessage = "", connection = connection, sourceChanged = false, catalogChecked = true, stage = PracticeStage.SELECT)
        }
    }
    override fun select(id: String) {
        val current = state.value
        if (current.busy)return
        val source = current.sources.firstOrNull { it.id == id } ?: return
        if (id !in current.selected && (current.selected.size >= 5 || source.text.length > 12000))return
        state.value = current.copy(selected = if (id in current.selected)current.selected - id else current.selected + id, problem = null)
    }
    override fun options(value: PracticeOptions) {
        if (!state.value.busy)state.value = state.value.copy(options = value, problem = null)
    }
    override fun excerpt(title: String, text: String, done: () -> Unit) {
        if (state.value.busy)return
        if (title.isBlank() || title.length > 200 || text.isBlank() || text.length > 12000) {
            fail(PracticeProblem.INPUT)
            return
        }
        state.value = state.value.copy(sources = state.value.sources + StudySource("excerpt:${UUID.randomUUID()}", title, text), problem = null)
        done()
    }
    override fun configure(mode: StudyConnectionMode, endpoint: String, secret: String, model: String, done: () -> Unit) = launch(PracticeProblem.SAVE) {
        if (mode == StudyConnectionMode.DIRECT)ai.configureDirect(secret, model)else ai.configure(endpoint, secret)
        currentCoroutineContext().ensureActive()
        state.value = state.value.copy(connection = ai.connection())
        done()
    }
    override fun disconnect(done: () -> Unit) = launch(PracticeProblem.SAVE) {
        ai.disconnect()
        currentCoroutineContext().ensureActive()
        state.value = state.value.copy(connection = StudyConnection())
        done()
    }
    override fun cancel() {
        epoch++
        operation?.cancel()
        state.value = state.value.copy(busy = false, problem = PracticeProblem.CANCELED)
    }
    override fun generate() {
        val current = state.value
        val course = current.course ?: return
        val sources = current.sources.filter { it.id in current.selected }
        if (runCatching {
                PracticeRules.request(sources, current.options)
                check(current.connection.configured && !current.sourceChanged && !current.storageFailed)
            }.isFailure
        ) {
            fail(PracticeProblem.INPUT)
            return
        }
        launch(PracticeProblem.GENERATE) {
            val generated = ai.generatePractice(course, sources, current.options)
            currentCoroutineContext().ensureActive()
            PracticeRules.generation(generated, sources, current.options)
            if (generated.status == "insufficient_evidence") {
                state.value = state.value.copy(generationMessage = generated.message, draft = null)
            } else {
                state.value = state.value.copy(draft = PracticeSet(UUID.randomUUID().toString(), course.accountId, course.id, course.shortname.ifBlank { course.fullname }.ifBlank { course.id.toString() }.take(240), current.options.topic.ifBlank { course.shortname }.ifBlank { course.fullname }.ifBlank { course.id.toString() }.take(160), generated.model, generated.generatedAt, current.options.language, sources, generated.questions), stage = PracticeStage.PREVIEW, generationMessage = "")
            }
        }
    }
    override fun title(value: String) {
        if (!state.value.busy)state.value = state.value.copy(draft = state.value.draft?.copy(title = value.take(160)))
    }
    override fun edit(question: PracticeQuestion, done: () -> Unit) {
        val draft = state.value.draft ?: return
        if (state.value.busy)return
        val next = draft.copy(questions = draft.questions.map { if (it.id == question.id)question else it })
        if (runCatching { PracticeRules.set(next) }.isFailure) {
            fail(PracticeProblem.INPUT)
            return
        }
        state.value = state.value.copy(draft = next, problem = null)
        done()
    }
    override fun removeQuestion(id: String) {
        val draft = state.value.draft ?: return
        if (state.value.busy)return
        state.value = state.value.copy(draft = draft.copy(questions = draft.questions.filterNot { it.id == id }), problem = null)
    }
    override fun reorder(id: String, offset: Int) {
        val draft = state.value.draft ?: return
        if (state.value.busy)return
        val q = draft.questions.toMutableList()
        val i = q.indexOfFirst { it.id == id }
        if (i >= 0 && i + offset in q.indices) {
            java.util.Collections.swap(q, i, i + offset)
            state.value = state.value.copy(draft = draft.copy(questions = q))
        }
    }
    override fun discard() {
        if (!state.value.busy)state.value = state.value.copy(draft = null, stage = if (state.value.course == null)PracticeStage.LIBRARY else PracticeStage.SELECT, problem = null)
    }
    override fun bank() {
        if (!state.value.busy)state.value = state.value.copy(stage = PracticeStage.LIBRARY, run = null, problem = null)
    }
    override fun selection() {
        if (state.value.course != null && !state.value.busy)state.value = state.value.copy(stage = PracticeStage.SELECT, problem = null)
    }
    override fun save() {
        val current = state.value
        val set = current.draft ?: return
        if (current.sourceChanged || runCatching { PracticeRules.set(set) }.isFailure) {
            fail(PracticeProblem.INPUT)
            return
        }
        launch(PracticeProblem.SAVE) {
            library.save(set)
            currentCoroutineContext().ensureActive()
            state.value = state.value.copy(stage = PracticeStage.LIBRARY, draft = null)
        }
    }
    override fun remove(record: PracticeRecord, done: () -> Unit) = launch(PracticeProblem.SAVE) {
        library.remove(record)
        currentCoroutineContext().ensureActive()
        done()
    }
    override fun start(record: PracticeRecord, missedOnly: Boolean) {
        if (state.value.busy || state.value.records.none { it.set == record.set })return
        val ids = record.set.questions.filter { q -> !missedOnly || record.progress.any { it.questionId == q.id && !it.lastOutcome } }.map { it.id }
        if (ids.isEmpty()) {
            fail(PracticeProblem.INPUT)
            return
        }
        state.value = state.value.copy(stage = PracticeStage.PRACTICE, run = PracticeRun(record.set.accountId, record.set.id, ids), problem = null)
    }
    override fun choose(index: Int) {
        val q = state.value.question ?: return
        val run = state.value.run ?: return
        if (!state.value.busy && !run.revealed && index in q.choices.indices)state.value = state.value.copy(run = run.copy(selected = index))
    }
    override fun reveal() {
        val run = state.value.run ?: return
        if (!state.value.busy && state.value.question?.kind == PracticeKind.FLASHCARD)state.value = state.value.copy(run = run.copy(revealed = true))
    }
    override fun answer(recalled: Boolean?) {
        val current = state.value
        val q = current.question ?: return
        val run = current.run ?: return
        if (current.busy || run.recorded || q.kind == PracticeKind.FLASHCARD && !run.revealed)return
        val value = run.pending ?: PracticeAttempt(run.attemptId, if (q.kind == PracticeKind.MCQ)run.selected else null, recalled)
        if (runCatching { PracticeRules.outcome(q, value) }.isFailure) {
            fail(PracticeProblem.INPUT)
            return
        }
        state.value = current.copy(run = run.copy(revealed = true, pending = value))
        retryAttempt()
    }
    override fun retryAttempt() {
        val current = state.value
        val run = current.run ?: return
        val record = current.record ?: return
        val q = current.question ?: return
        val attempt = run.pending ?: return
        launch(PracticeProblem.SAVE) {
            val updated = library.attempt(record.set, q.id, record.progress.firstOrNull { it.questionId == q.id }, attempt)
            currentCoroutineContext().ensureActive()
            val outcome = updated.progress.single { it.questionId == q.id }.lastOutcome
            state.value = state.value.copy(run = run.copy(recorded = true, outcomes = run.outcomes + (q.id to outcome)))
        }
    }
    override fun next() {
        val run = state.value.run ?: return
        if (state.value.busy || !run.recorded)return
        state.value = if (run.index + 1 == run.ids.size)state.value.copy(stage = PracticeStage.RESULTS, problem = null)else state.value.copy(run = run.copy(index = run.index + 1, attemptId = UUID.randomUUID().toString(), selected = null, revealed = false, pending = null, recorded = false), problem = null)
    }
}
