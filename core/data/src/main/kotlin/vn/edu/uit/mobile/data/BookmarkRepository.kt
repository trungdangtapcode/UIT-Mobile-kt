package vn.edu.uit.mobile.data

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.serialization.json.*
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.model.PortalType

/** Each account has a separately addressable encrypted bucket in the bookmark preference namespace. */
@OptIn(ExperimentalCoroutinesApi::class)
class LocalBookmarkRepository(private val storage: StudyStorage, private val accounts: AccountRepository) : BookmarkRepository {
    private val mutex = Mutex()
    private val revision = MutableStateFlow(0L)
    override val collection: Flow<BookmarkCollection> = combine(accounts.accounts, revision) { owners, _ -> owners.map { it.id }.toSet() }.mapLatest { owners ->
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val failed = mutableSetOf<String>()
                val items = owners.flatMap { id ->
                    try {
                        read(id)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        failed += id
                        emptyList()
                    }
                }
                BookmarkCollection(items.sortedByDescending { it.savedAt }, failed)
            }
        }
    }
    private fun key(accountId: String) = bookmarkDigest(accountId) + ":items"
    private fun read(accountId: String): List<Bookmark> {
        val raw = storage.read(key(accountId)) ?: return emptyList()
        require(raw.length <= 1_000_000)
        val document = Json.parseToJsonElement(raw).jsonObject
        require(document.getValue("version").jsonPrimitive.int == 1)
        val values = document.getValue("items").jsonArray
        require(values.size <= 200)
        return values.map { element ->
            val item = element.jsonObject
            fun text(name: String) = item.getValue(name).jsonPrimitive.content
            Bookmark(accountId, BookmarkKind.valueOf(text("kind")), text("target"), text("title"), text("context"), text("courseId").toLong(), text("url"), text("savedAt").toLong()).also(::validateBookmark)
        }.also { require(it.map(Bookmark::id).distinct().size == it.size) }
    }
    private fun write(accountId: String, items: List<Bookmark>) {
        if (items.isEmpty()) {
            storage.remove(key(accountId))
            return
        }
        storage.write(
            key(accountId),
            buildJsonObject {
                put("version", 1)
                put(
                    "items",
                    JsonArray(
                        items.map { item ->
                            buildJsonObject {
                                put("kind", item.kind.name)
                                put("target", item.target)
                                put("title", item.title)
                                put("context", item.context)
                                put("courseId", item.courseId)
                                put("url", item.url)
                                put("savedAt", item.savedAt)
                            }
                        }
                    )
                )
            }.toString()
        )
    }
    private suspend fun owned(accountId: String) = accounts.accounts.first().any { it.id == accountId }
    override suspend fun save(bookmark: Bookmark): Unit = withContext(Dispatchers.IO) {
        validateBookmark(bookmark)
        mutex.withLock {
            val owner = accounts.accounts.first().firstOrNull { it.id == bookmark.accountId } ?: error("Bookmark account is disconnected")
            when (bookmark.kind) {
                BookmarkKind.COURSE, BookmarkKind.MATERIAL, BookmarkKind.ASSIGNMENT, BookmarkKind.DISCUSSION -> require(owner.portal == PortalType.MOODLE)
                BookmarkKind.NOTICE -> require(owner.portal == PortalType.DAA)
                BookmarkKind.LINK -> Unit
            }
            if (bookmark.kind == BookmarkKind.DISCUSSION) require(bookmark.url == officialBookmarkUrl(owner.baseUrl.trimEnd('/') + "/mod/forum/discuss.php?d=${bookmark.target.substringAfterLast(':')}"))
            val old = read(bookmark.accountId)
            val previous = old.firstOrNull { it.id == bookmark.id }
            require(previous != null || old.size < 200) { "Bookmark limit reached" }
            try {
                write(bookmark.accountId, old.filterNot { it.id == bookmark.id } + bookmark.copy(savedAt = previous?.savedAt ?: Clock.System.now().epochSeconds))
            } finally {
                withContext(NonCancellable) {
                    if (!owned(bookmark.accountId)) storage.remove(key(bookmark.accountId))
                    revision.value++
                }
            }
            check(owned(bookmark.accountId)) { "Bookmark account is disconnected" }
        }
    }
    override suspend fun remove(accountId: String, id: String): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            check(owned(accountId)) { "Bookmark account is disconnected" }
            try {
                write(accountId, read(accountId).filterNot { it.id == id })
            } finally {
                withContext(NonCancellable) {
                    if (!owned(accountId)) storage.remove(key(accountId))
                    revision.value++
                }
            }
        }
    }
}
