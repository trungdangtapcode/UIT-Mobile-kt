package vn.edu.uit.mobile.data

import kotlinx.serialization.json.*
import vn.edu.uit.mobile.domain.*

/** Local encrypted draft format; never sent as a Discourse draft/autosave request. */
internal object ForumDraftCodec {
    fun encode(draft: ForumDraft): String {
        require(draft.raw.length <= 64000 && draft.title.length <= 1000 && draft.reason.length <= 1000)
        require((draft.edit?.raw?.length ?: 0) <= 64000)
        return buildJsonObject {
            put("version", 1)
            draft.topicId?.let { put("topic", it) }
            draft.parent?.let { put("parent", it) }
            draft.categoryId?.let { put("category", it) }
            put("raw", draft.raw)
            put("title", draft.title)
            put("reason", draft.reason)
            put("uncertain", draft.uncertain)
            draft.edit?.let { edit ->
                put(
                    "edit",
                    buildJsonObject {
                        put("post", edit.postId)
                        put("topic", edit.topicId)
                        put("number", edit.number)
                        put("raw", edit.raw)
                        put("version", edit.version)
                    }
                )
            }
        }.toString()
    }
    fun decode(raw: String): ForumDraft {
        require(raw.length <= 800000)
        val o = Json.parseToJsonElement(raw).jsonObject
        fun JsonObject.number(key: String) = (get(key) as? JsonPrimitive)?.longOrNull
        fun JsonObject.string(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
        require(o.number("version") == 1L)
        val edit = (o["edit"] as? JsonObject)?.let { e ->
            ForumEditable(requireNotNull(e.number("post")), requireNotNull(e.number("topic")), requireNotNull(e.number("number")).toInt(), e.string("raw"), requireNotNull(e.number("version")).toInt()).also { require(it.postId > 0 && it.topicId > 0 && it.number > 0 && it.version > 0) }
        }
        val draft = ForumDraft(o.number("topic"), o.number("parent")?.toInt(), edit, o.string("raw"), o.string("title"), o.number("category"), o.string("reason"), (o["uncertain"] as? JsonPrimitive)?.booleanOrNull == true)
        require(listOfNotNull(draft.topicId, draft.categoryId).all { it > 0 } && (draft.parent?.let { it > 0 } != false))
        encode(draft) // Validate bounds consistently on read and write.
        return draft
    }
}
