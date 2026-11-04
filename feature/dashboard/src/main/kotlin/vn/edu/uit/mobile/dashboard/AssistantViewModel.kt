package vn.edu.uit.mobile.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import vn.edu.uit.mobile.domain.*

enum class AssistantProblem { LOAD, ACTION, CONNECTION, GENERATION, ACCESS, QUOTA }
data class AssistantUiState(
    val library: AssistantLibrary = AssistantLibrary(),
    val selected: String? = null,
    val connection: StudyConnection = StudyConnection(),
    val busy: Boolean = false,
    val progress: AssistantProgress? = null,
    val problem: AssistantProblem? = null,
    val notices: AnnouncementInbox = AnnouncementInbox(),
    val forumChecks: Map<String, AssistantForumCheck> = emptyMap(),
    val checkingForum: Boolean = false,
    val preparingContext: Boolean = false
)
class AssistantViewModel(private val repository: AssistantRepository, private val study: StudyAiRepository, private val announcements: AnnouncementRepository) : ViewModel() {
    private val mutable = MutableStateFlow(AssistantUiState())
    val state = mutable.asStateFlow()
    private var request: Job? = null
    private var sourceCheck: Job? = null
    private var contextWork: Job? = null
    private var contextSerial = 0L
    init {
        viewModelScope.launch { repository.library.collect { library -> mutable.update { it.copy(library = library) } } }
        viewModelScope.launch { announcements.state.collect { notices -> mutable.update { it.copy(notices = notices) } } }
        action(AssistantProblem.LOAD) {
            repository.initialize()
            announcements.initialize()
            mutable.update { it.copy(connection = study.connection()) }
        }
    }
    private fun action(problem: AssistantProblem = AssistantProblem.ACTION, body: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                body()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                mutable.update { it.copy(problem = problem) }
            }
        }
    }
    fun select(id: String?) {
        stop()
        sourceCheck?.cancel()
        mutable.update { it.copy(selected = id, problem = null, checkingForum = false, forumChecks = emptyMap()) }
    }
    fun create(name: String, scope: AssistantScope) = prepareWorkspace(name, scope)
    fun createForForum(scope: AssistantForumScope) = prepareWorkspace(null, AssistantScope(emptySet(), forum = scope))
    private fun prepareWorkspace(name: String?, scope: AssistantScope) {
        stop()
        val token = contextSerial
        mutable.update { it.copy(selected = null, preparingContext = scope.forum != null, problem = null) }
        contextWork = viewModelScope.launch {
            try {
                val coverage = scope.forum?.let { repository.inspectForum(it) }
                currentCoroutineContext().ensureActive()
                if (token != contextSerial) return@launch
                val id = repository.create(name ?: requireNotNull(coverage).title.take(80), scope)
                currentCoroutineContext().ensureActive()
                if (token == contextSerial) mutable.update { it.copy(selected = id, forumChecks = emptyMap()) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (token == contextSerial) mutable.update { it.copy(problem = AssistantProblem.ACTION) }
            } finally {
                if (token == contextSerial) mutable.update { it.copy(preparingContext = false) }
            }
        }
    }
    fun deleteTurn(turnId: String) = action {
        val id = state.value.selected ?: return@action
        sourceCheck?.cancel()
        repository.deleteTurn(id, turnId)
        mutable.update { it.copy(checkingForum = false, forumChecks = it.forumChecks - turnId) }
    }
    fun recheckForum(turnId: String) {
        val id = state.value.selected ?: return
        if (state.value.busy || state.value.checkingForum) return
        mutable.update { it.copy(checkingForum = true) }
        sourceCheck = viewModelScope.launch {
            try {
                val check = repository.recheckForum(id, turnId)
                if (state.value.selected == id && state.value.library.workspaces.any { it.id == id }) mutable.update { it.copy(forumChecks = it.forumChecks + (turnId to check)) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (state.value.selected == id) mutable.update { it.copy(problem = AssistantProblem.ACTION) }
            } finally {
                if (state.value.selected == id) mutable.update { it.copy(checkingForum = false) }
            }
        }
    }
    fun createFor(url: String) = action {
        val item = announcements.state.value.items.first { it.url == url }
        prepareWorkspace(item.title.take(80), AssistantScope(setOf(item.source), setOf(url)))
    }
    fun rename(id: String, name: String) = action { repository.rename(id, name) }
    fun delete(id: String) {
        stop()
        action {
            repository.delete(id)
            if (state.value.selected == id) select(null)
        }
    }
    fun clear() {
        stop()
        action {
            repository.clear()
            select(null)
        }
    }
    fun refresh() = action { announcements.refresh() }
    fun configure(mode: StudyConnectionMode, endpoint: String, token: String, model: String) = action(AssistantProblem.CONNECTION) {
        stop()
        if (mode == StudyConnectionMode.DIRECT) study.configureDirect(token, model) else study.configure(endpoint, token)
        mutable.update { it.copy(connection = study.connection(), problem = null) }
    }
    fun disconnect() = action(AssistantProblem.CONNECTION) {
        stop()
        study.disconnect()
        mutable.update { it.copy(connection = study.connection()) }
    }
    fun dismiss() {
        mutable.update { it.copy(problem = null) }
    }
    fun ask(question: String, language: String) {
        val id = state.value.selected ?: return
        if (state.value.busy) return
        mutable.update { it.copy(busy = true, progress = AssistantProgress("thinking"), problem = null) }
        request = viewModelScope.launch {
            try {
                repository.ask(id, question, language) { progress -> mutable.update { it.copy(progress = progress) } }
            } catch (_: TimeoutCancellationException) {
                mutable.update { it.copy(problem = AssistantProblem.GENERATION) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = e.message.orEmpty()
                val problem = when {
                    "API key or project access" in message || "gateway token was rejected" in message -> AssistantProblem.ACCESS
                    "quota or rate limit" in message || "gateway is busy" in message -> AssistantProblem.QUOTA
                    else -> AssistantProblem.GENERATION
                }
                mutable.update { it.copy(problem = problem) }
            } finally {
                mutable.update { it.copy(busy = false, progress = null) }
            }
        }
    }
    fun stop() {
        request?.cancel()
        request = null
        contextSerial++
        contextWork?.cancel()
        contextWork = null
        sourceCheck?.cancel()
        sourceCheck = null
        mutable.update { it.copy(preparingContext = false, checkingForum = false) }
    }
    fun checklist(workspace: String, turn: String, items: List<AssistantChecklistItem>) = action { repository.checklist(workspace, turn, items) }
}
