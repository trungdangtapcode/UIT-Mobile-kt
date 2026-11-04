package vn.edu.uit.mobile.schedule

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.datetime.Clock
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.model.*

enum class WorkloadProblem { LOAD, SAVE, INPUT, ANALYSIS }
data class WorkloadState(val owners: List<Account> = emptyList(), val data: List<WorkloadAccount> = emptyList(), val selectedId: String? = null, val result: WorkloadResult? = null, val loading: Boolean = true, val busy: Boolean = false, val problem: WorkloadProblem? = null, val session: Long = 0) {
    val selected: WorkloadAccount? get() = data.firstOrNull { it.account.id == selectedId && owners.any { owner -> owner.id == it.account.id } }
}

class WorkloadViewModel(
    private val repository: WorkloadRepository,
    accounts: AccountRepository,
    private val personalStorage: FreeTimeBlockStorage? = null,
    private val clock: () -> Long = { Clock.System.now().epochSeconds },
    private val computation: CoroutineDispatcher = Dispatchers.Default,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    sync: Flow<SyncState> = flowOf(SyncState())
) : ViewModel() {
    private val state = MutableStateFlow(WorkloadState())
    val uiState = state.asStateFlow()
    private var active = false
    private var observation = 0L
    private var observer: Job? = null
    private var calculator: Job? = null
    private var timer: Job? = null
    private var mutation: Job? = null
    private var personal: List<PersonalTimeBlock> = emptyList()
    private var personalFailed = false
    private var refreshFailed = false
    init {
        viewModelScope.launch {
            sync.collect {
                refreshFailed = it.error != null
                calculate()
            }
        }
        viewModelScope.launch {
            accounts.accounts.collect { owners ->
                val changed = state.value.owners.map { it.id }.toSet() != owners.map { it.id }.toSet()
                val selected = state.value.selectedId?.takeIf { id -> owners.any { it.id == id } } ?: owners.firstOrNull { it.portal == PortalType.MOODLE }?.id ?: owners.firstOrNull()?.id
                if (changed) {
                    mutation?.cancel()
                    calculator?.cancel()
                    personal = emptyList()
                    state.value = WorkloadState(owners, selectedId = selected, session = state.value.session + 1)
                    if (active) observe()
                } else {
                    state.value = state.value.copy(owners = owners)
                }
            }
        }
    }
    fun open() {
        active = true
        observe()
        timer?.cancel()
        timer = viewModelScope.launch {
            while (isActive) {
                delay(60_000)
                calculate()
            }
        }
    }
    fun close() {
        active = false
        observation++
        observer?.cancel()
        calculator?.cancel()
        timer?.cancel()
        mutation?.cancel()
        state.value = state.value.copy(result = null, busy = false, session = state.value.session + 1)
    }
    fun select(id: String) {
        if (state.value.owners.none { it.id == id } || state.value.selectedId == id) return
        mutation?.cancel()
        calculator?.cancel()
        state.value = state.value.copy(selectedId = id, result = null, busy = false, problem = null, session = state.value.session + 1)
        calculate()
    }
    fun reload() {
        if (active) observe()
    }
    private fun observe() {
        observer?.cancel()
        calculator?.cancel()
        state.value = state.value.copy(loading = true, problem = null)
        val token = ++observation
        val ids = state.value.owners.map { it.id }.toSet()
        observer = viewModelScope.launch {
            try {
                personalFailed = false
                personal = try {
                    withContext(io) { personalStorage?.loadStrict(ids).orEmpty() }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    personalFailed = true
                    emptyList()
                }
                repository.accounts.collect { data ->
                    if (token != observation) return@collect
                    val connected = state.value.owners.map { it.id }.toSet()
                    state.value = state.value.copy(data = data.filter { it.account.id in connected }, loading = false)
                    calculate()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (token == observation) state.value = state.value.copy(loading = false, problem = WorkloadProblem.LOAD, result = null)
            }
        }
    }
    private fun calculate() {
        if (!active) return
        calculator?.cancel()
        val current = state.value
        val selected = current.selected ?: return
        if (selected.storageFailed) {
            state.value = current.copy(result = null, loading = false, problem = WorkloadProblem.LOAD)
            return
        }
        state.value = current.copy(loading = true)
        val blocks = personal
        val blocksFailed = personalFailed
        val workspaceFailed = refreshFailed
        calculator = viewModelScope.launch {
            try {
                val result = withContext(computation) { WorkloadEngine().analyze(selected.account.id, current.data, selected.policy, clock(), blocks, blocksFailed, workspaceFailed) }
                if (current.session == state.value.session) state.value = state.value.copy(result = result, loading = false, problem = state.value.problem?.takeUnless { it == WorkloadProblem.ANALYSIS || it == WorkloadProblem.LOAD })
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (current.session == state.value.session) state.value = state.value.copy(result = null, loading = false, problem = WorkloadProblem.ANALYSIS)
            }
        }
    }
    private fun save(onSaved: () -> Unit, action: suspend () -> Unit) {
        if (state.value.busy || state.value.selected == null || state.value.selected?.storageFailed == true) return
        val session = state.value.session
        state.value = state.value.copy(busy = true, problem = null)
        mutation = viewModelScope.launch {
            try {
                action()
                val data = repository.accounts.first()
                if (session == state.value.session) {
                    val ids = state.value.owners.map { it.id }.toSet()
                    state.value = state.value.copy(data = data.filter { it.account.id in ids })
                    calculate()
                    onSaved()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (session == state.value.session) state.value = state.value.copy(problem = WorkloadProblem.SAVE)
            } finally {
                if (session == state.value.session) state.value = state.value.copy(busy = false)
            }
        }
    }
    fun policy(value: WorkloadPolicy, onSaved: () -> Unit = {}) {
        val owner = state.value.selected ?: return
        if (runCatching { validateWorkloadPolicy(value) }.isFailure) {
            state.value = state.value.copy(problem = WorkloadProblem.INPUT)
            return
        }
        save(onSaved) { repository.savePolicy(owner.account.id, value) }
    }
    fun estimate(ref: WorkloadRef, remaining: String, duration: String, onSaved: () -> Unit = {}) {
        val result = state.value.result ?: return
        val task = (result.tasks + result.overdue).firstOrNull { it.item.ref == ref } ?: return
        val value = runCatching {
            val estimate = WorkloadEstimate(ref, task.item.title.take(240), remaining.trim().takeIf(String::isNotEmpty)?.toInt(), duration.trim().takeIf(String::isNotEmpty)?.toInt())
            if (estimate.remainingMinutes != null || estimate.examMinutes != null) validateWorkloadEstimate(estimate)
            estimate
        }.getOrElse {
            state.value = state.value.copy(problem = WorkloadProblem.INPUT)
            return
        }
        save(onSaved) { if (value.remainingMinutes == null && value.examMinutes == null) repository.removeEstimate(ref) else repository.saveEstimate(value) }
    }
    fun remove(ref: WorkloadRef) {
        val result = state.value.result ?: return
        if (result.orphanEstimates.none { it.ref == ref }) return
        save({}) { repository.removeEstimate(ref) }
    }
}
