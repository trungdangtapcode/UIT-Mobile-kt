package vn.edu.uit.mobile.data

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.*
import java.net.HttpURLConnection
import java.net.URI
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import vn.edu.uit.mobile.domain.*

/** Isolated OAuth server fixture. No actual subscriber identity or university requests. */
internal class ChatGptTestSupport : AutoCloseable {
    class Memory : StudyStorage {
        val data = ConcurrentHashMap<String, String>()
        override fun read(key: String) = data[key]
        override fun write(key: String, value: String) {
            data[key] = value
        }
        override fun remove(key: String) {
            data.remove(key)
        }
        override fun removeAccount(accountId: String) = Unit
    }
    val storage = Memory()
    val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    var now = System.currentTimeMillis() / 1000
    var nonce = ""
    var scopes = ChatGptOAuth.SCOPES
    var subject = "fixture-subject"
    var refreshCode: String? = null
    var refreshStatus = 200
    var revokeStatus = 200
    var corruptNonce = false
    var refreshCalls = 0
    var beforeRefresh: (suspend () -> Unit)? = null
    var beforeRevoke: (suspend () -> Unit)? = null
    var modelCalls = 0
    var modelStatus = 200
    var modelBody = """{"models":[{"slug":"fixture-model","display_name":"Fixture model","visibility":"list"},{"slug":"hidden","display_name":"Hidden","visibility":"hide"}]}"""
    var beforeModels: (suspend () -> Unit)? = null
    val requests = mutableListOf<String>()
    val authorizationQueries = mutableListOf<Map<String, String>>()
    var response: String? = null
    var responseStatus = 200
    var beforeResponse: (suspend () -> Unit)? = null
    var responseRequest: JsonObject? = null
    var responseBearer: String? = null
    val client = HttpClient(
        MockEngine { request ->
            val path = request.url.encodedPath
            synchronized(requests) { requests += request.url.toString() }
            check(request.headers[HttpHeaders.Cookie] == null)
            when (request.url.host + path) {
                "auth.openai.com/.well-known/jwks.json" -> respond(jwks().toString(), headers = headersOf(HttpHeaders.ContentType, "application/json"))
                "auth.openai.com/api/accounts/oauth/token" -> {
                    val form = when (val body = request.body) {
                        is TextContent -> body.text
                        is OutgoingContent.ByteArrayContent -> body.bytes().toString(Charsets.UTF_8)
                        else -> error("Unexpected form body")
                    }
                    val refresh = "grant_type=refresh_token" in form
                    if (refresh) refreshCalls++
                    if (refresh) beforeRefresh?.invoke()
                    if (refresh && refreshCode != null) {
                        respond("{\"error\":\"$refreshCode\"}", HttpStatusCode.fromValue(refreshStatus))
                    } else {
                        respond(tokens(refresh).toString(), headers = headersOf(HttpHeaders.ContentType, "application/json"))
                    }
                }
                "api.openai.com/v1/models" -> {
                    modelCalls++
                    beforeModels?.invoke()
                    respond(modelBody, HttpStatusCode.fromValue(modelStatus), headers = headersOf(HttpHeaders.ContentType to listOf("application/json"), "x-request-id" to listOf("fixture-model-request")))
                }
                "auth.openai.com/api/accounts/oauth/revoke" -> {
                    beforeRevoke?.invoke()
                    respond("", HttpStatusCode.fromValue(revokeStatus))
                }
                "api.openai.com/v1/responses" -> {
                    responseRequest = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                    responseBearer = request.headers[HttpHeaders.Authorization]
                    beforeResponse?.invoke()
                    respond(requireNotNull(response), HttpStatusCode.fromValue(responseStatus), headers = headersOf(HttpHeaders.ContentType to listOf(if (responseStatus == 200) "text/event-stream" else "application/json"), "x-request-id" to listOf("fixture-response-request")))
                }
                else -> error("Unexpected fixture route")
            }
        }
    ) { followRedirects = false }
    fun session() = ChatGptSession(storage, client, clock = { now })
    private fun b64(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    fun jwt(aud: String = "oaiapp_fixture", expectedNonce: String = nonce, expires: Long = now + 3600, issuer: String = ChatGptOAuth.ISSUER, subject: String = this.subject): String {
        val header = b64("""{"alg":"RS256","kid":"test-key"}""".toByteArray())
        val claims = buildJsonObject {
            put("iss", issuer)
            put("sub", subject)
            put("aud", aud)
            put("nonce", expectedNonce)
            put("iat", now)
            put("exp", expires)
            put("email", "student@example.test")
        }
        val content = "$header.${b64(claims.toString().toByteArray())}"
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(pair.private)
            update(content.toByteArray())
            sign()
        }
        return "$content.${b64(signature)}"
    }
    fun jwks(): JsonObject {
        val key = pair.public as RSAPublicKey
        return buildJsonObject {
            put(
                "keys",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("kid", "test-key")
                            put("kty", "RSA")
                            put("alg", "RS256")
                            put("use", "sig")
                            put("n", b64(key.modulus.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()))
                            put("e", b64(key.publicExponent.toByteArray()))
                        }
                    )
                }
            )
        }
    }
    private fun tokens(refresh: Boolean) = buildJsonObject {
        put("access_token", if (refresh) "fixture-refreshed-access-token" else "fixture-initial-access-token")
        put("refresh_token", if (refresh) "fixture-rotated-refresh-token" else "fixture-initial-refresh-token")
        put("id_token", jwt(expectedNonce = if (corruptNonce) "wrong" else nonce))
        put("token_type", "Bearer")
        put("scope", scopes)
        put("expires_in", 3600)
    }
    suspend fun connect(connector: ChatGptConnector, account: String? = null, badStateFirst: Boolean = false, denied: Boolean = false) = coroutineScope {
        connector.signIn({ url ->
            val args = ChatGptOAuth.parameters(URI(url).rawQuery)
            authorizationQueries += args
            nonce = args.getValue("nonce")
            launch(Dispatchers.IO) {
                fun callback(state: String): Int {
                    val values = mutableMapOf("state" to state, "client_id" to "oaiapp_fixture")
                    if (denied) values["error"] = "access_denied" else values["code"] = "fixture-authorization-code"
                    val conn = URI(args.getValue("redirect_uri") + "?" + ChatGptOAuth.query(values)).toURL().openConnection() as HttpURLConnection
                    conn.connectTimeout = 3000
                    conn.readTimeout = 5000
                    return try {
                        conn.responseCode
                    } finally {
                        conn.disconnect()
                    }
                }
                if (badStateFirst) check(callback("incorrect-state") == 400)
                check(callback(args.getValue("state")) == 200)
            }
        }, account)
    }
    override fun close() = client.close()
}
