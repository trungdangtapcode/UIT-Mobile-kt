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
    val notices: AnnouncementInbox = AnnouncementInbox()
)
class AssistantViewModel(private val repository: AssistantRepository, private val study: StudyAiRepository, private val announcements: AnnouncementRepository) : ViewModel() {
    private val mutable = MutableStateFlow(AssistantUiState())
    val state = mutable.asStateFlow()
    private var request: Job? = null
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
        mutable.update { it.copy(selected = id, problem = null) }
    }
    fun create(name: String, scope: AssistantScope) = action { select(repository.create(name, scope)) }
    fun createFor(url: String) = action {
        val item = announcements.state.value.items.first { it.url == url }
        select(repository.create(item.title.take(80), AssistantScope(setOf(item.source), setOf(url))))
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
    }
    fun checklist(workspace: String, turn: String, items: List<AssistantChecklistItem>) = action { repository.checklist(workspace, turn, items) }
}
