package vn.edu.uit.mobile.network

import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import java.io.*
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import vn.edu.uit.mobile.common.*
import vn.edu.uit.mobile.model.*

interface PortalTransport : Closeable {
    override fun close() {}
    val session: PortalSession
    suspend fun request(path: String, method: HttpMethod = HttpMethod.Get, configure: HttpRequestBuilder.() -> Unit = {}): ByteArray
    suspend fun download(url: String, destination: File): DownloadedFile
}
class OriginGuardPlugin(private val baseUrl: String) {
    private val base = URI(baseUrl)
    fun validate(value: String) {
        val uri = runCatching { URI(value) }.getOrElse { throw OriginRejectedException() }
        if (uri.scheme != "https" || uri.userInfo != null || MoodleUrlResolver.origin(uri) != MoodleUrlResolver.origin(base)) throw OriginRejectedException()
        val prefix = base.path.trimEnd('/')
        if (prefix.isNotEmpty() && uri.path != prefix && !uri.path.startsWith("$prefix/")) throw OriginRejectedException()
        if (Regex("/(?:login(?:/|$)|user/login(?:/|$))", RegexOption.IGNORE_CASE).containsMatchIn(uri.path)) throw SessionExpiredException()
    }
}
object LoginPageSnifferPlugin {
    private val pattern = Regex("<form\\b[^>]*(?:login|signin)|name\\s*=\\s*[\"']?(?:password|logintoken)\\b|id\\s*=\\s*[\"']?login(?:btn|form)\\b|<title[^>]*>[^<]*(?:log\\s*in|sign\\s*in)", RegexOption.IGNORE_CASE)
    fun inspect(prefix: ByteArray) {
        if (pattern.containsMatchIn(prefix.copyOfRange(0, minOf(8192, prefix.size)).toString(Charsets.UTF_8))) throw SessionExpiredException()
    }
}
class SafePortalTransport(override val session: PortalSession, private val client: HttpClient = newHttpClient()) :
    PortalTransport,
    Closeable {
    private val http = client.config {
        followRedirects = false
        expectSuccess = false
    }
    private val guard = OriginGuardPlugin(session.account.baseUrl)
    override suspend fun request(path: String, method: HttpMethod, configure: HttpRequestBuilder.() -> Unit): ByteArray = transfer(path, method, configure) { channel, prefix ->
        val output = ByteArrayOutputStream()
        output.write(prefix)
        val buffer = ByteArray(32768)
        while (true) {
            val count = channel.readAvailable(buffer)
            if (count == -1) break
            if (output.size() + count > MAX_PREVIEW_BYTES) throw InvalidResponseException("Response exceeds 25 MB")
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    }
    override suspend fun download(url: String, destination: File): DownloadedFile = withContext(Dispatchers.IO) {
        val clean = UrlSanitizer.credentialFreeUrl(MoodleUrlResolver.resolve(session.account.baseUrl, url)) ?: throw OriginRejectedException()
        destination.parentFile?.mkdirs()
        val temporary = File(destination.path + ".part-" + UUID.randomUUID())
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            transfer(clean, HttpMethod.Get, {}) { channel, prefix ->
                temporary.outputStream().use { output ->
                    output.write(prefix)
                    digest.update(prefix)
                    val buffer = ByteArray(65536)
                    while (true) {
                        val n = channel.readAvailable(buffer)
                        if (n == -1) break
                        output.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                    }
                    output.fd.sync()
                }
            }
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            DownloadedFile(destination.path, digest.digest().joinToString("") { "%02x".format(it) })
        } finally {
            temporary.delete()
        }
    }
    private suspend fun <T> transfer(
        path: String,
        method: HttpMethod,
        configure: HttpRequestBuilder.() -> Unit,
        consume: suspend (ByteReadChannel, ByteArray) -> T
    ): T {
        var target = MoodleUrlResolver.resolve(session.account.baseUrl, path)
        var verb = method
        var includeBody = true
        for (redirect in 0..5) {
            guard.validate(target)
            var next: String? = null
            var result: T? = null
            var complete = false
            http.prepareRequest(target) {
                this.method = verb
                if (includeBody) configure()
                guard.validate(url.buildString())
                headers.remove(HttpHeaders.Cookie)
                header(HttpHeaders.Cookie, session.cookie)
            }.execute { response ->
                if (response.status.value in setOf(301, 302, 303, 307, 308)) {
                    if (verb != HttpMethod.Get && verb != HttpMethod.Head) {
                        if (response.status.value in setOf(307, 308)) throw InvalidResponseException("Refusing to replay a write after a redirect")
                        verb = HttpMethod.Get
                        includeBody = false
                    }
                    val location = response.headers[HttpHeaders.Location] ?: throw InvalidResponseException("Redirect has no destination")
                    next = MoodleUrlResolver.redirect(session.account.baseUrl, target, location)
                    guard.validate(next!!)
                    response.bodyAsChannel().cancel()
                } else {
                    if (response.status.value == 401) throw SessionExpiredException()
                    if (response.status.value !in 200..299) throw PortalException("http${response.status.value}", "Portal request failed")
                    val channel = response.bodyAsChannel()
                    try {
                        val first = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (first.size() < 8192) {
                            val n = channel.readAvailable(buffer, 0, 8192 - first.size())
                            if (n == -1) break
                            first.write(buffer, 0, n)
                        }
                        val prefix = first.toByteArray()
                        LoginPageSnifferPlugin.inspect(prefix)
                        result = consume(channel, prefix)
                        complete = true
                    } finally {
                        channel.cancel()
                    }
                }
            }
            if (complete) {
                @Suppress("UNCHECKED_CAST")
                return result as T
            }
            target = next ?: throw InvalidResponseException("Invalid redirect")
        }
        throw InvalidResponseException("Too many redirects")
    }
    override fun close() {
        http.close()
        client.close()
    }
    companion object {
        const val MAX_PREVIEW_BYTES = 25 * 1024 * 1024
        fun newHttpClient(): HttpClient = HttpClient(OkHttp) {
            followRedirects = false
            engine {
                config {
                    followRedirects(false)
                    followSslRedirects(false)
                }
            }
            install(HttpTimeout) {
                requestTimeoutMillis = 120000
                connectTimeoutMillis = 15000
                socketTimeoutMillis = 60000
            }
        }
    }
}
