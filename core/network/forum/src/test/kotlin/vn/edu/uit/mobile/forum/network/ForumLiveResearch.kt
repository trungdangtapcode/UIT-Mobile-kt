package vn.edu.uit.mobile.forum.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.*
import java.io.File
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import vn.edu.uit.mobile.domain.*

/** Explicit manual task only. Public bounded GETs; no account, cookie or key input. */
object ForumLiveResearch {
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        val responses = AtomicInteger()
        val client = HttpClient(OkHttp) {
            followRedirects = false
            HttpResponseValidator { validateResponse { responses.incrementAndGet() } }
            engine {
                config {
                    followRedirects(false)
                    followSslRedirects(false)
                }
            }
        }
        val remote = AnonymousForumClient(client)
        val calls = mutableListOf<String>()
        suspend fun fetch(request: ForumRequest): ForumDocument {
            calls += ForumUrls.key(request)
            println("Checking anonymous GET ${ForumUrls.key(request)}")
            return ForumParser.parse(request, remote.get(request))
        }
        try {
            val categories = fetch(ForumRequest.Categories)
            val latest = fetch(ForumRequest.Latest())
            check(latest.topics.isNotEmpty())
            val page2 = fetch(ForumRequest.Latest(page = 1))
            check(page2.topics.any { t -> latest.topics.none { it.id == t.id } })
            val sample = latest.topics.maxBy { it.posts }
            val category = fetch(ForumRequest.Category(sample.categoryId))
            check(category.categories.single().id == sample.categoryId)
            val feed = fetch(ForumRequest.Latest(sample.categoryId))
            check(feed.topics.isNotEmpty())
            val search = fetch(ForumRequest.Search("uit"))
            check(search.hits.isNotEmpty() && search.hits.all { it.postNumber > 0 })
            val topic = fetch(ForumRequest.Topic(sample.id))
            check(topic.posts.isNotEmpty() && topic.stream.size > topic.posts.size)
            val ids = topic.stream.filterNot { id -> topic.posts.any { it.id == id } }.take(2)
            val batch = fetch(ForumRequest.Posts(sample.id, ids))
            check(batch.posts.isNotEmpty() && batch.posts.all { it.id in ids })
            val target = (sample.posts / 2).coerceAtLeast(1)
            val jumped = fetch(ForumRequest.Topic(sample.id, target))
            check(jumped.posts.isNotEmpty() && jumped.posts.any { it.number >= target - 10 })
            val report = buildJsonObject {
                put("checked_at", Instant.now().toString())
                put("mode", "anonymous-public-get-only")
                put("credentials_used", false)
                put("raw_content_retained", false)
                put("requests", JsonArray(calls.map(::JsonPrimitive)))
                put("http_responses", responses.get())
                put("site_categories", categories.categories.size)
                put("latest_topics", latest.topics.size)
                put("second_page_topics", page2.topics.size)
                put("category_topics", feed.topics.size)
                put("search_hits", search.hits.size)
                put("search_has_more", search.more)
                put("topic_stream_ids", topic.stream.size)
                put("initial_posts", topic.posts.size)
                put("batch_posts", batch.posts.size)
                put("jump_target_number", target)
                put("jump_first_number", jumped.posts.minOf { it.number })
                put("jump_last_number", jumped.posts.maxOf { it.number })
                put("native_block_types", JsonArray((topic.posts + batch.posts + jumped.posts).flatMap { it.blocks }.map { it.kind.name }.distinct().sorted().map(::JsonPrimitive)))
            }
            val destination = File(args.single()).apply { parentFile.mkdirs() }
            destination.writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), report) + "\n")
            println("Public Forum research passed: ${calls.size} logical requests, ${responses.get()} HTTP responses; aggregate report: ${destination.name}")
        } finally {
            client.close()
        }
    }
}
