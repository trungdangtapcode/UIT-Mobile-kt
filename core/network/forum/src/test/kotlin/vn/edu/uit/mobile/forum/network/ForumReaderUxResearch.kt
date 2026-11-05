package vn.edu.uit.mobile.forum.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import java.io.File
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import vn.edu.uit.mobile.domain.*

/** Manual, bounded public GET probe. Does not accept university credentials or perform writes. */
object ForumReaderUxResearch {
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        val http = HttpClient(OkHttp) {
            followRedirects = false
            install(HttpTimeout) {
                requestTimeoutMillis = 25000
                connectTimeoutMillis = 10000
                socketTimeoutMillis = 20000
            }
            engine {
                config {
                    followRedirects(false)
                    followSslRedirects(false)
                }
            }
        }
        val client = AnonymousForumClient(http)
        val rows = listOf(ForumRequest.Topic(163707), ForumRequest.Topic(163707, 20), ForumRequest.Topic(164929)).map { request ->
            try {
                val started = System.nanoTime()
                val raw = client.get(request)
                val doc = ForumParser.parse(request, raw)
                buildJsonObject {
                    put("request", ForumUrls.key(request))
                    put("success", true)
                    put("responseBytes", raw.toByteArray().size)
                    put("elapsedMs", (System.nanoTime() - started) / 1_000_000)
                    put("topicId", doc.topic?.id)
                    put("title", doc.topic?.title)
                    put("streamIds", doc.stream.size)
                    put("posts", doc.posts.size)
                    put("postNumbers", JsonArray(doc.posts.map { JsonPrimitive(it.number) }))
                    put("nativeBlockKinds", JsonArray(doc.posts.flatMap { it.blocks }.map { it.kind.name }.distinct().map(::JsonPrimitive)))
                }
            } catch (e: ForumException) {
                buildJsonObject {
                    put("request", ForumUrls.key(request))
                    put("success", false)
                    put("failure", e.reason.name)
                }
            }
        }
        val report = buildJsonObject {
            put("capturedAt", Instant.now().toString())
            put("runtime", "Production anonymous Kotlin transport and parser on JVM; no physical phone")
            put("universityAccountUsed", false)
            put("writes", 0)
            put("results", JsonArray(rows))
        }
        File(args.single()).writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), report) + "\n")
        println(report)
        http.close()
        check(rows.all { it.getValue("success").jsonPrimitive.boolean })
    }
}
