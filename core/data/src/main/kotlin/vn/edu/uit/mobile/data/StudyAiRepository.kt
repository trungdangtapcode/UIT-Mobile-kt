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

fun createStudyAiRepository(context: Context, accounts: AccountRepository): StudyAiRepository = GatewayStudyAiRepository(StudyPrivateStorage(context), accounts)

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

class GatewayStudyAiRepository(
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
    override suspend fun connection(): StudyConnection = withContext(Dispatchers.IO) {
        val config = config()
        StudyConnection(config?.get("endpoint")?.jsonPrimitive?.content.orEmpty(), !config?.get("token")?.jsonPrimitive?.content.isNullOrBlank())
    }
    override suspend fun configure(endpoint: String, token: String) = withContext(Dispatchers.IO) {
        val target = StudyAiRules.endpoint(endpoint)
        require(token.length in 32..512 && token.all { it.code in 33..126 }) { "Enter a gateway access token of 32 to 512 printable characters." }
        storage.write(
            "connection",
            buildJsonObject {
                put("endpoint", target)
                put("token", token)
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
        val config = config() ?: error("Configure a study gateway before generating an answer.")
        val endpoint = StudyAiRules.endpoint(config.getValue("endpoint").jsonPrimitive.content)
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
            client.post("$endpoint/v1/study") {
                header("Authorization", "Bearer ${config.getValue("token").jsonPrimitive.content}")
                header("Content-Type", "application/json")
                setBody(request.toString())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error("Unable to reach the study gateway. Check your connection and HTTPS settings.")
        }
        check(response.status.value == 200) {
            when (response.status.value) {
                401, 403 -> "The gateway token was rejected. Update the connection."
                429 -> "The study gateway is busy or its usage limit was reached. Try later."
                400, 413 -> "The gateway rejected this selection. Choose less text and try again."
                else -> "The gateway could not produce a supported answer. Try again later."
            }
        }
        return@withContext try {
            val bytes = ByteArrayOutputStream()
            val channel = response.bodyAsChannel()
            val buffer = ByteArray(4096)
            while (true) {
                val count = channel.readAvailable(buffer, 0, buffer.size)
                if (count < 0) break
                require(bytes.size() + count <= 65536) { "The gateway response exceeded the supported size." }
                bytes.write(buffer, 0, count)
            }
            val data = Json.parseToJsonElement(bytes.toString("UTF-8")).jsonObject
            require(data.getValue("version").jsonPrimitive.int == 1) { "Update the gateway to a supported version." }
            val answer = StudyAnswer(
                data.getValue("model").jsonPrimitive.content,
                data.getValue("generatedAt").jsonPrimitive.long,
                data.getValue("items").jsonArray.map { value ->
                    val item = value.jsonObject
                    StudyItem(
                        item.getValue("kind").jsonPrimitive.content,
                        item.getValue("text").jsonPrimitive.content,
                        item.getValue("citations").jsonArray.map { citation ->
                            val c = citation.jsonObject
                            StudyCitation(c.getValue("sourceId").jsonPrimitive.content, c.getValue("quote").jsonPrimitive.content)
                        }
                    )
                }
            )
            StudyAiRules.verify(answer, sources, mode)
            requireAccount(course)
            answer
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error("The gateway result could not be verified against your selected sources.")
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
