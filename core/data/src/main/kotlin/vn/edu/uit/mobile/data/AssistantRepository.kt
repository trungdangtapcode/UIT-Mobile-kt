package vn.edu.uit.mobile.data

import android.content.Context
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import vn.edu.uit.mobile.domain.*

fun createAssistantRepository(context: Context, announcements: AnnouncementRepository, study: ConnectedStudyAiRepository): AssistantRepository = createAssistantRepository(announcements, study, StudyPrivateStorage(context, "assistant_workspaces", "uit.assistant.v1"))
fun createAssistantRepository(announcements: AnnouncementRepository, study: ConnectedStudyAiRepository, storage: StudyStorage): AssistantRepository = LocalAssistantRepository(storage, announcements, study::assistantModel)

internal class LocalAssistantRepository(
    private val storage: StudyStorage,
    private val announcements: AnnouncementRepository,
    private val model: () -> AssistantModel,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 }
) : AssistantRepository {
    private val mutex = Mutex()
    private val runMutex = Mutex()
    private var loaded = false
    private var loadFailed = false
    private var epoch = 0L
    private val mutable = MutableStateFlow(AssistantLibrary())
    override val library = mutable.asStateFlow()
    override suspend fun initialize() = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!loaded) {
                try {
                    val restored = storage.read("library")?.let(AssistantCodec::decode) ?: AssistantLibrary()
                    val workspaces = restored.workspaces.map { w -> w.copy(turns = w.turns.map { if (it.status == AssistantTurnStatus.RUNNING) it.copy(status = AssistantTurnStatus.INTERRUPTED) else it }) }
                    mutable.value = restored.copy(workspaces = workspaces)
                } catch (_: Exception) {
                    loadFailed = true
                    mutable.value = AssistantLibrary(storageFailed = true)
                }
                loaded = true
            }
        }
    }
    private fun commit(value: AssistantLibrary, reset: Boolean = false) {
        try {
            check(!loadFailed || reset) { "Unreadable storage requires an explicit reset" }
            storage.write("library", AssistantCodec.encode(value.copy(storageFailed = false)))
            loadFailed = false
            mutable.value = value.copy(storageFailed = false)
        } catch (e: Exception) {
            mutable.value = mutable.value.copy(storageFailed = true)
            throw e
        }
    }
    override suspend fun create(name: String, scope: AssistantScope): String = withContext(Dispatchers.IO) {
        AssistantRules.name(name)
        AssistantRules.scope(scope)
        initialize()
        mutex.withLock {
            require(library.value.workspaces.size < AssistantRules.WORKSPACES)
            val id = UUID.randomUUID().toString()
            commit(library.value.copy(workspaces = listOf(AssistantWorkspace(id, name.trim(), scope, now())) + library.value.workspaces))
            id
        }
    }
    override suspend fun rename(id: String, name: String) = edit(id) {
        AssistantRules.name(name)
        it.copy(name = name.trim())
    }
    private suspend fun edit(id: String, action: (AssistantWorkspace) -> AssistantWorkspace) = withContext(Dispatchers.IO) {
        initialize()
        mutex.withLock {
            val old = library.value.workspaces.first { it.id == id }
            val next = action(old).copy(revision = old.revision + 1)
            commit(library.value.copy(workspaces = library.value.workspaces.map { if (it.id == id) next else it }))
        }
    }
    override suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        initialize()
        mutex.withLock { commit(library.value.copy(workspaces = library.value.workspaces.filterNot { it.id == id })) }
    }
    override suspend fun clear() = withContext(Dispatchers.IO) {
        initialize()
        mutex.withLock {
            epoch++
            commit(AssistantLibrary(), reset = true)
        }
    }
    override suspend fun ask(id: String, question: String, language: String, progress: (AssistantProgress) -> Unit) = withContext(Dispatchers.IO) {
        AssistantRules.question(question, language)
        initialize()
        check(runMutex.tryLock()) { "An assistant request is already running" }
        var started = false
        var token = 0L
        var revision = 0L
        val turnId = UUID.randomUUID().toString()
        try {
            val runner = AssistantRunner(model(), announcements)
            val workspace = mutex.withLock {
                val old = library.value.workspaces.first { it.id == id }
                require(old.turns.size < AssistantRules.TURNS) { "Create a new workspace to continue" }
                token = epoch
                revision = old.revision + 1
                val next = old.copy(revision = revision, turns = old.turns + AssistantTurn(turnId, question.trim(), now(), AssistantTurnStatus.RUNNING))
                commit(library.value.copy(workspaces = library.value.workspaces.map { if (it.id == id) next else it }))
                started = true
                old
            }
            suspend fun guard() {
                mutex.withLock { check(epoch == token && library.value.workspaces.any { it.id == id && it.revision == revision && it.turns.lastOrNull()?.id == turnId }) { "Workspace changed during generation" } }
            }
            val answer = runner.run(workspace, question, language, ::guard, progress)
            guard()
            mutex.withLock {
                check(epoch == token)
                val old = library.value.workspaces.first { it.id == id && it.revision == revision }
                val turn = old.turns.last().copy(status = AssistantTurnStatus.COMPLETE, facts = answer.facts, evidence = answer.evidence, events = answer.events, model = answer.model, coverage = answer.coverage)
                commit(library.value.copy(workspaces = library.value.workspaces.map { if (it.id == id) old.copy(turns = old.turns.dropLast(1) + turn) else it }))
            }
        } catch (error: Exception) {
            if (started) {
                withContext(NonCancellable) {
                    mutex.withLock {
                        val old = library.value.workspaces.find { it.id == id }
                        if (old != null && epoch == token && old.turns.any { it.id == turnId && it.status == AssistantTurnStatus.RUNNING }) {
                            runCatching { commit(library.value.copy(workspaces = library.value.workspaces.map { if (it.id == id) old.copy(turns = old.turns.map { t -> if (t.id == turnId) t.copy(status = if (error is CancellationException && error !is TimeoutCancellationException) AssistantTurnStatus.CANCELLED else AssistantTurnStatus.FAILED) else t }) else it })) }
                        }
                    }
                }
            }
            throw error
        } finally {
            runMutex.unlock()
        }
    }
    override suspend fun checklist(workspaceId: String, turnId: String, items: List<AssistantChecklistItem>) = edit(workspaceId) { workspace ->
        require(items.size <= 20 && items.all { it.id.length in 1..80 && it.text.isNotBlank() && it.text.length <= 1200 } && items.distinctBy { it.id }.size == items.size)
        require(workspace.turns.any { it.id == turnId && it.status == AssistantTurnStatus.COMPLETE })
        workspace.copy(turns = workspace.turns.map { if (it.id == turnId) it.copy(checklist = items) else it })
    }
}
