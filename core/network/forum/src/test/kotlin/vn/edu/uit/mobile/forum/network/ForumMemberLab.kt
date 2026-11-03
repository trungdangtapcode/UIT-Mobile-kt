package vn.edu.uit.mobile.forum.network

import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import vn.edu.uit.mobile.domain.*

/** Explicit opt-in local production-client acceptance. Never accepts a remote origin. */
object ForumMemberLab {
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        val config = Json.parseToJsonElement(File(args[0]).readText()).jsonObject
        check(config.s("origin") == "http://127.0.0.1:39300")
        val clients = (0..1).map {
            HttpClient(OkHttp) {
                followRedirects = false
                install(HttpTimeout) { requestTimeoutMillis = 30000 }
                engine {
                    config {
                        followRedirects(false)
                        followSslRedirects(false)
                        retryOnConnectionFailure(false)
                    }
                }
            }
        }
        try {
            val a = ForumSessionClient("http://127.0.0.1:39300", clients[0])
            val b = ForumSessionClient("http://127.0.0.1:39300", clients[1])
            val users = config.a("users").map { it.jsonObject }
            val author = a.login(users[0].s("username"), users[0].s("password"))
            val reader = b.login(users[1].s("username"), users[1].s("password"))
            check(author.id != reader.id && author.canCreateTopic && reader.canChat)
            val apiA = ForumMemberApi(a)
            val apiB = ForumMemberApi(b)
            val created = apiA.send(author, ForumCommand.Create("Native contract ${System.currentTimeMillis()}", "Local native client test with **formatting** and a poll.\n\n[poll type=regular]\n* Option one\n* Option two\n[/poll]", config.n("category")))
            val topicId = requireNotNull(created.topicId)
            val request = ForumRequest.Topic(topicId)
            val topic = ForumParser.parse(request, b.get(request), true)
            check(topic.canReply && topic.posts.size == 1)
            val post = topic.posts.single()
            val editable = requireNotNull(apiA.read(author, ForumMemberRequest.Edit(post.id)).editable)
            apiA.send(author, ForumCommand.Edit(editable, editable.raw.replace("Local native", "Updated local native"), "Native edit contract"))
            try {
                apiA.send(author, ForumCommand.Edit(editable, "Stale edit must not overwrite the latest content.", "Stale"))
                error("Stale edit accepted")
            } catch (e: ForumException) {
                check(e.reason == ForumFailure.CONFLICT)
            }
            apiB.send(reader, ForumCommand.Reply(topicId, "Native reply contract from the second isolated account.", 1))
            apiB.send(reader, ForumCommand.Bookmark(post.id))
            val bookmarks = apiB.read(reader, ForumMemberRequest.Bookmarks()).bookmarks
            val bookmark = bookmarks.first { it.topicId == topicId }
            apiB.send(reader, ForumCommand.RemoveBookmark(bookmark.id))
            check(apiB.read(reader, ForumMemberRequest.Bookmarks()).bookmarks.none { it.id == bookmark.id })
            apiB.send(reader, ForumCommand.Like(post.id))
            apiB.send(reader, ForumCommand.Like(post.id, true))
            val reaction = topic.reactionChoices.firstOrNull { it != "heart" } ?: error("No local reaction choice")
            apiB.send(reader, ForumCommand.Reaction(post.id, reaction))
            val reacted = ForumParser.parse(request, b.get(request), true).posts.first()
            check(reacted.reactions.any { it.id == reaction && it.selected })
            apiB.send(reader, ForumCommand.Reaction(post.id, reaction))
            val poll = post.polls.single()
            apiB.send(reader, ForumCommand.Vote(post.id, poll.name, listOf(poll.options.first().id)))
            check(ForumParser.parse(request, b.get(request), true).posts.first().polls.single().votes == listOf(poll.options.first().id))
            val channels = apiB.read(reader, ForumMemberRequest.Channels).channels
            val channel = channels.first { it.id == config.n("channel") }
            check(channel.canSend)
            val text = "Native local message ${System.currentTimeMillis()}"
            apiB.send(reader, ForumCommand.Chat(channel, text, java.util.UUID.randomUUID().toString()))
            val messages = apiB.read(reader, ForumMemberRequest.Messages(channel)).messages
            check(messages.any { it.blocks.any { block -> text in block.text } })
            val original = messages.first { it.blocks.any { block -> text in block.text } }
            // Seed a local thread through the upstream route; native UI currently
            // enters existing threads instead of creating new ones.
            val thread = b.write(io.ktor.http.HttpMethod.Post, "/chat/api/channels/${channel.id}/threads.json", listOf("original_message_id" to "${original.id}", "title" to "Native isolated thread")).n("id")
            check(thread > 0)
            val threadText = "Native thread reply ${System.currentTimeMillis()}"
            apiA.send(author, ForumCommand.Chat(channel, threadText, java.util.UUID.randomUUID().toString(), thread))
            val threadPage = apiB.read(reader, ForumMemberRequest.Messages(channel, threadId = thread))
            check(threadPage.thread?.id == thread && threadPage.thread?.channelId == channel.id)
            check(threadPage.messages.any { m -> m.threadId == thread && m.blocks.any { threadText in it.text } })
            apiB.read(reader, ForumMemberRequest.Notices())
            val restored = ForumSessionClient("http://127.0.0.1:39300", clients[0])
            restored.restore(a.snapshot())
            check(restored.current().id == author.id)
            a.clear()
            b.clear()
            restored.clear()
            if (args.size > 1) {
                File(args[1]).writeText(
                    buildJsonObject {
                        put("captured_at_utc", java.time.Instant.now().toString())
                        put("environment", "Production Kotlin ForumSessionClient and ForumMemberApi against isolated rootless Discourse")
                        put("upstream_revision", config.s("upstream_revision"))
                        put("production_UIT_requests", 0)
                        put("university_credentials_used", false)
                        put("checks", JsonArray(listOf("native login and cookie restore", "create, edit, stale edit conflict, reply", "bookmark creation and removal", "like and reaction add/remove", "poll vote reflected in subsequent read", "channel and existing thread send/read", "notification read").map { JsonPrimitive(it) }))
                        put("limits", "Local server contract acceptance, not production UIT mutations or installed Android/Keystore acceptance. No synthetic review screenshots.")
                    }.toString() + "\n"
                )
            }
            println("PASS: native session restore, create/edit/conflict/reply, bookmarks, like/reaction, poll, channel/thread chat and notification contracts on isolated localhost; no UIT writes.")
        } finally {
            clients.forEach { it.close() }
        }
    }
}
