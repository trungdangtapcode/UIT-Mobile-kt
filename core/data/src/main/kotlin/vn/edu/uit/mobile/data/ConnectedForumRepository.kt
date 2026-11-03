package vn.edu.uit.mobile.data

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.forum.network.*

/** Member content is memory-only and never enters the anonymous repository/cache. */
class ConnectedForumRepository(
    private val guest: ForumRepository,
    private val client: ForumSessionClient,
    private val vault: StudyStorage
) : ForumAccountRepository {
    private val epoch = AtomicLong()
    private val sessionLock = Mutex()
    private val localLock = Any()

    @Volatile private var user: ForumUser? = null

    @Volatile private var restored = false

    @Volatile private var memberMode = false
    private var memory = ForumLocalState()
    private val api = ForumMemberApi(client)
    private fun clearMemory() {
        synchronized(localLock) { memory = ForumLocalState() }
    }
    private fun invalidate() {
        epoch.incrementAndGet()
        user = null
        client.clear()
        clearMemory()
    }
    override suspend fun resume(): ForumUser? = withContext(Dispatchers.IO) {
        sessionLock.withLock {
            if (restored) return@withLock user
            val raw = try {
                vault.read("session")
            } catch (_: Exception) {
                throw ForumException(ForumFailure.STORAGE)
            }
            if (raw == null) {
                restored = true
                return@withLock null
            }
            val token = epoch.get()
            memberMode = true
            try {
                val root = Json.parseToJsonElement(raw).jsonObject
                client.restore(root.getValue("cookies").jsonPrimitive.content)
                val actual = client.current()
                if (actual.id != root.getValue("userId").jsonPrimitive.long) throw ForumException(ForumFailure.AUTHENTICATION)
                ensure(token)
                user = actual
                restored = true
                persist(actual)
                actual
            } catch (e: CancellationException) {
                throw e
            } catch (e: ForumException) {
                if (e.reason in listOf(ForumFailure.AUTHENTICATION, ForumFailure.FORBIDDEN)) {
                    invalidate()
                    vault.remove("session")
                    restored = true
                    memberMode = false
                    null
                } else {
                    throw e
                }
            } catch (_: Exception) {
                invalidate()
                vault.remove("session")
                restored = true
                memberMode = false
                null
            }
        }
    }
    override suspend fun login(username: String, password: String, secondFactor: String): ForumUser = withContext(Dispatchers.IO) {
        sessionLock.withLock {
            invalidate()
            try {
                vault.remove("session")
            } catch (_: Exception) {
                throw ForumException(ForumFailure.STORAGE)
            }
            val token = epoch.get()
            try {
                val actual = client.login(username, password, secondFactor)
                ensure(token)
                persist(actual)
                user = actual
                restored = true
                memberMode = true
                actual
            } catch (e: Exception) {
                if (token == epoch.get()) invalidate()
                throw e
            }
        }
    }
    override suspend fun disconnect() {
        // Invalidate immediately, including non-cancellable late network responses.
        invalidate()
        memberMode = false
        withContext(Dispatchers.IO) {
            sessionLock.withLock {
                try {
                    vault.remove("session")
                    restored = true
                } catch (_: Exception) {
                    throw ForumException(ForumFailure.STORAGE)
                }
            }
        }
    }
    private fun persist(actual: ForumUser) {
        try {
            vault.write(
                "session",
                buildJsonObject {
                    put("userId", actual.id)
                    put("cookies", client.snapshot())
                }.toString()
            )
        } catch (_: Exception) {
            throw ForumException(ForumFailure.STORAGE)
        }
    }
    private fun ensure(token: Long) {
        if (epoch.get() != token) throw CancellationException("Forum account changed")
    }
    private suspend fun <T> authenticated(block: suspend (ForumUser) -> T): T = withContext(Dispatchers.IO) {
        val token = epoch.get()
        val expected = user ?: throw ForumException(ForumFailure.AUTHENTICATION)
        try {
            val actual = client.current()
            ensure(token)
            if (actual.id != expected.id) throw ForumException(ForumFailure.AUTHENTICATION)
            val result = block(actual)
            ensure(token)
            user = actual
            // A confirmed write must not be reported as failed just because cookie
            // refresh persistence failed: that would encourage duplicate submission.
            runCatching { persist(actual) }
            result
        } catch (e: ForumException) {
            if (e.reason == ForumFailure.AUTHENTICATION && token == epoch.get()) {
                invalidate()
                runCatching { vault.remove("session") }
            }
            throw e
        }
    }
    override suspend fun load(request: ForumRequest): ForumLoaded {
        val token = epoch.get()
        if (memberMode && user == null) throw ForumException(ForumFailure.AUTHENTICATION)
        if (user == null) return guest.load(request).also { ensure(token) }
        return authenticated { ForumLoaded(ForumParser.parse(request, client.get(request), authenticated = true), System.currentTimeMillis() / 1000) }
    }
    override suspend fun member(request: ForumMemberRequest) = authenticated { api.read(it, request) }
    override suspend fun command(command: ForumCommand) = authenticated { api.send(it, command) }
    override suspend fun image(url: String): ByteArray {
        val token = epoch.get()
        if (memberMode && user == null) throw ForumException(ForumFailure.AUTHENTICATION)
        return if (user == null) guest.image(url).also { ensure(token) } else authenticated { client.image(url) }
    }
    override suspend fun local(): ForumLocalState = if (memberMode) synchronized(localLock) { memory } else guest.local()
    override suspend fun save(topic: ForumTopic, saved: Boolean): ForumLocalState {
        if (memberMode && user == null) throw ForumException(ForumFailure.AUTHENTICATION)
        if (user == null) return guest.save(topic, saved)
        return synchronized(localLock) {
            val others = memory.saved.filterNot { it.id == topic.id }
            if (saved && others.size >= ForumLimits.SAVED) throw ForumException(ForumFailure.CAPACITY)
            memory = memory.copy(
                saved = if (saved) listOf(topic) + others else others,
                savedAt = if (saved) memory.savedAt + (topic.id to (System.currentTimeMillis() / 1000)) else memory.savedAt - topic.id
            )
            memory
        }
    }
    override suspend fun position(position: ForumPosition) {
        if (memberMode && user == null) return
        if (user == null) {
            guest.position(position)
        } else {
            synchronized(localLock) { memory = memory.copy(positions = (listOf(position) + memory.positions.filterNot { it.topic.id == position.topic.id }).take(120)) }
        }
    }
    override suspend fun clear() {
        disconnect()
        guest.clear()
    }
}
