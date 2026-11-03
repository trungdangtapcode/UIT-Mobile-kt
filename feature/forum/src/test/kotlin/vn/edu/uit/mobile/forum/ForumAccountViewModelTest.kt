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
}
