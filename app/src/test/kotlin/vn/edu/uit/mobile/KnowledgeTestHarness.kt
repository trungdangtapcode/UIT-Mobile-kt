package vn.edu.uit.mobile

import java.lang.reflect.Proxy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import vn.edu.uit.mobile.courses.*
import vn.edu.uit.mobile.data.*
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.model.*

internal class KnowledgeTestHarness(dispatcher: CoroutineDispatcher) {
    val ownerFlow = MutableStateFlow(listOf(Account("owner", "https://example.invalid", 1, "Synthetic student", PortalType.MOODLE)))
    val accounts = object : AccountRepository {
        override val accounts = ownerFlow
        override suspend fun remove(accountId: String) = error("University mutation forbidden")
        override suspend fun saveSessions(sessions: List<PortalSession>) = error("University mutation forbidden")
    }
    val snapshot = MutableStateFlow(PortalSnapshot(courses = listOf(KnowledgeFixtures.course)))
    val calls = mutableListOf<String>()
    var failWrite = false
    var committedFailure = false
    var waitGenerate: CompletableDeferred<Unit>? = null
    var result = KnowledgeFixtures.generation
    var generations = 0
    val values = mutableMapOf<String, String>()
    val storage = object : StudyStorage {
        override fun read(key: String) = values[key]
        override fun write(key: String, value: String) {
            check(!failWrite)
            values[key] = value
            check(!committedFailure)
        }
        override fun remove(key: String) {
            values.remove(key)
        }
        override fun removeAccount(accountId: String) = error("Local cleanup is simulated explicitly")
    }
    val library = LocalKnowledgeRepository(storage, accounts, dispatcher)
    private val portal = Proxy.newProxyInstance(PortalRepository::class.java.classLoader, arrayOf(PortalRepository::class.java)) { _, method, _ ->
        calls += method.name
        if (method.name == "getSnapshot") snapshot else throw AssertionError("University operation forbidden: ${method.name}")
    } as PortalRepository
    private val ai = object : StudyAiRepository {
        override suspend fun connection() = StudyConnection(configured = true, model = "synthetic-map-model")
        override suspend fun configure(endpoint: String, token: String) = Unit
        override suspend fun configureDirect(apiKey: String, model: String) = Unit
        override suspend fun disconnect() = Unit
        override suspend fun generate(course: Course, mode: String, sources: List<StudySource>, question: String, language: String): StudyAnswer = error("Wrong AI route")
        override suspend fun tasks(course: Course, assignmentId: Long) = emptyList<StudyTask>()
        override suspend fun saveTasks(course: Course, assignmentId: Long, tasks: List<StudyTask>) = error("Task save forbidden")
        override suspend fun knowledge(course: Course, sources: List<StudySource>, focus: String, language: String): KnowledgeGeneration {
            generations++
            waitGenerate?.await()
            return result.copy(graph = result.graph.copy(focus = focus, language = language))
        }
    }
    val vm = KnowledgeViewModel(ai, library, accounts, portal, OfflineCourseSourceLoader { _, _, _ -> KnowledgeFixtures.sources }, dispatcher)
}
