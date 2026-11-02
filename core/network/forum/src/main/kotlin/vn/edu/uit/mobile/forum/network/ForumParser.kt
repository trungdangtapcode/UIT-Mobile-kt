package vn.edu.uit.mobile.forum.network

import com.fleeksoft.ksoup.Ksoup
import kotlinx.serialization.json.*
import vn.edu.uit.mobile.domain.*

object ForumParser {
    private fun JsonObject.s(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun JsonObject.n(key: String) = s(key).toLongOrNull() ?: 0L
    private fun JsonObject.b(key: String) = s(key) == "true"
    private fun JsonObject.a(key: String) = (get(key) as? JsonArray).orEmpty()
    private fun JsonObject.o(key: String) = (get(key) as? JsonObject) ?: JsonObject(emptyMap())
    private fun text(value: String) = Ksoup.parseBodyFragment(value.take(64000)).text()
    private fun topic(o: JsonObject) = ForumTopic(o.n("id"), text(o.s("title")).take(1000), o.n("category_id"), o.n("posts_count").coerceIn(0, Int.MAX_VALUE.toLong()).toInt(), o.s("last_posted_at").take(40), o.b("pinned"), o.b("closed"), o.a("tags").mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.take(80) }.take(20))
    fun parse(request: ForumRequest, raw: String): ForumDocument {
        try {
            val root = Json.parseToJsonElement(raw).jsonObject
            return when (request) {
                ForumRequest.Categories -> {
                    require(root["categories"] is JsonArray)
                    ForumDocument(categories = categories(root.a("categories")))
                }
                is ForumRequest.Category -> ForumDocument(categories = categories(listOf(root.o("category"))).filter { it.id == request.id })
                is ForumRequest.Latest -> {
                    require(root["topic_list"] is JsonObject && root.o("topic_list")["topics"] is JsonArray)
                    val list = root.o("topic_list")
                    ForumDocument(topics = list.a("topics").take(100).map { topic(it.jsonObject) }.filter { it.id > 0 }.distinctBy { it.id }, more = list.s("more_topics_url").isNotBlank())
                }
                is ForumRequest.Search -> {
                    require(root["topics"] is JsonArray && root["posts"] is JsonArray && root.o("grouped_search_result").s("error").isBlank())
                    val topics = root.a("topics").take(100).map { topic(it.jsonObject) }.associateBy { it.id }
                    ForumDocument(
                        hits = root.a("posts").take(100).mapNotNull {
                            val p = it.jsonObject
                            topics[p.n("topic_id")]?.let { t -> ForumHit(p.n("id"), t, p.n("post_number").toInt(), text(p.s("blurb")).take(1500)) }
                        }.filter { it.id > 0 && it.postNumber > 0 }.distinctBy { it.id },
                        more = root.o("grouped_search_result").b("more_full_page_results")
                    )
                }
                is ForumRequest.Topic -> {
                    if (root.s("archetype") == "private_message" || !root.s("deleted_at").isBlank() || root.s("visible") == "false") throw ForumException(ForumFailure.UNAVAILABLE)
                    require(root.n("id") == request.id && root.o("post_stream")["stream"] is JsonArray)
                    val stream = root.o("post_stream").a("stream").mapNotNull { it.jsonPrimitive.longOrNull }.distinct()
                    require(stream.size <= ForumLimits.STREAM && stream.all { it > 0 })
                    val posts = posts(root, request.id).filter { it.id in stream }
                    ForumDocument(topic = topic(root), stream = stream, posts = posts.sortedBy { stream.indexOf(it.id) })
                }
                is ForumRequest.Revision -> {
                    require(root.n("post_id") == request.postId && root.n("current_revision").toInt() == request.version)
                    if (root.b("current_hidden") || root.b("previous_hidden")) throw ForumException(ForumFailure.UNAVAILABLE)
                    ForumDocument(
                        revision = ForumRevision(
                            request.postId, request.version,
                            root.n("previous_revision").toInt().takeIf { it in 2 until request.version },
                            root.n("next_revision").toInt().takeIf { it > request.version },
                            root.s("display_username").ifBlank { root.s("username") }.take(100), root.s("created_at").take(40),
                            root.s("edit_reason").take(1000), blocks(root.o("body_changes").s("inline")),
                            blocks(root.o("title_changes").s("inline")), root.b("diff_error"), root.n("current_version").toInt().coerceAtLeast(1)
                        )
                    )
                }
                is ForumRequest.Posts -> {
                    require(root.n("id") == request.topicId && root.o("post_stream")["posts"] is JsonArray)
                    ForumDocument(posts = posts(root, request.topicId).filter { it.id in request.ids }.sortedBy { request.ids.indexOf(it.id) })
                }
            }
        } catch (e: ForumException) {
            throw e
        } catch (_: Exception) {
            throw ForumException(ForumFailure.INVALID)
        }
    }
    private fun categories(values: List<JsonElement>) = values.take(500).map { it.jsonObject }.filter { !it.b("read_restricted") }.map { ForumCategory(it.n("id"), text(it.s("name")), it.n("parent_category_id").takeIf { v -> v > 0 }, text(it.s("description_text")).take(500)) }.filter { it.id > 0 }.distinctBy { it.id }
    private fun posts(root: JsonObject, id: Long): List<ForumPost> = root.o("post_stream").a("posts").take(ForumLimits.WINDOW).map { it.jsonObject }.filter {
        it.n("topic_id") == id && it.n("id") > 0 && it.n("post_number") in 1..Int.MAX_VALUE.toLong() && !it.b("hidden") && it.s("deleted_at").isBlank() && !it.b("user_deleted")
    }.map { ForumPost(it.n("id"), id, it.n("post_number").toInt(), it.s("username").take(100), it.s("created_at").take(40), blocks(it.s("cooked")), it.n("version").toInt().coerceAtLeast(1), it.s("updated_at").take(40), it.b("can_view_edit_history"), it.n("reply_to_post_number").toInt().takeIf { n -> n > 0 }) }.distinctBy { it.id }

    fun blocks(html: String): List<ForumBlock> = ForumHtml.parse(html)
}
