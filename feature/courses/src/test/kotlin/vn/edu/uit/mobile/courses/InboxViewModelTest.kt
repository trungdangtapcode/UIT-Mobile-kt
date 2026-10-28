package vn.edu.uit.mobile.courses

import app.cash.turbine.test
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import vn.edu.uit.mobile.common.*
import vn.edu.uit.mobile.domain.FakePortalRepository
import vn.edu.uit.mobile.model.*

@OptIn(ExperimentalCoroutinesApi::class)
class InboxViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test fun widgetDeadlineFocusExitsChatAndSelectsOverdueWithoutARefresh() = runTest(dispatcher) {
        var refreshes = 0
        val repository = object : FakePortalRepository() {
            override suspend fun refresh(): AppResult<Unit, AppError> {
                refreshes++
                return AppResult.Success(Unit)
            }
        }
        val model = InboxViewModel(repository)
        model.onIntent(InboxIntent.Open(Conversation(1, "Test conversation")))
        runCurrent()
        model.onIntent(InboxIntent.ShowDeadlines(overdue = true))
        runCurrent()
        assertEquals(0, model.uiState.value.tab)
        assertNull(model.uiState.value.selected)
        assertTrue(model.uiState.value.overdueFocus)
        assertEquals(0, refreshes)
    }

    @Test fun previousConversationCannotReplaceCurrentMessages() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val repository = object : FakePortalRepository() {
            override suspend fun messages(conversationId: Long): AppResult<List<Message>, AppError> {
                if (conversationId == 1L) gate.await()
                return AppResult.Success(listOf(Message(conversationId, "Conversation $conversationId")))
            }
        }
        val viewModel = InboxViewModel(repository)
        viewModel.onIntent(InboxIntent.Open(Conversation(1, "First")))
        runCurrent()
        viewModel.onIntent(InboxIntent.Open(Conversation(2, "Second")))
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertEquals(2L, viewModel.uiState.value.selected!!.id)
        assertEquals("Conversation 2", viewModel.uiState.value.messages.single().text)
    }

    @Test fun failedSendDoesNotEmitAnEventThatClearsTheDraft() = runTest(dispatcher) {
        val repository = object : FakePortalRepository() {
            override suspend fun sendMessage(conversationId: Long, text: String): AppResult<Unit, AppError> = AppResult.Failure(AppError.NetworkOffline)
        }
        val viewModel = InboxViewModel(repository)
        viewModel.onIntent(InboxIntent.Open(Conversation(1, "First")))
        runCurrent()
        viewModel.uiEffect.test {
            viewModel.onIntent(InboxIntent.Send("Keep this draft"))
            runCurrent()
            assertTrue(awaitItem() is InboxEffect.Notice)
            expectNoEvents()
        }
        assertFalse(viewModel.uiState.value.sending)
    }

    @Test fun successfulSendTargetsTheOriginalConversationAfterSwitching() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val repository = object : FakePortalRepository() {
            override suspend fun sendMessage(conversationId: Long, text: String): AppResult<Unit, AppError> {
                gate.await()
                return AppResult.Success(Unit)
            }
            override suspend fun messages(conversationId: Long): AppResult<List<Message>, AppError> = AppResult.Success(listOf(Message(conversationId, "Conversation $conversationId")))
        }
        val viewModel = InboxViewModel(repository)
        viewModel.onIntent(InboxIntent.Open(Conversation(1, "First")))
        runCurrent()
        viewModel.uiEffect.test {
            viewModel.onIntent(InboxIntent.Send("Sent to the first conversation"))
            runCurrent()
            viewModel.onIntent(InboxIntent.Open(Conversation(2, "Second")))
            runCurrent()
            gate.complete(Unit)
            runCurrent()
            assertEquals(InboxEffect.MessageSent(1), awaitItem())
        }
        assertEquals("Conversation 2", viewModel.uiState.value.messages.single().text)
    }
}
