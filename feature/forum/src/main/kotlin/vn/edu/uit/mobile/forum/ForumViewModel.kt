package vn.edu.uit.mobile.forum

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import vn.edu.uit.mobile.domain.*

enum class ForumMemberTab { NOTICES, BOOKMARKS, CHAT }
typealias ForumComposer = ForumDraft
enum class ForumTab { LATEST, CATEGORIES, TAGS, SEARCH, SAVED }
data class ForumState(
    val open: Boolean = false,
    val publicCitation: Boolean = false,
    val user: ForumUser? = null,
    val loginVisible: Boolean = false,
    val memberVisible: Boolean = false,
    val memberTab: ForumMemberTab = ForumMemberTab.NOTICES,
    val memberPage: ForumAccountPage = ForumAccountPage(),
    val memberIndex: Int = 0,
    val channel: ForumChannel? = null,
    val threadId: Long? = null,
    val accountBusy: Boolean = false,
    val accountError: ForumFailure? = null,
    val accountDetail: String = "",
    val accountRetryAt: Long = 0,
    val composer: ForumComposer? = null,
    val draft: ForumDraft? = null,
    val draftSaving: Boolean = false,
    val draftError: Boolean = false,
    val draftChoiceVisible: Boolean = false,
    val chatScrollVersion: Int = 0,
    val chatTarget: Long? = null,
    val commandBusy: Boolean = false,
    val commandUncertain: Boolean = false,
    val canReply: Boolean = false,
    val remoteReadNumber: Int? = null,
    val reactionChoices: List<String> = emptyList(),
    val tab: ForumTab = ForumTab.LATEST,
    val categories: List<ForumCategory> = emptyList(),
    val category: ForumCategory? = null,
    val query: String = "",
    val filters: List<ForumFilter> = listOf(ForumFilter.LATEST),
    val filter: ForumFilter = ForumFilter.LATEST,
    val period: ForumPeriod = ForumPeriod.WEEKLY,
    val tag: ForumTag? = null,
    val tags: List<ForumTag> = emptyList(),
    val tagsTruncated: Boolean = false,
    val topics: List<ForumTopic> = emptyList(),
    val hits: List<ForumHit> = emptyList(),
    val page: Int = 0,
    val more: Boolean = false,
    val local: ForumLocalState = ForumLocalState(),
    val topic: ForumTopic? = null,
    val stream: List<Long> = emptyList(),
    val posts: List<ForumPost> = emptyList(),
    val start: Int = 0,
    val end: Int = 0,
    val unavailable: Int = 0,
    val anchorNumber: Int = 1,
    val anchorOffsetDp: Int = 0,
    val scrollVersion: Int = 0,
    val loading: Boolean = false,
    val saving: Boolean = false,
    val offline: Boolean = false,
    val fetchedAt: Long = 0,
    val error: ForumFailure? = null,
    val retryAt: Long = 0,
    val historyPost: ForumPost? = null,
    val history: ForumRevision? = null,
    val historyLoading: Boolean = false,
    val historyError: ForumFailure? = null,
    val historyRetryAt: Long = 0,
    val historyRequestedVersion: Int = 0,
    val readerSession: String = UUID.randomUUID().toString()
)

class ForumViewModel(private val repository: ForumRepository, private val clock: () -> Long = { System.currentTimeMillis() / 1000 }) : ViewModel() {
    private val state = MutableStateFlow(ForumState())
    val uiState = state.asStateFlow()
    private val accountRepository = repository as? ForumAccountRepository
    val supportsAccount get() = accountRepository != null
    private var accountWork: Job? = null
    private var commandWork: Job? = null
    private var draftWork: Job? = null
    private var draftSerial = 0L
    private var accountSerial = 0L
    private var resumed = false
    private var historyWork: Job? = null
    private var historySerial = 0L
    private var work: Job? = null
    private var positionWork: Job? = null
    private var saveWork: Job? = null
    private var localSerial = 0L
    private var serial = 0L
    private var retry: (() -> Unit)? = null
    private var pendingPosition: ForumPosition? = null
    private val attemptedCategories = mutableSetOf<Long>()
    private var feedStamp: Pair<Long, Boolean> = 0L to false
    private suspend fun read(request: ForumRequest): ForumLoaded = if (state.value.publicCitation) repository.loadPublic(request) else repository.load(request)
    fun openPublicTopic(id: Long, number: Int) {
        accountWork?.cancel()
        commandWork?.cancel()
        draftWork?.cancel()
        work?.cancel()
        historyWork?.cancel()
        positionWork?.cancel()
        saveWork?.cancel()
        accountSerial++
        draftSerial++
        historySerial++
        localSerial++
        serial++
        pendingPosition = null
        resumed = false
        attemptedCategories.clear()
        state.value = ForumState(open = true, publicCitation = true)
        topic(id, number)
    }
    fun open() {
        state.value = state.value.copy(open = true, publicCitation = false)
        if (!resumed && accountRepository != null) {
            resumed = true
            val token = ++accountSerial
            accountWork = viewModelScope.launch {
                state.value = state.value.copy(accountBusy = true)
                try {
                    val user = accountRepository.resume()
                    val draft = if (user != null) accountRepository.draft() else null
                    if (token != accountSerial) return@launch
                    state.value = state.value.copy(user = user, draft = draft, accountBusy = false)
                    latest()
                } catch (e: ForumException) {
                    if (token == accountSerial) state.value = state.value.copy(accountBusy = false, loginVisible = true, accountError = e.reason)
                }
            }
        } else if (state.value.fetchedAt == 0L && state.value.topics.isEmpty()) {
            latest()
        } else {
            refreshLocal()
        }
    }
    fun close() {
        closeHistory()
        work?.cancel()
        serial++
        state.value = state.value.copy(open = false, loading = false)
        flushPosition()
    }
    private fun refreshLocal() {
        val token = localSerial
        viewModelScope.launch {
            try {
                val local = repository.local()
                if (token == localSerial) state.value = state.value.copy(local = local)
            } catch (e: ForumException) {
                if (token == localSerial) failure(e, ::refreshLocal)
            }
        }
    }
    private fun failure(e: ForumException, action: (() -> Unit)? = null) {
        if (e.reason == ForumFailure.AUTHENTICATION && state.value.user != null) {
            resetAccountUi()
            state.value = state.value.copy(loginVisible = true, accountError = e.reason)
            return
        }
        if (action != null) retry = action
        val s = state.value
        val denied = e.reason in listOf(ForumFailure.UNAVAILABLE, ForumFailure.FORBIDDEN) && s.topic != null
        if (denied) retry = { topic(s.topic!!.id, s.anchorNumber) }
        state.value = s.copy(
            error = e.reason,
            retryAt = if (e.reason == ForumFailure.RATE_LIMIT) clock() + (e.retryAfterSeconds ?: 30) else s.retryAt,
            posts = if (denied) emptyList() else s.posts,
            stream = if (denied) emptyList() else s.stream,
            start = if (denied) 0 else s.start,
            end = if (denied) 0 else s.end,
            unavailable = if (denied) 0 else s.unavailable
        )
    }
    private fun launch(action: () -> Unit, block: suspend (Long) -> Unit) {
        if (state.value.retryAt > clock()) return
        work?.cancel()
        val token = ++serial
        retry = action
        state.value = state.value.copy(loading = true, error = null, retryAt = 0)
        work = viewModelScope.launch {
            try {
                block(token)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ForumException) {
                if (serial == token) failure(e)
            } catch (_: Exception) {
                if (serial == token) failure(ForumException(ForumFailure.INVALID))
            } finally {
                if (serial == token) state.value = state.value.copy(loading = false)
            }
        }
    }
    fun retry() {
        if (state.value.retryAt > clock()) return
        state.value = state.value.copy(error = null)
        retry?.invoke()
    }
    fun latest(category: ForumCategory? = state.value.category, append: Boolean = false) {
        if (state.value.retryAt > clock()) return
        val page = if (append) state.value.page + 1 else 0
        if (page >= 100) return
        if (!append) {
            closeHistory()
            state.value = state.value.copy(
                tab = ForumTab.LATEST,
                category = category,
                topic = null,
                topics = emptyList(),
                hits = emptyList(),
                more = false,
                filter = if (category == null) state.value.filter else ForumFilter.LATEST,
                tag = if (category == null) state.value.tag else null
            )
        }
        launch({ latest(category, append) }) { token ->
            if (state.value.categories.isEmpty()) {
                try {
                    val c = read(ForumRequest.Categories)
                    if (serial == token) state.value = state.value.copy(categories = c.document.categories, filters = c.document.filters)
                } catch (e: ForumException) {
                    if (e.reason in listOf(ForumFailure.RATE_LIMIT, ForumFailure.AUTHENTICATION)) throw e
                }
            }
            val local = repository.local()
            val selection = state.value
            val request = if (category != null || selection.filter == ForumFilter.LATEST && selection.tag == null) {
                ForumRequest.Latest(category?.id, page)
            } else {
                ForumRequest.Feed(selection.filter, page, selection.period, selection.tag?.id)
            }
            val loaded = read(request)
            if (serial != token) return@launch
            val old = if (append) state.value.topics else emptyList()
            val topics = (old + loaded.document.topics).distinctBy { it.id }.take(1000)
            feedStamp = loaded.fetchedAt to loaded.offline
            state.value = state.value.copy(local = local, topics = topics, page = page, more = loaded.document.more && topics.size > old.size && topics.size < 1000, fetchedAt = loaded.fetchedAt, offline = loaded.offline)
            enrichCategories(topics.map { it.categoryId }, token)
        }
    }
    fun discover(filter: ForumFilter, period: ForumPeriod = state.value.period, tag: ForumTag? = state.value.tag) {
        if (filter !in state.value.filters || state.value.retryAt > clock()) return
        state.value = state.value.copy(filter = filter, period = period, tag = tag)
        latest(null)
    }
    fun tags() {
        if (state.value.retryAt > clock()) return
        closeHistory()
        state.value = state.value.copy(tab = ForumTab.TAGS, topic = null, more = false)
        launch(::tags) { token ->
            val result = read(ForumRequest.Tags)
            if (token == serial) {
                feedStamp = result.fetchedAt to result.offline
                state.value = state.value.copy(tags = result.document.tags, tagsTruncated = result.document.tagsTruncated, fetchedAt = result.fetchedAt, offline = result.offline)
            }
        }
    }
    fun tab(tab: ForumTab) {
        closeHistory()
        work?.cancel()
        serial++
        state.value = state.value.copy(tab = tab, topic = null, loading = false, error = if (state.value.retryAt > clock()) state.value.error else null, offline = feedStamp.second, fetchedAt = feedStamp.first)
        when (tab) {
            ForumTab.LATEST -> discover(ForumFilter.LATEST, tag = null)
            ForumTab.TAGS -> tags()
            ForumTab.CATEGORIES -> launch({ tab(tab) }) { token ->
                if (state.value.categories.isEmpty()) {
                    val result = read(ForumRequest.Categories)
                    if (token == serial) state.value = state.value.copy(categories = result.document.categories, filters = result.document.filters, fetchedAt = result.fetchedAt, offline = result.offline)
                }
                enrichCategories(state.value.topics.map { it.categoryId } + state.value.hits.map { it.topic.categoryId } + state.value.categories.mapNotNull { it.parentId }, token)
            }
            ForumTab.SAVED -> refreshLocal()
            ForumTab.SEARCH -> if (state.value.query.isNotBlank()) search(state.value.query) else state.value = state.value.copy(hits = emptyList(), more = false, page = 1)
        }
    }
    fun search(query: String, append: Boolean = false) {
        if (state.value.retryAt > clock()) return
        val q = query.trim().take(200)
        if (q.isBlank()) return
        val page = if (append) state.value.page + 1 else 1
        if (page > 100) return
        if (!append) state.value = state.value.copy(tab = ForumTab.SEARCH, query = q, topic = null, hits = emptyList(), more = false)
        launch({ search(q, append) }) { token ->
            val result = read(ForumRequest.Search(q, page))
            if (serial != token) return@launch
            val old = if (append) state.value.hits else emptyList()
            val hits = (old + result.document.hits).distinctBy { it.id }.take(1000)
            feedStamp = result.fetchedAt to result.offline
            state.value = state.value.copy(hits = hits, page = page, more = result.document.more && hits.size > old.size && hits.size < 1000, fetchedAt = result.fetchedAt, offline = result.offline)
            enrichCategories(hits.map { it.topic.categoryId }, token)
        }
    }
    fun topic(id: Long, number: Int = 1, offsetDp: Int = 0) {
        if (id <= 0 || number <= 0 || state.value.retryAt > clock()) return
        closeHistory()
        flushPosition()
        val same = state.value.topic?.id == id
        if (!same) state.value = state.value.copy(topic = ForumTopic(id, "", 0, 0, ""), posts = emptyList(), stream = emptyList(), unavailable = 0)
        launch({ topic(id, number, offsetDp) }) { token ->
            val result = read(ForumRequest.Topic(id, number))
            if (serial != token) return@launch
            val doc = result.document
            val indexes = doc.posts.map { doc.stream.indexOf(it.id) }.filter { it >= 0 }
            val start = indexes.minOrNull() ?: 0
            val end = indexes.maxOrNull()?.plus(1) ?: 0
            state.value = state.value.copy(commandUncertain = false, accountError = null, accountDetail = "", remoteReadNumber = doc.lastReadPostNumber, canReply = doc.canReply, reactionChoices = doc.reactionChoices, topic = doc.topic, stream = doc.stream, posts = doc.posts, start = start, end = end, unavailable = end - start - doc.posts.size, fetchedAt = result.fetchedAt, offline = result.offline, anchorNumber = number, anchorOffsetDp = offsetDp, scrollVersion = state.value.scrollVersion + 1)
            enrichCategories(listOfNotNull(doc.topic?.categoryId), token)
        }
    }

    // Site metadata can omit categories present in public feeds. Discover only
    // referenced IDs, never scan category IDs or follow arbitrary server URLs.
    private suspend fun enrichCategories(ids: List<Long>, token: Long) {
        val queue = java.util.ArrayDeque(ids.filter { it > 0 && state.value.categories.none { c -> c.id == it } && it !in attemptedCategories }.distinct().take(8))
        var remaining = 8
        while (queue.isNotEmpty() && remaining > 0 && token == serial) {
            val id = queue.removeFirst()
            if (state.value.categories.any { it.id == id } || id in attemptedCategories) continue
            remaining--
            try {
                val result = read(ForumRequest.Category(id))
                if (token != serial) return
                attemptedCategories += id
                val c = result.document.categories
                state.value = state.value.copy(categories = (state.value.categories + c).distinctBy { it.id })
                c.mapNotNull { it.parentId }.forEach { queue.addLast(it) }
            } catch (e: ForumException) {
                if (e.reason in listOf(ForumFailure.RATE_LIMIT, ForumFailure.AUTHENTICATION)) throw e
                if (e.reason == ForumFailure.UNAVAILABLE) attemptedCategories += id
            }
        }
    }
    fun back() {
        closeHistory()
        work?.cancel()
        serial++
        flushPosition()
        state.value = state.value.copy(topic = null, posts = emptyList(), stream = emptyList(), loading = false, error = null, fetchedAt = feedStamp.first, offline = feedStamp.second)
    }
    fun window(forward: Boolean) {
        val s = state.value
        val topic = s.topic ?: return
        if (s.loading) return
        val first = if (forward) s.end else (s.start - ForumLimits.BATCH).coerceAtLeast(0)
        val last = if (forward) (s.end + ForumLimits.BATCH).coerceAtMost(s.stream.size) else s.start
        val ids = s.stream.subList(first, last)
        if (ids.isEmpty()) return
        launch({ window(forward) }) { token ->
            val result = read(ForumRequest.Posts(topic.id, ids))
            if (serial != token) return@launch
            val all = (s.posts + result.document.posts).distinctBy { it.id }.sortedBy { s.stream.indexOf(it.id) }
            val kept = if (forward) all.takeLast(ForumLimits.WINDOW) else all.take(ForumLimits.WINDOW)
            val start = if (forward && all.size > ForumLimits.WINDOW) s.stream.indexOf(kept.first().id) else minOf(first, s.start)
            val end = if (!forward && all.size > ForumLimits.WINDOW) s.stream.indexOf(kept.last().id) + 1 else maxOf(last, s.end)
            state.value = state.value.copy(posts = kept, start = start, end = end, unavailable = (end - start - kept.size).coerceAtLeast(0), fetchedAt = result.fetchedAt, offline = result.offline)
        }
    }
    fun save(topic: ForumTopic) {
        if (state.value.publicCitation) return
        if (state.value.topic?.id != topic.id && state.value.topics.none { it.id == topic.id } && state.value.local.saved.none { it.id == topic.id }) return
        saveValue(topic, state.value.local.saved.none { it.id == topic.id })
    }
    private fun saveValue(topic: ForumTopic, desired: Boolean) {
        if (state.value.saving) return
        val token = localSerial
        state.value = state.value.copy(saving = true)
        saveWork = viewModelScope.launch {
            try {
                val local = repository.save(topic, desired)
                if (localSerial == token) state.value = state.value.copy(local = local)
            } catch (e: ForumException) {
                if (localSerial == token) failure(e) { saveValue(topic, desired) }
            } finally {
                if (localSerial == token) state.value = state.value.copy(saving = false)
            }
        }
    }
    fun position(post: ForumPost, offsetDp: Int) {
        if (state.value.publicCitation) return
        val topic = state.value.topic ?: return
        if (post.topicId != topic.id) return
        pendingPosition = ForumPosition(topic, post.id, post.number, offsetDp.coerceIn(0, 10000))
        positionWork?.cancel()
        positionWork = viewModelScope.launch {
            delay(500)
            persistPosition()
        }
    }
    private suspend fun persistPosition() {
        val p = pendingPosition ?: return
        val token = localSerial
        try {
            repository.position(p)
            if (token != localSerial) return
            if (pendingPosition == p) pendingPosition = null
            state.value = state.value.copy(local = state.value.local.copy(positions = (listOf(p) + state.value.local.positions.filterNot { it.topic.id == p.topic.id }).take(120)))
        } catch (e: ForumException) {
            if (token == localSerial) failure(e, ::flushPosition)
        }
    }
    private fun flushPosition() {
        positionWork?.cancel()
        positionWork = viewModelScope.launch { persistPosition() }
    }
    fun clear() {
        accountWork?.cancel()
        resetAccountUi()
        launch({ clear() }) {
            repository.clear()
            attemptedCategories.clear()
            state.value = ForumState(open = true, tab = ForumTab.SAVED)
        }
    }
    fun closeHistory() {
        historyWork?.cancel()
        historySerial++
        state.value = state.value.copy(historyPost = null, history = null, historyLoading = false, historyError = null, historyRetryAt = 0)
    }
    fun history(post: ForumPost, version: Int = post.version) {
        if (!post.canViewHistory || version < 2 || post.topicId != state.value.topic?.id || state.value.historyRetryAt > clock()) return
        historyWork?.cancel()
        val serial = ++historySerial
        state.value = state.value.copy(historyPost = post, history = null, historyLoading = true, historyError = null, historyRequestedVersion = version)
        historyWork = viewModelScope.launch {
            try {
                val result = read(ForumRequest.Revision(post.topicId, post.id, version)).document.revision ?: throw ForumException(ForumFailure.INVALID)
                if (serial == historySerial) state.value = state.value.copy(history = result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ForumException) {
                if (serial == historySerial) {
                    if (e.reason == ForumFailure.AUTHENTICATION) {
                        failure(e)
                    } else {
                        state.value = state.value.copy(historyError = e.reason, historyRetryAt = if (e.reason == ForumFailure.RATE_LIMIT) clock() + (e.retryAfterSeconds ?: 30) else 0)
                    }
                }
            } catch (_: Exception) {
                if (serial == historySerial) state.value = state.value.copy(historyError = ForumFailure.INVALID)
            } finally {
                if (serial == historySerial) state.value = state.value.copy(historyLoading = false)
            }
        }
    }
    private fun resetAccountUi() {
        closeHistory()
        work?.cancel()
        positionWork?.cancel()
        saveWork?.cancel()
        commandWork?.cancel()
        draftWork?.cancel()
        draftSerial++
        serial++
        localSerial++
        accountSerial++
        pendingPosition = null
        retry = null
        feedStamp = 0L to false
        attemptedCategories.clear()
        state.value = ForumState(open = true)
    }
    fun account() {
        state.value = state.value.copy(loginVisible = state.value.user == null, memberVisible = state.value.user != null, accountError = null)
        if (state.value.user != null) member(ForumMemberTab.NOTICES)
    }
    fun login(username: String, password: String, code: String) {
        val repo = accountRepository ?: return
        if (state.value.accountBusy || state.value.accountRetryAt > clock()) return
        accountWork?.cancel()
        resetAccountUi()
        val token = accountSerial
        state.value = state.value.copy(loginVisible = true, accountBusy = true)
        accountWork = viewModelScope.launch {
            try {
                val user = repo.login(username, password, code)
                if (token != accountSerial) return@launch
                val draft = repo.draft()
                if (token != accountSerial) return@launch
                state.value = state.value.copy(user = user, draft = draft, loginVisible = false)
                latest(null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ForumException) {
                if (token == accountSerial) state.value = state.value.copy(accountError = e.reason, accountDetail = e.detail, accountRetryAt = if (e.reason == ForumFailure.RATE_LIMIT) clock() + (e.retryAfterSeconds ?: 30) else 0)
            } catch (_: Exception) {
                if (token == accountSerial) state.value = state.value.copy(accountError = ForumFailure.INVALID)
            } finally {
                if (token == accountSerial) state.value = state.value.copy(accountBusy = false)
            }
        }
    }
    fun disconnect() {
        val repo = accountRepository ?: return
        accountWork?.cancel()
        resetAccountUi()
        state.value = state.value.copy(accountBusy = true)
        accountWork = viewModelScope.launch {
            try {
                repo.disconnect()
                state.value = state.value.copy(accountBusy = false)
                latest(null)
            } catch (e: ForumException) {
                state.value = state.value.copy(accountBusy = false, loginVisible = true, accountError = e.reason)
            }
        }
    }
    fun dismissAccount() {
        if (state.value.accountBusy || state.value.commandBusy) return
        if (state.value.loginVisible) disconnect() else state.value = state.value.copy(memberVisible = false, memberPage = ForumAccountPage(), channel = null, accountError = null)
    }
    fun member(tab: ForumMemberTab = state.value.memberTab, append: Boolean = false, channel: ForumChannel? = null, threadId: Long? = null, newer: Boolean = false, target: Long? = null) {
        val repo = accountRepository ?: return
        if (state.value.user == null || state.value.commandBusy || state.value.accountRetryAt > clock()) return
        accountWork?.cancel()
        val token = ++accountSerial
        val index = if (tab == ForumMemberTab.CHAT) {
            0
        } else if (append) {
            state.value.memberIndex + 1
        } else {
            0
        }
        if (index > 100) return
        val sameScope = tab == state.value.memberTab && channel?.id == state.value.channel?.id && threadId == state.value.threadId
        val before = if (append && sameScope) state.value.memberPage else ForumAccountPage()
        val visible = if (sameScope) state.value.memberPage else ForumAccountPage()
        val request = when (tab) {
            ForumMemberTab.NOTICES -> ForumMemberRequest.Notices(index * 30)
            ForumMemberTab.BOOKMARKS -> ForumMemberRequest.Bookmarks(index)
            ForumMemberTab.CHAT -> channel?.let { ForumMemberRequest.Messages(it, if (append && !newer) before.messages.minOfOrNull { m -> m.id } else null, threadId, after = if (append && newer) before.messages.maxOfOrNull { m -> m.id } else null, target = target) } ?: ForumMemberRequest.Channels
        }
        state.value = state.value.copy(memberVisible = true, memberTab = tab, channel = channel, threadId = threadId, accountBusy = true, accountError = null, memberPage = visible)
        accountWork = viewModelScope.launch {
            try {
                val result = repo.member(request)
                if (token != accountSerial) return@launch
                val allMessages = (before.messages + result.messages).distinctBy { it.id }.sortedBy { it.id }
                val messages = if (append && !newer) allMessages.take(500) else allMessages.takeLast(500)
                val combined = result.copy(notices = (before.notices + result.notices).distinctBy { it.id }.take(1000), bookmarks = (before.bookmarks + result.bookmarks).distinctBy { it.id }.take(1000), messages = messages)
                val grew = combined.notices.size > before.notices.size || combined.bookmarks.size > before.bookmarks.size
                val advanced = !append || result.messages.any { m -> if (newer) m.id > (before.messages.maxOfOrNull { it.id } ?: 0) else m.id < (before.messages.minOfOrNull { it.id } ?: Long.MAX_VALUE) }
                val olderAvailable = if (tab != ForumMemberTab.CHAT) {
                    result.more && grew
                } else if (append && newer) {
                    before.more || allMessages.size > messages.size
                } else {
                    result.more && advanced
                }
                val newerAvailable = if (append && !newer) before.moreNewer || allMessages.size > messages.size else result.moreNewer && advanced
                state.value = state.value.copy(commandUncertain = false, memberPage = combined.copy(more = olderAvailable, moreNewer = newerAvailable), memberIndex = index, chatScrollVersion = state.value.chatScrollVersion + if (tab == ForumMemberTab.CHAT && !append) 1 else 0, chatTarget = if (append) state.value.chatTarget else target)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ForumException) {
                failure(e)
                if (token == accountSerial) state.value = state.value.copy(memberPage = if (e.reason in listOf(ForumFailure.FORBIDDEN, ForumFailure.UNAVAILABLE)) ForumAccountPage() else state.value.memberPage, accountError = e.reason, accountDetail = e.detail, accountRetryAt = if (e.reason == ForumFailure.RATE_LIMIT) clock() + (e.retryAfterSeconds ?: 30) else 0)
            } catch (_: Exception) {
                if (token == accountSerial) state.value = state.value.copy(accountError = ForumFailure.INVALID)
            } finally {
                if (token == accountSerial) state.value = state.value.copy(accountBusy = false)
            }
        }
    }
    fun compose(parent: Int? = null, create: Boolean = false) {
        if (state.value.user == null || state.value.commandBusy || state.value.accountRetryAt > clock()) return
        if (create && state.value.user?.canCreateTopic != true || !create && !state.value.canReply) return
        val context = ForumComposer(if (create) null else state.value.topic?.id, parent)
        val existing = state.value.draft
        if (existing != null && existing.key != context.key) {
            state.value = state.value.copy(draftChoiceVisible = true)
            return
        }
        val draft = existing ?: context
        state.value = state.value.copy(composer = draft, accountError = null, commandUncertain = draft.uncertain)
    }
    fun edit(post: ForumPost) {
        val repo = accountRepository ?: return
        if (!post.canEdit || state.value.user == null || state.value.commandBusy || state.value.accountRetryAt > clock()) return
        accountWork?.cancel()
        val token = ++accountSerial
        state.value = state.value.copy(accountBusy = true, accountError = null)
        accountWork = viewModelScope.launch {
            try {
                val source = repo.member(ForumMemberRequest.Edit(post.id)).editable ?: throw ForumException(ForumFailure.INVALID)
                if (token == accountSerial) {
                    val existing = state.value.draft
                    if (existing != null && existing.key != "edit:${source.postId}") {
                        state.value = state.value.copy(draftChoiceVisible = true)
                    } else {
                        val draft = existing ?: ForumDraft(edit = source, raw = source.raw)
                        state.value = state.value.copy(composer = draft, commandUncertain = draft.uncertain)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ForumException) {
                failure(e)
                if (token == accountSerial) state.value = state.value.copy(accountError = e.reason, accountRetryAt = if (e.reason == ForumFailure.RATE_LIMIT) clock() + (e.retryAfterSeconds ?: 30) else 0)
            } catch (_: Exception) {
                if (token == accountSerial) state.value = state.value.copy(accountError = ForumFailure.INVALID)
            } finally {
                if (token == accountSerial) state.value = state.value.copy(accountBusy = false)
            }
        }
    }
    fun resumeDraft() {
        if (state.value.user == null || state.value.commandBusy) return
        val draft = state.value.draft ?: return
        state.value = state.value.copy(composer = draft, draftChoiceVisible = false, commandUncertain = draft.uncertain)
    }
    fun dismissDraftChoice() {
        state.value = state.value.copy(draftChoiceVisible = false)
    }
    fun updateDraft(draft: ForumDraft) {
        if (state.value.user == null || state.value.commandBusy || state.value.composer?.key != draft.key) return
        state.value = state.value.copy(composer = draft, draft = draft, draftSaving = true, draftError = false, commandUncertain = draft.uncertain)
        persistDraft(draft)
    }
    private fun persistDraft(draft: ForumDraft?, close: Boolean = false) {
        val repo = accountRepository ?: return
        draftWork?.cancel()
        val token = ++draftSerial
        val account = localSerial
        state.value = state.value.copy(draftSaving = true, draftError = false)
        draftWork = viewModelScope.launch {
            try {
                repo.saveDraft(draft)
                if (token == draftSerial && account == localSerial) state.value = state.value.copy(draft = draft, draftSaving = false, composer = if (close) null else state.value.composer, draftChoiceVisible = false)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (token == draftSerial && account == localSerial) state.value = state.value.copy(draftSaving = false, draftError = true)
            }
        }
    }
    fun dismissComposer() {
        if (state.value.commandBusy) return
        val draft = state.value.composer ?: return
        persistDraft(draft.takeIf { it.hasChanges || it.uncertain }, close = true)
    }
    fun discardDraft() {
        if (!state.value.commandBusy && state.value.user != null) persistDraft(null, close = true)
    }
    fun openBookmark(bookmark: ForumBookmark) {
        val target = bookmark.target as? ForumBookmarkTarget.Chat ?: return
        val repo = accountRepository ?: return
        if (state.value.user == null || state.value.accountBusy || state.value.commandBusy) return
        accountWork?.cancel()
        val token = ++accountSerial
        state.value = state.value.copy(accountBusy = true, accountError = null)
        accountWork = viewModelScope.launch {
            try {
                val channel = repo.member(ForumMemberRequest.Channel(target.channelId)).channels.single()
                if (token == accountSerial) member(ForumMemberTab.CHAT, channel = channel, threadId = target.threadId, target = target.messageId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ForumException) {
                failure(e)
                if (token == accountSerial) state.value = state.value.copy(accountError = e.reason)
            } catch (_: Exception) {
                if (token == accountSerial) state.value = state.value.copy(accountError = ForumFailure.INVALID)
            } finally {
                if (token == accountSerial) state.value = state.value.copy(accountBusy = false)
            }
        }
    }
    fun submit(command: ForumCommand) {
        val repo = accountRepository ?: return
        if (state.value.user == null || state.value.commandBusy || state.value.commandUncertain || state.value.composer?.uncertain == true || state.value.accountRetryAt > clock()) return
        val token = accountSerial
        val topic = state.value.topic
        val composer = state.value.composer
        draftWork?.cancel()
        draftSerial++
        state.value = state.value.copy(commandBusy = true, accountError = null, accountDetail = "")
        commandWork = viewModelScope.launch {
            try {
                if (composer != null) repo.saveDraft(composer.copy(uncertain = true))
                val result = repo.command(command)
                var cleanupFailed = false
                if (composer != null) {
                    try {
                        repo.saveDraft(null)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        cleanupFailed = true
                    }
                }

                if (token != accountSerial) return@launch
                state.value = state.value.copy(commandBusy = false, composer = null, draft = if (composer != null) composer.copy(uncertain = true).takeIf { cleanupFailed } else state.value.draft, draftSaving = false, draftError = cleanupFailed)
                if (state.value.memberVisible) {
                    member(state.value.memberTab, channel = state.value.channel, threadId = state.value.threadId)
                } else {
                    result.topicId?.let { topic(it, result.postNumber) } ?: topic?.let { topic(it.id, state.value.anchorNumber) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ForumException) {
                if (composer != null) {
                    val pending = composer.copy(uncertain = e.reason == ForumFailure.UNCERTAIN)
                    try {
                        repo.saveDraft(pending)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) { /* Preserve the in-memory draft and the earlier durable pending marker. */ }
                    if (token == accountSerial) state.value = state.value.copy(composer = pending, draft = pending)
                }
                failure(e)
                if (token == accountSerial) state.value = state.value.copy(accountError = e.reason, accountDetail = e.detail, accountRetryAt = if (e.reason == ForumFailure.RATE_LIMIT) clock() + (e.retryAfterSeconds ?: 30) else 0, commandUncertain = e.reason == ForumFailure.UNCERTAIN)
            } catch (_: Exception) {
                if (token == accountSerial) state.value = state.value.copy(accountError = ForumFailure.UNCERTAIN, commandUncertain = true, composer = composer?.copy(uncertain = true), draft = composer?.copy(uncertain = true) ?: state.value.draft)
            } finally {
                if (token == accountSerial) state.value = state.value.copy(commandBusy = false)
            }
        }
    }
    suspend fun image(url: String): ByteArray = try {
        if (state.value.publicCitation) repository.imagePublic(url) else repository.image(url)
    } catch (e: ForumException) {
        if (e.reason == ForumFailure.AUTHENTICATION) withContext(Dispatchers.Main) { failure(e) }
        throw e
    }
}
