package vn.edu.uit.mobile.courses

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import vn.edu.uit.mobile.common.*
import vn.edu.uit.mobile.designsystem.text
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.model.*

data class WorkspaceSearchState(val hits: List<WorkspaceSearchHit> = emptyList(), val query: String = "", val selected: String? = null, val busy: Boolean = false, val message: String? = null, val unownedNotices: Boolean = false)
data class WorkspaceSearchFile(val path: String, val key: String, val generation: Int)
class WorkspaceSearchViewModel(private val repository: PortalRepository, accounts: AccountRepository) : ViewModel() {
    private val state = MutableStateFlow(WorkspaceSearchState())
    val uiState = state.asStateFlow()
    private val effects = Channel<WorkspaceSearchFile>(Channel.BUFFERED)
    val files = effects.receiveAsFlow()
    private var download: Job? = null
    private var epoch = 0
    init {
        viewModelScope.launch {
            combine(repository.snapshot, accounts.accounts) { snapshot, owners -> snapshot to owners }.collect { (snapshot, owners) ->
                val hits = SearchWorkspaceUseCase().index(snapshot, owners)
                val selected = state.value.selected
                val removed = selected != null && hits.none { it.key == selected }
                if (removed) {
                    epoch++
                    download?.cancel()
                }
                state.value = state.value.copy(hits = hits, selected = selected?.takeUnless { removed }, busy = state.value.busy && !removed, message = if (removed) "That saved result is no longer available." else state.value.message, unownedNotices = snapshot.daaSearchSources.isEmpty() && (snapshot.notices.isNotEmpty() || snapshot.announcements.isNotEmpty()))
            }
        }
    }
    fun canOpen(file: WorkspaceSearchFile) = file.generation == epoch && state.value.selected == file.key && state.value.hits.any { it.key == file.key }
    fun query(value: String) {
        state.value = state.value.copy(query = value.take(120), message = null)
    }
    fun open(key: String) {
        if (!state.value.busy && state.value.hits.any { it.key == key }) state.value = state.value.copy(selected = key, message = null)
    }
    fun back() {
        epoch++
        download?.cancel()
        state.value = state.value.copy(selected = null, busy = false)
    }
    fun close() {
        back()
        state.value = state.value.copy(query = "", message = null)
    }
    fun download(file: CourseFile) {
        val hit = state.value.hits.firstOrNull { it.key == state.value.selected } ?: return
        val course = hit.course ?: return
        val allowed = listOfNotNull(hit.file) + hit.module?.files.orEmpty() + hit.assignment?.files.orEmpty()
        if (state.value.busy || file !in allowed) return
        val expected = ++epoch
        state.value = state.value.copy(busy = true, message = null)
        download = viewModelScope.launch {
            try {
                when (val result = repository.download(course, file)) {
                    is AppResult.Success -> {
                        currentCoroutineContext().ensureActive()
                        if (epoch == expected && state.value.hits.any { it.key == hit.key }) effects.send(WorkspaceSearchFile(result.data.path, hit.key, expected))
                    }
                    is AppResult.Failure -> if (epoch == expected) state.value = state.value.copy(message = result.error.text())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (epoch == expected) state.value = state.value.copy(message = "The saved file could not be opened. Try again.")
            } finally {
                if (epoch == expected) state.value = state.value.copy(busy = false)
            }
        }
    }
}
