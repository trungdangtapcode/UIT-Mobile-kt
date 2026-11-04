package vn.edu.uit.mobile.data

import com.fleeksoft.ksoup.Ksoup
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import vn.edu.uit.mobile.domain.*

/** Read-only facade: it never exposes the wider repositories' mutation methods to the model. */
internal class AssistantEvidenceTools(private val repository: AnnouncementRepository, private val scope: AssistantScope, private val otherChars: () -> Int = { 0 }) {
    private val discovered = linkedMapOf<String, String>()
    val evidence = linkedMapOf<String, AssistantEvidence>()
    private var totalChars = 0
    private fun allowed(item: OfficialAnnouncement) = item.source in scope.sources && (scope.urls.isEmpty() || item.url in scope.urls) && repository.state.value.sources.getValue(item.source).enabled
    fun coverage(): String = buildJsonObject {
        put("asOfVietnam", java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).toString())
        put("scope", "Public announcements in the downloaded cache only; private student records and attachments are not included.")
        put("cachedCount", repository.state.value.items.count(::allowed))
        put("selectedUrls", scope.urls.size)
        put(
            "sources",
            JsonArray(
                scope.sources.sortedBy { it.name }.map { source ->
                    val state = repository.state.value.sources.getValue(source)
                    buildJsonObject {
                        put("source", source.name)
                        put("enabled", state.enabled)
                        put("checkedAt", state.checkedAt)
                        put("failed", state.failed)
                        put("archiveMayHaveMore", state.more)
                    }
                }
            )
        )
    }.toString()
    suspend fun call(name: String, args: JsonObject): Pair<String, Int> {
        currentCoroutineContext().ensureActive()
        return when (name) {
            "search_announcements" -> {
                require(args.keys == setOf("query"))
                val query = args.getValue("query").jsonPrimitive.content.also { require(it.length <= 200) }
                val tokens = announcementText(query).split(' ').filter { it.isNotBlank() }.distinct()
                val items = repository.state.value.items.filter(::allowed).map { item ->
                    val text = announcementText(item.title + " " + item.excerpt)
                    item to tokens.count { it in text }
                }.filter { tokens.isEmpty() || it.second > 0 }.sortedWith(compareByDescending<Pair<OfficialAnnouncement, Int>> { it.second }.thenByDescending { it.first.publishedDate }).take(10).map { it.first }
                val result = buildJsonObject {
                    put("coverage", Json.parseToJsonElement(coverage()))
                    put(
                        "hits",
                        JsonArray(
                            items.map { item ->
                                val id = "notice_" + announcementHash(item.url).take(24)
                                discovered[id] = item.url
                                buildJsonObject {
                                    put("source_id", id)
                                    put("title", item.title)
                                    put("source", requireNotNull(item.source).name)
                                    put("date", item.publishedDate)
                                    put("excerpt", item.excerpt.take(350))
                                }
                            }
                        )
                    )
                }.toString()
                result to items.size
            }
            "read_announcement" -> {
                require(args.keys == setOf("source_id"))
                val id = args.getValue("source_id").jsonPrimitive.content
                val url = requireNotNull(discovered[id]) { "Search the selected sources first" }
                require(repository.state.value.items.any { it.url == url && allowed(it) }) { "Source no longer allowed" }
                val existing = evidence[id]
                if (existing != null) return encode(existing) to existing.paragraphs.size
                require(evidence.size < AssistantRules.ANNOUNCEMENTS)
                val loaded = repository.open(url)
                require(allowed(loaded.item)) { "Source scope changed" }
                val doc = Ksoup.parseBodyFragment(loaded.item.bodyHtml)
                // Preserve visible lists/tables as ordered text; no images or links are fetched.
                doc.select("script,style,form").remove()
                val mediaMissing = doc.select("img,iframe,object,embed,a[href$=.pdf]").isNotEmpty()
                doc.select("br").forEach { it.after("\n") }
                doc.select("p,li,tr,h1,h2,h3,h4,blockquote,pre").forEach { it.after("\n") }
                val text = doc.wholeText().lines().map(AssistantRules::normalize).filter { it.isNotBlank() }
                var remaining = 32000 - totalChars - otherChars()
                require(remaining >= 8) { "Evidence budget reached" }
                val paragraphs = buildList {
                    for (passage in text.flatMap { it.chunked(AssistantRules.MAX_PASSAGE) }.take(AssistantRules.MAX_PARAGRAPHS)) {
                        if (remaining <= 0) break
                        val excerpt = passage.take(remaining)
                        add(excerpt)
                        remaining -= excerpt.length
                    }
                }
                require(paragraphs.isNotEmpty()) { "No supported text extracted" }
                val chars = paragraphs.sumOf { it.length }
                totalChars += chars
                val value = AssistantEvidence(id, url, loaded.item.source, loaded.item.title, loaded.item.publishedDate, loaded.item.bodySavedAt, assistantSourceVersion(loaded.item), paragraphs, loaded.offline, mediaMissing || text.sumOf { it.length } > chars)
                evidence[id] = value
                encode(value) to paragraphs.size
            }
            else -> error("Tool unavailable")
        }
    }
    private fun encode(item: AssistantEvidence) = buildJsonObject {
        put("id", item.id)
        put("title", item.title)
        put("source", requireNotNull(item.source).name)
        put("url", item.url)
        put("publicationDate", item.publishedDate)
        put("fetchedAt", item.fetchedAt)
        put("version", item.version)
        put("offline", item.offline)
        put("partialTextOrUnprocessedMedia", item.abbreviated)
        put(
            "paragraphs",
            JsonArray(
                item.paragraphs.mapIndexed { index, text ->
                    buildJsonObject {
                        put("paragraph", index)
                        put("text", text)
                    }
                }
            )
        )
    }.toString()
}
