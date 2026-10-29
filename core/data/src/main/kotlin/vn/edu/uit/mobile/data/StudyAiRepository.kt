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

class StudyPrivateStorage(context: Context) : StudyStorage {
    private val prefs = context.applicationContext.getSharedPreferences("study_ai", Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey("uit.study.v1", null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("uit.study.v1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
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
    private suspend fun requireAccount(course: Course) {
        check(accounts.accounts.first().any { it.id == course.accountId }) { "This course account was removed. Open the course again." }
    }
    override suspend fun generate(course: Course, mode: String, sources: List<StudySource>, question: String, language: String): StudyAnswer = withContext(Dispatchers.IO) {
        requireAccount(course)
        StudyAiRules.validate(sources, mode, question)
        require(language in setOf("English", "Vietnamese")) { "Choose a supported answer language." }
        val config = config() ?: error("Configure an AI connection before generating an answer.")
        val direct = mode(config) == StudyConnectionMode.DIRECT
        val endpoint = if (direct) "https://api.openai.com/v1/responses" else StudyAiRules.endpoint(config.getValue("endpoint").jsonPrimitive.content) + "/v1/study"
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
        val response = try {
            client.post(endpoint) {
                header("Authorization", "Bearer ${config.getValue("token").jsonPrimitive.content}")
                header("Content-Type", "application/json")
                setBody(if (direct) OpenAiStudyProtocol.request(config.getValue("model").jsonPrimitive.content, request).toString() else request.toString())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error(if (direct) "Unable to reach OpenAI. Check your internet connection." else "Unable to reach the study gateway. Check your connection and HTTPS settings.")
        }
        return@withContext try {
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
            val bytes = ByteArrayOutputStream()
            val channel = response.bodyAsChannel()
            val buffer = ByteArray(4096)
            while (true) {
                val count = channel.readAvailable(buffer, 0, buffer.size)
                if (count < 0) break
                require(bytes.size() + count <= if (direct) 262144 else 65536) { "The AI response exceeded the supported size." }
                bytes.write(buffer, 0, count)
            }
            val data = Json.parseToJsonElement(bytes.toString("UTF-8")).jsonObject
            val answer = if (direct) OpenAiStudyProtocol.directResult(data) else OpenAiStudyProtocol.gatewayResult(data)
            StudyAiRules.verify(answer, sources, mode)
            requireAccount(course)
            answer
        } catch (e: CancellationException) {
            throw e
        } catch (e: StudyAiException) {
            throw e
        } catch (e: Exception) {
            error("The AI result could not be verified against your selected sources.")
        } finally {
            response.bodyAsChannel().cancel(null)
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
