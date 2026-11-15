package vn.edu.uit.mobile.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.model.UmaSession

class UmaViewModel(private val repository: UmaRepository) : ViewModel() {
    val state = repository.state
    private var request: Job? = null
    fun initialize() {
        viewModelScope.launch { repository.initialize() }
    }
    fun connect(session: UmaSession) {
        stop()
        request = viewModelScope.launch { repository.connect(session) }
    }
    fun ask(question: String, suggestion: Long? = null) {
        if (request?.isActive == true || state.value.busy || state.value.loading || state.value.identity == null || question.isBlank() || question.length > 1000) return
        request = viewModelScope.launch { repository.ask(question, suggestion) }
    }
    fun stop() {
        request?.cancel()
        request = null
    }
    fun clear() {
        stop()
        viewModelScope.launch { repository.clear() }
    }
    fun sources(sources: Set<UmaSource>) {
        stop()
        viewModelScope.launch { repository.sources(sources) }
    }
    fun disconnect() {
        stop()
        viewModelScope.launch { repository.disconnect() }
    }
}
