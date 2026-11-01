package vn.edu.uit.mobile.domain

import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import kotlinx.coroutines.flow.Flow
import vn.edu.uit.mobile.model.*

enum class BookmarkKind { COURSE, MATERIAL, ASSIGNMENT, NOTICE, DISCUSSION, LINK }
data class Bookmark(val accountId: String, val kind: BookmarkKind, val target: String, val title: String, val context: String, val courseId: Long = 0, val url: String = "", val savedAt: Long = 0) {
    val id: String get() = bookmarkId(accountId, kind, target)
}
data class BookmarkCollection(val items: List<Bookmark> = emptyList(), val failedAccounts: Set<String> = emptySet())
interface BookmarkRepository {
    val collection: Flow<BookmarkCollection>
    suspend fun save(bookmark: Bookmark)
    suspend fun remove(accountId: String, id: String)
}

fun bookmarkDigest(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
fun bookmarkId(accountId: String, kind: BookmarkKind, target: String) = bookmarkDigest("$accountId\u0000${kind.name}\u0000$target")
fun bookmarkFor(hit: WorkspaceSearchHit): Bookmark? {
    val course = hit.course
    val kind = when (hit.kind) {
        WorkspaceSearchKind.COURSES -> BookmarkKind.COURSE
        WorkspaceSearchKind.MATERIALS -> BookmarkKind.MATERIAL
        WorkspaceSearchKind.ASSIGNMENTS -> BookmarkKind.ASSIGNMENT
        WorkspaceSearchKind.NOTICES -> BookmarkKind.NOTICE
        else -> return null
    }
    val target = when (kind) {
        BookmarkKind.COURSE -> "course:${course?.id ?: return null}"
        BookmarkKind.MATERIAL -> if (hit.file != null) "file:${course?.id ?: return null}:${bookmarkDigest(hit.file.url)}" else "module:${course?.id ?: return null}:${hit.module?.id ?: return null}"
        BookmarkKind.ASSIGNMENT -> "assignment:${course?.id ?: return null}:${hit.assignment?.id ?: return null}"
        else -> hit.key
    }
    return Bookmark(hit.accountId, kind, target, hit.title, hit.context, course?.id ?: 0)
}

/** Only explicit public UIT HTTPS links are retained; service credentials never become bookmark data. */
fun officialBookmarkUrl(value: String): String {
    val uri = URI(value.trim())
    val host = uri.host?.lowercase().orEmpty()
    require(uri.scheme.equals("https", true) && uri.userInfo == null && uri.port in setOf(-1, 443))
    require(host == "uit.edu.vn" || host.endsWith(".uit.edu.vn"))
    require(host != "sso.uit.edu.vn" && !Regex("/(?:auth|login|logout)(?:/|\\.|$)", RegexOption.IGNORE_CASE).containsMatchIn(uri.path.orEmpty()))
    val forbidden = Regex("token|sess|password|secret|authorization|saml|ticket|^code$|^state$|^key$|^access_?key$|^api_?key$", RegexOption.IGNORE_CASE)
    (uri.rawQuery.orEmpty() + "&" + uri.rawFragment.orEmpty()).split('&', '?').filter(String::isNotBlank).forEach { pair ->
        require(!forbidden.containsMatchIn(URLDecoder.decode(pair.substringBefore('='), "UTF-8")))
    }
    require(value.length <= 2048 && value.none { it.isISOControl() })
    require(!uri.path.orEmpty().contains("tokenpluginfile.php", true))
    return URI("https://$host" + uri.rawPath.ifBlank { "/" } + uri.rawQuery?.let { "?$it" }.orEmpty() + uri.rawFragment?.let { "#$it" }.orEmpty()).normalize().toASCIIString()
}

fun validateBookmark(bookmark: Bookmark) {
    require(bookmark.accountId.length in 1..256 && bookmark.target.length in 1..1024)
    require(bookmark.accountId.none { it.isISOControl() } && bookmark.target.none { it.isISOControl() })
    require(bookmark.title.isNotBlank() && bookmark.title.length <= 240 && bookmark.context.length <= 500 && bookmark.savedAt >= 0)
    if (bookmark.kind in setOf(BookmarkKind.LINK, BookmarkKind.DISCUSSION)) {
        require(officialBookmarkUrl(bookmark.url) == bookmark.url)
        if (bookmark.kind == BookmarkKind.LINK) {
            require(bookmark.target == "link:${bookmarkDigest(bookmark.url)}")
        } else {
            require(bookmark.courseId > 0 && bookmark.target.matches(Regex("discussion:${bookmark.courseId}:[1-9][0-9]*")))
            val uri = URI(bookmark.url)
            require(uri.path.endsWith("/mod/forum/discuss.php") && uri.rawQuery == "d=${bookmark.target.substringAfterLast(':')}")
        }
    } else {
        require(bookmark.url.isEmpty())
        when (bookmark.kind) {
            BookmarkKind.COURSE -> require(bookmark.courseId > 0 && bookmark.target == "course:${bookmark.courseId}")
            BookmarkKind.ASSIGNMENT -> require(bookmark.courseId > 0 && bookmark.target.matches(Regex("assignment:${bookmark.courseId}:[1-9][0-9]*")))
            BookmarkKind.MATERIAL -> require(bookmark.courseId > 0 && bookmark.target.matches(Regex("(?:file:${bookmark.courseId}:[a-f0-9]{64}|module:${bookmark.courseId}:[1-9][0-9]*)")))
            BookmarkKind.NOTICE -> require(bookmark.target.startsWith("announcement:${bookmark.accountId}:") || bookmark.target.startsWith("notice:${bookmark.accountId}:"))
            else -> error("Unsupported bookmark")
        }
    }
}
