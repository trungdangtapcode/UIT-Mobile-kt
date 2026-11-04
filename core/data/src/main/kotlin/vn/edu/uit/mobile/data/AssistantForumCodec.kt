package vn.edu.uit.mobile.data

import kotlinx.serialization.json.*
import vn.edu.uit.mobile.domain.*

/** Public source metadata shared by persistence and read-only tool output, never provider authority. */
internal object AssistantForumCodec {
    fun scope(s: AssistantForumScope) = buildJsonObject {
        put("topicId", s.topicId)
        put("startNumber", s.startNumber)
        put("limit", s.limit)
    }
    fun scope(o: JsonObject) = AssistantForumScope(o.long("topicId"), o.int("startNumber"), o.int("limit")).also(AssistantRules::forum)
    fun post(p: AssistantForumPost) = buildJsonObject {
        put("topicId", p.topicId)
        put("postId", p.postId)
        put("number", p.number)
        put("username", p.username)
        put("created", p.created)
        put("updated", p.updated)
        put("revision", p.revision)
        put("replyTo", p.replyTo?.let(::JsonPrimitive) ?: JsonNull)
        put("staff", p.staff?.let(::JsonPrimitive) ?: JsonNull)
        put("accepted", p.accepted?.let(::JsonPrimitive) ?: JsonNull)
    }
    fun post(o: JsonObject) = AssistantForumPost(o.long("topicId"), o.long("postId"), o.int("number"), o.text("username"), o.text("created"), o.text("updated"), o.int("revision"), o["replyTo"]?.jsonPrimitive?.intOrNull, o["staff"]?.jsonPrimitive?.booleanOrNull, o["accepted"]?.jsonPrimitive?.booleanOrNull).also {
        require(it.topicId > 0 && it.postId > 0 && it.number > 0 && it.revision > 0 && it.username.length in 1..100 && it.created.length <= 50 && it.updated.length <= 50 && (it.replyTo?.let { n -> n > 0 } != false))
    }
    fun coverage(c: AssistantForumCoverage) = buildJsonObject {
        put("topicId", c.topicId)
        put("title", c.title)
        put("checkedAt", c.checkedAt)
        put("streamCount", c.streamCount)
        put("selectedIds", ids(c.selectedIds))
        put("loadedIds", ids(c.loadedIds))
        put("unavailableIds", ids(c.unavailableIds))
        put("firstNumber", c.firstNumber)
        put("lastNumber", c.lastNumber)
        put("moreBefore", c.moreBefore)
        put("moreAfter", c.moreAfter)
        put("latestAt", c.latestAt)
        put("streamVersion", c.streamVersion)
        put("sourceVersion", c.sourceVersion)
        put("abbreviated", c.abbreviated)
    }
    fun coverage(o: JsonObject) = AssistantForumCoverage(o.long("topicId"), o.text("title"), o.long("checkedAt"), o.int("streamCount"), o.ids("selectedIds"), o.ids("loadedIds"), o.ids("unavailableIds"), o.int("firstNumber"), o.int("lastNumber"), o.bool("moreBefore"), o.bool("moreAfter"), o.text("latestAt"), o.text("streamVersion"), o.text("sourceVersion"), o.bool("abbreviated")).also { c ->
        require(c.topicId > 0 && c.title.length <= 1000 && c.streamCount in 1..ForumLimits.STREAM && c.firstNumber > 0 && c.lastNumber >= c.firstNumber && c.selectedIds.size in 1..AssistantRules.FORUM_POSTS)
        require(c.selectedIds.distinct().size == c.selectedIds.size && c.selectedIds.all { it > 0 } && c.loadedIds.distinct().size == c.loadedIds.size && c.loadedIds.all { it in c.selectedIds } && c.unavailableIds == c.selectedIds.filterNot { it in c.loadedIds })
        require(listOf(c.streamVersion, c.sourceVersion).all { it.matches(Regex("[a-f0-9]{64}")) })
    }
    fun evidence(e: AssistantEvidence) = buildJsonObject {
        put("evidenceId", e.id)
        put("url", e.url)
        put("title", e.title)
        put("version", e.version)
        put("fetchedAt", e.fetchedAt)
        val metadata = requireNotNull(e.forum)
        put("post", post(metadata))
        put(
            "staffStatus",
            when (metadata.staff) {
                true -> "Verified staff metadata"
                false -> "Explicit non-staff metadata"
                null -> "Unknown; server did not report staff status"
            }
        )
        put(
            "acceptedAnswerStatus",
            when (metadata.accepted) {
                true -> "Verified accepted answer"
                false -> "Explicitly not the selected accepted answer"
                null -> "Unknown; server did not report accepted-answer status"
            }
        )
        put("partialTextOrUnprocessedMedia", e.abbreviated)
        put(
            "paragraphs",
            JsonArray(
                e.paragraphs.mapIndexed { i, text ->
                    buildJsonObject {
                        put("paragraph", i)
                        put("text", text)
                    }
                }
            )
        )
    }
    private fun ids(v: List<Long>) = JsonArray(v.map(::JsonPrimitive))
    private fun JsonObject.ids(key: String) = getValue(key).jsonArray.also { require(it.size <= AssistantRules.FORUM_POSTS) }.map { it.jsonPrimitive.long }
    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
    private fun JsonObject.long(key: String) = getValue(key).jsonPrimitive.long
    private fun JsonObject.int(key: String) = getValue(key).jsonPrimitive.int
    private fun JsonObject.bool(key: String) = getValue(key).jsonPrimitive.boolean
}
