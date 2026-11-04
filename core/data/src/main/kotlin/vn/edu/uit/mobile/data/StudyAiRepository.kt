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
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.model.Course

interface StudyStorage {
    fun read(key: String): String?
    fun write(key: String, value: String)
    fun remove(key: String)
    fun removeAccount(accountId: String)
}

fun createStudyAiRepository(context: Context, accounts: AccountRepository): StudyAiRepository = ConnectedStudyAiRepository(StudyPrivateStorage(context), accounts)

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

    @Synchronized override fun removeAccount(accountId: String) {
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith(studyAccountPrefix(accountId)) }.forEach(editor::remove)
        check(editor.commit()) { "Unable to clear saved study tasks." }
    }
}

private fun studyAccountPrefix(accountId: String) = MessageDigest.getInstance("SHA-256").digest(accountId.toByteArray()).joinToString("") { "%02x".format(it) } + ":"

class ConnectedStudyAiRepository(
    private val storage: StudyStorage,
    private val accounts: AccountRepository,
    private val client: HttpClient = HttpClient(OkHttp) {
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
) : StudyAiRepository {
    private fun config() = storage.read("connection")?.let { Json.parseToJsonElement(it).jsonObject }
    private fun mode(config: JsonObject) = if (config["mode"]?.jsonPrimitive?.content == "DIRECT") StudyConnectionMode.DIRECT else StudyConnectionMode.GATEWAY
    override suspend fun connection(): StudyConnection = withContext(Dispatchers.IO) {
        val config = config() ?: return@withContext StudyConnection()
        val mode = mode(config)
        StudyConnection(
            if (mode == StudyConnectionMode.DIRECT) "https://api.openai.com" else config.getValue("endpoint").jsonPrimitive.content,
            !config["token"]?.jsonPrimitive?.content.isNullOrBlank(),
            mode,
            config["model"]?.jsonPrimitive?.content.orEmpty()
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
    }
    override suspend fun disconnect() = withContext(Dispatchers.IO) { storage.remove("connection") }
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
        val direct = mode(config) == StudyConnectionMode.DIRECT
        val streaming = direct && onText != null && request["mode"]?.jsonPrimitive?.content == "assistant"
        val endpoint = if (direct) "https://api.openai.com/v1/responses" else StudyAiRules.endpoint(config.getValue("endpoint").jsonPrimitive.content) + "/v1/study"
        return try {
            client.preparePost(endpoint) {
                header("Authorization", "Bearer ${config.getValue("token").jsonPrimitive.content}")
                header("Content-Type", "application/json")
                if (streaming) header("Accept", "text/event-stream")
                setBody(
                    if (direct) {
                        val model = config.getValue("model").jsonPrimitive.content
                        when (request["mode"]?.jsonPrimitive?.content) {
                            "assistant" -> JsonObject(AssistantProtocol.request(model, request) + ("stream" to JsonPrimitive(streaming)))
                            "knowledge" -> KnowledgeProtocol.request(model, request)
                            "practice" -> PracticeProtocol.request(model, request)
                            "tutor" -> TutorProtocol.request(model, request)
                            else -> OpenAiStudyProtocol.request(model, request)
                        }.toString()
                    } else {
                        request.toString()
                    }
                )
            }.execute { response ->
                val channel = response.bodyAsChannel()
                try {
                    if (response.status.value != 200) {
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
                            }
                        )
                    }
                    val data = if (streaming) {
                        require(response.headers["Content-Type"]?.substringBefore(';')?.trim() == "text/event-stream") { "Expected an SSE response" }
                        AssistantStream.read(channel, requireNotNull(onText))
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
                    answer
                } catch (e: CancellationException) {
                    throw e
                } catch (e: StudyAiException) {
                    throw e
                } catch (e: Exception) {
                    throw StudyAiException("The AI result could not be verified against your selected sources.")
                } finally {
                    channel.cancel(null)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: StudyAiException) {
            throw e
        } catch (e: Exception) {
            error(if (direct) "Unable to reach OpenAI. Check your internet connection." else "Unable to reach the study gateway. Check your connection and HTTPS settings.")
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
