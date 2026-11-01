package vn.edu.uit.mobile.courses

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.model.*

enum class BookmarkProblem { LOAD, SAVE, LINK, SOURCE, BROWSER }
data class BookmarksState(val items: List<Bookmark> = emptyList(), val sources: List<WorkspaceSearchHit> = emptyList(), val owners: List<Account> = emptyList(), val ready: Boolean = false, val busy: Boolean = false, val problem: BookmarkProblem? = null) {
    private val sourceIndex by lazy { sources.mapNotNull { hit -> bookmarkFor(hit)?.id?.let { it to hit } }.toMap() }
    val savedIds get() = items.map { it.id }.toSet()
    fun source(item: Bookmark) = sourceIndex[item.id]
    fun available(item: Bookmark) = item.accountId in owners.map { it.id } && (item.kind in setOf(BookmarkKind.LINK, BookmarkKind.DISCUSSION) || source(item) != null)
}

class BookmarksViewModel(private val repository: BookmarkRepository, portal: PortalRepository, private val accounts: AccountRepository) : ViewModel() {
    private val state = MutableStateFlow(BookmarksState())
    val uiState = state.asStateFlow()
    private var snapshot = PortalSnapshot()
    private var failedAccounts = emptySet<String>()
    init {
        viewModelScope.launch {
            accounts.accounts.collect { owners ->
                state.value = state.value.copy(owners = owners, items = state.value.items.filter { item -> owners.any { it.id == item.accountId } })
                index()
            }
        }
        viewModelScope.launch {
            portal.snapshot.collect {
                snapshot = it
                index()
            }
        }
        viewModelScope.launch {
            repository.collection.collect(::applyCollection)
        }
    }
    private fun index() {
        state.value = state.value.copy(sources = SearchWorkspaceUseCase().index(snapshot, state.value.owners).filter { bookmarkFor(it) != null })
    }
    private fun applyCollection(collection: BookmarkCollection) {
        failedAccounts = collection.failedAccounts
        val owners = state.value.owners.map { it.id }.toSet()
        state.value = state.value.copy(items = collection.items.filter { it.accountId in owners }, ready = true, problem = if (failedAccounts.intersect(owners).isNotEmpty()) BookmarkProblem.LOAD else state.value.problem?.takeUnless { it == BookmarkProblem.LOAD })
    }
    private fun currentOwner(id: String) = state.value.owners.firstOrNull { it.id == id }
    fun problem(problem: BookmarkProblem) {
        state.value = state.value.copy(problem = problem)
    }
    fun refresh() {
        mutate { applyCollection(repository.collection.first()) }
    }
    private fun mutate(onSaved: () -> Unit = {}, block: suspend () -> Unit) {
        if (!state.value.ready || state.value.busy) return
        state.value = state.value.copy(busy = true, problem = null)
        viewModelScope.launch {
            try {
                block()
                applyCollection(repository.collection.first())
                onSaved()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                state.value = state.value.copy(problem = BookmarkProblem.SAVE)
            } finally {
                state.value = state.value.copy(busy = false)
            }
        }
    }
    fun toggleHit(key: String) {
        val hit = state.value.sources.firstOrNull { it.key == key } ?: return problem(BookmarkProblem.SOURCE)
        val item = bookmarkFor(hit) ?: return
        toggle(item)
    }
    fun toggleCourse(course: Course) {
        val hit = state.value.sources.firstOrNull { it.kind == WorkspaceSearchKind.COURSES && it.course?.id == course.id && it.accountId == course.accountId } ?: return problem(BookmarkProblem.SOURCE)
        toggleHit(hit.key)
    }
    fun toggleDiscussion(course: Course, discussion: Discussion) {
        val owner = currentOwner(course.accountId) ?: return
        if (owner.portal != PortalType.MOODLE || snapshot.courses.none { it.id == course.id && it.accountId == course.accountId } || discussion.id <= 0) return problem(BookmarkProblem.SOURCE)
        val url = runCatching { officialBookmarkUrl(owner.baseUrl.trimEnd('/') + "/mod/forum/discuss.php?d=${discussion.id}") }.getOrElse { return problem(BookmarkProblem.LINK) }
        toggle(Bookmark(course.accountId, BookmarkKind.DISCUSSION, "discussion:${course.id}:${discussion.id}", discussion.name.take(240), course.shortname.take(500), course.id, url))
    }
    private fun toggle(item: Bookmark) {
        if (currentOwner(item.accountId) == null) return
        val existing = state.value.items.firstOrNull { it.id == item.id }
        mutate { if (existing != null) repository.remove(existing.accountId, existing.id) else repository.save(item.copy(title = item.title.take(240), context = item.context.take(500))) }
    }
    fun addLink(accountId: String, title: String, value: String, onSaved: () -> Unit = {}) {
        if (!state.value.ready || state.value.busy || currentOwner(accountId) == null) return
        val url = runCatching { officialBookmarkUrl(value) }.getOrElse {
            problem(BookmarkProblem.LINK)
            return
        }
        if (title.isBlank() || title.length > 240) {
            problem(BookmarkProblem.LINK)
            return
        }
        val item = Bookmark(accountId, BookmarkKind.LINK, "link:${bookmarkDigest(url)}", title.trim(), java.net.URI(url).host, url = url)
        mutate(onSaved) { repository.save(item) }
    }
    fun remove(id: String) {
        val item = state.value.items.firstOrNull { it.id == id && currentOwner(it.accountId) != null } ?: return
        mutate { repository.remove(item.accountId, item.id) }
    }
    fun source(id: String): WorkspaceSearchHit? {
        val item = state.value.items.firstOrNull { it.id == id && currentOwner(it.accountId) != null } ?: return null
        return state.value.source(item).also { if (it == null) problem(BookmarkProblem.SOURCE) }
    }
    fun originalUrl(id: String): String? {
        val item = state.value.items.firstOrNull { it.id == id && currentOwner(it.accountId) != null } ?: return null
        if (item.kind !in setOf(BookmarkKind.LINK, BookmarkKind.DISCUSSION)) return null
        return runCatching { officialBookmarkUrl(item.url) }.getOrNull()
    }
}
