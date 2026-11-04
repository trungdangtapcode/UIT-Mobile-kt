package vn.edu.uit.mobile.forum.network

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Element
import com.fleeksoft.ksoup.nodes.TextNode
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
    }.map { ForumPost(it.n("id"), id, it.n("post_number").toInt(), it.s("username").take(100), it.s("created_at").take(40), blocks(it.s("cooked"))) }.distinctBy { it.id }

    fun blocks(html: String): List<ForumBlock> {
        var abbreviated = html.length > 64000
        fun clipped(value: String, limit: Int): String {
            if (value.length > limit) abbreviated = true
            return value.take(limit)
        }
        val doc = Ksoup.parseBodyFragment(html.take(64000))
        val hasEmbed = doc.select("iframe,object,embed,video,audio").isNotEmpty()
        doc.select("script,style,form,input,button,iframe,object,embed,svg,canvas,link,meta").remove()
        // Discourse emoji are inline textual reactions, not image attachments.
        doc.select("img.emoji").forEach { it.replaceWith(TextNode(it.attr("alt").take(80))) }
        val output = mutableListOf<ForumBlock>()
        fun links(e: Element) = e.select("a[href]").mapNotNull { a -> ForumUrls.safe(a.attr("href"))?.let { ForumLink(a.text().take(300).ifBlank { it }, it) } }.distinctBy { it.url }.take(40)
        fun visit(e: Element, depth: Int) {
            if (output.size >= 200 || depth > 32) {
                abbreviated = true
                return
            }
            val tag = e.tagName()
            when {
                tag == "pre" -> output += ForumBlock(ForumBlockKind.CODE, clipped(e.wholeText(), 16000))
                tag == "table" -> output += ForumBlock(ForumBlockKind.TABLE, "", rows = e.select("tr").take(100).map { row -> row.select("th,td").take(12).map { it.text().take(1000) } })
                tag == "blockquote" || (tag == "aside" && e.hasClass("quote")) -> output += ForumBlock(ForumBlockKind.QUOTE, clipped(e.text(), 8000), links(e))
                tag == "details" -> output += ForumBlock(ForumBlockKind.DETAILS, clipped(e.text(), 10000), links(e))
                tag == "img" -> {
                    val url = ForumUrls.safe(e.attr("src"))
                    if (url != null) output += ForumBlock(ForumBlockKind.IMAGE, e.attr("alt").take(300), listOf(ForumLink(e.attr("alt").ifBlank { "Image" }, url)))
                }
                tag in listOf("video", "audio") -> output += ForumBlock(ForumBlockKind.UNSUPPORTED, "")
                tag == "li" -> {
                    val marker = if (e.parent()?.tagName() == "ol") ((e.parent()?.attr("start")?.toIntOrNull() ?: 1) + (e.parent()?.children()?.filter { it.tagName() == "li" }?.indexOf(e) ?: 0)).toString() + ". " else "• "
                    val copy = e.clone()
                    copy.select("ul,ol").remove()
                    output += ForumBlock(ForumBlockKind.TEXT, marker + clipped(copy.text(), 10000), links(copy))
                    e.children().filter { it.tagName() in listOf("ul", "ol") }.forEach { visit(it, depth + 1) }
                }
                tag.matches(Regex("h[1-6]")) -> output += ForumBlock(ForumBlockKind.HEADING, clipped(e.text(), 2000), links(e))
                tag == "p" || e.children().isEmpty() -> {
                    if (e.text().isNotBlank()) {
                        val onlyLink = tag == "a" || e.ownText().isBlank() && e.children().size == 1 && e.children().first()?.tagName() == "a"
                        output += ForumBlock(if (onlyLink) ForumBlockKind.LINK else ForumBlockKind.TEXT, if (onlyLink) "" else clipped(e.text(), 10000), links(e))
                    }
                    e.select("img").forEach { visit(it, depth + 1) }
                }
                else -> {
                    if (e.ownText().isNotBlank()) output += ForumBlock(ForumBlockKind.TEXT, clipped(e.ownText(), 10000))
                    e.children().forEach { visit(it, depth + 1) }
                }
            }
        }
        doc.body().childNodes().forEach { node ->
            if (output.size >= 200) {
                abbreviated = true
                return@forEach
            }
            when (node) {
                is Element -> visit(node, 0)
                is TextNode -> if (node.text().isNotBlank()) output += ForumBlock(ForumBlockKind.TEXT, clipped(node.text(), 10000))
            }
        }
        if (hasEmbed && output.none { it.kind == ForumBlockKind.UNSUPPORTED }) output += ForumBlock(ForumBlockKind.UNSUPPORTED, "")
        if (abbreviated) output += ForumBlock(ForumBlockKind.OMITTED, "")
        return output
    }
}
