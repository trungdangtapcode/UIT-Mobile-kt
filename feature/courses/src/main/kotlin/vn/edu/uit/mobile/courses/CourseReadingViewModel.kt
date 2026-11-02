package vn.edu.uit.mobile.courses

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import vn.edu.uit.mobile.designsystem.NativePdfDocument
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.model.*

enum class ReadingProblem { FILE, PDF, PAGE, STORE, SAVE, STALE, REMOVED, EXPORT, LIMIT }
enum class ReadingTab { PAGE, BOOKMARKS, NOTES }
data class ReadingCandidate(val course: Course, val file: CourseFile) {
    val id get() = offlineDownloadId(course, file)
}
data class ReadingNoteDraft(val existing: ReadingNote?, val page: Int, val text: String, val x: Float, val y: Float)
data class CourseReadingState(
    val open: Boolean = false,
    val candidate: ReadingCandidate? = null,
    val record: ReadingRecord? = null,
    val collections: List<ReadingCollection> = emptyList(),
    val candidates: List<ReadingCandidate> = emptyList(),
    val ownerNames: Map<String, String> = emptyMap(),
    val catalogLoading: Boolean = false,
    val image: Bitmap? = null,
    val pageCount: Int = 0,
    val position: ReadingPosition = ReadingPosition(),
    val tab: ReadingTab = ReadingTab.PAGE,
    val loading: Boolean = false,
    val saving: Boolean = false,
    val problem: ReadingProblem? = null,
    val dirty: Boolean = false,
    val pendingSave: Boolean = false,
    val archived: Boolean = false,
    val otherVersions: Int = 0,
    val session: Long = 0,
    val draft: ReadingNoteDraft? = null,
    val ready: Boolean = false
) {
    val editable get() = record != null && !archived && !loading && !saving && !pendingSave && image != null
    val currentNotes get() = record?.notes.orEmpty().filter { it.page == position.page }.sortedWith(compareBy({ it.createdAt }, { it.id }))
}
interface ReadingPdf : AutoCloseable {
    val pageCount: Int
    fun render(page: Int): Bitmap
}
fun interface ReadingPdfFactory {
    fun open(bytes: ByteArray): ReadingPdf
}
fun nativeReadingPdfFactory(cacheDirectory: File) = ReadingPdfFactory { bytes ->
    val document = NativePdfDocument(bytes, cacheDirectory)
    object : ReadingPdf {
        override val pageCount get() = document.pageCount
        override fun render(page: Int) = document.render(page, 4.0)
        override fun close() = document.close()
    }
}

/** Native/local only: no portal refresh, university operation or AI processor. */
class CourseReadingViewModel(
    private val repository: ReadingRepository,
    private val owners: AccountRepository,
    private val portal: PortalRepository,
    private val downloads: OfflineDownloadStorage,
    private val loader: ReadingDocumentLoader,
    private val factory: ReadingPdfFactory,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = { Clock.System.now().epochSeconds }
) : ViewModel() {
    private val state = MutableStateFlow(CourseReadingState())
    val uiState = state.asStateFlow()
    private data class PendingWrite(val expected: ReadingRecord, val value: ReadingRecord, val done: () -> Unit)
    private var pendingWrite: PendingWrite? = null
    var pendingExport: Pair<ReadingRecord, String>? = null
        private set
    var exportPickerPending: Boolean = false
        private set
    private val writes = Mutex()
    private var document: ReadingPdf? = null
    private var operation: Job? = null
    private var autosave: Job? = null
    private var epoch = 0L
    private var ownerIds = emptySet<String>()
    init {
        viewModelScope.launch {
            owners.accounts.collect { accounts ->
                ownerIds = accounts.map { it.id }.toSet()
                val current = state.value
                val owner = current.record?.identity?.accountId ?: current.candidate?.course?.accountId
                if (owner != null && owner !in ownerIds) {
                    clear(true)
                    state.value = state.value.copy(problem = ReadingProblem.REMOVED)
                }
                state.value = state.value.copy(collections = state.value.collections.filter { it.accountId in ownerIds }, candidates = state.value.candidates.filter { it.course.accountId in ownerIds }, ownerNames = accounts.associate { it.id to it.displayName })
            }
        }
        viewModelScope.launch { combine(repository.collections, owners.accounts) { collections, accounts -> collections.filter { c -> accounts.any { it.id == c.accountId } } }.collect { collections -> state.value = state.value.copy(collections = collections, ready = true) } }
    }
    fun library() {
        clear(true)
        refresh()
    }
    fun refresh() {
        val token = epoch
        state.value = state.value.copy(catalogLoading = true)
        viewModelScope.launch {
            try {
                val snapshot = portal.snapshot.first()
                val journal = withContext(io) { downloads.load() }
                val specs = (savedDownloadSpecs(snapshot, ownerIds) + journal.items.filter { it.status == OfflineDownloadStatus.SAVED }.map { it.spec }).distinctBy { it.id }
                val candidates = withContext(io) { specs.filter { it.course.accountId in ownerIds && it.file.name.endsWith(".pdf", true) && downloads.cached(it.course, it.file) != null }.map { ReadingCandidate(it.course, it.file) } }
                if (token == epoch) state.value = state.value.copy(candidates = candidates.filter { it.course.accountId in ownerIds })
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (token == epoch) state.value = state.value.copy(problem = ReadingProblem.FILE)
            } finally {
                if (token == epoch) state.value = state.value.copy(catalogLoading = false)
            }
        }
    }
    private fun clear(open: Boolean) {
        epoch++
        pendingWrite = null
        pendingExport = null
        exportPickerPending = false
        operation?.cancel()
        autosave?.cancel()
        document?.close()
        document = null
        val old = state.value
        state.value = CourseReadingState(open = open, collections = old.collections, candidates = old.candidates, ownerNames = old.ownerNames, ready = old.ready, session = old.session + 1)
    }
    fun discardToLibrary() {
        clear(true)
        refresh()
    }
    fun preempt() {
        clear(false)
    }
    override fun onCleared() {
        document?.close()
        document = null
    }
    fun open(course: Course, file: CourseFile) {
        clear(true)
        val token = epoch
        state.value = state.value.copy(candidate = ReadingCandidate(course, file), loading = true)
        operation = viewModelScope.launch {
            var opened: ReadingPdf? = null
            try {
                check(owners.accounts.first().any { it.id == course.accountId })
                val captured = loader.load(course, file)
                currentCoroutineContext().ensureActive()
                check(captured.identity.accountId == course.accountId && captured.identity.documentId == offlineDownloadId(course, file))
                try {
                    withContext(io) { opened = factory.open(captured.bytes) }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    if (token == epoch) state.value = state.value.copy(problem = ReadingProblem.PDF)
                    return@launch
                }
                val pdf = checkNotNull(opened)
                var record: ReadingRecord? = null
                var storeProblem: ReadingProblem? = null
                try {
                    record = repository.load(captured.identity, file.name.take(200), course.id, pdf.pageCount)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    storeProblem = ReadingProblem.STORE
                }
                val position = record?.position ?: ReadingPosition()
                val image = withContext(io) { pdf.render(position.page) }
                currentCoroutineContext().ensureActive()
                check(token == epoch && owners.accounts.first().any { it.id == course.accountId })
                document = pdf
                opened = null
                val other = state.value.collections.flatMap { it.records }.count { it.identity.accountId == course.accountId && it.identity.documentId == captured.identity.documentId && it.identity.version != captured.identity.version }
                state.value = state.value.copy(record = record, position = position, pageCount = pdf.pageCount, image = image, problem = storeProblem, otherVersions = other, dirty = record?.revision == 0L)
                if (state.value.dirty && !persistPosition() && state.value.problem == ReadingProblem.LIMIT) state.value = state.value.copy(record = null, dirty = false)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (token == epoch) state.value = state.value.copy(problem = ReadingProblem.FILE)
            } finally {
                opened?.close()
                if (token == epoch) state.value = state.value.copy(loading = false)
            }
        }
    }
    fun retry() {
        val current = state.value
        if (current.problem in setOf(ReadingProblem.SAVE, ReadingProblem.STALE)) {
            savePosition()
            return
        }
        current.candidate?.let { open(it.course, it.file) } ?: refresh()
    }
    fun review(record: ReadingRecord) {
        if (record.identity.accountId !in ownerIds || state.value.collections.none { c -> c.records.any { it == record } }) return
        clear(true)
        state.value = state.value.copy(record = record, position = record.position, pageCount = record.pageCount, archived = true, tab = ReadingTab.NOTES)
    }
    fun tab(value: ReadingTab) {
        state.value = state.value.copy(tab = value)
    }
    fun viewport(value: ReadingPosition) {
        val current = state.value
        if (current.archived || current.loading || value.page != current.position.page || current.pageCount == 0) return
        if (runCatching { ReadingRules.position(value, current.pageCount) }.isFailure) return
        state.value = current.copy(position = value, dirty = current.record?.position != value)
        autosave?.cancel()
        autosave = viewModelScope.launch {
            delay(500)
            persistPosition()
        }
    }
    private suspend fun persistPosition(): Boolean = writes.withLock {
        val pending = pendingWrite
        if (pending != null && !save(pending.expected, pending.value, pending.done)) return@withLock false
        val current = state.value
        val record = current.record ?: return@withLock !current.dirty
        if (!current.dirty) return@withLock true
        return@withLock save(record, record.copy(position = current.position))
    }
    private suspend fun save(expected: ReadingRecord, next: ReadingRecord, done: () -> Unit = {}): Boolean {
        val token = epoch
        pendingWrite = PendingWrite(expected, next, done)
        state.value = state.value.copy(saving = true, pendingSave = false, problem = null)
        return try {
            val saved = repository.save(expected, next)
            currentCoroutineContext().ensureActive()
            if (token != epoch) return false
            pendingWrite = null
            state.value = state.value.copy(record = saved, pendingSave = false, dirty = !state.value.archived && state.value.position != saved.position)
            done()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (token == epoch) {
                if (e is ReadingLimitException) {
                    pendingWrite = null
                    state.value = state.value.copy(problem = ReadingProblem.LIMIT, pendingSave = false)
                } else {
                    state.value = state.value.copy(problem = ReadingProblem.SAVE, pendingSave = true)
                }
            }
            false
        } finally {
            if (token == epoch) state.value = state.value.copy(saving = false)
        }
    }
    fun savePosition() {
        autosave?.cancel()
        viewModelScope.launch { persistPosition() }
    }
    fun page(page: Int) {
        val current = state.value
        if (current.loading || current.saving || page !in 1..current.pageCount) return
        if (current.archived) {
            state.value = current.copy(position = ReadingPosition(page), tab = ReadingTab.NOTES)
            return
        }
        val pdf = document ?: return
        autosave?.cancel()
        val token = epoch
        operation = viewModelScope.launch {
            if (token != epoch || !persistPosition()) return@launch
            state.value = state.value.copy(loading = true)
            try {
                val image = withContext(io) { pdf.render(page) }
                currentCoroutineContext().ensureActive()
                if (token == epoch) {
                    state.value = state.value.copy(image = image, position = ReadingPosition(page), dirty = state.value.record != null, tab = ReadingTab.PAGE, problem = if (state.value.record == null) state.value.problem?.takeIf { it in setOf(ReadingProblem.STORE, ReadingProblem.LIMIT) } ?: ReadingProblem.STORE else null)
                    persistPosition()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (token == epoch) state.value = state.value.copy(problem = ReadingProblem.PAGE)
            } finally {
                if (token == epoch) state.value = state.value.copy(loading = false)
            }
        }
    }
    private fun change(block: (ReadingRecord) -> ReadingRecord, done: () -> Unit = {}) {
        val current = state.value
        if (current.saving || current.loading || current.pendingSave || current.record == null) return
        autosave?.cancel()
        val token = epoch
        viewModelScope.launch {
            writes.withLock {
                if (token != epoch || state.value.record?.identity != current.record.identity) return@withLock
                val record = state.value.record ?: return@withLock
                val next = try {
                    block(record.copy(position = if (state.value.archived) record.position else state.value.position)).also(ReadingRules::record)
                } catch (_: Exception) {
                    state.value = state.value.copy(problem = ReadingProblem.LIMIT)
                    return@withLock
                }
                save(record, next, done)
            }
        }
    }
    fun bookmark() {
        if (!state.value.editable) return
        val page = state.value.position.page
        change({ it.copy(bookmarks = if (page in it.bookmarks) it.bookmarks - page else it.bookmarks + page) })
    }
    fun note(existing: ReadingNote?, page: Int, text: String, x: Float, y: Float, done: () -> Unit) {
        if (!state.value.editable || text.isBlank()) return
        change({ record ->
            check(existing == null || record.notes.any { it == existing })
            val time = now()
            val note = ReadingNote(existing?.id ?: UUID.randomUUID().toString(), page, text.trim(), x, y, existing?.createdAt ?: time, maxOf(existing?.createdAt ?: time, time))
            record.copy(notes = record.notes.filterNot { it.id == note.id } + note)
        }, done)
    }
    fun editNote(existing: ReadingNote? = null, page: Int = state.value.position.page, x: Float = .5f, y: Float = .5f) {
        if (!state.value.editable) return
        state.value = state.value.copy(draft = ReadingNoteDraft(existing, existing?.page ?: page, existing?.text.orEmpty(), existing?.x ?: x, existing?.y ?: y))
    }
    fun draftText(text: String) {
        val draft = state.value.draft ?: return
        if (!state.value.saving && !state.value.pendingSave && text.length <= 4000) state.value = state.value.copy(draft = draft.copy(text = text))
    }
    fun dismissDraft() {
        if (!state.value.saving) state.value = state.value.copy(draft = null)
    }
    fun saveDraft() {
        if (state.value.pendingSave) {
            retry()
            return
        }
        val draft = state.value.draft ?: return
        note(draft.existing, draft.page, draft.text, draft.x, draft.y) { state.value = state.value.copy(draft = null) }
    }
    fun removeNote(note: ReadingNote, done: () -> Unit) {
        change({
            check(it.notes.any { value -> value == note })
            it.copy(notes = it.notes.filterNot { value -> value.id == note.id })
        }, done)
    }
    fun removeRecord(done: () -> Unit) {
        val current = state.value
        val record = current.record ?: return
        if (current.saving || current.loading) return
        autosave?.cancel()
        val token = epoch
        viewModelScope.launch {
            writes.withLock {
                if (token != epoch) return@withLock
                state.value = state.value.copy(saving = true)
                try {
                    repository.remove(record)
                    currentCoroutineContext().ensureActive()
                    if (token == epoch) {
                        clear(true)
                        done()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    if (token == epoch) state.value = state.value.copy(problem = ReadingProblem.SAVE)
                } finally {
                    if (token == epoch) state.value = state.value.copy(saving = false)
                }
            }
        }
    }
    fun leave(done: () -> Unit) {
        if (state.value.saving || state.value.loading) {
            if (state.value.loading) {
                clear(false)
                done()
            }
            return
        }
        autosave?.cancel()
        val token = epoch
        viewModelScope.launch {
            if (token == epoch && persistPosition() && token == epoch) {
                clear(false)
                done()
            }
        }
    }
    fun libraryFromDocument() {
        leave { library() }
    }
    fun export(done: (ReadingRecord, String) -> Unit) {
        if (state.value.loading || state.value.saving) return
        autosave?.cancel()
        val token = epoch
        viewModelScope.launch {
            if (token != epoch || !persistPosition() || token != epoch) return@launch
            val record = state.value.record ?: return@launch
            try {
                val encoded = repository.export(record)
                currentCoroutineContext().ensureActive()
                if (token == epoch && canExport(record)) {
                    pendingExport = record to encoded
                    done(record, encoded)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (token == epoch) exportFailed()
            }
        }
    }
    suspend fun exportTo(write: suspend (String) -> Unit): Boolean {
        val prepared = pendingExport ?: return false
        val token = epoch
        return try {
            check(canExport(prepared.first))
            withContext(io) {
                check(token == epoch && canExport(prepared.first))
                write(prepared.second)
            }
            currentCoroutineContext().ensureActive()
            if (token == epoch) cancelExport()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            if (token == epoch) {
                cancelExport()
                exportFailed()
            }
            false
        }
    }
    fun beginExportPicker() {
        exportPickerPending = true
    }
    fun cancelExport() {
        pendingExport = null
        exportPickerPending = false
    }
    fun reopen() {
        val candidate = state.value.candidate
        if (candidate != null) open(candidate.course, candidate.file) else library()
    }
    fun exportFailed() {
        state.value = state.value.copy(problem = ReadingProblem.EXPORT)
    }
    fun canExport(record: ReadingRecord) = record.identity.accountId in ownerIds && state.value.record == record && !state.value.dirty && !state.value.saving
}
