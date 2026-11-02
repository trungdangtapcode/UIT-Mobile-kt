package vn.edu.uit.mobile.forum.network

import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import java.io.ByteArrayOutputStream
import java.net.URI
import kotlinx.coroutines.CancellationException
import vn.edu.uit.mobile.domain.*

interface ForumTransport {
    suspend fun get(request: ForumRequest): String
    suspend fun image(url: String): ByteArray
}

/** A dedicated client: no cookie plugin, account transport, auth header or mutation API. */
class AnonymousForumClient(
    private val client: HttpClient = HttpClient(OkHttp) {
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
) : ForumTransport {
    override suspend fun get(request: ForumRequest): String {
        val path = when (request) {
            ForumRequest.Categories -> "/site.json"
            is ForumRequest.Category -> {
                require(request.id > 0)
                "/c/${request.id}/show.json"
            }
            is ForumRequest.Latest -> {
                require(request.page in 0..99 && (request.categoryId?.let { it > 0 } != false))
                request.categoryId?.let { "/c/$it.json" } ?: "/latest.json"
            }
            is ForumRequest.Search -> {
                require(request.query.isNotBlank() && request.query.length <= 200 && request.page in 1..100)
                "/search.json"
            }
            is ForumRequest.Topic -> {
                require(request.id > 0 && request.postNumber > 0)
                if (request.postNumber == 1) "/t/${request.id}.json" else "/t/${request.id}/${request.postNumber}.json"
            }
            is ForumRequest.Revision -> {
                require(request.topicId > 0 && request.postId > 0 && request.version > 1)
                "/posts/${request.postId}/revisions/${request.version}.json"
            }
            is ForumRequest.Posts -> {
                require(request.topicId > 0 && request.ids.size in 1..ForumLimits.BATCH && request.ids.all { it > 0 })
                "/t/${request.topicId}/posts.json"
            }
        }
        return requestBytes(ForumLimits.ORIGIN + path, 4 * 1024 * 1024, false, (request as? ForumRequest.Latest)?.takeIf { it.categoryId != null }) {
            when (request) {
                is ForumRequest.Latest -> parameter("page", request.page)
                is ForumRequest.Search -> {
                    parameter("q", request.query)
                    parameter("page", request.page)
                }
                is ForumRequest.Posts -> request.ids.distinct().forEach { parameter("post_ids[]", it) }
                else -> Unit
            }
        }.toString(Charsets.UTF_8)
    }
    override suspend fun image(url: String): ByteArray {
        val safe = ForumUrls.image(url) ?: throw ForumException(ForumFailure.INVALID)
        return requestBytes(safe, 3 * 1024 * 1024, true) {}
    }
    private suspend fun requestBytes(url: String, limit: Int, image: Boolean, category: ForumRequest.Latest? = null, configure: HttpRequestBuilder.() -> Unit): ByteArray {
        try {
            return client.prepareGet(url) {
                configure()
                header(HttpHeaders.Accept, if (image) "image/png,image/jpeg,image/webp,image/gif" else "application/json")
                header(HttpHeaders.UserAgent, "UIT-Mobile/0.1 (anonymous public reader)")
                listOf("Cookie", "Authorization", "Api-Key", "Api-Username", "User-Api-Key").forEach { headers.remove(it) }
            }.execute { response ->
                when (response.status.value) {
                    200 -> Unit
                    301, 302, 307, 308 -> {
                        val location = response.headers[HttpHeaders.Location]
                        val safe = location?.let(ForumUrls::safe)?.let(::URI)
                        val id = category?.categoryId
                        val expectedPath = id?.let { Regex("/c/(?:[a-z0-9-]+/){1,4}$it\\.json") }
                        if (safe == null || id == null || safe.host != "forum.uit.edu.vn" || safe.rawFragment != null || expectedPath?.matches(safe.path) != true || safe.rawQuery != "page=${category.page}") throw ForumException(ForumFailure.INVALID)
                        response.bodyAsChannel().cancel()
                        // Exactly one canonical category rewrite; every other redirect is rejected.
                        return@execute requestBytes(ForumLimits.ORIGIN + safe.rawPath, limit, image, null, configure)
                    }
                    429 -> throw ForumException(ForumFailure.RATE_LIMIT, response.headers[HttpHeaders.RetryAfter]?.toLongOrNull()?.coerceIn(1, 86400))
                    401, 403, 404, 410 -> throw ForumException(ForumFailure.UNAVAILABLE)
                    in 500..599 -> throw ForumException(ForumFailure.NETWORK)
                    else -> throw ForumException(ForumFailure.INVALID)
                }
                val type = response.contentType()?.withoutParameters()?.toString()
                require(if (image) type in setOf("image/png", "image/jpeg", "image/webp", "image/gif") else type == "application/json")
                require((response.contentLength() ?: 0) <= limit)
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                val channel = response.bodyAsChannel()
                while (true) {
                    val count = channel.readAvailable(buffer)
                    if (count < 0) break
                    require(output.size() + count <= limit)
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ForumException) {
            throw e
        } catch (e: IllegalArgumentException) {
            throw ForumException(ForumFailure.INVALID)
        } catch (_: Exception) {
            throw ForumException(ForumFailure.NETWORK)
        }
    }
}
