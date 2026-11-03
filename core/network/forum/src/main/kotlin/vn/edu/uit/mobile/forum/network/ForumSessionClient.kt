package vn.edu.uit.mobile.forum.network

import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import java.io.ByteArrayOutputStream
import java.net.HttpCookie
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import vn.edu.uit.mobile.domain.*

/** Fixed-origin, session-scoped transport. No shared SSO cookie jar or automatic write retry. */
class ForumSessionClient internal constructor(private val origin: String, private val client: HttpClient) : ForumTransport {
    constructor(client: HttpClient) : this(ForumLimits.ORIGIN, client)
    constructor() : this(
        ForumLimits.ORIGIN,
        HttpClient(OkHttp) {
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
                    retryOnConnectionFailure(false)
                }
            }
        }
    )
    init {
        require(origin == ForumLimits.ORIGIN || origin == "http://127.0.0.1:39300")
    }
    private val lock = Any()
    private val mutation = Mutex()
    private var generation = 0L
    private var csrf = ""
    private val cookies = mutableMapOf<String, Pair<String, Long>>()
    private var identity: ForumUser? = null
    private val host get() = java.net.URI(origin).host
    fun clear() = synchronized(lock) {
        generation++
        cookies.clear()
        csrf = ""
        identity = null
    }
    fun snapshot(): String = synchronized(lock) {
        JsonArray(
            cookies.map { (name, entry) ->
                buildJsonObject {
                    put("name", name)
                    put("value", entry.first)
                    put("expires", entry.second)
                }
            }
        ).toString()
    }
    fun restore(raw: String) = synchronized(lock) {
        clear()
        require(raw.length <= 32768)
        val restored = mutableMapOf<String, Pair<String, Long>>()
        Json.parseToJsonElement(raw).jsonArray.forEach {
            val o = it.jsonObject
            val name = o.getValue("name").jsonPrimitive.content
            val value = o.getValue("value").jsonPrimitive.content
            val expires = o.getValue("expires").jsonPrimitive.long
            require(name in setOf("_t", "_forum_session") && validCookie(value))
            require(expires >= 0)
            if (expires == 0L || expires > System.currentTimeMillis() / 1000) restored[name] = value to expires
        }
        cookies.putAll(restored)
    }
    private fun validCookie(value: String) = value.length in 1..16000 && value.all { it.code in 33..126 && it != ';' && it != ',' }
    suspend fun login(username: String, password: String, secondFactor: String = ""): ForumUser = mutation.withLock {
        require(username.isNotBlank() && username.length <= 200 && password.length in 1..1000 && secondFactor.length <= 100)
        clear()
        try {
            token()
            val fields = mutableListOf("login" to username.trim(), "password" to password)
            if (secondFactor.isNotBlank()) fields += listOf("second_factor_token" to secondFactor, "second_factor_method" to "1")
            // UIT returns 204 on successful login; upstream commonly returns a user object.
            val result = json(HttpMethod.Post, "/session", fields)
            if (result.b("totp_enabled") || result.b("second_factor_required")) throw ForumException(ForumFailure.SECOND_FACTOR)
            if (result.s("error").isNotBlank()) throw ForumException(ForumFailure.AUTHENTICATION)
            current()
        } catch (e: Exception) {
            clear()
            throw e
        }
    }
    suspend fun current(): ForumUser {
        val epoch = synchronized(lock) { generation }
        val root = json(HttpMethod.Get, "/session/current.json").o("current_user")
        val user = ForumUser(root.n("id"), root.s("username"), root.s("name"), root.b("can_create_topic"), root.b("can_chat"))
        if (user.id <= 0 || user.username.isBlank()) throw ForumException(ForumFailure.AUTHENTICATION)
        synchronized(lock) {
            if (generation != epoch) throw CancellationException("Forum session changed")
            if (identity != null && identity?.id != user.id) {
                clear()
                throw ForumException(ForumFailure.AUTHENTICATION)
            }
            identity = user
        }
        return user
    }
    private suspend fun token() {
        val epoch = synchronized(lock) { generation }
        val value = json(HttpMethod.Get, "/session/csrf.json").s("csrf")
        require(value.length in 1..1000)
        synchronized(lock) {
            if (generation != epoch) throw CancellationException("Forum session changed")
            csrf = value
        }
    }
    override suspend fun get(request: ForumRequest): String {
        val (path, fields) = route(request)
        return bytes(HttpMethod.Get, path, fields, category = request as? ForumRequest.Latest).toString(Charsets.UTF_8)
    }
    override suspend fun image(url: String): ByteArray {
        val safe = ForumUrls.image(url) ?: throw ForumException(ForumFailure.INVALID)
        return bytes(HttpMethod.Get, java.net.URI(safe).rawPath, image = true)
    }
    suspend fun read(path: String, fields: List<Pair<String, String>> = emptyList()): JsonObject = json(HttpMethod.Get, path, fields)
    suspend fun write(method: HttpMethod, path: String, fields: List<Pair<String, String>> = emptyList()): JsonObject = mutation.withLock {
        require(method in listOf(HttpMethod.Post, HttpMethod.Put, HttpMethod.Delete))
        token()
        json(method, path, fields)
    }
    private suspend fun json(method: HttpMethod, path: String, fields: List<Pair<String, String>> = emptyList()): JsonObject {
        val raw = bytes(method, path, fields).toString(Charsets.UTF_8)
        return if (raw.isBlank()) {
            JsonObject(emptyMap())
        } else {
            try {
                Json.parseToJsonElement(raw).jsonObject
            } catch (_: Exception) {
                throw ForumException(if (method == HttpMethod.Get) ForumFailure.INVALID else ForumFailure.UNCERTAIN)
            }
        }
    }
    private suspend fun bytes(method: HttpMethod, path: String, fields: List<Pair<String, String>> = emptyList(), image: Boolean = false, category: ForumRequest.Latest? = null): ByteArray {
        require(path.startsWith('/') && !path.startsWith("//") && '?' !in path && '#' !in path && '\\' !in path && path.none { it.isISOControl() })
        val epoch = synchronized(lock) { generation }
        val writing = method != HttpMethod.Get
        try {
            return client.prepareRequest(origin + path) {
                this.method = method
                header(HttpHeaders.Accept, if (image) "image/png,image/jpeg,image/webp,image/gif" else "application/json")
                header("X-Requested-With", "XMLHttpRequest")
                header(HttpHeaders.UserAgent, "UIT-Mobile/0.1 (native Forum)")
                listOf("Authorization", "Api-Key", "Api-Username", "User-Api-Key", "Discourse-Present", "Cookie").forEach { headers.remove(it) }
                val auth = synchronized(lock) {
                    cookies.entries.removeAll { it.value.second != 0L && it.value.second <= System.currentTimeMillis() / 1000 }
                    cookies.map { "${it.key}=${it.value.first}" }.joinToString("; ") to csrf
                }
                if (auth.first.isNotBlank()) header(HttpHeaders.Cookie, auth.first)
                if (writing) {
                    header("X-CSRF-Token", auth.second)
                    header(HttpHeaders.Origin, origin)
                    setBody(FormDataContent(Parameters.build { fields.forEach { (k, v) -> append(k, v) } }))
                } else {
                    fields.forEach { (k, v) -> parameter(k, v) }
                }
            }.execute { response ->
                synchronized(lock) { if (generation != epoch) throw CancellationException("Forum session changed") }
                val status = response.status.value
                if (status in listOf(301, 302, 307, 308) && category?.categoryId != null && method == HttpMethod.Get) {
                    val target = response.headers[HttpHeaders.Location]?.let(ForumUrls::safe)?.let { java.net.URI(it) }
                    if (target?.host == host && target.rawFragment == null && target.rawQuery == "page=${category.page}" && Regex("/c/(?:[a-z0-9-]+/){1,4}${category.categoryId}\\.json").matches(target.path)) {
                        response.bodyAsChannel().cancel()
                        return@execute bytes(method, target.rawPath, fields)
                    }
                }
                if (status == 429) throw ForumException(ForumFailure.RATE_LIMIT, response.headers[HttpHeaders.RetryAfter]?.toLongOrNull()?.coerceIn(1, 86400))
                if (status == 401 || status in 300..399 || status in listOf(403, 404) && path == "/session/current.json") throw ForumException(ForumFailure.AUTHENTICATION)
                if (status == 403) throw ForumException(ForumFailure.FORBIDDEN)
                if (status == 409) throw ForumException(ForumFailure.CONFLICT)
                if (status == 404 || status == 410) throw ForumException(ForumFailure.UNAVAILABLE)
                if (status >= 500) throw ForumException(if (writing) ForumFailure.UNCERTAIN else ForumFailure.NETWORK)
                val limit = if (image) 3 * 1024 * 1024 else 4 * 1024 * 1024
                require((response.contentLength() ?: 0) <= limit)
                val body = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                val channel = response.bodyAsChannel()
                while (true) {
                    val count = channel.readAvailable(buffer)
                    if (count < 0) break
                    require(body.size() + count <= limit)
                    body.write(buffer, 0, count)
                }
                if (status !in 200..299) {
                    val detail = runCatching { Json.parseToJsonElement(body.toString("UTF-8")).jsonObject.a("errors").joinToString("\n") { it.jsonPrimitive.content }.take(500) }.getOrDefault("")
                    throw ForumException(ForumFailure.REJECTED, detail = detail)
                }
                val type = response.contentType()?.withoutParameters()?.toString()
                require(status == 204 || if (image) type in setOf("image/png", "image/jpeg", "image/webp", "image/gif") else type == "application/json")
                synchronized(lock) {
                    if (generation != epoch) throw CancellationException("Forum session changed")
                    response.headers.getAll(HttpHeaders.SetCookie).orEmpty().forEach { header ->
                        runCatching { HttpCookie.parse(header) }.getOrDefault(emptyList()).forEach { c ->
                            if (c.name in setOf("_t", "_forum_session") && (c.domain == null || c.domain.trimStart('.') == host) && (c.path == null || c.path == "/") && (origin.startsWith("http://127.0.0.1") || c.secure)) {
                                if (c.maxAge == 0L || c.value.isBlank()) {
                                    cookies.remove(c.name)
                                } else if (validCookie(c.value)) {
                                    cookies[c.name] = c.value to (if (c.maxAge > 0) System.currentTimeMillis() / 1000 + c.maxAge.coerceAtMost(315360000) else 0)
                                }
                            }
                        }
                    }
                }
                body.toByteArray()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ForumException) {
            throw e
        } catch (_: IllegalArgumentException) {
            throw ForumException(if (writing) ForumFailure.UNCERTAIN else ForumFailure.INVALID)
        } catch (_: Exception) {
            throw ForumException(if (writing) ForumFailure.UNCERTAIN else ForumFailure.NETWORK)
        }
    }
    private fun route(request: ForumRequest): Pair<String, List<Pair<String, String>>> = when (request) {
        ForumRequest.Categories -> "/site.json" to emptyList()
        ForumRequest.Tags -> "/tags.json" to emptyList()
        is ForumRequest.Category -> {
            require(request.id > 0)
            "/c/${request.id}/show.json" to emptyList()
        }
        is ForumRequest.Latest -> {
            require(request.page in 0..99 && (request.categoryId?.let { it > 0 } != false))
            (request.categoryId?.let { "/c/$it.json" } ?: "/latest.json") to listOf("page" to "${request.page}")
        }
        is ForumRequest.Feed -> {
            require(request.page in 0..99 && (request.tagId?.let { it > 0 } != false))
            (request.tagId?.let { "/tag/$it/l/${request.filter.route}.json" } ?: "/${request.filter.route}.json") to (listOf("page" to "${request.page}") + if (request.filter == ForumFilter.TOP) listOf("period" to request.period.route) else emptyList())
        }
        is ForumRequest.Search -> {
            require(request.query.isNotBlank() && request.query.length <= 200 && request.page in 1..100)
            "/search.json" to listOf("q" to request.query, "page" to "${request.page}")
        }
        is ForumRequest.Topic -> {
            require(request.id > 0 && request.postNumber > 0)
            (if (request.postNumber == 1) "/t/${request.id}.json" else "/t/${request.id}/${request.postNumber}.json") to emptyList()
        }
        is ForumRequest.Revision -> {
            require(request.postId > 0 && request.version > 1)
            "/posts/${request.postId}/revisions/${request.version}.json" to emptyList()
        }
        is ForumRequest.Posts -> {
            require(request.topicId > 0 && request.ids.size in 1..ForumLimits.BATCH && request.ids.all { it > 0 })
            "/t/${request.topicId}/posts.json" to request.ids.distinct().map { "post_ids[]" to "$it" }
        }
    }
}
internal fun JsonObject.s(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
internal fun JsonObject.n(key: String) = s(key).toLongOrNull() ?: 0L
internal fun JsonObject.b(key: String) = s(key) == "true"
internal fun JsonObject.a(key: String) = (get(key) as? JsonArray).orEmpty()
internal fun JsonObject.o(key: String) = (get(key) as? JsonObject) ?: JsonObject(emptyMap())
