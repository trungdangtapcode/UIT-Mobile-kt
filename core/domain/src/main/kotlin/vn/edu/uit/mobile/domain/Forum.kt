package vn.edu.uit.mobile.domain

import java.net.URI

/** Shared read contracts; authenticated commands live in ForumAccount.kt. */
object ForumLimits {
    const val ORIGIN = "https://forum.uit.edu.vn"
    const val BATCH = 20
    const val WINDOW = 80
    const val STREAM = 20000
    const val SAVED = 100
    const val CACHE_CHARS = 1000000
}

enum class ForumFilter(val route: String, val memberOnly: Boolean = false) { LATEST("latest"), TOP("top"), HOT("hot"), VOTES("votes"), NEW("new", true), UNREAD("unread", true), UNSEEN("unseen", true), READ("read", true), POSTED("posted", true), BOOKMARKS("bookmarks", true) }
enum class ForumPeriod(val route: String) { DAILY("daily"), WEEKLY("weekly"), MONTHLY("monthly"), QUARTERLY("quarterly"), YEARLY("yearly"), ALL("all") }
data class ForumTag(val id: Long, val name: String, val count: Int, val description: String = "")
data class ForumCategory(val id: Long, val name: String, val parentId: Long? = null, val description: String = "", val canCreate: Boolean = false)
data class ForumTopic(val id: Long, val title: String, val categoryId: Long, val posts: Int, val lastPosted: String, val pinned: Boolean = false, val closed: Boolean = false, val tags: List<String> = emptyList()) {
    val url: String get() = "${ForumLimits.ORIGIN}/t/$id"
}
data class ForumHit(val id: Long, val topic: ForumTopic, val postNumber: Int, val excerpt: String)
enum class ForumBlockKind { TEXT, HEADING, QUOTE, CODE, TABLE, DETAILS, SPOILER, IMAGE, LINK, POLL, UNSUPPORTED, OMITTED }
data class ForumLink(val label: String, val url: String)
data class ForumInline(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    val strike: Boolean = false,
    val underline: Boolean = false,
    val link: String? = null,
    val spoiler: Boolean = false,
    val change: Int = 0
)
data class ForumBlock(
    val kind: ForumBlockKind,
    val text: String,
    val links: List<ForumLink> = emptyList(),
    val rows: List<List<String>> = emptyList(),
    val inline: List<ForumInline> = emptyList(),
    val children: List<ForumBlock> = emptyList(),
    val cells: List<List<List<ForumBlock>>> = emptyList(),
    val label: String = "",
    val original: String? = null,
    val level: Int = 0,
    val poll: ForumPoll? = null,
    val imageWidth: Int = 0,
    val imageHeight: Int = 0
)
data class ForumPost(val id: Long, val topicId: Long, val number: Int, val author: String, val created: String, val blocks: List<ForumBlock>, val version: Int = 1, val updated: String = "", val canViewHistory: Boolean = false, val replyTo: Int? = null, val canEdit: Boolean = false, val canLike: Boolean = false, val liked: Boolean = false, val likeCount: Int = 0, val reactions: List<ForumReaction> = emptyList(), val polls: List<ForumPoll> = emptyList())
data class ForumRevision(
    val postId: Long,
    val version: Int,
    val previous: Int?,
    val next: Int?,
    val author: String,
    val created: String,
    val reason: String,
    val blocks: List<ForumBlock>,
    val title: List<ForumBlock> = emptyList(),
    val diffUnavailable: Boolean = false,
    val displayVersion: Int = version
)
data class ForumDocument(
    val categories: List<ForumCategory> = emptyList(),
    val topics: List<ForumTopic> = emptyList(),
    val hits: List<ForumHit> = emptyList(),
    val topic: ForumTopic? = null,
    val stream: List<Long> = emptyList(),
    val posts: List<ForumPost> = emptyList(),
    val more: Boolean = false,
    val revision: ForumRevision? = null,
    val filters: List<ForumFilter> = listOf(ForumFilter.LATEST),
    val tags: List<ForumTag> = emptyList(),
    val tagsTruncated: Boolean = false,
    val canReply: Boolean = false,
    val lastReadPostNumber: Int? = null,
    val reactionChoices: List<String> = emptyList()
)
sealed interface ForumRequest {
    data object Categories : ForumRequest
    data object Tags : ForumRequest
    data class Feed(val filter: ForumFilter, val page: Int = 0, val period: ForumPeriod = ForumPeriod.WEEKLY, val tagId: Long? = null) : ForumRequest
    data class Category(val id: Long) : ForumRequest
    data class Latest(val categoryId: Long? = null, val page: Int = 0) : ForumRequest
    data class Search(val query: String, val page: Int = 1) : ForumRequest
    data class Topic(val id: Long, val postNumber: Int = 1) : ForumRequest
    data class Revision(val topicId: Long, val postId: Long, val version: Int) : ForumRequest
    data class Posts(val topicId: Long, val ids: List<Long>) : ForumRequest
}
enum class ForumFailure { NETWORK, RATE_LIMIT, UNAVAILABLE, INVALID, STORAGE, CAPACITY, AUTHENTICATION, SECOND_FACTOR, FORBIDDEN, REJECTED, CONFLICT, UNCERTAIN }
enum class ForumNetworkIssue { DNS, TLS, TIMEOUT, CONNECT, IO, SERVER, UNKNOWN }
class ForumException(val reason: ForumFailure, val retryAfterSeconds: Long? = null, val detail: String = "", val networkIssue: ForumNetworkIssue? = null) : Exception(reason.name)
data class ForumLoaded(val document: ForumDocument, val fetchedAt: Long, val offline: Boolean = false)
data class ForumPosition(val topic: ForumTopic, val postId: Long, val postNumber: Int, val offsetDp: Int = 0)
data class ForumLocalState(val saved: List<ForumTopic> = emptyList(), val positions: List<ForumPosition> = emptyList(), val savedAt: Map<Long, Long> = emptyMap())
data class ForumTarget(val topicId: Long, val postNumber: Int = 1)
interface ForumRepository {
    suspend fun load(request: ForumRequest): ForumLoaded

    /** Explicit public citation navigation must not inherit an authenticated transport. */
    suspend fun loadPublic(request: ForumRequest): ForumLoaded = throw ForumException(ForumFailure.FORBIDDEN)
    suspend fun local(): ForumLocalState
    suspend fun save(topic: ForumTopic, saved: Boolean): ForumLocalState
    suspend fun position(position: ForumPosition)
    suspend fun image(url: String): ByteArray
    suspend fun imagePublic(url: String): ByteArray = throw ForumException(ForumFailure.FORBIDDEN)
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
    fun chatTarget(raw: String): ForumBookmarkTarget.Chat? = safe(raw)?.let {
        val uri = URI(it)
        if (uri.host != "forum.uit.edu.vn" || uri.rawQuery != null || uri.rawFragment != null) return@let null
        val match = Regex("/chat/c/[^/]+/([0-9]+)(?:/t/([0-9]+))?/([0-9]+)/?").matchEntire(uri.path) ?: return@let null
        val channel = match.groupValues[1].toLongOrNull()?.takeIf { n -> n > 0 } ?: return@let null
        val message = match.groupValues[3].toLongOrNull()?.takeIf { n -> n > 0 } ?: return@let null
        val thread = match.groupValues[2].takeIf { v -> v.isNotEmpty() }?.toLongOrNull()?.takeIf { n -> n > 0 }
        if (match.groupValues[2].isNotEmpty() && thread == null) return@let null
        ForumBookmarkTarget.Chat(channel, message, thread)
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
        ForumRequest.Tags -> "tags"
        is ForumRequest.Feed -> "feed:${request.filter.route}:${request.period.route}:${request.tagId}:${request.page}"
        is ForumRequest.Category -> "category:${request.id}"
        is ForumRequest.Latest -> "latest:${request.categoryId}:${request.page}"
        is ForumRequest.Search -> "search:${request.page}:${request.query}"
        is ForumRequest.Topic -> "topic:${request.id}:${request.postNumber}"
        is ForumRequest.Revision -> "revision:${request.topicId}:${request.postId}:${request.version}"
        is ForumRequest.Posts -> "posts:${request.topicId}:${request.ids.joinToString(",")}"
    }
}
