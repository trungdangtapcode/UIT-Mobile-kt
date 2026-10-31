package vn.edu.uit.mobile.courses

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import vn.edu.uit.mobile.common.*
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.model.*

data class OfflineDownloadsState(val snapshot: PortalSnapshot = PortalSnapshot(), val owners: Set<String> = emptySet(), val items: List<OfflineDownloadItem> = emptyList(), val wifiOnly: Boolean = true, val wifi: Boolean = false, val ready: Boolean = false, val usedBytes: Long = 0, val message: String? = null)
data class OfflineOpenFile(val path: String, val id: String, val generation: Int)
class OfflineDownloadsViewModel(private val repository: PortalRepository, accounts: AccountRepository, private val storage: OfflineDownloadStorage, wifi: Flow<Boolean>) : ViewModel() {
    private val state = MutableStateFlow(OfflineDownloadsState())
    val uiState = state.asStateFlow()
    private val persist = Channel<Unit>(Channel.CONFLATED)
    private val effects = Channel<OfflineOpenFile>(Channel.BUFFERED)
    val files = effects.receiveAsFlow()
    private var operation: Job? = null
    private var opening: Job? = null
    private var current: String? = null
    private var generation = 0
    init {
        viewModelScope.launch {
            for (signal in persist) {
                val value = state.value
                try {
                    storage.save(OfflineDownloadJournal(value.items, value.wifiOnly))
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    state.value = state.value.copy(message = "Download settings could not be saved on this device.")
                }
            }
        }
        viewModelScope.launch {
            val journal = try {
                storage.load()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                state.value = state.value.copy(message = "Saved download list is unavailable. Existing files have been retained.")
                OfflineDownloadJournal()
            }
            val owners = accounts.accounts.first().filter { it.portal == PortalType.MOODLE }.map { it.id }.toSet()
            val restored = journal.items.filter { it.spec.course.accountId in owners }.map { item ->
                val cached = storage.cached(item.spec.course, item.spec.file)
                if (cached != null) item.copy(status = OfflineDownloadStatus.SAVED, bytes = cached.bytes) else item.copy(status = if (item.status == OfflineDownloadStatus.CANCELED) item.status else OfflineDownloadStatus.FAILED, message = "Interrupted or unavailable. Retry to resume.")
            }
            val latestOwners = accounts.accounts.first().filter { it.portal == PortalType.MOODLE }.map { it.id }.toSet()
            state.value = state.value.copy(items = restored.filter { it.spec.course.accountId in latestOwners }, wifiOnly = journal.wifiOnly, ready = true, owners = latestOwners)
            changed()
            refreshUsage()
            kick()
        }
        viewModelScope.launch { repository.snapshot.collect { state.value = state.value.copy(snapshot = it) } }
        viewModelScope.launch {
            accounts.accounts.collect { list ->
                val ids = list.filter { it.portal == PortalType.MOODLE }.map { it.id }.toSet()
                if (ids != state.value.owners) {
                    generation++
                    if (state.value.items.firstOrNull { it.id == current }?.spec?.course?.accountId !in ids) operation?.cancel()
                }
                state.value = state.value.copy(owners = ids, items = state.value.items.filter { it.spec.course.accountId in ids })
                changed()
                refreshUsage()
                kick()
            }
        }
        viewModelScope.launch {
            wifi.collect { available ->
                state.value = state.value.copy(wifi = available)
                if (!available && state.value.wifiOnly) pauseForWifi()
                kick()
            }
        }
    }
    private fun changed() {
        if (state.value.ready) persist.trySend(Unit)
    }
    private fun update(id: String, change: (OfflineDownloadItem) -> OfflineDownloadItem) {
        state.value = state.value.copy(items = state.value.items.map { if (it.id == id) change(it) else it })
        changed()
    }
    private fun pauseForWifi() {
        current?.let { id ->
            update(id) { if (it.status == OfflineDownloadStatus.DOWNLOADING) it.copy(status = OfflineDownloadStatus.WAITING_WIFI) else it }
            operation?.cancel()
        }
    }
    fun wifiOnly(value: Boolean) {
        state.value = state.value.copy(wifiOnly = value)
        changed()
        if (value && !state.value.wifi) pauseForWifi()
        kick()
    }
    fun enqueue(specs: List<OfflineDownloadSpec>) {
        if (!state.value.ready) return
        val allowed = savedDownloadSpecs(state.value.snapshot, state.value.owners).associateBy { it.id to it.section }
        val selected = specs.distinctBy { it.id }.mapNotNull { allowed[it.id to it.section] }
        if (selected.isEmpty() || selected.size > 100 || (state.value.items.map { it.id } + selected.map { it.id }).distinct().size > 500) {
            state.value = state.value.copy(message = "Choose 1–100 saved files per pack; the list holds at most 500 files.")
            return
        }
        val existing = state.value.items.associateBy { it.id }.toMutableMap()
        selected.forEach { spec -> if (existing[spec.id]?.status !in setOf(OfflineDownloadStatus.DOWNLOADING, OfflineDownloadStatus.REMOVING)) existing[spec.id] = OfflineDownloadItem(existing[spec.id]?.spec?.copy(course = spec.course, section = spec.section, sectionName = spec.sectionName) ?: spec) }
        state.value = state.value.copy(items = existing.values.toList(), message = null)
        changed()
        kick()
    }
    fun cancel(id: String) {
        update(id) { if (it.status in setOf(OfflineDownloadStatus.QUEUED, OfflineDownloadStatus.DOWNLOADING, OfflineDownloadStatus.WAITING_WIFI)) it.copy(status = OfflineDownloadStatus.CANCELED, message = null) else it }
        if (id == current) operation?.cancel()
    }
    fun retry(id: String) {
        val item = state.value.items.firstOrNull { it.id == id } ?: return
        if (item.spec.course.accountId !in state.value.owners || item.status in setOf(OfflineDownloadStatus.DOWNLOADING, OfflineDownloadStatus.REMOVING)) return
        update(id) { it.copy(status = OfflineDownloadStatus.QUEUED, message = null) }
        kick()
    }
    private fun kick() {
        if (!state.value.ready || operation != null) return
        val item = state.value.items.firstOrNull { it.status == OfflineDownloadStatus.QUEUED || it.status == OfflineDownloadStatus.WAITING_WIFI && (!state.value.wifiOnly || state.value.wifi) } ?: return
        current = item.id
        operation = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val cached = storage.cached(item.spec.course, item.spec.file)
                currentCoroutineContext().ensureActive()
                if (item.spec.course.accountId !in state.value.owners) return@launch
                if (cached != null) {
                    update(item.id) { it.copy(status = OfflineDownloadStatus.SAVED, bytes = cached.bytes, message = null) }
                    return@launch
                }
                if (state.value.wifiOnly && !state.value.wifi) {
                    update(item.id) { it.copy(status = OfflineDownloadStatus.WAITING_WIFI) }
                    return@launch
                }
                update(item.id) { it.copy(status = OfflineDownloadStatus.DOWNLOADING, message = null) }
                val result = repository.download(item.spec.course, item.spec.file)
                currentCoroutineContext().ensureActive()
                if (item.spec.course.accountId !in state.value.owners) return@launch
                val saved = storage.cached(item.spec.course, item.spec.file)
                if (result is AppResult.Success && saved != null) {
                    update(item.id) { it.copy(status = OfflineDownloadStatus.SAVED, bytes = saved.bytes, message = null) }
                } else {
                    update(item.id) { it.copy(status = OfflineDownloadStatus.FAILED, bytes = 0, message = if (result is AppResult.Failure && result.error == AppError.SessionExpired) "Sign in again, then retry this file." else "File unavailable or download failed. Retry when connected.") }
                }
            } catch (e: CancellationException) {
                update(item.id) { if (it.status == OfflineDownloadStatus.DOWNLOADING) it.copy(status = OfflineDownloadStatus.CANCELED) else it }
                throw e
            } catch (_: Exception) {
                update(item.id) { it.copy(status = OfflineDownloadStatus.FAILED, message = "Download could not be completed. Retry this file.") }
            } finally {
                operation = null
                current = null
                changed()
                refreshUsage()
                viewModelScope.launch {
                    yield()
                    kick()
                }
            }
        }
        operation?.start()
    }
    fun refreshUsage() = viewModelScope.launch {
        val ids = state.value.owners
        val bytes = try {
            storage.usedBytes(ids)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return@launch
        }
        if (ids == state.value.owners) state.value = state.value.copy(usedBytes = bytes)
    }
    fun refreshSaved() = viewModelScope.launch {
        for (item in state.value.items.filter { it.status == OfflineDownloadStatus.SAVED }) {
            val cached = storage.cached(item.spec.course, item.spec.file)
            update(item.id) { current -> if (current.status != OfflineDownloadStatus.SAVED) current else current.copy(status = if (cached == null) OfflineDownloadStatus.FAILED else OfflineDownloadStatus.SAVED, bytes = cached?.bytes ?: 0, message = if (cached == null) "Saved copy is missing or failed verification. Retry." else null) }
        }
        refreshUsage()
    }
    fun canOpen(file: OfflineOpenFile) = file.generation == generation && state.value.items.any { it.id == file.id && it.status == OfflineDownloadStatus.SAVED && it.spec.course.accountId in state.value.owners }
    fun dismissOpenRequests() {
        generation++
        opening?.cancel()
    }
    fun open(id: String) {
        opening?.cancel()
        val expected = ++generation
        opening = viewModelScope.launch {
            val item = state.value.items.firstOrNull { it.id == id && it.status == OfflineDownloadStatus.SAVED && it.spec.course.accountId in state.value.owners } ?: return@launch
            val cached = storage.cached(item.spec.course, item.spec.file)
            currentCoroutineContext().ensureActive()
            if (cached != null && expected == generation) {
                effects.send(OfflineOpenFile(cached.path, id, expected))
            } else if (cached == null) {
                update(id) { it.copy(status = OfflineDownloadStatus.FAILED, bytes = 0, message = "Saved copy is missing or failed verification. Retry.") }
            }
        }
    }
    fun remove(id: String) = viewModelScope.launch {
        val item = state.value.items.firstOrNull { it.id == id && it.spec.course.accountId in state.value.owners } ?: return@launch
        generation++
        update(id) { it.copy(status = OfflineDownloadStatus.REMOVING) }
        if (id == current) operation?.cancelAndJoin()
        try {
            storage.remove(item.spec.course, item.spec.file)
            state.value = state.value.copy(items = state.value.items.filterNot { it.id == id })
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            update(id) { it.copy(status = OfflineDownloadStatus.FAILED, message = "The file could not be removed. Try again.") }
        }
        changed()
        refreshUsage()
        kick()
    }
    fun loadCourse(course: Course) = viewModelScope.launch {
        if (course.accountId !in state.value.owners) return@launch
        state.value = state.value.copy(message = "Loading course file metadata…")
        val result = repository.courseDetails(course)
        val assignments = if (course.accountId in state.value.owners) repository.assignments(course) else null
        state.value = state.value.copy(message = if (result is AppResult.Success && assignments is AppResult.Success) "Course file metadata updated." else "Some metadata could not be loaded. Existing saved files are retained; sign in or retry when connected.")
    }
}
