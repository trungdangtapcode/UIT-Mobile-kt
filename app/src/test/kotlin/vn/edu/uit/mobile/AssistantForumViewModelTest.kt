package vn.edu.uit.mobile

import android.app.Application
import androidx.lifecycle.ViewModelStore
import java.lang.reflect.Proxy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import vn.edu.uit.mobile.dashboard.*
import vn.edu.uit.mobile.domain.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class AssistantForumViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val coverage = AssistantForumCoverage(7, "Public topic", 1, 2, listOf(11, 22), listOf(11, 22), emptyList(), 1, 5, false, false, "", "a".repeat(64), "b".repeat(64), false)

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    @After fun cleanup() {
        store.clear()
        Dispatchers.resetMain()
    }
    private inner class Repo : AssistantRepository {
        override val library = MutableStateFlow(AssistantLibrary())
        var pending: CompletableDeferred<Unit>? = null
        var fail = false
        var creates = 0
        var sends = 0
        var failure: Exception? = null
        override suspend fun initialize() = Unit
        override suspend fun inspectForum(scope: AssistantForumScope): AssistantForumCoverage {
            pending?.let { withContext(NonCancellable) { it.await() } }
            check(!fail)
            return coverage
        }
        override suspend fun create(name: String, scope: AssistantScope): String {
            creates++
            library.value = AssistantLibrary(listOf(AssistantWorkspace("new", name, scope, 1)))
            return "new"
        }
        override suspend fun ask(id: String, question: String, language: String, progress: (AssistantProgress) -> Unit) {
            sends++
            failure?.let { throw it }
        }
        override suspend fun rename(id: String, name: String) = Unit
        override suspend fun delete(id: String) = Unit
        override suspend fun clear() = Unit
        override suspend fun checklist(workspaceId: String, turnId: String, items: List<AssistantChecklistItem>) = Unit
    }
    private fun vm(repo: Repo): AssistantViewModel {
        val notices = MutableStateFlow(AnnouncementInbox())
        val announcements = Proxy.newProxyInstance(AnnouncementRepository::class.java.classLoader, arrayOf(AnnouncementRepository::class.java)) { _, method, _ ->
            when (method.name) {
                "getState" -> notices
                "initialize" -> Unit
                else -> error("Unexpected university operation ${method.name}")
            }
        } as AnnouncementRepository
        val study = Proxy.newProxyInstance(StudyAiRepository::class.java.classLoader, arrayOf(StudyAiRepository::class.java)) { _, method, _ ->
            when (method.name) {
                "connection" -> StudyConnection(configured = true)
                "getConnectionChanges" -> kotlinx.coroutines.flow.emptyFlow<StudyConnection>()
                else -> error("No inference or configuration allowed ${method.name}")
            }
        } as StudyAiRepository
        return AssistantViewModel(repo, study, announcements).also { store.put("assistant", it) }
    }

    @Test fun typedRequestFailuresStayDistinctAndNeverRetryAutomatically() = runTest(dispatcher) {
        val cases = listOf(
            ChatGptException(ChatGptProblem.QUOTA, ChatGptDiagnostic(200, "subscription_sharing_usage_limit_exceeded", phase = "response.failed")) to AssistantProblem.QUOTA,
            ChatGptException(ChatGptProblem.NETWORK, ChatGptDiagnostic(200, "socket_failure", phase = "response_body")) to AssistantProblem.NETWORK,
            StudyAiException("fixture-private-message", ChatGptDiagnostic(400, "invalid_request_error", phase = "http_error", parameter = "tools[0]")) to AssistantProblem.REQUEST,
            StudyAiException("fixture-private-message", ChatGptDiagnostic(0, "answer_evidence_unverified", phase = "validation")) to AssistantProblem.VERIFICATION
        )
        for ((error, expected) in cases) {
            val repo = Repo().apply { failure = error }
            val vm = vm(repo)
            runCurrent()
            vm.select("workspace")
            vm.ask("hello", "English")
            advanceUntilIdle()
            assertEquals(expected, vm.state.value.problem)
            assertNotNull(vm.state.value.diagnostic)
            assertFalse(vm.state.value.diagnostic!!.report().contains("fixture-private-message"))
            assertEquals(1, repo.sends)
            vm.dismiss()
            assertNull(vm.state.value.diagnostic)
        }
    }

    @Test fun creationChecksPublicAvailabilityWithoutSendingOrChangingTheSelectedScope() = runTest(dispatcher) {
        val repo = Repo()
        val vm = vm(repo)
        runCurrent()
        vm.createForForum(AssistantForumScope(7, 5, 2))
        runCurrent()
        assertEquals("new", vm.state.value.selected)
        assertEquals(AssistantForumScope(7, 5, 2), vm.state.value.library.workspaces.single().scope.forum)
        assertEquals(0, repo.sends)
        assertFalse(vm.state.value.preparingContext)
    }

    @Test fun leavingDuringNonCooperativePreflightCannotCreateOrSelectALateWorkspace() = runTest(dispatcher) {
        val repo = Repo()
        val vm = vm(repo)
        runCurrent()
        val gate = CompletableDeferred<Unit>()
        repo.pending = gate
        vm.createForForum(AssistantForumScope(7))
        runCurrent()
        assertTrue(vm.state.value.preparingContext)
        vm.stop()
        gate.complete(Unit)
        runCurrent()
        assertEquals(0, repo.creates)
        assertNull(vm.state.value.selected)
        assertFalse(vm.state.value.preparingContext)
    }

    @Test fun unavailablePublicContextRemainsAnErrorWithoutCreatingOrGenerating() = runTest(dispatcher) {
        val repo = Repo().apply { fail = true }
        val vm = vm(repo)
        runCurrent()
        vm.createForForum(AssistantForumScope(7))
        runCurrent()
        assertEquals(AssistantProblem.ACTION, vm.state.value.problem)
        assertEquals(0, repo.creates)
        assertEquals(0, repo.sends)
        assertFalse(vm.state.value.preparingContext)
    }
}
