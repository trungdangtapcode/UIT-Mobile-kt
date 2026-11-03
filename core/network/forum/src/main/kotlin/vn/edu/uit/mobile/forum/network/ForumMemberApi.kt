package vn.edu.uit.mobile.forum.network

import com.fleeksoft.ksoup.Ksoup
import io.ktor.http.*
import kotlinx.serialization.json.*
import vn.edu.uit.mobile.domain.*

/** Typed member requests; server validation remains authoritative for every mutation. */
class ForumMemberApi(private val client: ForumSessionClient) {
    suspend fun read(user: ForumUser, request: ForumMemberRequest): ForumAccountPage = when (request) {
        is ForumMemberRequest.Notices -> {
            require(request.offset in 0..10000)
            val root = client.read("/notifications.json", listOf("offset" to "${request.offset}", "limit" to "30"))
            require(root["notifications"] is JsonArray)
            val values = root.a("notifications").take(30).map { it.jsonObject }
            ForumAccountPage(
                notices = values.map { n ->
                    val data = n.o("data")
                    val title = listOf(n.s("fancy_title"), data.s("topic_title"), data.s("badge_name"), data.s("title"), data.s("display_username"), data.s("username")).firstOrNull { it.isNotBlank() }.orEmpty()
                    val source = data.n("badge_id").takeIf { n.n("notification_type") == 12L && it > 0 }?.let { "${ForumLimits.ORIGIN}/badges/$it" }.orEmpty()
                    ForumNotice(n.n("id"), Ksoup.parseBodyFragment(title.take(4000)).text().take(1000), n.n("topic_id").takeIf { it > 0 }, n.n("post_number").toInt().coerceAtLeast(1), n.b("read"), n.n("notification_type").toInt(), n.s("created_at").take(40), source)
                }.filter { it.id > 0 },
                more = values.size == 30
            )
        }
        is ForumMemberRequest.Bookmarks -> {
            require(request.page in 0..100 && user.username.matches(Regex("[a-zA-Z0-9_.-]{1,200}")))
            val root = client.read("/u/${user.username}/bookmarks.json", listOf("page" to "${request.page}"))
            val data = if (root["user_bookmark_list"] is JsonObject) root.o("user_bookmark_list") else root
            require(data["bookmarks"] is JsonArray)
            ForumAccountPage(bookmarks = data.a("bookmarks").take(100).map { it.jsonObject }.filter { it.n("topic_id") > 0 && !it.b("deleted") && !it.b("hidden") }.map { ForumBookmark(it.n("id"), it.s("title").take(1000), it.n("topic_id"), it.n("linked_post_number").toInt().coerceAtLeast(1), it.s("reminder_at")) }, more = data.s("more_bookmarks_url").isNotBlank())
        }
        ForumMemberRequest.Channels -> {
            if (!user.canChat) throw ForumException(ForumFailure.FORBIDDEN)
            val root = client.read("/chat/api/me/channels.json")
            require(root["public_channels"] is JsonArray && root["direct_message_channels"] is JsonArray)
            ForumAccountPage(
                channels = (root.a("public_channels") + root.a("direct_message_channels")).take(500).map { it.jsonObject }.map {
                    ForumChannel(it.n("id"), it.s("title").take(300), it.s("status") == "open" && it["current_user_membership"] is JsonObject && it.o("meta")["user_silenced"] == JsonPrimitive(false), it.s("chatable_type") == "DirectMessage")
                }.filter { it.id > 0 }.distinctBy { it.id }
            )
        }
        is ForumMemberRequest.Messages -> {
            if (!user.canChat) throw ForumException(ForumFailure.FORBIDDEN)
            require(request.channel.id > 0 && (request.before?.let { it > 0 } != false) && (request.threadId?.let { it > 0 } != false))
            val thread = request.threadId?.let { id ->
                val o = client.read("/chat/api/channels/${request.channel.id}/threads/$id.json").o("thread")
                require(o.n("id") == id && o.n("channel_id") == request.channel.id)
                ForumThread(id, request.channel.id, o.s("title").take(300), o.s("status"))
            }
            val params = mutableListOf("page_size" to "50")
            if (request.before == null) params += "fetch_from_last_message" to "true"
            request.before?.let {
                params += "target_message_id" to "$it"
                params += "direction" to "past"
            }
            val scope = thread?.let { "/threads/${it.id}" }.orEmpty()
            val root = client.read("/chat/api/channels/${request.channel.id}$scope/messages.json", params)
            require(root["messages"] is JsonArray)
            ForumAccountPage(messages = root.a("messages").take(50).map { it.jsonObject }.filter { it.n("chat_channel_id") == request.channel.id && it.s("deleted_at").isBlank() && (thread == null || it.n("thread_id") == thread.id) }.map { ForumChatMessage(it.n("id"), request.channel.id, it.o("user").s("username").take(100), it.s("created_at"), ForumParser.blocks(it.s("cooked")), it.n("thread_id").takeIf { n -> n > 0 }) }.filter { it.id > 0 }, more = root.o("meta").b("can_load_more_past"), thread = thread)
        }
        is ForumMemberRequest.Edit -> {
            require(request.postId > 0)
            val root = client.read("/posts/${request.postId}.json")
            if (!root.b("can_edit")) throw ForumException(ForumFailure.FORBIDDEN)
            require(root.n("id") == request.postId && root["raw"] is JsonPrimitive)
            ForumAccountPage(editable = ForumEditable(request.postId, root.n("topic_id"), root.n("post_number").toInt(), root.s("raw"), root.n("version").toInt()))
        }
    }
    suspend fun send(user: ForumUser, command: ForumCommand): ForumCommandResult {
        fun body(text: String) {
            require(text.isNotBlank() && text.length <= 64000)
        }
        val result = when (command) {
            is ForumCommand.Create -> {
                if (!user.canCreateTopic) throw ForumException(ForumFailure.FORBIDDEN)
                body(command.raw)
                require(command.title.isNotBlank() && command.title.length <= 1000 && command.categoryId > 0)
                client.write(HttpMethod.Post, "/posts.json", listOf("title" to command.title, "raw" to command.raw, "category" to "${command.categoryId}", "archetype" to "regular"))
            }
            is ForumCommand.Reply -> {
                body(command.raw)
                require(command.topicId > 0 && (command.parent?.let { it > 0 } != false))
                client.write(HttpMethod.Post, "/posts.json", listOf("topic_id" to "${command.topicId}", "raw" to command.raw) + command.parent?.let { listOf("reply_to_post_number" to "$it") }.orEmpty())
            }
            is ForumCommand.Edit -> {
                body(command.raw)
                require(command.source.postId > 0 && command.reason.length <= 1000)
                client.write(HttpMethod.Put, "/posts/${command.source.postId}.json", listOf("post[raw]" to command.raw, "post[original_text]" to command.source.raw, "post[edit_reason]" to command.reason))
            }
            is ForumCommand.Bookmark -> {
                require(command.postId > 0)
                client.write(HttpMethod.Post, "/bookmarks.json", listOf("bookmarkable_type" to "Post", "bookmarkable_id" to "${command.postId}"))
            }
            is ForumCommand.RemoveBookmark -> {
                require(command.id > 0)
                client.write(HttpMethod.Delete, "/bookmarks/${command.id}.json")
            }
            is ForumCommand.Reaction -> {
                require(command.postId > 0 && command.reaction.matches(Regex("[a-zA-Z0-9_+:-]{1,80}")))
                client.write(HttpMethod.Put, "/discourse-reactions/posts/${command.postId}/custom-reactions/${command.reaction}/toggle.json")
            }
            is ForumCommand.Like -> {
                require(command.postId > 0)
                if (command.remove) {
                    client.write(HttpMethod.Delete, "/post_actions/${command.postId}.json", listOf("post_action_type_id" to "2"))
                } else {
                    client.write(HttpMethod.Post, "/post_actions.json", listOf("id" to "${command.postId}", "post_action_type_id" to "2"))
                }
            }
            is ForumCommand.Vote -> {
                require(command.postId > 0 && command.name.length in 1..100 && command.options.size in 1..100 && command.options.all { it.length in 1..100 })
                client.write(HttpMethod.Put, "/polls/vote.json", listOf("post_id" to "${command.postId}", "poll_name" to command.name) + command.options.map { "options[]" to it })
            }
            is ForumCommand.Chat -> {
                if (!user.canChat || !command.channel.canSend) throw ForumException(ForumFailure.FORBIDDEN)
                body(command.text)
                require((command.threadId?.let { it > 0 } != false) && command.channel.id > 0 && command.clientId.matches(Regex("[a-zA-Z0-9-]{1,100}")))
                client.write(HttpMethod.Post, "/chat/${command.channel.id}.json", listOf("message" to command.text, "staged_id" to command.clientId) + command.threadId?.let { listOf("thread_id" to "$it") }.orEmpty())
            }
        }
        if (result.s("error").isNotBlank() || result.a("errors").isNotEmpty() || result["success"] == JsonPrimitive(false)) throw ForumException(ForumFailure.REJECTED, detail = (result.s("error") + result.a("errors").joinToString()).take(500))
        if (command is ForumCommand.Create || command is ForumCommand.Reply) {
            if (result.n("id") <= 0 || result.n("topic_id") <= 0) throw ForumException(ForumFailure.UNCERTAIN)
        }
        val post = if (result["post"] is JsonObject) result.o("post") else result
        return ForumCommandResult(post.n("topic_id").takeIf { it > 0 }, post.n("post_number").toInt().coerceAtLeast(1))
    }
}
