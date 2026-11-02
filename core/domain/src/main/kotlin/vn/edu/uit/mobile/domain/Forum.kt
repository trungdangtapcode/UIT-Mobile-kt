package vn.edu.uit.mobile.domain

import java.net.URI

/** Public, anonymous Discourse reading. No account or mutation contract exists here. */
object ForumLimits {
    const val ORIGIN = "https://forum.uit.edu.vn"
    const val BATCH = 20
    const val WINDOW = 80
    const val STREAM = 20000
    const val SAVED = 100
    const val CACHE_CHARS = 1000000
}

data class ForumCategory(val id: Long, val name: String, val parentId: Long? = null, val description: String = "")
data class ForumTopic(val id: Long, val title: String, val categoryId: Long, val posts: Int, val lastPosted: String, val pinned: Boolean = false, val closed: Boolean = false, val tags: List<String> = emptyList()) {
    val url: String get() = "${ForumLimits.ORIGIN}/t/$id"
}
data class ForumHit(val id: Long, val topic: ForumTopic, val postNumber: Int, val excerpt: String)
enum class ForumBlockKind { TEXT, HEADING, QUOTE, CODE, TABLE, DETAILS, IMAGE, LINK, UNSUPPORTED, OMITTED }
data class ForumLink(val label: String, val url: String)
data class ForumBlock(val kind: ForumBlockKind, val text: String, val links: List<ForumLink> = emptyList(), val rows: List<List<String>> = emptyList())
data class ForumPost(val id: Long, val topicId: Long, val number: Int, val author: String, val created: String, val blocks: List<ForumBlock>)
data class ForumDocument(
    val categories: List<ForumCategory> = emptyList(),
    val topics: List<ForumTopic> = emptyList(),
    val hits: List<ForumHit> = emptyList(),
    val topic: ForumTopic? = null,
    val stream: List<Long> = emptyList(),
    val posts: List<ForumPost> = emptyList(),
    val more: Boolean = false
)
sealed interface ForumRequest {
    data object Categories : ForumRequest
    data class Category(val id: Long) : ForumRequest
    data class Latest(val categoryId: Long? = null, val page: Int = 0) : ForumRequest
    data class Search(val query: String, val page: Int = 1) : ForumRequest
    data class Topic(val id: Long, val postNumber: Int = 1) : ForumRequest
    data class Posts(val topicId: Long, val ids: List<Long>) : ForumRequest
}
enum class ForumFailure { NETWORK, RATE_LIMIT, UNAVAILABLE, INVALID, STORAGE, CAPACITY }
class ForumException(val reason: ForumFailure, val retryAfterSeconds: Long? = null) : Exception(reason.name)
data class ForumLoaded(val document: ForumDocument, val fetchedAt: Long, val offline: Boolean = false)
data class ForumPosition(val topic: ForumTopic, val postId: Long, val postNumber: Int, val offsetDp: Int = 0)
data class ForumLocalState(val saved: List<ForumTopic> = emptyList(), val positions: List<ForumPosition> = emptyList(), val savedAt: Map<Long, Long> = emptyMap())
data class ForumTarget(val topicId: Long, val postNumber: Int = 1)
interface ForumRepository {
    suspend fun load(request: ForumRequest): ForumLoaded
    suspend fun local(): ForumLocalState
    suspend fun save(topic: ForumTopic, saved: Boolean): ForumLocalState
    suspend fun position(position: ForumPosition)
    suspend fun image(url: String): ByteArray
    suspend fun clear()
}

object ForumUrls {
    fun safe(raw: String): String? = runCatching {
        require(raw.length <= 2048 && raw.none { it.isISOControl() || it == '\\' })
        val uri = URI(ForumLimits.ORIGIN + "/").resolve(raw.trim()).normalize()
        require(uri.scheme == "https" && uri.host != null && uri.rawUserInfo == null && uri.port in listOf(-1, 443))
        uri.toASCIIString()
    }.getOrNull()
    fun image(raw: String): String? = safe(raw)?.takeIf {
        val uri = URI(it)
        uri.host == "forum.uit.edu.vn" && uri.path.startsWith("/uploads/") && uri.rawQuery == null && uri.path.split('/').none { part -> part == "." || part == ".." || part.any(Char::isISOControl) } && !uri.path.contains('\\')
    }
    fun target(raw: String): ForumTarget? = safe(raw)?.let {
        val uri = URI(it)
        if (uri.host != "forum.uit.edu.vn" || uri.rawQuery != null) return@let null
        val parts = uri.path.trim('/').split('/')
        if (parts.firstOrNull() != "t") return@let null
        val rest = parts.drop(1)
        val numbers = if (rest.firstOrNull()?.toLongOrNull() != null) rest else rest.drop(1)
        if (numbers.size !in 1..2) return@let null
        val id = numbers[0].toLongOrNull()?.takeIf { v -> v > 0 } ?: return@let null
        val post = numbers.getOrNull(1)?.toIntOrNull() ?: 1
        if (post <= 0) null else ForumTarget(id, post)
    }
    fun key(request: ForumRequest): String = when (request) {
        ForumRequest.Categories -> "categories"
        is ForumRequest.Category -> "category:${request.id}"
        is ForumRequest.Latest -> "latest:${request.categoryId}:${request.page}"
        is ForumRequest.Search -> "search:${request.page}:${request.query}"
        is ForumRequest.Topic -> "topic:${request.id}:${request.postNumber}"
        is ForumRequest.Posts -> "posts:${request.topicId}:${request.ids.joinToString(",")}"
    }
}
