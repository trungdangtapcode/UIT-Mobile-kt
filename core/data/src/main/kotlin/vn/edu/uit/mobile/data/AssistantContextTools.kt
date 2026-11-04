package vn.edu.uit.mobile.data

import kotlinx.serialization.json.*
import vn.edu.uit.mobile.domain.*

/** Source adapters share one read-only scope and one extracted-text budget. */
internal class AssistantContextTools(private val repository: AnnouncementRepository, private val scope: AssistantScope, private val forumSource: AssistantForumSource) {
    private var forumEvidence = emptyList<AssistantEvidence>()
    private val announcements = AssistantEvidenceTools(repository, scope) { forumEvidence.sumOf { e -> e.paragraphs.sumOf(String::length) } }
    var forumCoverage: AssistantForumCoverage? = null
        private set
    val evidence: List<AssistantEvidence> get() = announcements.evidence.values.toList() + forumEvidence
    fun coverage(): String = buildJsonObject {
        put("announcements", Json.parseToJsonElement(announcements.coverage()))
        put("forumScope", scope.forum?.let(AssistantForumCodec::scope) ?: JsonNull)
        put("forumCoverage", forumCoverage?.let(AssistantForumCodec::coverage) ?: JsonNull)
        put("forumBoundary", "Only the explicitly selected anonymous regular-topic post window. No private topics, messages, account credentials, timing or mark-read capability. A partial window is not the whole topic.")
    }.toString()
    suspend fun call(name: String, args: JsonObject): Pair<String, Int> {
        if (name != "read_forum_topic") return announcements.call(name, args)
        require(args.isEmpty())
        val selected = requireNotNull(scope.forum) { "No Forum topic selected" }
        if (forumCoverage == null) {
            val read = forumSource.read(selected)
            var remaining = 32000 - announcements.evidence.values.sumOf { e -> e.paragraphs.sumOf(String::length) }
            require(remaining >= 8) { "Evidence budget reached" }
            forumEvidence = read.evidence.mapIndexedNotNull { index, e ->
                var left = remaining / (read.evidence.size - index)
                val clipped = buildList {
                    for (p in e.paragraphs) {
                        if (left <= 0) break
                        add(p.take(left))
                        left -= last().length
                    }
                }
                remaining -= clipped.sumOf(String::length)
                if (clipped.isEmpty()) null else e.copy(paragraphs = clipped, abbreviated = e.abbreviated || clipped != e.paragraphs)
            }
            forumCoverage = read.coverage.copy(abbreviated = read.coverage.abbreviated || forumEvidence.any { it.abbreviated } || forumEvidence.size != read.evidence.size)
        }
        val output = buildJsonObject {
            put("selectionMeaning", "Starting at the requested lower-bound post number, take the next requested COUNT of listed post IDs. The count is not an ending post number. Numeric gaps imply nothing about unread posts.")
            put("loadedPostNumbers", JsonArray(forumEvidence.map { JsonPrimitive(requireNotNull(it.forum).number) }))
            put("coverage", AssistantForumCodec.coverage(requireNotNull(forumCoverage)))
            put("posts", JsonArray(forumEvidence.map(AssistantForumCodec::evidence)))
        }
        return output.toString() to forumEvidence.size
    }
    fun sourcesEnabled() = evidence.all { it.source?.let { source -> repository.state.value.sources.getValue(source).enabled } != false }
}
