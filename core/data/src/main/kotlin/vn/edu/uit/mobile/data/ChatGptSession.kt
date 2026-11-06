package vn.edu.uit.mobile.data

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import vn.edu.uit.mobile.domain.*

/** Per-installation OAuth registrations, protected by the existing Keystore storage. */
internal class ChatGptSession(
    private val storage: StudyStorage,
    private val client: HttpClient,
    private val returnUri: String = "uitmobile-chatgpt://vn.edu.uit.mobile/return",
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 }
) : ChatGptConnector {
    private val mutable = MutableStateFlow(ChatGptState())
    override val state = mutable.asStateFlow()
    private val mutex = Mutex()
    private val signing = Mutex()
    private val epoch = AtomicLong()
    private val signingOut = ConcurrentHashMap.newKeySet<String>()

    @Volatile private var listener: ChatGptLoopback? = null
    private fun records(): List<JsonObject> = storage.read("chatgpt_accounts")?.let { Json.parseToJsonElement(it).jsonArray.map(JsonElement::jsonObject) }.orEmpty()
    private fun text(record: JsonObject, key: String) = record[key]?.jsonPrimitive?.contentOrNull.orEmpty()
    private fun models(record: JsonObject): List<ChatGptModel> = record["models"]?.jsonArray?.map { ChatGptModel(it.jsonObject.getValue("id").jsonPrimitive.content, it.jsonObject.getValue("name").jsonPrimitive.content) }.orEmpty()
    private fun allowed(record: JsonObject) = text(record, "scope").split(' ').containsAll(listOf(ChatGptOAuth.PLAN, "resource.invoke"))
    private fun save(record: JsonObject) {
        val all = records().filter { text(it, "id") != text(record, "id") } + record
        require(all.size <= 10)
        storage.write("chatgpt_accounts", JsonArray(all).toString())
    }
    private fun publish(problem: ChatGptProblem? = null, busy: Boolean = false, diagnostic: ChatGptDiagnostic? = null, loadingModels: Boolean = false) {
        val all = records()
        val selected = storage.read("chatgpt_selected")?.takeIf { id -> all.any { text(it, "id") == id } }
        val record = all.firstOrNull { text(it, "id") == selected }
        mutable.value = ChatGptState(
            all.map { ChatGptAccount(text(it, "id"), text(it, "label"), text(it, "access_token").isNotEmpty(), allowed(it)) },
            selected,
            record?.let(::models).orEmpty(),
            busy,
            problem,
            record?.get("welcome")?.jsonPrimitive?.booleanOrNull == true,
            diagnostic,
            loadingModels
        )
    }
    override suspend fun initialize() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val previous = state.value
            publish(previous.problem, previous.busy, previous.diagnostic, previous.loadingModels)
        }
    }
    override fun cancelSignIn() {
        epoch.incrementAndGet()
        runCatching { listener?.close() }
        listener = null
    }
    override suspend fun signIn(openBrowser: suspend (String) -> Unit, accountId: String?, enablePlan: Boolean) = withContext(Dispatchers.IO) {
        if (!signing.tryLock()) return@withContext
        val attempt = epoch.incrementAndGet()
        var callback: ChatGptLoopback? = null
        try {
            val old = mutex.withLock {
                publish(busy = true)
                if (accountId == null) null else records().single { text(it, "id") == accountId }
            }
            val issued = old?.let { text(it, "client_id") } ?: storage.read("chatgpt_pending_client")
            val host = storage.read("chatgpt_host") ?: "urn:uuid:${UUID.randomUUID()}".also { storage.write("chatgpt_host", it) }
            val state = ChatGptOAuth.random()
            val nonce = ChatGptOAuth.random()
            val verifier = ChatGptOAuth.random()
            callback = ChatGptLoopback(state, returnUri)
            listener = callback
            val params = linkedMapOf(
                "client_id" to (issued ?: "dynamic_agent_client"), "ext_agent_host_id" to host,
                "response_type" to "code", "redirect_uri" to callback.redirect, "scope" to ChatGptOAuth.SCOPES,
                "resource" to ChatGptOAuth.RESOURCE, "state" to state, "nonce" to nonce,
                "code_challenge_method" to "S256", "code_challenge" to ChatGptOAuth.challenge(verifier)
            )
            if (issued == null) params["agent_name_hint"] = "UIT Mobile"
            // Omit the optional ID-token hint so identity tokens never enter Android Intent URLs.
            if (enablePlan) params["prompt"] = "consent"
            openBrowser(ChatGptOAuth.AUTHORIZE + "?" + ChatGptOAuth.query(params))
            val values = callback.await()
            checkAttempt(attempt)
            if (values["error"] != null) throw ChatGptException(ChatGptProblem.CANCELLED)
            val clientId = values["client_id"] ?: issued ?: throw ChatGptException(ChatGptProblem.VALIDATION)
            require(clientId != "dynamic_agent_client" && clientId.matches(Regex("[A-Za-z0-9_-]{6,200}")))
            require(issued == null || clientId == issued)
            if (old == null) storage.write("chatgpt_pending_client", clientId)
            val result = token(mapOf("grant_type" to "authorization_code", "client_id" to clientId, "code" to values.getValue("code"), "code_verifier" to verifier, "redirect_uri" to callback.redirect, "resource" to ChatGptOAuth.RESOURCE))
            val idToken = result.getValue("id_token").jsonPrimitive.content
            val keys = getJson(ChatGptOAuth.JWKS)
            val identity = try {
                ChatGptOAuth.identity(idToken, keys, clientId, nonce, clock())
            } catch (_: Exception) {
                throw ChatGptException(ChatGptProblem.VALIDATION)
            }
            require(old == null || text(old, "subject") == identity.getValue("sub").jsonPrimitive.content)
            val id = old?.let { text(it, "id") } ?: UUID.randomUUID().toString()
            val label = identity["email"]?.jsonPrimitive?.contentOrNull?.take(100)
                ?: identity["name"]?.jsonPrimitive?.contentOrNull?.take(100) ?: "ChatGPT"
            val base = buildJsonObject {
                put("id", id)
                put("client_id", clientId)
                put("subject", identity.getValue("sub"))
                put("label", "$label · ${clientId.takeLast(6)}")
                put("session", UUID.randomUUID().toString())
                put("welcome", old?.get("welcome_seen")?.jsonPrimitive?.booleanOrNull != true)
                put("welcome_seen", old?.get("welcome_seen") ?: JsonPrimitive(false))
            }
            val record = credentials(base, result, idToken)
            mutex.withLock {
                checkAttempt(attempt)
                save(record)
                storage.write("chatgpt_selected", id)
                storage.remove("chatgpt_pending_client")
                publish()
            }
            if (allowed(record)) reloadModels()
        } catch (_: TimeoutCancellationException) {
            mutable.update { it.copy(problem = ChatGptProblem.TIMEOUT, busy = false) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ChatGptException) {
            report(e)
        } catch (_: IllegalArgumentException) {
            mutable.update { it.copy(problem = ChatGptProblem.VALIDATION, busy = false) }
        } catch (_: Exception) {
            mutable.update { it.copy(problem = if (attempt != epoch.get()) ChatGptProblem.CANCELLED else ChatGptProblem.NETWORK, busy = false) }
        } finally {
            runCatching { callback?.close() }
            if (listener === callback) listener = null
            mutable.update { it.copy(busy = false) }
            signing.unlock()
        }
    }
    private fun checkAttempt(attempt: Long) {
        if (attempt != epoch.get()) throw ChatGptException(ChatGptProblem.CANCELLED)
    }
    internal fun report(error: ChatGptException) {
        mutable.update { it.copy(problem = error.problem, diagnostic = error.diagnostic, busy = false) }
    }
    internal fun beginResponse() {
        mutable.update { if (it.diagnostic?.operation == "responses") it.copy(problem = null, diagnostic = null) else it }
    }
    private fun credentials(old: JsonObject, result: JsonObject, idToken: String): JsonObject {
        require(result["token_type"]?.jsonPrimitive?.content?.equals("Bearer", true) == true)
        val access = result.getValue("access_token").jsonPrimitive.content
        require(access.length in 20..32768 && access.all { it.code in 33..126 })
        val refresh = result["refresh_token"]?.jsonPrimitive?.contentOrNull ?: text(old, "refresh_token")
        require(refresh.length <= 32768 && refresh.all { it.code in 33..126 })
        val seconds = result.getValue("expires_in").jsonPrimitive.long.also { require(it in 1..86400) }
        return JsonObject(
            old + mapOf(
                "access_token" to JsonPrimitive(access),
                "refresh_token" to JsonPrimitive(refresh),
                "id_token" to JsonPrimitive(idToken),
                "scope" to (result["scope"] ?: old["scope"] ?: JsonPrimitive("")),
                "expires_at" to JsonPrimitive(clock() + seconds),
                "earliest_refresh_at" to (result["earliest_refresh_at"] ?: JsonPrimitive(0))
            )
        )
    }
    override suspend fun selectAccount(id: String) = withContext(Dispatchers.IO) {
        cancelSignIn()
        mutex.withLock {
            require(records().any { text(it, "id") == id })
            storage.write("chatgpt_selected", id)
            publish()
        }
    }
    override suspend fun acknowledgeWelcome() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val previous = state.value
            records().firstOrNull { text(it, "id") == state.value.selected }?.let { save(JsonObject(it + mapOf("welcome" to JsonPrimitive(false), "welcome_seen" to JsonPrimitive(true)))) }
            publish(previous.problem, previous.busy, previous.diagnostic, previous.loadingModels)
        }
    }
    internal class Lease(val account: String, val session: String, val token: String)
    internal suspend fun lease(id: String): Lease = mutex.withLock {
        if (id in signingOut) throw ChatGptException(ChatGptProblem.REAUTH)
        var record = records().firstOrNull { text(it, "id") == id } ?: throw ChatGptException(ChatGptProblem.REAUTH)
        if (!allowed(record)) throw ChatGptException(ChatGptProblem.ACCESS)
        if (text(record, "access_token").isEmpty()) throw ChatGptException(ChatGptProblem.REAUTH)
        if ((record["expires_at"]?.jsonPrimitive?.longOrNull ?: 0) <= clock() + 60) {
            val refresh = text(record, "refresh_token").takeIf(String::isNotEmpty) ?: throw ChatGptException(ChatGptProblem.REAUTH)
            val earliest = record["earliest_refresh_at"]?.jsonPrimitive?.longOrNull ?: 0
            if (earliest > clock()) throw ChatGptException(ChatGptProblem.UNAVAILABLE)
            try {
                val result = token(mapOf("grant_type" to "refresh_token", "client_id" to text(record, "client_id"), "refresh_token" to refresh, "resource" to ChatGptOAuth.RESOURCE))
                val idToken = result["id_token"]?.jsonPrimitive?.contentOrNull
                if (idToken != null) {
                    val identity = ChatGptOAuth.identity(idToken, getJson(ChatGptOAuth.JWKS), text(record, "client_id"), null, clock())
                    require(identity.getValue("sub").jsonPrimitive.content == text(record, "subject"))
                }
                record = credentials(record, result, idToken ?: text(record, "id_token"))
                save(record)
                val previous = state.value
                publish(busy = previous.busy, loadingModels = previous.loadingModels)
            } catch (e: ChatGptException) {
                if (e.problem == ChatGptProblem.REAUTH) {
                    save(withoutTokens(record))
                    publish(e.problem, diagnostic = e.diagnostic)
                }
                throw e
            }
        }
        if (!allowed(record)) throw ChatGptException(ChatGptProblem.ACCESS)
        if (id in signingOut) throw ChatGptException(ChatGptProblem.REAUTH)
        Lease(id, text(record, "session"), text(record, "access_token"))
    }
    internal fun current(lease: Lease): Boolean = lease.account !in signingOut && records().any { text(it, "id") == lease.account && text(it, "session") == lease.session && text(it, "access_token").isNotEmpty() }
    internal fun selected(model: String): Pair<String, String> {
        val id = state.value.selected ?: throw ChatGptException(ChatGptProblem.REAUTH)
        val record = records().single { text(it, "id") == id }
        require(allowed(record) && text(record, "access_token").isNotEmpty())
        require(models(record).any { it.id == model })
        return id to text(record, "label")
    }
    internal fun connected(id: String): Boolean = id !in signingOut && records().any { text(it, "id") == id && text(it, "access_token").isNotEmpty() && allowed(it) }
    override suspend fun reloadModels() = withContext(Dispatchers.IO) {
        try {
            mutable.update { it.copy(busy = true, loadingModels = true, problem = null, diagnostic = null) }
            val id = state.value.selected ?: throw ChatGptException(ChatGptProblem.REAUTH)
            val lease = lease(id)
            val result = getJson("${ChatGptOAuth.RESOURCE}/models", lease.token, "models", ChatGptModelCatalog.MAX_BYTES)
            val entries = ChatGptModelCatalog.parse(result)
            mutex.withLock {
                if (state.value.selected != id || !current(lease)) return@withContext
                val record = records().single { text(it, "id") == id }
                save(
                    JsonObject(
                        record + (
                            "models" to JsonArray(
                                entries.map {
                                    buildJsonObject {
                                        put("id", it.id)
                                        put("name", it.name)
                                    }
                                }
                            )
                            )
                    )
                )
                if (entries.isEmpty()) {
                    publish(ChatGptProblem.NO_MODELS, diagnostic = ChatGptDiagnostic(200, "no_visible_models", bodyShape = "models-array", operation = "models"))
                } else {
                    publish()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ChatGptException) {
            report(e)
        } catch (_: Exception) {
            report(ChatGptException(ChatGptProblem.UNAVAILABLE, ChatGptDiagnostic(0, "model_load_failed", operation = "models")))
        } finally {
            mutable.update { it.copy(busy = false, loadingModels = false) }
        }
    }
    private fun withoutTokens(record: JsonObject): JsonObject = JsonObject(record - setOf("access_token", "refresh_token", "id_token", "session", "models", "scope"))
    override suspend fun signOut() {
        cancelSignIn()
        val generation = epoch.get()
        val target = state.value.selected ?: return
        signingOut.add(target)
        mutable.update { it.copy(busy = true) }
        val record = withContext(NonCancellable + Dispatchers.IO) {
            try {
                mutex.withLock {
                    val item = records().firstOrNull { text(it, "id") == target } ?: return@withContext null
                    save(withoutTokens(item))
                    val config = storage.read("connection")?.let { Json.parseToJsonElement(it).jsonObject }
                    if (config?.get("chatgpt_account")?.jsonPrimitive?.content == text(item, "id")) storage.remove("connection")
                    publish()
                    item
                }
            } finally {
                signingOut.remove(target)
                mutable.update { it.copy(busy = false) }
            }
        } ?: return
        // Until a successful response arrives, remote revocation remains unconfirmed,
        // including when the settings screen or its coroutine is closed mid-request.
        if (generation == epoch.get()) mutable.update { it.copy(problem = ChatGptProblem.REVOCATION) }
        withContext(Dispatchers.IO) {
            if (text(record, "refresh_token").isNotEmpty()) {
                val revoked = try {
                    withTimeoutOrNull(10000) {
                        client.submitForm(
                            ChatGptOAuth.REVOKE,
                            Parameters.build {
                                append("token", text(record, "refresh_token"))
                                append("token_type_hint", "refresh_token")
                                append("client_id", text(record, "client_id"))
                            }
                        ).status.value == 200
                    } ?: false
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    false
                }
                if (revoked && generation == epoch.get()) mutable.update { it.copy(problem = null) }
            }
        }
    }
    private suspend fun token(values: Map<String, String>): JsonObject = client.preparePost(ChatGptOAuth.TOKEN) {
        setBody(FormDataContent(Parameters.build { values.forEach { (k, v) -> append(k, v) } }))
    }.execute { response ->
        val result = if (response.status.value == 200) boundedJson(response, operation = "token") else errorBody(response)
        if (response.status.value != 200) {
            val code = result["error"]?.let { if (it is JsonPrimitive) it.content else it.jsonObject["code"]?.jsonPrimitive?.content }.orEmpty()
            throw ChatGptException(
                if (code in setOf("invalid_grant", "invalid_refresh_token", "token_expired", "refresh_token_expired", "refresh_token_invalidated", "refresh_token_reused")) {
                    ChatGptProblem.REAUTH
                } else if (response.status.value >= 500) {
                    ChatGptProblem.UNAVAILABLE
                } else {
                    ChatGptProblem.ACCESS
                },
                ChatGptDiagnostic(response.status.value, code.takeIf { it.matches(Regex("[a-zA-Z0-9_-]{1,100}")) }.orEmpty(), response.headers["x-request-id"].orEmpty().takeIf { it.matches(Regex("[a-zA-Z0-9._-]{0,160}")) }.orEmpty(), "oauth-error")
            )
        }
        result
    }
    private suspend fun getJson(url: String, bearer: String? = null, operation: String = "identity_keys", maxBytes: Int = 262144): JsonObject {
        val attempts = if (operation == "models") 2 else 1
        for (attempt in 1..attempts) {
            try {
                return getJsonOnce(url, bearer, operation, maxBytes)
            } catch (e: ChatGptException) {
                currentCoroutineContext().ensureActive()
                if (attempt == attempts || !ChatGptTransport.retryCatalog(e)) {
                    throw ChatGptException(e.problem, e.diagnostic?.copy(attempt = attempt))
                }
                delay(300)
            }
        }
        error("Catalog request exhausted")
    }
    private suspend fun getJsonOnce(url: String, bearer: String?, operation: String, maxBytes: Int): JsonObject {
        var observed: HttpResponse? = null
        return try {
            client.prepareGet(url) { bearer?.let { header(HttpHeaders.Authorization, "Bearer $it") } }.execute { response ->
                observed = response
                if (response.status.value != 200) {
                    val error = failure(response)
                    throw ChatGptException(error.problem, error.diagnostic?.copy(operation = operation))
                }
                boundedJson(response, maxBytes, operation)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive()
            throw ChatGptTransport.failure(
                e,
                operation,
                observed?.status?.value ?: 0,
                observed?.headers?.get("x-request-id").orEmpty().takeIf { it.matches(Regex("[a-zA-Z0-9._-]{0,160}")) }.orEmpty(),
                protocol = observed?.version?.toString().orEmpty()
            )
        }
    }
    internal companion object {
        private suspend fun errorBody(response: HttpResponse): JsonObject = try {
            boundedJson(response)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            buildJsonObject {}
        }
        suspend fun failure(response: HttpResponse): ChatGptException {
            val body = errorBody(response)
            val code = (body["error"] as? JsonObject)?.get("code")?.jsonPrimitive?.content?.takeIf { it.matches(Regex("[a-zA-Z0-9_-]{1,100}")) }.orEmpty()
            val parameter = (body["error"] as? JsonObject)?.get("param")?.jsonPrimitive?.contentOrNull?.takeIf { it.matches(Regex("[A-Za-z0-9_.\\[\\]-]{1,160}")) }.orEmpty()
            val requestId = response.headers["x-request-id"]?.takeIf { it.matches(Regex("[a-zA-Z0-9._-]{1,160}")) }.orEmpty()
            val shape = when {
                body["error"] is JsonObject -> "error-object"
                body.containsKey("detail") -> "detail"
                body.isEmpty() -> "empty-or-non-json"
                else -> "other-json"
            }
            return ChatGptException(httpProblem(response.status.value, body), ChatGptDiagnostic(response.status.value, code, requestId, shape, parameter = parameter))
        }
        fun httpProblem(status: Int, body: JsonObject): ChatGptProblem {
            val code = (body["error"] as? JsonObject)?.get("code")?.jsonPrimitive?.content
            return when {
                status == 429 || code == "subscription_sharing_usage_limit_exceeded" -> ChatGptProblem.QUOTA
                code in setOf("subscription_sharing_user_not_eligible", "chatpass_v2_scope_not_authorized", "chatpass_v2_invalid_authorization_context") -> ChatGptProblem.ACCESS
                code in setOf("subscription_sharing_route_not_supported", "model_not_found") -> ChatGptProblem.UNSUPPORTED
                status == 401 -> ChatGptProblem.REAUTH
                status == 403 -> ChatGptProblem.ACCESS
                status == 400 || code == "subscription_sharing_unsupported_capability" -> ChatGptProblem.UNSUPPORTED
                else -> ChatGptProblem.UNAVAILABLE
            }
        }
        fun responseProblem(diagnostic: ChatGptDiagnostic): ChatGptProblem = when {
            diagnostic.code == "request_timeout" -> ChatGptProblem.TIMEOUT
            diagnostic.code in setOf("dns_failure", "tls_failure", "network_timeout", "network_unreachable", "connect_failure", "http2_stream_reset", "http2_shutdown", "unexpected_eof", "http_protocol", "socket_failure", "network_interrupted", "network_io", "stream_ended_without_completion") -> ChatGptProblem.NETWORK
            diagnostic.phase in setOf("response_decode", "response_assembly") || diagnostic.code in setOf("response_incomplete", "response_refused", "invalid_stream_json", "stream_byte_limit", "stream_frame_limit", "stream_draft_limit", "stream_result_limit") -> ChatGptProblem.RESPONSE
            else -> httpProblem(diagnostic.status, buildJsonObject { put("error", buildJsonObject { put("code", diagnostic.code) }) })
        }
        suspend fun boundedJson(response: HttpResponse, maxBytes: Int = 262144, operation: String = "response"): JsonObject {
            val stream = response.bodyAsChannel()
            val out = ByteArrayOutputStream()
            fun invalid(code: String, size: Int) = ChatGptException(
                ChatGptProblem.RESPONSE,
                ChatGptDiagnostic(response.status.value, code, response.headers["x-request-id"].orEmpty().takeIf { it.matches(Regex("[a-zA-Z0-9._-]{0,160}")) }.orEmpty(), "unreadable-json", operation, size)
            )
            try {
                val buffer = ByteArray(4096)
                while (true) {
                    val count = stream.readAvailable(buffer, 0, buffer.size)
                    if (count < 0) break
                    if (out.size() + count > maxBytes) throw invalid("response_too_large", out.size() + count)
                    out.write(buffer, 0, count)
                }
                return try {
                    Json.parseToJsonElement(out.toString("UTF-8")).jsonObject
                } catch (_: IllegalArgumentException) {
                    throw invalid("invalid_json", out.size())
                }
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                throw ChatGptTransport.failure(
                    e,
                    operation,
                    response.status.value,
                    response.headers["x-request-id"].orEmpty().takeIf { it.matches(Regex("[a-zA-Z0-9._-]{0,160}")) }.orEmpty(),
                    out.size(),
                    response.version.toString()
                )
            } finally {
                stream.cancel(null)
            }
        }
    }
}
