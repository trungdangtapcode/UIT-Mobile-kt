package vn.edu.uit.mobile.schedule

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.datetime.*
import vn.edu.uit.mobile.common.*
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.model.*

enum class RevisionMessage { LOAD, SAVE, INPUT, CHANGED, CONFLICT, GENERATED }
data class RevisionState(val base: WorkloadState = WorkloadState(), val saved: RevisionWorkspace = RevisionWorkspace(), val failed: Boolean = false, val selectedExam: String? = null, val draft: RevisionDraft? = null, val busy: Boolean = false, val message: RevisionMessage? = null, val now: Long = 0) {
    val owner get() = base.selected?.takeIf { it.account.portal != PortalType.MOODLE }
    val exams get() = owner?.let { revisionItems(it).filter { item -> item.ref.kind == WorkloadKind.EXAM && item.due > now } }.orEmpty()
    val plan get() = saved.plans.firstOrNull { it.exam.id == selectedExam }
}
private fun revisionItems(owner: WorkloadAccount): List<WorkloadItem> = runCatching { WorkloadSourceIndex().items(owner) }.getOrDefault(emptyList())

/** Shares the activity-owned workload source observer; never reads the flattened workspace. */
class RevisionViewModel(private val repository: RevisionRepository, val workload: WorkloadViewModel, private val clock: () -> Long = { Clock.System.now().epochSeconds }, private val computation: CoroutineDispatcher = Dispatchers.Default) : ViewModel() {
    private val state = MutableStateFlow(RevisionState())
    val uiState = state.asStateFlow()
    private var observer: Job? = null
    private var action: Job? = null
    private var generation = 0L
    private var pending: RevisionWorkspace? = null
    fun open() {
        observer?.cancel()
        workload.open()
        observer = viewModelScope.launch {
            try {
                combine(workload.uiState, repository.accounts) { base, local -> base to local }.collect { (base, local) ->
                    val allowed = base.owners.filter { it.portal != PortalType.MOODLE }
                    if (allowed.isNotEmpty() && allowed.none { it.id == base.selectedId }) {
                        workload.select(allowed.first().id)
                        return@collect
                    }
                    val old = state.value
                    val owner = base.selected?.takeIf { it.account.portal != PortalType.MOODLE }
                    val saved = local.firstOrNull { it.accountId == owner?.account?.id }
                    val changed = old.base.selectedId != base.selectedId || old.base.session != base.session
                    val dataChanged = old.base.data != base.data || old.saved != saved?.workspace && saved?.workspace != pending
                    if (changed || dataChanged) {
                        generation++
                        action?.cancel()
                    }
                    val workspace = saved?.workspace ?: RevisionWorkspace()
                    val selection = old.selectedExam?.takeIf { id -> workspace.plans.any { it.exam.id == id } || owner?.let { revisionItems(it).any { item -> item.ref.id == id } } == true }
                    state.value = old.copy(
                        base = base,
                        saved = workspace,
                        failed = saved?.failed == true,
                        selectedExam = if (changed) null else selection,
                        draft = if (changed || dataChanged) null else old.draft,
                        busy = if (changed || dataChanged) false else old.busy,
                        message = if (old.draft != null && (changed || dataChanged)) {
                            RevisionMessage.CHANGED
                        } else if (changed) {
                            null
                        } else {
                            old.message
                        },
                        now = clock()
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                state.value = state.value.copy(failed = true, message = RevisionMessage.LOAD, busy = false)
            }
        }
    }
    fun close() {
        generation++
        observer?.cancel()
        action?.cancel()
        pending = null
        workload.close()
        state.value = RevisionState()
    }
    fun selectOwner(id: String) {
        if (state.value.base.owners.any { it.id == id && it.portal != PortalType.MOODLE }) workload.select(id)
    }
    fun selectExam(id: String) {
        state.value = state.value.copy(selectedExam = id, message = null)
    }
    fun reload() {
        close()
        open()
    }
    private fun fail(message: RevisionMessage) {
        state.value = state.value.copy(message = message, busy = false)
    }
    private fun change(done: () -> Unit = {}, scheduling: Boolean = false, build: (RevisionState) -> RevisionWorkspace) {
        val current = state.value
        val id = current.owner?.account?.id ?: return
        if (current.busy || current.failed) return
        if (workload.uiState.value.session != current.base.session || workload.uiState.value.selectedId != current.base.selectedId) {
            fail(RevisionMessage.CHANGED)
            return
        }
        if (scheduling && (workload.uiState.value != current.base || current.base.loading || current.base.busy)) {
            fail(RevisionMessage.CHANGED)
            return
        }
        val value = runCatching { build(current).also { validateRevision(it, id) } }.getOrElse {
            fail(RevisionMessage.INPUT)
            return
        }
        val token = generation
        pending = value
        state.value = current.copy(busy = true, message = null)
        action = viewModelScope.launch {
            try {
                repository.replace(id, current.saved, value)
                if (token == generation) {
                    state.value = state.value.copy(saved = value, draft = null, busy = false)
                    done()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (token == generation) fail(RevisionMessage.SAVE)
            } finally {
                if (pending == value) pending = null
            }
        }
    }
    fun addExam(id: String) = change {
        val exam = it.exams.single { e -> e.ref.id == id && !e.conflict }
        require(it.saved.plans.none { p -> p.exam == exam.ref })
        it.saved.copy(plans = it.saved.plans + RevisionPlan(exam.ref, exam.title, exam.due))
    }
    fun topic(ref: WorkloadRef, id: String?, title: String, minutes: String, done: () -> Unit) = change(done) { s ->
        val value = StudyTopic(id ?: UUID.randomUUID().toString(), title.trim(), minutes.toInt())
        s.saved.copy(
            plans = s.saved.plans.map { p ->
                if (p.exam != ref) {
                    p
                } else {
                    p.copy(
                        topics = if (id == null) p.topics + value else p.topics.map { if (it.id == id) value else it },
                        sessions = p.sessions.mapNotNull { session ->
                            if (session.topicId != id || session.status != RevisionStatus.PLANNED || session.locked) {
                                session
                            } else if (session.end <= clock()) {
                                session.copy(status = RevisionStatus.MISSED)
                            } else {
                                null
                            }
                        }
                    )
                }
            }
        )
    }
    fun reorder(ref: WorkloadRef, id: String, offset: Int) = change { s ->
        s.saved.copy(
            plans = s.saved.plans.map { p ->
                if (p.exam != ref) {
                    p
                } else {
                    val items = p.topics.toMutableList()
                    val at = items.indexOfFirst { it.id == id }
                    require(at >= 0 && at + offset in items.indices)
                    java.util.Collections.swap(items, at, at + offset)
                    p.copy(topics = items)
                }
            }
        )
    }
    fun archive(ref: WorkloadRef) = change { s -> s.saved.copy(plans = s.saved.plans.map { if (it.exam == ref) it.copy(active = !it.active) else it }) }
    fun removePlan(ref: WorkloadRef) = change { it.saved.copy(plans = it.saved.plans.filterNot { p -> p.exam == ref }) }
    fun relink(ref: WorkloadRef, examId: String, done: () -> Unit) = change(done) { s ->
        val exam = s.exams.single { it.ref.id == examId && !it.conflict }
        s.saved.copy(plans = s.saved.plans.map { if (it.exam == ref) it.copy(exam = exam.ref, title = exam.title, due = exam.due) else it })
    }
    fun session(ref: WorkloadRef, id: String, status: RevisionStatus? = null, toggleLock: Boolean = false) = change { s ->
        s.saved.copy(
            plans = s.saved.plans.map { p ->
                if (p.exam != ref) {
                    p
                } else {
                    p.copy(
                        sessions = p.sessions.map { item ->
                            if (item.id != id) {
                                item
                            } else {
                                require(item.status == RevisionStatus.PLANNED)
                                if (status == RevisionStatus.COMPLETED) require(item.end <= clock())
                                item.copy(
                                    status = status ?: item.status,
                                    locked = if (status == RevisionStatus.MISSED) {
                                        false
                                    } else if (toggleLock) {
                                        !item.locked
                                    } else {
                                        item.locked
                                    }
                                )
                            }
                        }
                    )
                }
            }
        )
    }
    fun move(ref: WorkloadRef, id: String, day: String, start: String, end: String, done: () -> Unit) = change(done, scheduling = true) { s ->
        val from = dateAt(LocalDate.parse(day), parsePlannerTime(start))
        val through = dateAt(LocalDate.parse(day), parsePlannerTime(end))
        require(from >= clock() && through > from)
        val candidate = s.saved.copy(
            plans = s.saved.plans.map { p ->
                if (p.exam != ref) {
                    p
                } else {
                    p.copy(
                        sessions = p.sessions.map { item ->
                            if (item.id != id) {
                                item
                            } else {
                                require(item.status == RevisionStatus.PLANNED)
                                item.copy(start = from, end = through, effort = minOf(item.effort, ((through - from) / 60).toInt()), locked = true)
                            }
                        }
                    )
                }
            }
        )
        require(RevisionPlanner().inspect(candidate, s.base.result ?: error("No capacity"), s.owner!!.policy, WorkloadSourceIndex().items(s.owner!!), clock()).isEmpty())
        candidate
    }
    fun options(maximum: String, rest: String, delay: String, done: () -> Unit) = change(done) {
        val options = RevisionOptions(maximum.toInt(), rest.toInt(), delay.toInt())
        require(options.maximumSession >= it.owner!!.policy.minimumSession)
        it.saved.copy(options = options)
    }
    fun generate() {
        val current = state.value
        val owner = current.owner ?: return
        val result = current.base.result ?: return
        if (current.busy || current.failed || current.base.loading) return
        val token = generation
        state.value = current.copy(busy = true, message = null, draft = null)
        action = viewModelScope.launch {
            try {
                val draft = withContext(computation) {
                    val context = currentCoroutineContext()
                    RevisionPlanner().generate(current.saved, result, owner.policy, WorkloadSourceIndex().items(owner), clock()) { context.ensureActive() }
                }
                if (token == generation) state.value = state.value.copy(draft = draft, busy = false, message = if (draft.problems.isEmpty()) RevisionMessage.GENERATED else RevisionMessage.CONFLICT)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (token == generation) fail(RevisionMessage.CONFLICT)
            }
        }
    }
    fun discard() {
        state.value = state.value.copy(draft = null, message = null)
    }
    fun saveDraft() = change(scheduling = true) { s ->
        val draft = s.draft ?: error("No draft")
        require(draft.problems.isEmpty())
        require(draft.workspace.plans.flatMap { it.sessions }.filter { it.status == RevisionStatus.PLANNED && s.saved.plans.flatMap { p -> p.sessions }.none { old -> old == it } }.all { it.start >= clock() })
        require(RevisionPlanner().inspect(draft.workspace, s.base.result ?: error("No capacity"), s.owner!!.policy, WorkloadSourceIndex().items(s.owner!!), clock()).isEmpty())
        draft.workspace
    }
}
