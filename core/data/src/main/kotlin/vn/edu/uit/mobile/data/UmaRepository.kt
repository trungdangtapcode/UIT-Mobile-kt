package vn.edu.uit.mobile.data

import android.content.Context
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.model.UmaSession

fun createUmaRepository(context: Context): UmaRepository = ConnectedUmaRepository(StudyPrivateStorage(context, "uma", "uit.uma.v1"))

/** Only the UMA session is encrypted on disk. Personal conversations remain in process memory. */
class ConnectedUmaRepository(private val storage: StudyStorage, private val remote: UmaRemote = UmaClient()) : UmaRepository {
    private val mutable = MutableStateFlow(UmaState())
    override val state = mutable.asStateFlow()
    private val mutex = Mutex()
    private var session: UmaSession? = null
    private var epoch = 0L
    private var initialized = false
    override suspend fun initialize() {
        val shouldLoad = mutex.withLock {
            if (initialized) {
                false
            } else {
                initialized = true
                true
            }
        }
        if (!shouldLoad) return
        try {
            val raw = withContext(Dispatchers.IO) { storage.read("session") }
            if (raw == null) {
                mutex.withLock { if (epoch == 0L) mutable.value = UmaState(loading = false) }
                return
            }
            val d = Json.parseToJsonElement(raw).jsonObject
            establish(UmaSession(d.getValue("cookie").jsonPrimitive.content, d.getValue("csrf").jsonPrimitive.content), false)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            mutex.withLock { if (epoch == 0L) mutable.value = UmaState(loading = false, error = UmaFailure.STORAGE) }
        }
    }
    override suspend fun connect(session: UmaSession) = establish(session, true)
    private suspend fun establish(value: UmaSession, replace: Boolean) {
        val token = mutex.withLock {
            if (!replace && epoch != 0L) return
            epoch++
            session = null
            mutable.value = UmaState(loading = true, revision = epoch)
            epoch
        }
        try {
            if (replace) {
                mutex.withLock {
                    if (epoch != token) return
                    withContext(Dispatchers.IO) { storage.remove("session") }
                }
            }
            UmaProtocol.session(value)
            val connected = remote.connect(value)
            mutex.withLock {
                if (epoch != token) return
                withContext(Dispatchers.IO) { save(connected.session) }
                session = connected.session
                mutable.value = UmaState(loading = false, identity = connected.bootstrap.identity, available = connected.bootstrap.available, selected = setOf(UmaSource.GENERAL).intersect(connected.bootstrap.available).ifEmpty { setOf(connected.bootstrap.available.first()) }, revision = epoch)
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { mutex.withLock { if (epoch == token) mutable.value = mutable.value.copy(loading = false) } }
            throw e
        } catch (e: Exception) {
            mutex.withLock {
                if (epoch == token) mutable.value = UmaState(loading = false, error = (e as? UmaException)?.reason ?: UmaFailure.STORAGE, revision = epoch)
            }
        }
    }
    private fun save(value: UmaSession) = storage.write(
        "session",
        buildJsonObject {
            put("cookie", value.cookie)
            put("csrf", value.csrf)
        }.toString()
    )
    override suspend fun disconnect() = mutex.withLock {
        epoch++
        session = null
        mutable.value = UmaState(loading = false, revision = epoch)
        try {
            withContext(Dispatchers.IO) { storage.remove("session") }
        } catch (_: Exception) {
            mutable.value = mutable.value.copy(error = UmaFailure.STORAGE)
        }
    }
    override suspend fun sources(sources: Set<UmaSource>) = mutex.withLock {
        require(sources.isNotEmpty() && mutable.value.available.containsAll(sources))
        epoch++
        mutable.value = mutable.value.copy(selected = sources, turns = emptyList(), busy = false, error = null, revision = epoch)
    }
    override suspend fun clear() = mutex.withLock {
        epoch++
        mutable.value = mutable.value.copy(turns = emptyList(), busy = false, error = null, revision = epoch)
    }
    override suspend fun ask(question: String, suggestionId: Long?) {
        val id = UUID.randomUUID().toString()
        val lease = mutex.withLock {
            val s = session ?: throw UmaException(UmaFailure.EXPIRED)
            check(!mutable.value.busy)
            val body = UmaProtocol.request(question.trim(), mutable.value.selected, mutable.value.turns, suggestionId)
            mutable.value = mutable.value.copy(busy = true, error = null, turns = (mutable.value.turns + UmaTurn(id, question.trim(), UmaTurnStatus.RUNNING, sources = mutable.value.selected)).takeLast(24))
            Triple(epoch, s, body)
        }
        try {
            val result = remote.ask(lease.second, lease.third)
            mutex.withLock {
                if (epoch != lease.first) return
                withContext(Dispatchers.IO) { save(result.session) }
                session = result.session
                mutable.value = mutable.value.copy(busy = false, turns = mutable.value.turns.map { if (it.id == id) it.copy(status = UmaTurnStatus.COMPLETE, answer = result.answer) else it })
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { mutex.withLock { if (epoch == lease.first) mutable.value = mutable.value.copy(busy = false, turns = mutable.value.turns.map { if (it.id == id) it.copy(status = UmaTurnStatus.CANCELLED) else it }) } }
            throw e
        } catch (e: Exception) {
            mutex.withLock {
                if (epoch != lease.first) return
                val reason = (e as? UmaException)?.reason ?: UmaFailure.STORAGE
                if (reason in setOf(UmaFailure.EXPIRED, UmaFailure.CSRF)) {
                    session = null
                    withContext(Dispatchers.IO) { runCatching { storage.remove("session") } }
                    mutable.value = mutable.value.copy(identity = null)
                }
                mutable.value = mutable.value.copy(busy = false, error = reason, turns = mutable.value.turns.map { if (it.id == id) it.copy(status = UmaTurnStatus.FAILED) else it })
            }
        }
    }
}
