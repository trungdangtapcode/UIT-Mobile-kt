package vn.edu.uit.mobile.forum

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import vn.edu.uit.mobile.domain.*

@OptIn(ExperimentalCoroutinesApi::class)
class ForumAccountViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun before() {
        Dispatchers.setMain(dispatcher)
    }

    @After fun after() {
        Dispatchers.resetMain()
    }
    private class Repo : ForumAccountRepository {
        var commands = 0
        var savedDraft: ForumDraft? = null
        var failDraft = false
        var chat = false
        override suspend fun draft() = savedDraft
        override suspend fun saveDraft(draft: ForumDraft?) {
            if (failDraft) throw ForumException(ForumFailure.STORAGE)
            savedDraft = draft
        }
        var error: ForumFailure? = null
        var gate: CompletableDeferred<Unit>? = null
        var disconnected = false
        var categoryError = false
        var positionGate: CompletableDeferred<Unit>? = null
        override suspend fun resume(): ForumUser? = null
        override suspend fun login(username: String, password: String, secondFactor: String) = ForumUser(7, username, "Member", true, true)
        override suspend fun disconnect() {
            disconnected = true
        }
        override suspend fun member(request: ForumMemberRequest): ForumAccountPage {
            gate?.let { withContext(NonCancellable) { it.await() } }
            if (request is ForumMemberRequest.Channel) return ForumAccountPage(channels = listOf(ForumChannel(request.id, "Channel", true)))
            if (chat && request is ForumMemberRequest.Messages) {
                val end = request.before?.minus(1) ?: request.after?.plus(50)?.coerceAtMost(600) ?: request.target?.plus(24)?.coerceAtMost(600) ?: 600L
                val start = if (request.after != null) request.after!! + 1 else (end - 49).coerceAtLeast(1)
                return ForumAccountPage(messages = (start..end).map { ForumChatMessage(it, request.channel.id, "Fixture", "", emptyList()) }, more = start > 1, moreNewer = end < 600, thread = request.threadId?.let { ForumThread(it, request.channel.id, "Thread", "open") })
            }
            return ForumAccountPage(notices = listOf(ForumNotice(8, "Private notice", 9, 1, false)))
        }
        override suspend fun command(command: ForumCommand): ForumCommandResult {
            commands++
            error?.let { throw ForumException(it) }
            return ForumCommandResult(9)
        }
        override suspend fun load(request: ForumRequest): ForumLoaded {
            if (request is ForumRequest.Category && categoryError) throw ForumException(ForumFailure.AUTHENTICATION)
            return ForumLoaded(ForumDocument(categories = listOf(ForumCategory(1, "Category")), topic = if (request is ForumRequest.Topic) ForumTopic(request.id, "Title", 2, 1, "") else null, canReply = true), 1)
        }
        override suspend fun local() = ForumLocalState()
        override suspend fun save(topic: ForumTopic, saved: Boolean) = ForumLocalState()
        override suspend fun position(position: ForumPosition) {
            positionGate?.let { withContext(NonCancellable) { it.await() } }
        }
        override suspend fun image(url: String) = byteArrayOf()
        override suspend fun clear() = Unit
    }

    @Test fun ambiguousSubmissionIsNotRetriedOrSentTwice() = runTest(dispatcher) {
        val repo = Repo()
        val vm = ForumViewModel(repo)
        vm.login("member", "not-real", "")
        runCurrent()
        repo.error = ForumFailure.UNCERTAIN
        val command = ForumCommand.Create("Title", "Body", 1)
        vm.submit(command)
        runCurrent()
        vm.submit(command)
        vm.retry()
        runCurrent()
        assertEquals(1, repo.commands)
        assertTrue(vm.uiState.value.commandUncertain)
        assertEquals(ForumFailure.UNCERTAIN, vm.uiState.value.accountError)
    }

    @Test fun disconnectClearsAccountUiAndRejectsLateMemberData() = runTest(dispatcher) {
        val repo = Repo()
        val vm = ForumViewModel(repo)
        vm.login("member", "not-real", "")
        runCurrent()
        val gate = CompletableDeferred<Unit>()
        repo.gate = gate
        vm.member()
        runCurrent()
        assertTrue(vm.uiState.value.accountBusy)
        vm.disconnect()
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertNull(vm.uiState.value.user)
        assertTrue(vm.uiState.value.memberPage.notices.isEmpty())
        assertFalse(vm.uiState.value.memberVisible)
        assertTrue(repo.disconnected)
    }

    @Test fun expiredMutationClearsPrivateUiAndRequestsLogin() = runTest(dispatcher) {
        val repo = Repo()
        val vm = ForumViewModel(repo)
        vm.login("member", "not-real", "")
        runCurrent()
        vm.member()
        runCurrent()
        assertFalse(vm.uiState.value.memberPage.notices.isEmpty())
        repo.error = ForumFailure.AUTHENTICATION
        vm.submit(ForumCommand.Bookmark(9))
        runCurrent()
        assertNull(vm.uiState.value.user)
        assertTrue(vm.uiState.value.loginVisible)
        assertTrue(vm.uiState.value.memberPage.notices.isEmpty())
        assertNull(vm.uiState.value.composer)
    }

    @Test fun expiredMetadataReadClearsAlreadyLoadedPrivateTopic() = runTest(dispatcher) {
        val repo = Repo()
        val vm = ForumViewModel(repo)
        vm.login("member", "not-real", "")
        runCurrent()
        repo.categoryError = true
        vm.topic(9)
        runCurrent()
        assertNull(vm.uiState.value.user)
        assertNull(vm.uiState.value.topic)
        assertTrue(vm.uiState.value.loginVisible)
    }

    @Test fun latePositionCannotRepopulateDisconnectedAccountState() = runTest(dispatcher) {
        val repo = Repo()
        val vm = ForumViewModel(repo)
        vm.login("member", "not-real", "")
        runCurrent()
        vm.topic(9)
        runCurrent()
        val gate = CompletableDeferred<Unit>()
        repo.positionGate = gate
        vm.position(ForumPost(7, 9, 1, "private", "", emptyList()), 0)
        advanceTimeBy(501)
        runCurrent()
        vm.disconnect()
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertTrue(vm.uiState.value.local.positions.isEmpty())
        assertNull(vm.uiState.value.user)
    }

    @Test fun explicitReadCanResolveAmbiguousActionWithoutReplayingIt() = runTest(dispatcher) {
        val repo = Repo()
        val vm = ForumViewModel(repo)
        vm.login("member", "not-real", "")
        runCurrent()
        repo.error = ForumFailure.UNCERTAIN
        vm.submit(ForumCommand.Bookmark(7))
        runCurrent()
        assertTrue(vm.uiState.value.commandUncertain)
        vm.member()
        runCurrent()
        assertFalse(vm.uiState.value.commandUncertain)
        assertEquals(1, repo.commands)
    }

    @Test fun draftSurvivesCloseAndNewViewModelWithoutPosting() = runTest(dispatcher) {
        val repo = Repo()
        val vm = ForumViewModel(repo)
        vm.login("member", "not-real", "")
        runCurrent()
        vm.compose(create = true)
        val draft = ForumDraft(raw = "**Source**\n\nBody", title = "Title", categoryId = 1)
        vm.updateDraft(draft)
        runCurrent()
        vm.dismissComposer()
        runCurrent()
        assertNull(vm.uiState.value.composer)
        val restored = ForumViewModel(repo)
        restored.login("member", "not-real", "")
        runCurrent()
        restored.resumeDraft()
        assertEquals(draft, restored.uiState.value.composer)
        assertEquals(0, repo.commands)
        restored.discardDraft()
        runCurrent()
        assertNull(repo.savedDraft)
        assertNull(restored.uiState.value.composer)
    }

    @Test fun storageFailureKeepsEditorAndBlocksPublishingBeforeNetworkWrite() = runTest(dispatcher) {
        val repo = Repo()
        val vm = ForumViewModel(repo)
        vm.login("member", "not-real", "")
        runCurrent()
        vm.compose(create = true)
        repo.failDraft = true
        vm.updateDraft(ForumDraft(raw = "Body", title = "Title", categoryId = 1))
        runCurrent()
        vm.dismissComposer()
        runCurrent()
        assertTrue(vm.uiState.value.draftError)
        assertEquals("Body", vm.uiState.value.composer?.raw)
        vm.submit(ForumCommand.Create("Title", "Body", 1))
        runCurrent()
        assertEquals(0, repo.commands)
        assertNotNull(vm.uiState.value.composer)
    }

    @Test fun ambiguousDraftSurvivesRestartAndReadDoesNotAllowDuplicatePost() = runTest(dispatcher) {
        val repo = Repo()
        val vm = ForumViewModel(repo)
        vm.login("member", "not-real", "")
        runCurrent()
        vm.compose(create = true)
        vm.updateDraft(ForumDraft(raw = "Body", title = "Title", categoryId = 1))
        runCurrent()
        repo.error = ForumFailure.UNCERTAIN
        val command = ForumCommand.Create("Title", "Body", 1)
        vm.submit(command)
        runCurrent()
        assertTrue(repo.savedDraft!!.uncertain)
        val restored = ForumViewModel(repo)
        restored.login("member", "not-real", "")
        runCurrent()
        restored.resumeDraft()
        restored.member()
        runCurrent()
        restored.submit(command)
        runCurrent()
        assertEquals(1, repo.commands)
        restored.updateDraft(restored.uiState.value.composer!!.copy(uncertain = false))
        runCurrent()
        repo.error = null
        restored.submit(command)
        runCurrent()
        assertEquals(2, repo.commands)
        assertNull(repo.savedDraft)
        assertNull(restored.uiState.value.draft)
    }

    @Test fun newContextNeverOverwritesAnExistingDraft() = runTest(dispatcher) {
        val repo = Repo().apply { savedDraft = ForumDraft(topicId = 9, parent = 2, raw = "Reply") }
        val vm = ForumViewModel(repo)
        vm.login("member", "not-real", "")
        runCurrent()
        vm.compose(create = true)
        assertTrue(vm.uiState.value.draftChoiceVisible)
        assertNull(vm.uiState.value.composer)
        vm.resumeDraft()
        assertEquals("reply:9:2", vm.uiState.value.composer?.key)
        assertEquals("Reply", repo.savedDraft?.raw)
    }

    @Test fun chatWindowCanReachBothEndsBeyondFiveHundredMessages() = runTest(dispatcher) {
        val repo = Repo().apply { chat = true }
        val vm = ForumViewModel(repo)
        vm.login("member", "not-real", "")
        runCurrent()
        val channel = ForumChannel(3, "Channel", true)
        vm.member(ForumMemberTab.CHAT, channel = channel)
        runCurrent()
        repeat(10) {
            vm.member(ForumMemberTab.CHAT, true, channel)
            runCurrent()
        }
        var page = vm.uiState.value.memberPage
        assertEquals(500, page.messages.size)
        assertEquals(51L, page.messages.first().id)
        assertEquals(550L, page.messages.last().id)
        assertTrue(page.more)
        assertTrue(page.moreNewer)
        vm.member(ForumMemberTab.CHAT, true, channel)
        runCurrent()
        assertEquals(1L, vm.uiState.value.memberPage.messages.first().id)
        assertFalse(vm.uiState.value.memberPage.more)
        repeat(2) {
            vm.member(ForumMemberTab.CHAT, true, channel, newer = true)
            runCurrent()
        }
        page = vm.uiState.value.memberPage
        assertEquals(101L, page.messages.first().id)
        assertEquals(600L, page.messages.last().id)
        assertTrue(page.more)
        assertFalse(page.moreNewer)
        assertEquals(0, repo.commands)
    }

    @Test fun bookmarkOpensNativeThreadAtRequestedMessageAndRefreshKeepsScope() = runTest(dispatcher) {
        val repo = Repo().apply { chat = true }
        val vm = ForumViewModel(repo)
        vm.login("member", "not-real", "")
        runCurrent()
        vm.openBookmark(ForumBookmark(1, "Chat", 0, 1, target = ForumBookmarkTarget.Chat(3, 200, 8)))
        runCurrent()
        assertEquals(8L, vm.uiState.value.threadId)
        assertEquals(200L, vm.uiState.value.chatTarget)
        assertTrue(vm.uiState.value.memberPage.messages.any { it.id == 200L })
        repo.gate = CompletableDeferred()
        vm.member(ForumMemberTab.CHAT, channel = vm.uiState.value.channel, threadId = 8)
        runCurrent()
        assertTrue(vm.uiState.value.accountBusy)
        assertEquals("open", vm.uiState.value.memberPage.thread?.status)
        assertTrue(vm.uiState.value.memberPage.messages.isNotEmpty())
        repo.gate!!.complete(Unit)
        runCurrent()
        assertEquals(0, repo.commands)
    }

    @Test fun explicitGuestInteractionReturnsToTheTopicAfterLoginWithoutSubmittingAnything() = runTest(dispatcher) {
        val repo = Repo()
        val vm = ForumViewModel(repo)
        vm.accountForTopic(9, 20)
        assertTrue(vm.uiState.value.loginVisible)
        vm.login("member", "fixture-password", "")
        advanceUntilIdle()
        assertEquals(9L, vm.uiState.value.topic!!.id)
        assertEquals(20, vm.uiState.value.anchorNumber)
        assertEquals(0, repo.commands)
        assertNull(vm.uiState.value.composer)
    }
}
