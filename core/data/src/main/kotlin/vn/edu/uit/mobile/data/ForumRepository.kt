package vn.edu.uit.mobile.data

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.forum.network.*

fun createForumRepository(context: Context): ForumRepository = ConnectedForumRepository(LocalForumRepository(AnonymousForumClient(), StudyPrivateStorage(context, "forum_public", "uit.forum.public.v1")), ForumSessionClient(), StudyPrivateStorage(context, "forum_session", "uit.forum.session.v1"))

/** Guest namespace: public responses and local preferences never share a UIT account. */
class LocalForumRepository(private val remote: ForumTransport, private val storage: StudyStorage, private val now: () -> Long = { System.currentTimeMillis() / 1000 }) : ForumRepository {
    private val mutex = Mutex()
    private var generation = 0L
    private data class Cached(val key: String, val body: String, val at: Long)
    private fun JsonObject.string(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun JsonObject.number(key: String) = string(key).toLongOrNull() ?: 0L
    private fun topic(value: ForumTopic) = buildJsonObject {
        put("id", value.id)
        put("title", value.title)
        put("category", value.categoryId)
        put("posts", value.posts)
        put("date", value.lastPosted)
        put("pinned", value.pinned)
        put("closed", value.closed)
        put("tags", JsonArray(value.tags.map(::JsonPrimitive)))
    }
    private fun topic(value: JsonObject) = ForumTopic(value.number("id"), value.string("title").take(1000), value.number("category"), value.number("posts").toInt(), value.string("date"), value.string("pinned") == "true", value.string("closed") == "true", (value["tags"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.take(20))
    private fun readLocal(): ForumLocalState {
        val raw = storage.read("local") ?: return ForumLocalState()
        val root = Json.parseToJsonElement(raw).jsonObject
        val saved = (root["saved"] as? JsonArray).orEmpty().take(ForumLimits.SAVED).map { topic(it.jsonObject) }.filter { it.id > 0 }.distinctBy { it.id }
        val positions = (root["positions"] as? JsonArray).orEmpty().take(120).map { it.jsonObject }.map {
            ForumPosition(topic(it.getValue("topic").jsonObject), it.number("postId"), it.number("number").toInt(), it.number("offset").toInt().coerceIn(0, 10000))
        }.filter { it.topic.id > 0 && it.postId > 0 && it.postNumber > 0 }.distinctBy { it.topic.id }
        val savedAt = (root["saved"] as? JsonArray).orEmpty().map { it.jsonObject }.filter { it.number("id") in saved.map { t -> t.id } && it.number("savedAt") > 0 }.associate { it.number("id") to it.number("savedAt") }
        return ForumLocalState(saved, positions, savedAt)
    }
    private fun writeLocal(value: ForumLocalState) {
        storage.write(
            "local",
            buildJsonObject {
                put("saved", JsonArray(value.saved.map { JsonObject(topic(it) + ("savedAt" to JsonPrimitive(value.savedAt[it.id] ?: 0))) }))
                put(
                    "positions",
                    JsonArray(
                        value.positions.map { p ->
                            buildJsonObject {
                                put("topic", topic(p.topic))
                                put("postId", p.postId)
                                put("number", p.postNumber)
                                put("offset", p.offsetDp)
                            }
                        }
                    )
                )
            }.toString()
        )
    }
    private fun cache(): List<Cached> = runCatching {
        storage.read("cache")?.let { raw -> Json.parseToJsonElement(raw).jsonArray.take(32).map { it.jsonObject }.map { Cached(it.string("key"), it.string("body"), it.number("at")) } }.orEmpty()
    }.getOrDefault(emptyList())
    private fun writeCache(entries: List<Cached>) {
        val kept = mutableListOf<Cached>()
        var size = 0
        entries.distinctBy { it.key }.take(32).forEach { item ->
            val estimate = item.body.length + item.key.length + 100
            if (size + estimate <= ForumLimits.CACHE_CHARS) {
                kept += item
                size += estimate
            }
        }
        // The encoded JSON budget is measured as well; escaping can expand cooked HTML.
        fun encode(items: List<Cached>) = JsonArray(
            items.map {
                buildJsonObject {
                    put("key", it.key)
                    put("body", it.body)
                    put("at", it.at)
                }
            }
        ).toString()
        var encoded = encode(kept)
        while (encoded.length > ForumLimits.CACHE_CHARS && kept.isNotEmpty()) {
            kept.removeAt(kept.lastIndex)
            encoded = encode(kept)
        }
        storage.write("cache", encoded)
    }
    override suspend fun loadPublic(request: ForumRequest): ForumLoaded = load(request)
    override suspend fun load(request: ForumRequest): ForumLoaded = withContext(Dispatchers.IO) {
        // Revision permissions may change independently of cached topic metadata.
        // Never persist or replay history offline after a permission revocation.
        if (request is ForumRequest.Revision) return@withContext ForumLoaded(ForumParser.parse(request, remote.get(request)), now())
        val key = ForumUrls.key(request)
        val epoch = mutex.withLock { generation }
        try {
            val body = remote.get(request)
            val document = ForumParser.parse(request, body)
            val at = now()
            mutex.withLock { if (generation == epoch) runCatching { writeCache(listOf(Cached(key, body, at)) + cache()) } }
            ForumLoaded(document, at)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ForumException) {
            if (e.reason == ForumFailure.UNAVAILABLE) {
                mutex.withLock {
                    if (generation == epoch) {
                        runCatching {
                            val id = when (request) {
                                is ForumRequest.Topic -> request.id
                                is ForumRequest.Posts -> request.topicId
                                else -> null
                            }
                            writeCache(cache().filterNot { it.key == key || id != null && (it.key.startsWith("topic:$id:") || it.key.startsWith("posts:$id:")) })
                        }
                    }
                }
            }
            if (e.reason != ForumFailure.NETWORK) throw e
            val entries = mutex.withLock { if (generation == epoch) cache() else emptyList() }
            offlineTopic(request, entries)?.let { return@withContext it }
            val cached = entries.firstOrNull { it.key == key } ?: throw e
            ForumLoaded(ForumParser.parse(request, cached.body), cached.at, true)
        }
    }

    /** Reassemble retained bodies across response windows; never fabricate a stream. */
    private fun offlineTopic(request: ForumRequest, entries: List<Cached>): ForumLoaded? {
        val id = when (request) {
            is ForumRequest.Topic -> request.id
            is ForumRequest.Posts -> request.topicId
            else -> return null
        }
        val documents = entries.mapNotNull { cached ->
            val parts = cached.key.split(':')
            if (parts.size != 3 || parts[1].toLongOrNull() != id) return@mapNotNull null
            val original = when (parts[0]) {
                "topic" -> parts[2].toIntOrNull()?.let { ForumRequest.Topic(id, it) }
                "posts" -> parts[2].split(',').mapNotNull(String::toLongOrNull).takeIf { it.size in 1..ForumLimits.BATCH }?.let { ForumRequest.Posts(id, it) }
                else -> null
            } ?: return@mapNotNull null
            runCatching { ForumParser.parse(original, cached.body) to cached.at }.getOrNull()
        }
        val bodies = documents.flatMap { (doc, at) -> doc.posts.map { it to at } }.distinctBy { it.first.id }
        if (request is ForumRequest.Posts) {
            val kept = bodies.filter { it.first.id in request.ids }.sortedBy { request.ids.indexOf(it.first.id) }
            if (kept.isEmpty()) return null
            return ForumLoaded(ForumDocument(posts = kept.map { it.first }), kept.minOf { it.second }, true)
        }
        request as ForumRequest.Topic
        val metadata = documents.firstOrNull { it.first.topic != null } ?: return null
        val stream = metadata.first.stream
        val indexes = stream.withIndex().associate { it.value to it.index }
        val ordered = bodies.filter { it.first.id in indexes }.sortedBy { indexes.getValue(it.first.id) }
        val anchor = ordered.minByOrNull { kotlin.math.abs(it.first.number.toLong() - request.postNumber) } ?: return null
        val center = indexes.getValue(anchor.first.id)
        val first = (center - 5).coerceAtLeast(0)
        val last = (first + ForumLimits.BATCH).coerceAtMost(stream.size)
        val kept = ordered.filter { indexes.getValue(it.first.id) in first until last }
        return ForumLoaded(metadata.first.copy(posts = kept.map { it.first }), minOf(metadata.second, kept.minOf { it.second }), true)
    }
    override suspend fun local(): ForumLocalState = localOperation { readLocal() }
    override suspend fun save(topic: ForumTopic, saved: Boolean): ForumLocalState = localOperation {
        require(topic.id > 0)
        val state = readLocal()
        val others = state.saved.filterNot { it.id == topic.id }
        if (saved && others.size >= ForumLimits.SAVED) throw ForumException(ForumFailure.CAPACITY)
        state.copy(
            saved = if (saved) listOf(topic) + others else others,
            savedAt = if (saved) state.savedAt + (topic.id to (state.savedAt[topic.id] ?: now())) else state.savedAt - topic.id
        ).also(::writeLocal)
    }
    override suspend fun position(position: ForumPosition) {
        localOperation {
            require(position.topic.id > 0 && position.postId > 0 && position.postNumber > 0)
            val state = readLocal()
            writeLocal(state.copy(positions = (listOf(position.copy(offsetDp = position.offsetDp.coerceIn(0, 10000))) + state.positions.filterNot { it.topic.id == position.topic.id }).take(120)))
        }
    }
    override suspend fun imagePublic(url: String): ByteArray = image(url)
    override suspend fun image(url: String): ByteArray = remote.image(url)
    override suspend fun clear() = localOperation {
        generation++
        // Each key is independently removed. Failure remains visible and retryable.
        storage.remove("cache")
        storage.remove("local")
    }
    private suspend fun <T> localOperation(block: () -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                block()
            } catch (e: ForumException) {
                throw e
            } catch (_: Exception) {
                throw ForumException(ForumFailure.STORAGE)
            }
        }
    }
}
