package vn.edu.uit.mobile.data

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CancellationException
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.model.UmaSession

data class UmaConnected(val session: UmaSession, val bootstrap: UmaProtocol.Bootstrap)
data class UmaReceived(val session: UmaSession, val answer: UmaAnswer)
interface UmaRemote {
    suspend fun connect(session: UmaSession): UmaConnected
    suspend fun ask(session: UmaSession, body: String): UmaReceived
}

/** Only two fixed UMA routes. No redirects, cookie forwarding, automatic retries or mutation tools. */
class UmaClient(
    private val http: HttpClient = studyAiHttpClient { config { retryOnConnectionFailure(false) } }.config {
        expectSuccess = false
        followRedirects = false
    }
) : UmaRemote {
    override suspend fun connect(session: UmaSession): UmaConnected {
        val (fresh, body) = exchange(session, false)
        val bootstrap = UmaProtocol.bootstrap(body)
        return UmaConnected(fresh.copy(csrf = bootstrap.csrf), bootstrap)
    }
    override suspend fun ask(session: UmaSession, body: String): UmaReceived {
        val (fresh, raw) = exchange(session, true, body)
        return UmaReceived(fresh, UmaProtocol.answer(raw))
    }
    private suspend fun exchange(session: UmaSession, post: Boolean, body: String = ""): Pair<UmaSession, String> {
        UmaProtocol.session(session)
        try {
            return http.prepareRequest(if (post) "${UmaUrls.ORIGIN}/uma/api/hoi/" else UmaUrls.EMBED) {
                method = if (post) HttpMethod.Post else HttpMethod.Get
                header(HttpHeaders.Cookie, session.cookie)
                header(HttpHeaders.Accept, if (post) "application/json" else "text/html")
                if (post) {
                    header("X-CSRFToken", session.csrf)
                    header(HttpHeaders.Origin, UmaUrls.ORIGIN)
                    header(HttpHeaders.Referrer, UmaUrls.EMBED)
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            }.execute { response ->
                when (response.status.value) {
                    in 300..399, 401 -> throw UmaException(UmaFailure.EXPIRED)
                    403 -> throw UmaException(UmaFailure.CSRF)
                    429 -> throw UmaException(UmaFailure.RATE_LIMIT)
                    !in 200..299 -> throw UmaException(UmaFailure.SERVER)
                }
                if (post && response.contentType()?.match(ContentType.Application.Json) != true) throw UmaException(UmaFailure.EXPIRED)
                val channel = response.bodyAsChannel()
                val output = ByteArrayOutputStream()
                try {
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = channel.readAvailable(buffer)
                        if (count < 0) break
                        if (output.size() + count > 1024 * 1024) throw UmaException(UmaFailure.RESPONSE)
                        output.write(buffer, 0, count)
                    }
                } finally {
                    channel.cancel()
                }
                val cookies = session.cookie.split(';').associate { it.trim().substringBefore('=') to it.trim().substringAfter('=') }.toMutableMap()
                response.headers.getAll(HttpHeaders.SetCookie).orEmpty().forEach { header ->
                    val pair = header.substringBefore(';')
                    val name = pair.substringBefore('=')
                    if (name in cookies) cookies[name] = pair.substringAfter('=', "")
                }
                val csrf = cookies.getValue("ums2_csrftoken")
                val fresh = UmaSession(cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }, if (csrf != session.cookie.split(';').first { it.trim().startsWith("ums2_csrftoken=") }.substringAfter('=')) csrf else session.csrf)
                try {
                    UmaProtocol.session(fresh)
                } catch (_: IllegalArgumentException) {
                    throw UmaException(UmaFailure.EXPIRED)
                }
                fresh to output.toString("UTF-8")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: UmaException) {
            throw e
        } catch (_: Exception) {
            throw UmaException(UmaFailure.NETWORK)
        }
    }
}
