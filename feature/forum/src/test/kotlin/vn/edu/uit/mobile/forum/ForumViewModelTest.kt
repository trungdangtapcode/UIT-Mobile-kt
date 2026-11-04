package vn.edu.uit.mobile.forum

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import vn.edu.uit.mobile.domain.*

@OptIn(ExperimentalCoroutinesApi::class)
class ForumViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    @After fun cleanup() {
        Dispatchers.resetMain()
    }
    private class Repo : ForumRepository {
        val calls = mutableListOf<ForumRequest>()
        val publicCalls = mutableListOf<ForumRequest>()
        override suspend fun loadPublic(request: ForumRequest): ForumLoaded {
            publicCalls += request
            return load(request)
        }
        var local = ForumLocalState()
        var failure: ForumFailure? = null
        var revisionGate: CompletableDeferred<Unit>? = null
        override suspend fun load(request: ForumRequest): ForumLoaded {
            calls += request
            failure?.let { throw ForumException(it, 10) }
            val d = when (request) {
                is ForumRequest.Revision -> {
                    revisionGate?.let { withContext(NonCancellable) { it.await() } }
                    ForumDocument(revision = ForumRevision(request.postId, request.version, null, null, "author", "", "", emptyList()))
                }
                is ForumRequest.Category -> ForumDocument()
                ForumRequest.Categories -> ForumDocument(categories = listOf(ForumCategory(1, "Public")), filters = listOf(ForumFilter.LATEST, ForumFilter.TOP, ForumFilter.HOT))
                ForumRequest.Tags -> ForumDocument(tags = listOf(ForumTag(17, "algorithms", 2)))
                is ForumRequest.Feed -> ForumDocument(topics = listOf(ForumTopic(request.page.toLong() + 10, "Filtered topic", 1, 3, "")), more = request.page == 0)
                is ForumRequest.Latest -> ForumDocument(topics = listOf(ForumTopic(1, "Topic", 1, 1000, "")), more = true)
                is ForumRequest.Search -> ForumDocument(hits = listOf(ForumHit(8, ForumTopic(1, "Topic", 1, 1000, ""), 123, "Matched reply")))
                is ForumRequest.Topic -> {
                    val start = ((request.postNumber - 1) / 2).coerceAtMost(980)
                    ForumDocument(topic = ForumTopic(request.id, "Topic", 1, 1000, ""), stream = (1L..1000L).toList(), posts = (start + 1..start + 20).map { ForumPost(it.toLong(), request.id, it * 2 - 1, "author", "", emptyList()) })
                }
                is ForumRequest.Posts -> ForumDocument(posts = request.ids.reversed().filter { it != 23L }.map { ForumPost(it, request.topicId, it.toInt() * 2 - 1, "author", "", emptyList()) })
            }
            return ForumLoaded(d, 123)
        }
        override suspend fun local() = local
        override suspend fun save(topic: ForumTopic, saved: Boolean): ForumLocalState {
            local = local.copy(saved = if (saved) listOf(topic) else emptyList())
            return local
        }
        override suspend fun position(position: ForumPosition) {
            local = local.copy(positions = listOf(position))
        }
        override suspend fun image(url: String) = error("Unexpected image")
        override suspend fun clear() {
            local = ForumLocalState()
        }
    }

    @Test fun publicCitationNavigationUsesAnonymousReadsAndDoesNotSaveReadingState() = runTest(dispatcher) {
        val repo = Repo()
        val vm = ForumViewModel(repo)
        vm.openPublicTopic(7, 123)
        runCurrent()
        assertTrue(vm.uiState.value.open && vm.uiState.value.publicCitation)
        assertEquals(repo.calls, repo.publicCalls)
        assertEquals(7L, vm.uiState.value.topic!!.id)
        vm.position(vm.uiState.value.posts.first(), 12)
        vm.save(vm.uiState.value.topic!!)
        advanceUntilIdle()
        assertTrue(repo.local.positions.isEmpty() && repo.local.saved.isEmpty())
        vm.window(true)
        runCurrent()
        assertEquals(repo.calls, repo.publicCalls)
    }

    @Test fun discoveryKeepsFilterPeriodAndTagThroughPaginationAndBack() = runTest(dispatcher) {
        val repo = Repo()
        val vm = ForumViewModel(repo)
        vm.open()
        runCurrent()
        vm.tags()
        runCurrent()
        val tag = vm.uiState.value.tags.single()
        vm.discover(ForumFilter.TOP, ForumPeriod.MONTHLY, tag)
        runCurrent()
        assertEquals(ForumRequest.Feed(ForumFilter.TOP, 0, ForumPeriod.MONTHLY, 17), repo.calls.last())
        vm.latest(append = true)
        runCurrent()
        assertEquals(ForumRequest.Feed(ForumFilter.TOP, 1, ForumPeriod.MONTHLY, 17), repo.calls.last())
        assertEquals(2, vm.uiState.value.topics.size)
        assertFalse(vm.uiState.value.more)
        vm.topic(10)
        runCurrent()
        vm.back()
        runCurrent()
        assertEquals(tag, vm.uiState.value.tag)
        assertEquals(ForumFilter.TOP, vm.uiState.value.filter)
        assertEquals(2, vm.uiState.value.topics.size)
        vm.discover(ForumFilter.HOT, tag = null)
        runCurrent()
        assertEquals(0, vm.uiState.value.page)
        assertEquals(1, vm.uiState.value.topics.size)
        assertNull(vm.uiState.value.tag)
    }

    @Test fun disabledFiltersAndRateLimitedSelectionCannotStartRequests() = runTest(dispatcher) {
        val repo = Repo()
        val vm = ForumViewModel(repo) { 100 }
        vm.open()
        runCurrent()
        val before = repo.calls.size
        vm.discover(ForumFilter.VOTES)
        runCurrent()
        assertEquals(before, repo.calls.size)
        repo.failure = ForumFailure.RATE_LIMIT
        vm.discover(ForumFilter.HOT)
        runCurrent()
        val calls = repo.calls.size
        vm.discover(ForumFilter.TOP)
        vm.tags()
        runCurrent()
        assertEquals(calls, repo.calls.size)
        assertEquals(ForumFilter.HOT, vm.uiState.value.filter)
    }

    @Test fun categoryNavigationClearsUnrelatedTagAndSort() = runTest(dispatcher) {
        val repo = Repo()
        val vm = ForumViewModel(repo)
        vm.open()
        runCurrent()
        vm.discover(ForumFilter.TOP, tag = ForumTag(17, "algorithms", 2))
        runCurrent()
        vm.latest(ForumCategory(1, "Public"))
        runCurrent()
        assertNull(vm.uiState.value.tag)
        assertEquals(ForumFilter.LATEST, vm.uiState.value.filter)
        assertEquals(ForumRequest.Latest(1), repo.calls.last())
    }

    @Test fun historyRespectsPermissionRateLimitAndIgnoresLateResultsAfterDismissal() = runTest(dispatcher) {
        val repo = Repo()
        var now = 100L
        val vm = ForumViewModel(repo) { now }
        vm.topic(1)
        runCurrent()
        val post = vm.uiState.value.posts.first().copy(version = 3, canViewHistory = true)
        val count = repo.calls.size
        vm.history(post.copy(canViewHistory = false))
        runCurrent()
        vm.history(post.copy(topicId = 99))
        runCurrent()
        assertEquals(count, repo.calls.size)
        repo.failure = ForumFailure.RATE_LIMIT
        vm.history(post)
        runCurrent()
        assertEquals(ForumFailure.RATE_LIMIT, vm.uiState.value.historyError)
        vm.history(post)
        runCurrent()
        assertEquals(count + 1, repo.calls.size)
        now = 111
        repo.failure = null
        val gate = CompletableDeferred<Unit>()
        repo.revisionGate = gate
        vm.history(post)
        runCurrent()
        assertTrue(vm.uiState.value.historyLoading)
        vm.closeHistory()
        gate.complete(Unit)
        runCurrent()
        assertNull(vm.uiState.value.history)
        assertNull(vm.uiState.value.historyPost)
        assertFalse(vm.uiState.value.historyLoading)
    }

    @Test fun revisionBeforeAndAfterViewsDoNotExposeRemovedOrAddedTextOnWrongSide() {
        val b = ForumBlock(ForumBlockKind.TEXT, "beforeoldnew", inline = listOf(ForumInline("before"), ForumInline("old", strike = true, change = -1), ForumInline("new", underline = true, change = 1)))
        assertEquals("beforeold", revisionSide(b, 1).text)
        assertEquals("beforenew", revisionSide(b, 2).text)
        assertTrue(revisionSide(b, 2).inline.all { it.change == 0 })
    }

    @Test fun noRequestBeforeEntryAndSearchOpensMatchedReply() = runTest(dispatcher) {
        val repo = Repo()
        val vm = ForumViewModel(repo)
        runCurrent()
        assertTrue(repo.calls.isEmpty())
        vm.open()
        runCurrent()
        assertEquals(1, vm.uiState.value.topics.size)
        vm.search("kernel")
        runCurrent()
        val hit = vm.uiState.value.hits.single()
        vm.topic(hit.topic.id, hit.postNumber)
        runCurrent()
        assertEquals(ForumRequest.Topic(1, 123), repo.calls.last())
        assertTrue(vm.uiState.value.posts.any { it.number == 123 })
    }

    @Test fun streamWindowsAreBoundedOrderedAndTolerateMissingPosts() = runTest(dispatcher) {
        val repo = Repo()
        val vm = ForumViewModel(repo)
        vm.topic(1)
        runCurrent()
        repeat(5) {
            vm.window(true)
            runCurrent()
        }
        assertEquals(80, vm.uiState.value.posts.size)
        assertEquals(120, vm.uiState.value.end)
        assertEquals(vm.uiState.value.posts.map { it.id }.sorted(), vm.uiState.value.posts.map { it.id })
        vm.window(false)
        runCurrent()
        assertTrue(vm.uiState.value.posts.size <= 80)
        assertTrue(repo.calls.filterIsInstance<ForumRequest.Posts>().all { it.ids.size <= 20 })
    }

    @Test fun duplicatePageStopsPaginationAndBackKeepsFeed() = runTest(dispatcher) {
        val repo = Repo()
        val vm = ForumViewModel(repo)
        vm.open()
        runCurrent()
        vm.latest(append = true)
        runCurrent()
        assertEquals(1, vm.uiState.value.topics.size)
        assertFalse(vm.uiState.value.more)
        vm.topic(1)
        runCurrent()
        vm.back()
        runCurrent()
        assertNull(vm.uiState.value.topic)
        assertEquals(1, vm.uiState.value.topics.size)
    }

    @Test fun retryAfterBlocksImmediateRequestsAndPositionIsLocal() = runTest(dispatcher) {
        val repo = Repo()
        var now = 100L
        val vm = ForumViewModel(repo) { now }
        vm.topic(1)
        runCurrent()
        vm.position(vm.uiState.value.posts[3], 48)
        advanceTimeBy(501)
        runCurrent()
        assertEquals(7, repo.local.positions.single().postNumber)
        repo.failure = ForumFailure.RATE_LIMIT
        vm.window(true)
        runCurrent()
        val count = repo.calls.size
        vm.retry()
        runCurrent()
        assertEquals(count, repo.calls.size)
        now = 111
        repo.failure = null
        vm.retry()
        runCurrent()
        assertEquals(count + 1, repo.calls.size)
    }
}
