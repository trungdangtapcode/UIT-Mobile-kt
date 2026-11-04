package vn.edu.uit.mobile

import java.lang.reflect.Proxy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import vn.edu.uit.mobile.courses.*
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.model.*

/** Synthetic adapters reject every university operation except reading the local snapshot. */
internal class CodeTutorTestHarness(dispatcher: CoroutineDispatcher) {
    val course = Course(1, "owner", "IT001", "Introduction to programming")
    val ownerFlow = MutableStateFlow(listOf(Account("owner", "https://example.invalid", 1, "Synthetic student", PortalType.MOODLE)))
    val owners = object : AccountRepository {
        override val accounts = ownerFlow
        override suspend fun remove(accountId: String) = error("University writes forbidden")
        override suspend fun saveSessions(sessions: List<PortalSession>) = error("University writes forbidden")
    }
    val snapshot = MutableStateFlow(PortalSnapshot(courses = listOf(course)))
    val universityCalls = mutableListOf<String>()
    private val portal = Proxy.newProxyInstance(PortalRepository::class.java.classLoader, arrayOf(PortalRepository::class.java)) { _, method, _ ->
        universityCalls += method.name
        when (method.name) {
            "getSnapshot" -> snapshot
            else -> throw AssertionError("University operation forbidden: ${method.name}")
        }
    } as PortalRepository
    val imported = TutorCodeSource("synthetic:cell:3", "first-item.ipynb", TutorFixtures.code, "Python", "a".repeat(64), 3)
    var contextPending: CompletableDeferred<List<StudySource>>? = null
    var replyPending: CompletableDeferred<TutorResult>? = null
    var configured = true
    var failGenerate = false
    var failConfigure = false
    var unknown = false
    val requests = mutableListOf<TutorRequest>()
    val ai = object : StudyAiRepository {
        override suspend fun connection() = StudyConnection(configured = configured, model = "synthetic-code-tutor")
        override suspend fun configure(endpoint: String, token: String) {
            check(!failConfigure)
            configured = true
        }
        override suspend fun configureDirect(apiKey: String, model: String) {
            check(!failConfigure)
            configured = true
        }
        override suspend fun disconnect() {
            configured = false
        }
        override suspend fun tutor(course: Course?, request: TutorRequest): TutorResult {
            requests += request
            check(!failGenerate)
            return replyPending?.await() ?: TutorFixtures.result(request).let { result ->
                if (request.sources.any { it.id == TutorFixtures.context.id }) result.copy(observations = result.observations + TutorPoint("course", "The selected lesson describes last-in, first-out removal; it does not define an empty-list contract.", citations = listOf(StudyCitation(TutorFixtures.context.id, TutorFixtures.context.text)))) else result
            }.let {
                if (unknown) it.copy(status = "insufficient_context", summary = "The implementation of the called dependency is missing.", observations = emptyList(), hints = emptyList(), tests = emptyList(), proposedCode = "", editReason = "") else it
            }
        }
        override suspend fun generate(course: Course, mode: String, sources: List<StudySource>, question: String, language: String): StudyAnswer = error("Wrong AI mode")
        override suspend fun tasks(course: Course, assignmentId: Long) = emptyList<StudyTask>()
        override suspend fun saveTasks(course: Course, assignmentId: Long, tasks: List<StudyTask>) = error("Task writes forbidden")
    }
    val vm = CodeTutorViewModel(ai, owners, portal, OfflineCourseSourceLoader { _, _, _ -> contextPending?.await() ?: listOf(TutorFixtures.context) }, OfflineTutorCodeLoader { _, _, _ -> TutorCodeCatalog(listOf(imported)) }, dispatcher)
}
