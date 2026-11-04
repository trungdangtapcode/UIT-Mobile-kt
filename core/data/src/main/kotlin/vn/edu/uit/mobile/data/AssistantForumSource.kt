package vn.edu.uit.mobile.data

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.forum.network.*

/** Dedicated anonymous reads: never receives the member repository, cookies or account commands. */
class AnonymousAssistantForumSource(private val transport: ForumTransport = AnonymousForumClient(), private val now: () -> Long = { System.currentTimeMillis() / 1000 }) : AssistantForumSource {
    override suspend fun read(scope: AssistantForumScope): AssistantForumRead = withContext(Dispatchers.IO) {
        AssistantRules.forum(scope)
        val request = ForumRequest.Topic(scope.topicId, scope.startNumber)
        val raw = transport.get(request)
        val root = Json.parseToJsonElement(raw).jsonObject
        require(root["archetype"]?.jsonPrimitive?.content == "regular") { "Only anonymously available regular topics are supported" }
        val document = ForumParser.parse(request, raw)
        val topic = requireNotNull(document.topic)
        val anchor = document.posts.filter { it.number >= scope.startNumber }.minByOrNull { it.number } ?: error("No available post at the selected starting number")
        val start = document.stream.indexOf(anchor.id).also { require(it >= 0) }
        val ids = document.stream.drop(start).take(scope.limit)
        val posts = document.posts.associateBy { it.id }.toMutableMap()
        val metadata = linkedMapOf<Long, JsonObject>()
        fun retainMetadata(data: JsonObject) {
            data["post_stream"]?.jsonObject?.get("posts")?.jsonArray.orEmpty().forEach { value ->
                val post = value.jsonObject
                post["id"]?.jsonPrimitive?.longOrNull?.let { metadata[it] = post }
            }
        }
        retainMetadata(root)
        ids.filterNot { it in posts }.chunked(ForumLimits.BATCH).forEach { batch ->
            currentCoroutineContext().ensureActive()
            val next = ForumRequest.Posts(scope.topicId, batch)
            val body = transport.get(next)
            ForumParser.parse(next, body).posts.forEach { posts[it.id] = it }
            retainMetadata(Json.parseToJsonElement(body).jsonObject)
        }
        val selected = ids.mapNotNull(posts::get).filter { it.number >= scope.startNumber }
        require(selected.isNotEmpty()) { "Selected posts are unavailable" }
        val at = now()
        val acceptedId = (root["accepted_answer"] as? JsonObject)?.get("post_id")?.jsonPrimitive?.longOrNull
        val versions = linkedMapOf<Long, String>()
        selected.forEach { post ->
            val rawPost = metadata.getValue(post.id)
            versions[post.id] = practiceDigest(listOf("forum-assistant-text-v2", topic.title, post.id.toString(), post.number.toString(), post.author, post.created, post.updated, post.version.toString(), post.replyTo.toString(), rawPost["staff"].toString(), rawPost["admin"].toString(), rawPost["moderator"].toString(), (acceptedId?.let { it == post.id }).toString(), rawPost["cooked"]?.jsonPrimitive?.content.orEmpty()))
        }
        var chars = 32000
        var wireBudget = 70000
        val evidence = selected.mapIndexedNotNull { index, post ->
            val extracted = ForumEvidenceText.extract(post.blocks)
            // Reserve space for later replies; a long first post must not starve the selected tail.
            val budget = minOf(chars / (selected.size - index), 4800)
            var left = budget
            val paragraphs = buildList {
                for (text in extracted.first.flatMap { it.chunked(AssistantRules.MAX_PASSAGE) }.take(AssistantRules.MAX_PARAGRAPHS)) {
                    if (left <= 0) break
                    add(text.take(left))
                    left -= last().length
                }
            }
            if (paragraphs.isEmpty()) return@mapIndexedNotNull null
            val meta = metadata.getValue(post.id)
            fun flag(key: String) = meta[key]?.jsonPrimitive?.booleanOrNull
            val staff = flag("staff") ?: if (flag("admin") != null && flag("moderator") != null) flag("admin") == true || flag("moderator") == true else null
            val details = AssistantForumPost(post.topicId, post.id, post.number, post.author, post.created, post.updated, post.version, post.replyTo, staff, acceptedId?.let { it == post.id })
            val version = versions.getValue(post.id)
            val item = AssistantEvidence("forum_${post.topicId}_${post.id}", "${ForumLimits.ORIGIN}/t/${post.topicId}/${post.number}", null, topic.title, post.created, at, version, paragraphs, abbreviated = extracted.second || paragraphs.sumOf { it.length } < extracted.first.sumOf { it.length }, forum = details)
            val bytes = AssistantForumCodec.evidence(item).toString().toByteArray(Charsets.UTF_8).size
            if (bytes > wireBudget) return@mapIndexedNotNull null
            wireBudget -= bytes
            chars -= paragraphs.sumOf { it.length }
            item
        }
        require(evidence.isNotEmpty()) { "No supported text in selected posts" }
        val loaded = selected.map { it.id }
        val streamVersion = practiceDigest(document.stream.map(Long::toString))
        val coverage = AssistantForumCoverage(topic.id, topic.title, at, document.stream.size, ids, loaded, ids.filterNot { it in loaded }, selected.minOf { it.number }, selected.maxOf { it.number }, start > 0, start + ids.size < document.stream.size, topic.lastPosted, streamVersion, practiceDigest(listOf(topic.title, streamVersion) + selected.map { versions.getValue(it.id) }), evidence.any { it.abbreviated } || evidence.size < selected.size)
        AssistantForumRead(evidence, coverage)
    }
}

/** Semantic blocks from the production Forum reader; concealed or unsupported content stays explicit. */
internal object ForumEvidenceText {
    fun extract(blocks: List<ForumBlock>): Pair<List<String>, Boolean> {
        var omitted = false
        val text = mutableListOf<String>()
        fun visit(items: List<ForumBlock>, quote: Boolean = false) {
            items.forEach { block ->
                when (block.kind) {
                    ForumBlockKind.IMAGE, ForumBlockKind.SPOILER, ForumBlockKind.UNSUPPORTED, ForumBlockKind.OMITTED, ForumBlockKind.POLL -> omitted = true
                    ForumBlockKind.TABLE -> {
                        if (block.cells.isNotEmpty()) {
                            block.cells.forEach { row ->
                                val rendered = row.map { cell ->
                                    val start = text.size
                                    visit(cell, quote)
                                    val value = text.drop(start).joinToString(" / ")
                                    while (text.size > start) text.removeAt(text.lastIndex)
                                    value
                                }
                                text += rendered.joinToString(" | ")
                            }
                        } else {
                            block.rows.forEach { row -> text += row.joinToString(" | ") }
                        }
                    }
                    ForumBlockKind.QUOTE -> {
                        if (block.children.isNotEmpty()) {
                            visit(block.children, true)
                        } else if (block.text.isNotBlank()) {
                            text += "[Quoted content, not necessarily the post author's claim] " + block.text
                        }
                    }
                    else -> {
                        val visible = if (block.inline.isNotEmpty()) {
                            if (block.inline.any { it.spoiler }) omitted = true
                            block.inline.filterNot { it.spoiler }.joinToString("") { it.text }
                        } else {
                            // Container fallback text repeats its semantic children (including spoilers).
                            if (block.children.isEmpty()) block.text else ""
                        }
                        if (block.kind == ForumBlockKind.DETAILS && block.label.isNotBlank()) text += block.label
                        if (visible.isNotBlank()) text += (if (quote) "[Quoted content, not necessarily the post author's claim] " else "") + (if (block.kind == ForumBlockKind.CODE) visible else AssistantRules.normalize(visible))
                        if (block.children.isNotEmpty()) visit(block.children, quote)
                    }
                }
            }
        }
        visit(blocks)
        return text to omitted
    }
}
