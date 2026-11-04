package vn.edu.uit.mobile.data

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import vn.edu.uit.mobile.daa.*
import vn.edu.uit.mobile.domain.*

fun createAnnouncementRepository(context: Context): AnnouncementRepository = LocalAnnouncementRepository(PublicAnnouncementClient(), StudyPrivateStorage(context, "public_announcements", "uit.announcements.v1"))

/** Public/device-local preferences, intentionally independent of SSO/account snapshots. */
class LocalAnnouncementRepository(
    private val remote: AnnouncementRemote,
    private val storage: StudyStorage,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 }
) : AnnouncementRepository {
    private val mutex = Mutex()
    private val refreshMutex = Mutex()
    private var generation = 0L
    private val mutable = MutableStateFlow(AnnouncementInbox())
    override val state: StateFlow<AnnouncementInbox> = mutable.asStateFlow()
    private var loaded = false
    private var loadFailed = false
    override suspend fun initialize() = withContext(Dispatchers.IO) { load() }
    private suspend fun load() = mutex.withLock {
        if (!loaded) {
            mutable.value = try {
                storage.read("inbox")?.let(AnnouncementCodec::decode) ?: AnnouncementInbox()
            } catch (_: Exception) {
                loadFailed = true
                AnnouncementInbox(storageFailed = true)
            }
            loaded = true
        }
    }
    private fun commit(value: AnnouncementInbox, reset: Boolean = false) {
        try {
            check(!loadFailed || reset) { "Reset unreadable local storage before replacing it" }
            storage.write("inbox", AnnouncementCodec.encode(value.copy(storageFailed = false)))
            loadFailed = false
            mutable.value = value.copy(storageFailed = false)
        } catch (e: Exception) {
            mutable.value = mutable.value.copy(storageFailed = true)
            throw e
        }
    }
    private suspend fun change(transform: (AnnouncementInbox) -> AnnouncementInbox) = withContext(Dispatchers.IO) {
        load()
        mutex.withLock { commit(transform(state.value)) }
    }
    override suspend fun refresh() = withContext(Dispatchers.IO) {
        load()
        refreshMutex.withLock {
            for (source in AnnouncementSource.entries) {
                val token = mutex.withLock { generation }
                val before = state.value.sources.getValue(source)
                if (!before.enabled) continue
                try {
                    // Refresh a bounded overlap; pagination remains explicitly available for archives.
                    val first = remote.list(source, 0)
                    var overlapFailed = false
                    val second = if (first.more) {
                        try {
                            remote.list(source, 1)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            overlapFailed = true
                            null
                        }
                    } else {
                        null
                    }
                    val listed = (first.items + second?.items.orEmpty()).distinctBy { it.url }
                    // Revalidate a rotating bounded set of opened articles, including saved older notices.
                    // A correction need not change the list title or publication date.
                    val tracked = state.value.items.filter { it.source == source && it.bodyHtml.isNotEmpty() }
                        .sortedBy { it.bodySavedAt }.take(8)
                    var detailFailed = false
                    val refreshed = tracked.map { cached ->
                        val metadata = listed.firstOrNull { it.url == cached.url } ?: cached
                        try {
                            remote.article(metadata).copy(bodySavedAt = now())
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            detailFailed = true
                            metadata
                        }
                    }
                    val incoming = (refreshed + listed).distinctBy { it.url }
                    mutex.withLock {
                        if (generation != token || !state.value.sources.getValue(source).enabled) return@withLock
                        merge(source, incoming, before.checkedAt > 0, 2.takeIf { second != null } ?: 1, second?.more ?: first.more, now())
                        if (detailFailed || overlapFailed) commit(state.value.copy(sources = state.value.sources + (source to state.value.sources.getValue(source).copy(failed = true))))
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    mutex.withLock {
                        if (generation == token) {
                            val value = state.value
                            runCatching { commit(value.copy(sources = value.sources + (source to value.sources.getValue(source).copy(failed = true)))) }
                        }
                    }
                }
            }
        }
    }
    private fun merge(source: AnnouncementSource, incoming: List<OfficialAnnouncement>, alert: Boolean, nextPage: Int, more: Boolean, checkedAt: Long) {
        val old = state.value
        val previous = old.items.associateBy { it.url }
        val pending = old.pending.toMutableSet()
        val previousGroups = old.items.map { it.groupKey }.toSet()
        val threshold = java.time.Instant.ofEpochSecond(old.sources.getValue(source).checkedAt).atZone(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).toLocalDate().minusDays(1).toString()
        val merged = incoming.map { remoteItem ->
            val prior = previous[remoteItem.url]
            val item = if (prior == null) remoteItem else remoteItem.copy(bodyHtml = remoteItem.bodyHtml.ifEmpty { prior.bodyHtml }, bodySavedAt = remoteItem.bodySavedAt.takeIf { it > 0 } ?: prior.bodySavedAt, readRevision = prior.readRevision, saved = prior.saved, reminderAt = prior.reminderAt, reminderDelivered = prior.reminderDelivered, changed = prior.changed || remoteItem.title != prior.title || remoteItem.excerpt != prior.excerpt || remoteItem.bodyHtml.isNotEmpty() && prior.bodyHtml.isNotEmpty() && remoteItem.bodyHtml != prior.bodyHtml)
            if (alert &&
                old.alertsEnabled &&
                (prior == null && item.publishedDate >= threshold && item.publishedDate.isNotBlank() && item.groupKey !in previousGroups || prior != null && item.revision != prior.revision)
            ) {
                pending += item.url
            }
            item
        }
        val all = (merged + old.items.filter { it.url !in incoming.map { n -> n.url }.toSet() }).sortedWith(compareByDescending<OfficialAnnouncement> { it.saved || it.reminderAt > 0 && !it.reminderDelivered }.thenByDescending { it.publishedDate }).take(800)
        val status = old.sources.getValue(source)
        commit(old.copy(items = all, pending = pending.intersect(all.map { it.url }.toSet()), sources = old.sources + (source to status.copy(checkedAt = checkedAt, failed = false, nextPage = maxOf(status.nextPage, nextPage), more = if (status.nextPage > nextPage) status.more else more))))
    }
    override suspend fun more(source: AnnouncementSource) = withContext(Dispatchers.IO) {
        load()
        refreshMutex.withLock {
            val token = mutex.withLock { generation }
            val status = state.value.sources.getValue(source)
            require(status.enabled && status.more)
            try {
                val result = remote.list(source, status.nextPage)
                mutex.withLock { if (generation == token) merge(source, result.items, false, status.nextPage + 1, result.more, status.checkedAt) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutex.withLock { if (generation == token) commit(state.value.copy(sources = state.value.sources + (source to status.copy(failed = true)))) }
                throw e
            }
        }
    }
    override suspend fun configure(source: AnnouncementSource, enabled: Boolean) = change {
        generation++
        val status = it.sources.getValue(source)
        it.copy(sources = it.sources + (source to status.copy(enabled = enabled, checkedAt = if (enabled) 0 else status.checkedAt)), pending = it.pending.filterNot { url -> it.items.any { item -> item.url == url && item.source == source } }.toSet())
    }
    override suspend fun alerts(enabled: Boolean) = change { it.copy(alertsEnabled = enabled, pending = emptySet()) }
    override suspend fun open(url: String): AnnouncementDocument = withContext(Dispatchers.IO) {
        load()
        val token = mutex.withLock { generation }
        val old = state.value.items.firstOrNull { it.url == url } ?: error("Announcement not cached")
        try {
            val fetched = remote.article(old).copy(bodySavedAt = now())
            val item = mutex.withLock {
                check(generation == token) { "Announcement session changed" }
                val latest = state.value.items.first { it.url == url }
                val updated = fetched.copy(readRevision = latest.readRevision, saved = latest.saved, reminderAt = latest.reminderAt, reminderDelivered = latest.reminderDelivered, changed = latest.changed || latest.bodyHtml.isNotEmpty() && latest.bodyHtml != fetched.bodyHtml)
                // Bound complete article bodies separately from the metadata archive.
                var bodies = 0
                val items = (listOf(updated) + state.value.items.filterNot { it.url == url }).map {
                    if (it.bodyHtml.isEmpty() || ++bodies <= 40 || it.saved) it else it.copy(bodyHtml = "", bodySavedAt = 0, readRevision = if (it.unread) "" else it.copy(bodyHtml = "").revision)
                }
                commit(state.value.copy(items = items))
                updated
            }
            AnnouncementDocument(item, AnnouncementParser.blocks(item))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            check(generation == token)
            if (old.bodyHtml.isEmpty()) throw e
            AnnouncementDocument(old, AnnouncementParser.blocks(old), offline = true)
        }
    }
    override suspend fun markRead(url: String, revision: String?) = change {
        if (revision != null && it.items.firstOrNull { item -> item.url == url }?.revision != revision) return@change it
        it.copy(items = it.items.map { item -> if (item.url == url) item.copy(readRevision = item.revision, changed = false) else item }, pending = it.pending - url)
    }
    override suspend fun save(url: String, saved: Boolean) = change { inbox ->
        require(!saved || inbox.items.count { it.saved && it.url != url } < 100)
        inbox.copy(items = inbox.items.map { if (it.url == url) it.copy(saved = saved) else it })
    }
    override suspend fun remind(url: String, at: Long) = change { inbox ->
        require(at == 0L || at in (now() + 1)..(now() + 366L * 86400))
        require(at == 0L || inbox.items.count { it.reminderAt > 0 && !it.reminderDelivered && it.url != url } < 100)
        inbox.copy(items = inbox.items.map { if (it.url == url) it.copy(reminderAt = at, reminderDelivered = false) else it })
    }
    override suspend fun acknowledge(urls: Set<String>, reminders: Boolean) = change { inbox -> inbox.copy(pending = if (reminders) inbox.pending else inbox.pending - urls, items = inbox.items.map { if (reminders && it.url in urls) it.copy(reminderDelivered = true) else it }) }
    override suspend fun clear() = withContext(Dispatchers.IO) {
        load()
        mutex.withLock {
            generation++
            commit(AnnouncementInbox(), reset = true)
        }
    }
    override suspend fun image(url: String) = remote.image(url)
}

internal object AnnouncementCodec {
    fun encode(state: AnnouncementInbox): String = buildJsonObject {
        put("alerts", state.alertsEnabled)
        put("pending", JsonArray(state.pending.map(::JsonPrimitive)))
        put(
            "sources",
            buildJsonObject {
                state.sources.forEach { (source, status) ->
                    put(
                        source.name,
                        buildJsonObject {
                            put("enabled", status.enabled)
                            put("checked", status.checkedAt)
                            put("failed", status.failed)
                            put("next", status.nextPage)
                            put("more", status.more)
                        }
                    )
                }
            }
        )
        put(
            "items",
            JsonArray(
                state.items.map { item ->
                    buildJsonObject {
                        put("url", item.url)
                        put("source", item.source.name)
                        put("title", item.title)
                        put("date", item.publishedDate)
                        put("excerpt", item.excerpt)
                        put("labels", JsonArray(item.labels.map(::JsonPrimitive)))
                        put("body", item.bodyHtml)
                        put("bodyAt", item.bodySavedAt)
                        put("read", item.readRevision)
                        put("saved", item.saved)
                        put("reminder", item.reminderAt)
                        put("delivered", item.reminderDelivered)
                        put("changed", item.changed)
                    }
                }
            )
        )
    }.toString().also { require(it.length <= 24 * 1024 * 1024) }
    private fun JsonObject.s(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun JsonObject.n(key: String) = s(key).toLongOrNull() ?: 0
    private fun JsonObject.b(key: String) = s(key) == "true"
    fun decode(raw: String): AnnouncementInbox {
        require(raw.length <= 24 * 1024 * 1024)
        val root = Json.parseToJsonElement(raw).jsonObject
        val sources = root["sources"]?.jsonObject.orEmpty()
        val items = root["items"]?.jsonArray.orEmpty().take(800).map { it.jsonObject }.mapNotNull { v ->
            val source = AnnouncementSource.entries.find { it.name == v.s("source") } ?: return@mapNotNull null
            val url = AnnouncementUrls.article(v.s("url"), source) ?: return@mapNotNull null
            OfficialAnnouncement(url, source, v.s("title").take(1000), v.s("date"), v.s("excerpt").take(2000), v["labels"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content }.take(15), v.s("body").take(128000), v.n("bodyAt"), v.s("read"), v.b("saved"), v.n("reminder"), v.b("delivered"), v.b("changed"))
        }
        return AnnouncementInbox(items, AnnouncementSource.entries.associateWith { source -> sources[source.name]?.jsonObject?.let { AnnouncementSourceState(it.b("enabled"), it.n("checked"), it.b("failed"), it.n("next").toInt().coerceIn(0, 100), it.b("more")) } ?: AnnouncementSourceState(enabled = source == AnnouncementSource.PORTAL) }, root.b("alerts"), root["pending"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content }.toSet().intersect(items.map { it.url }.toSet()))
    }
}
