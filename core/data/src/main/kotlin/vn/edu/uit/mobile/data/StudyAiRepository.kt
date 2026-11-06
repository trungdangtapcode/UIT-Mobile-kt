package vn.edu.uit.mobile.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.model.Course

interface StudyStorage {
    fun read(key: String): String?
    fun write(key: String, value: String)
    fun remove(key: String)
    fun removeAccount(accountId: String)
    fun clear(): Unit = error("Storage reset is unavailable")
}

fun createStudyAiRepository(context: Context, accounts: AccountRepository): StudyAiRepository = ConnectedStudyAiRepository(StudyPrivateStorage(context), accounts, chatGptReturnUri = "uitmobile-chatgpt://${context.packageName}/return")

class StudyPrivateStorage(context: Context, preferenceName: String = "study_ai", private val keyAlias: String = "uit.study.v1") : StudyStorage {
    private val prefs = context.applicationContext.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey(keyAlias, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        }.generateKey()
    }

    @Synchronized override fun read(key: String): String? {
        val encoded = prefs.getString(key, null) ?: return null
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        require(bytes.size >= 28) { "Study storage is unavailable. Reset the connection." }
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
        }
    }

    @Synchronized override fun write(key: String, value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray()), Base64.NO_WRAP)
        check(prefs.edit().putString(key, encrypted).commit()) { "Unable to save study settings." }
    }

    @Synchronized override fun remove(key: String) {
        check(prefs.edit().remove(key).commit()) { "Unable to clear study settings." }
    }

    @Synchronized override fun clear() {
        check(prefs.edit().clear().commit()) { "Unable to reset local storage." }
    }

    @Synchronized override fun removeAccount(accountId: String) {
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith(studyAccountPrefix(accountId)) }.forEach(editor::remove)
        check(editor.commit()) { "Unable to clear saved study tasks." }
    }
}

private fun studyAccountPrefix(accountId: String) = MessageDigest.getInstance("SHA-256").digest(accountId.toByteArray()).joinToString("") { "%02x".format(it) } + ":"

internal fun studyAiHttpClient(): HttpClient = HttpClient(OkHttp) {
    followRedirects = false
    install(HttpTimeout) {
        requestTimeoutMillis = 60000
        connectTimeoutMillis = 10000
        socketTimeoutMillis = 55000
    }
    engine {
        config {
            followRedirects(false)
            followSslRedirects(false)
        }
    }
}

class ConnectedStudyAiRepository(
    private val storage: StudyStorage,
    private val accounts: AccountRepository,
    private val client: HttpClient = studyAiHttpClient(),
    chatGptReturnUri: String = "uitmobile-chatgpt://vn.edu.uit.mobile/return"
) : StudyAiRepository {
    private val chatSession = ChatGptSession(storage, client, chatGptReturnUri)
    override val chatGpt: ChatGptConnector get() = chatSession
    private val connectionSignals = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val connectionChanges: Flow<StudyConnection> = merge(connectionSignals, chatSession.state.map { Unit }).map {
        try {
            connection()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            StudyConnection()
        }
    }.distinctUntilChanged()
    private fun config() = storage.read("connection")?.let { Json.parseToJsonElement(it).jsonObject }
    private fun mode(config: JsonObject) = when (config["mode"]?.jsonPrimitive?.content) {
        "CHATGPT" -> StudyConnectionMode.CHATGPT
        "DIRECT" -> StudyConnectionMode.DIRECT
        else -> StudyConnectionMode.GATEWAY
    }
    override suspend fun connection(): StudyConnection = withContext(Dispatchers.IO) {
        val config = config() ?: return@withContext StudyConnection()
        val mode = mode(config)
        StudyConnection(
            if (mode != StudyConnectionMode.GATEWAY) "https://api.openai.com" else config.getValue("endpoint").jsonPrimitive.content,
            if (mode == StudyConnectionMode.CHATGPT) chatSession.connected(config.getValue("chatgpt_account").jsonPrimitive.content) else !config["token"]?.jsonPrimitive?.content.isNullOrBlank(),
            mode,
            config["model"]?.jsonPrimitive?.content.orEmpty(),
            config["account_label"]?.jsonPrimitive?.content.orEmpty()
        )
    }
    override suspend fun configure(endpoint: String, token: String) = withContext(Dispatchers.IO) {
        val target = StudyAiRules.endpoint(endpoint)
        require(token.length in 32..512 && token.all { it.code in 33..126 }) { "Enter a gateway access token of 32 to 512 printable characters." }
        require(!token.startsWith("sk-")) { "Enter a gateway token here. Use Direct OpenAI for your personal API key." }
        storage.write(
            "connection",
            buildJsonObject {
                put("mode", "GATEWAY")
                put("endpoint", target)
                put("token", token)
            }.toString()
        )
        connectionSignals.tryEmit(Unit)
        Unit
    }
    override suspend fun configureDirect(apiKey: String, model: String) = withContext(Dispatchers.IO) {
        require(apiKey.length in 24..1024 && apiKey.all { it.code in 33..126 }) { "Enter your personal OpenAI API key without spaces." }
        require(model.matches(Regex("[A-Za-z0-9._-]{1,100}"))) { "Enter an available OpenAI model ID that supports Responses and structured outputs." }
        storage.write(
            "connection",
            buildJsonObject {
                put("mode", "DIRECT")
                put("model", model)
                put("token", apiKey)
            }.toString()
        )
        connectionSignals.tryEmit(Unit)
        Unit
    }
    override suspend fun configureChatGpt(model: String) = withContext(Dispatchers.IO) {
        val (account, label) = chatSession.selected(model)
        storage.write(
            "connection",
            buildJsonObject {
                put("mode", "CHATGPT")
                put("chatgpt_account", account)
                put("account_label", label)
                put("model", model)
                put("selection", java.util.UUID.randomUUID().toString())
            }.toString()
        )
        connectionSignals.tryEmit(Unit)
        Unit
    }
    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        val expected = config()
        if (expected != null && mode(expected) == StudyConnectionMode.CHATGPT) {
            chatSession.selectAccount(expected.getValue("chatgpt_account").jsonPrimitive.content)
            chatSession.signOut()
        }
        if (config() == expected) storage.remove("connection")
        connectionSignals.tryEmit(Unit)
        Unit
    }
    private suspend fun requireAccount(course: Course?) {
        if (course == null) return
        check(accounts.accounts.first().any { it.id == course.accountId }) { "This course account was removed. Open the course again." }
    }
    override suspend fun generate(course: Course, mode: String, sources: List<StudySource>, question: String, language: String): StudyAnswer = withContext(Dispatchers.IO) {
        StudyAiRules.validate(sources, mode, question)
        require(language in setOf("English", "Vietnamese")) { "Choose a supported answer language." }
        val request = buildJsonObject {
            put("version", 1)
            put("mode", mode)
            put("question", question)
            put("language", language)
            put(
                "sources",
                JsonArray(
                    sources.map { source ->
                        buildJsonObject {
                            put("id", source.id)
                            put("title", source.title)
                            put("text", source.text)
                        }
                    }
                )
            )
        }
        exchange(course, request) { data, direct ->
            (if (direct) OpenAiStudyProtocol.directResult(data) else OpenAiStudyProtocol.gatewayResult(data)).also { StudyAiRules.verify(it, sources, mode) }
        }
    }
    override suspend fun generatePractice(course: Course, sources: List<StudySource>, options: PracticeOptions): PracticeGeneration = withContext(Dispatchers.IO) {
        PracticeRules.request(sources, options)
        exchange(course, PracticeProtocol.payload(sources, options)) { data, direct -> PracticeProtocol.result(data, direct).also { PracticeRules.generation(it, sources, options) } }
    }
    override suspend fun knowledge(course: Course, sources: List<StudySource>, focus: String, language: String): KnowledgeGeneration = withContext(Dispatchers.IO) {
        KnowledgeRules.request(sources, focus, language)
        exchange(course, KnowledgeProtocol.payload(sources, focus, language)) { data, direct -> KnowledgeProtocol.result(data, direct, focus, language).also { KnowledgeRules.generation(it, sources, focus, language) } }
    }
    override suspend fun tutor(course: Course?, request: TutorRequest): TutorResult = withContext(Dispatchers.IO) {
        TutorRules.request(request)
        require(course != null || request.sources.isEmpty()) { "Choose a course before adding course context." }
        exchange(course, TutorProtocol.payload(request)) { data, direct -> TutorProtocol.result(data, direct, request).also { TutorRules.result(it, request) } }
    }
    override suspend fun embed(course: Course, texts: List<String>, config: PassageEmbeddingConfig): PassageEmbeddingBatch = withContext(Dispatchers.IO) {
        config.validate()
        require(texts.size in 1..PassageRules.BATCH && texts.all { it.isNotBlank() && it.length <= 3000 && it.toByteArray(Charsets.UTF_8).size <= 8000 } && texts.sumOf { it.toByteArray(Charsets.UTF_8).size } <= 64000)
        requireAccount(course)
        val expected = this@ConnectedStudyAiRepository.config() ?: error("Configure a personal AI connection")
        require(mode(expected) == StudyConnectionMode.DIRECT) { "This gateway does not support embeddings; local BM25 remains available" }
        val payload = buildJsonObject {
            put("model", config.model)
            put("dimensions", config.dimensions)
            put("encoding_format", "float")
            put("input", JsonArray(texts.map(::JsonPrimitive)))
        }
        client.preparePost("https://api.openai.com/v1/embeddings") {
            header("Authorization", "Bearer ${expected.getValue("token").jsonPrimitive.content}")
            header("Content-Type", "application/json")
            setBody(payload.toString())
        }.execute { response ->
            val channel = response.bodyAsChannel()
            try {
                check(this@ConnectedStudyAiRepository.config() == expected) { "AI connection changed" }
                require(response.status.value == 200) { "Embedding service unavailable (HTTP ${response.status.value})" }
                val bytes = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = channel.readAvailable(buffer, 0, buffer.size)
                    if (count < 0) break
                    require(bytes.size() + count <= 2 * 1024 * 1024)
                    bytes.write(buffer, 0, count)
                }
                val root = Json.parseToJsonElement(bytes.toString("UTF-8")).jsonObject
                require(root.getValue("model").jsonPrimitive.content == config.model)
                val data = root.getValue("data").jsonArray.map { it.jsonObject }.sortedBy { it.getValue("index").jsonPrimitive.int }
                require(data.size == texts.size && data.map { it.getValue("index").jsonPrimitive.int } == texts.indices.toList())
                val vectors = data.map { value -> PassageRules.normalize(value.getValue("embedding").jsonArray.map { it.jsonPrimitive.float }, config.dimensions) }
                val tokens = root.getValue("usage").jsonObject.getValue("total_tokens").jsonPrimitive.long.also { require(it in 0..300000) }
                requireAccount(course)
                check(this@ConnectedStudyAiRepository.config() == expected) { "AI connection changed" }
                PassageEmbeddingBatch(vectors, tokens, config.model, response.headers["x-request-id"].orEmpty().take(200))
            } finally {
                channel.cancel(null)
            }
        }
    }
    internal fun assistantModel(): AssistantModel {
        val expected = config() ?: error("Configure an AI connection")
        return object : AssistantModel {
            override suspend fun step(payload: JsonObject): JsonObject = stream(payload) {}
            override suspend fun stream(payload: JsonObject, onText: (String) -> Unit): JsonObject {
                check(config() == expected) { "AI connection changed" }
                return assistantStep(payload) { text ->
                    check(config() == expected) { "AI connection changed" }
                    onText(text)
                }.also { check(config() == expected) { "AI connection changed" } }
            }
        }
    }
    internal suspend fun assistantStep(request: JsonObject, onText: (String) -> Unit = {}): JsonObject = withContext(Dispatchers.IO) {
        require(request["mode"]?.jsonPrimitive?.content == "assistant")
        exchange(null, request, onText) { data, _ ->
            AssistantProtocol.output(data)
            data
        }
    }
    private suspend fun <T> exchange(course: Course?, request: JsonObject, onText: ((String) -> Unit)? = null, decode: (JsonObject, Boolean) -> T): T {
        requireAccount(course)
        val config = config() ?: error("Configure an AI connection before generating an answer.")
        val plan = mode(config) == StudyConnectionMode.CHATGPT
        val direct = mode(config) != StudyConnectionMode.GATEWAY
        val lease = if (plan) {
            try {
                chatSession.lease(config.getValue("chatgpt_account").jsonPrimitive.content)
            } catch (e: ChatGptException) {
                if (this@ConnectedStudyAiRepository.config() == config) chatSession.report(e)
                throw e
            }
        } else {
            null
        }
        val streaming = plan || (direct && onText != null && request["mode"]?.jsonPrimitive?.content == "assistant")
        val endpoint = if (direct) "https://api.openai.com/v1/responses" else StudyAiRules.endpoint(config.getValue("endpoint").jsonPrimitive.content) + "/v1/study"
        var observed: HttpResponse? = null
        fun metadata(code: String = "", phase: String = ""): ChatGptDiagnostic = ChatGptDiagnostic(
            observed?.status?.value ?: 0,
            code,
            observed?.headers?.get("x-request-id").orEmpty().takeIf { it.matches(Regex("[A-Za-z0-9._-]{0,160}")) }.orEmpty(),
            operation = "responses",
            phase = phase,
            protocol = observed?.version?.toString().orEmpty()
        )
        if (plan) chatSession.beginResponse()
        return try {
            client.preparePost(endpoint) {
                check(this@ConnectedStudyAiRepository.config() == config && (lease == null || chatSession.current(lease))) { "AI connection changed" }
                header("Authorization", "Bearer ${lease?.token ?: config.getValue("token").jsonPrimitive.content}")
                header("Content-Type", "application/json")
                if (streaming) header("Accept", "text/event-stream")
                setBody(
                    if (direct) {
                        val model = config.getValue("model").jsonPrimitive.content
                        val payload = when (request["mode"]?.jsonPrimitive?.content) {
                            "assistant" -> JsonObject(AssistantProtocol.request(model, request) + ("stream" to JsonPrimitive(streaming)))
                            "knowledge" -> KnowledgeProtocol.request(model, request)
                            "practice" -> PracticeProtocol.request(model, request)
                            "tutor" -> TutorProtocol.request(model, request)
                            else -> OpenAiStudyProtocol.request(model, request)
                        }
                        (if (plan) ChatGptPlanProtocol.request(payload) else payload).toString()
                    } else {
                        request.toString()
                    }
                )
            }.execute { response ->
                observed = response
                if (response.status.value != 200) {
                    val failure = ChatGptSession.failure(response)
                    val diagnostic = failure.diagnostic?.copy(operation = "responses", phase = "http_error", protocol = response.version.toString())
                    if (plan) throw ChatGptException(failure.problem, diagnostic)
                    throw StudyAiException(
                        if (direct) {
                            when (response.status.value) {
                                401, 403 -> "OpenAI rejected the API key or project access. Check your personal key and model permissions."
                                429 -> "Your OpenAI quota or rate limit was reached. Check your API account and try later."
                                400, 404 -> "OpenAI rejected the request or model. Choose an available model supporting Responses and structured outputs."
                                else -> "OpenAI could not complete this request. Try again later."
                            }
                        } else {
                            when (response.status.value) {
                                401, 403 -> "The gateway token was rejected. Update the connection."
                                429 -> "The study gateway is busy or its usage limit was reached. Try later."
                                400, 413 -> "The gateway rejected this selection. Choose less text and try again."
                                else -> "The gateway could not produce a supported answer. Try again later."
                            }
                        },
                        diagnostic
                    )
                }
                val channel = response.bodyAsChannel()
                try {
                    val data = if (streaming) {
                        if (response.headers["Content-Type"]?.substringBefore(';')?.trim() != "text/event-stream") throw StudyAiException("Expected an SSE response", metadata("unexpected_stream_content_type", "response_headers"))
                        AssistantStream.read(channel, metadata(), config["model"]?.jsonPrimitive?.content) { text ->
                            check(this@ConnectedStudyAiRepository.config() == config && (lease == null || chatSession.current(lease))) { "AI connection changed" }
                            onText?.invoke(text)
                        }
                    } else {
                        val bytes = ByteArrayOutputStream()
                        val buffer = ByteArray(4096)
                        while (true) {
                            val count = channel.readAvailable(buffer, 0, buffer.size)
                            if (count < 0) break
                            require(bytes.size() + count <= if (direct || request["mode"]?.jsonPrimitive?.content == "assistant") 262144 else 65536) { "The AI response exceeded the supported size." }
                            bytes.write(buffer, 0, count)
                        }
                        Json.parseToJsonElement(bytes.toString("UTF-8")).jsonObject
                    }
                    val answer = decode(data, direct)
                    requireAccount(course)
                    check(this@ConnectedStudyAiRepository.config() == config && (lease == null || chatSession.current(lease))) { "AI connection changed" }
                    answer
                } catch (e: CancellationException) {
                    throw e
                } catch (e: StudyAiException) {
                    throw e
                } catch (e: ChatGptException) {
                    throw e
                } catch (e: IOException) {
                    currentCoroutineContext().ensureActive()
                    val details = ChatGptTransport.failure(e, "responses", response.status.value, metadata().requestId, protocol = response.version.toString()).diagnostic
                    throw StudyAiException("The AI response stream was interrupted.", details)
                } catch (e: Exception) {
                    throw StudyAiException("The AI result could not be verified against your selected sources.", metadata("response_decode_failed", "response_decode"))
                } finally {
                    channel.cancel(null)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: StudyAiException) {
            val details = e.diagnostic
            if (plan && details != null) {
                val failure = ChatGptException(ChatGptSession.responseProblem(details), details)
                if (this@ConnectedStudyAiRepository.config() == config) chatSession.report(failure)
                throw failure
            }
            throw e
        } catch (e: ChatGptException) {
            if (this@ConnectedStudyAiRepository.config() == config) chatSession.report(e)
            throw e
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive()
            val failure = ChatGptTransport.failure(e, "responses", observed?.status?.value ?: 0, metadata().requestId, protocol = metadata().protocol)
            if (plan) {
                if (this@ConnectedStudyAiRepository.config() == config) chatSession.report(failure)
                throw failure
            }
            throw StudyAiException("Unable to reach the AI service.", failure.diagnostic)
        } catch (e: Exception) {
            throw StudyAiException(if (direct) "Unable to reach OpenAI. Check your internet connection." else "Unable to reach the study gateway. Check your connection and HTTPS settings.", metadata("request_failed", "request"))
        }
    }
    private fun taskKey(course: Course, assignmentId: Long) = "${studyAccountPrefix(course.accountId)}${course.id}:$assignmentId"
    override suspend fun tasks(course: Course, assignmentId: Long): List<StudyTask> = withContext(Dispatchers.IO) {
        requireAccount(course)
        storage.read(taskKey(course, assignmentId))?.let { raw -> Json.parseToJsonElement(raw).jsonArray.map { StudyTask(it.jsonObject.getValue("text").jsonPrimitive.content, it.jsonObject.getValue("checked").jsonPrimitive.boolean) } }.orEmpty()
    }
    override suspend fun saveTasks(course: Course, assignmentId: Long, tasks: List<StudyTask>) = withContext(Dispatchers.IO) {
        requireAccount(course)
        require(tasks.size <= 30 && tasks.all { it.text.isNotBlank() && it.text.length <= 1500 }) { "Keep at most 30 tasks, each under 1,500 characters." }
        storage.write(
            taskKey(course, assignmentId),
            JsonArray(
                tasks.map {
                    buildJsonObject {
                        put("text", it.text)
                        put("checked", it.checked)
                    }
                }
            ).toString()
        )
        if (accounts.accounts.first().none { it.id == course.accountId }) {
            storage.removeAccount(course.accountId)
            error("This course account was removed.")
        }
    }
}
