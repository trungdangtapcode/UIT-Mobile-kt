package vn.edu.uit.mobile.data

import io.ktor.client.plugins.*
import io.ktor.http.*
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import vn.edu.uit.mobile.daa.AnnouncementRemote
import vn.edu.uit.mobile.domain.*

/** Explicit owner-operated diagnostic. Verified probe grant arrives only through stdin. */
object ChatGptSubscriberProbe {
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        var requests = 0
        var networkRequests = 0
        var media = ""
        var protocol = ""
        var hint = ""
        var validated = false
        var failure: ChatGptDiagnostic? = null
        var outcome = "failed"
        try {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val count = System.`in`.read(buffer)
                if (count < 0) break
                require(out.size() + count <= 65536)
                out.write(buffer, 0, count)
            }
            val grant = Json.parseToJsonElement(out.toString("UTF-8")).jsonObject
            fun value(name: String) = grant.getValue(name).jsonPrimitive.content
            require(grant["identityVerified"]?.jsonPrimitive?.boolean == true)
            val access = value("access")
            val subject = value("subject")
            val clientId = value("clientId")
            val scope = value("scope")
            require(scope.split(' ').containsAll(listOf("resource.invoke", "chatgpt.tokens.use.direct")))
            require(access.length in 20..32768 && access.all { it.code in 33..126 })
            require(grant.getValue("expiresAt").jsonPrimitive.long > System.currentTimeMillis() / 1000 + 60)
            fun redact(text: String): String = text.replace(access, "[token]").replace(subject, "[subject]").replace(clientId, "[client]")
                .replace(Regex("[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}"), "[jwt]")
                .replace(Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"), "[email]")
                .replace(Regex("https?://[^\\s]+"), "[url]").replace(Regex("[A-Za-z0-9_-]{60,}"), "[identifier]")
                .replace(Regex("[\\p{Cntrl}]"), " ").take(400)
            val storage = AssistantMemory()
            val account = "subscriber-diagnostic"
            storage.write("chatgpt_selected", account)
            storage.write(
                "chatgpt_accounts",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("id", account)
                            put("client_id", clientId)
                            put("subject", subject)
                            put("label", "Owner-authorized diagnostic")
                            put("access_token", access)
                            put("scope", scope)
                            put("expires_at", grant.getValue("expiresAt"))
                            put("session", UUID.randomUUID().toString())
                            put("welcome", false)
                            put("models", JsonArray(emptyList()))
                        }
                    )
                }.toString()
            )
            studyAiHttpClient {
                config {
                    retryOnConnectionFailure(false)
                    addNetworkInterceptor { chain ->
                        val response = chain.proceed(chain.request())
                        if (chain.request().url.encodedPath == "/v1/responses") {
                            networkRequests++
                            media = StudyResponseReader.mediaType(response.header("Content-Type"))
                            protocol = response.protocol.toString()
                            if (media != "text/event-stream") {
                                val json = runCatching { Json.parseToJsonElement(response.peekBody(262144).string()).jsonObject }.getOrNull()
                                val error = json?.get("error") as? JsonObject
                                val detail = ((error?.get("message") ?: json?.get("detail") ?: json?.get("message")) as? JsonPrimitive)?.contentOrNull
                                if (detail != null) hint = redact(detail)
                            }
                        }
                        response
                    }
                }
            }.use { client ->
                client.plugin(HttpSend).intercept { request ->
                    if (request.url.encodedPath == "/v1/responses") requests++
                    execute(request)
                }
                withTimeout(180000) {
                    val study = ConnectedStudyAiRepository(storage, AssistantNoAccounts(), client)
                    study.chatGpt.initialize()
                    study.chatGpt.reloadModels()
                    study.chatGpt.state.value.problem?.let { throw ChatGptException(it, study.chatGpt.state.value.diagnostic) }
                    if (study.chatGpt.state.value.models.none { it.id == "gpt-6.1-sol" }) throw StudyAiException("Requested model is unavailable", ChatGptDiagnostic(200, "requested_model_not_available", operation = "models"))
                    study.configureChatGpt("gpt-6.1-sol")
                    val notices = LocalAnnouncementRepository(
                        object : AnnouncementRemote {
                            override suspend fun list(source: AnnouncementSource, page: Int) = AnnouncementPage(emptyList(), false)
                            override suspend fun article(item: OfficialAnnouncement) = error("University reads are disabled")
                            override suspend fun image(url: String): ByteArray = error("University media reads are disabled")
                        },
                        AssistantMemory()
                    )
                    val repo = LocalAssistantRepository(AssistantMemory(), notices, study::assistantModel)
                    val id = repo.create("Subscriber diagnostic", AssistantScope())
                    repo.ask(id, "hello", "English")
                    val turn = repo.library.value.workspaces.single().turns.single()
                    AssistantRules.verify(turn.facts, turn.evidence)
                    validated = turn.status == AssistantTurnStatus.COMPLETE
                    outcome = if (validated) "completed" else "failed"
                }
            }
        } catch (e: ChatGptException) {
            failure = e.diagnostic
        } catch (e: StudyAiException) {
            failure = e.diagnostic
        } catch (_: TimeoutCancellationException) {
            failure = ChatGptDiagnostic(0, "native_check_timeout", operation = "responses")
        } catch (_: Exception) {
            failure = ChatGptDiagnostic(0, "native_check_setup_failed", operation = "setup")
        }
        val report = buildJsonObject {
            put("result", outcome)
            put("model", "gpt-6.1-sol")
            put("connectionMode", "CHATGPT")
            put("runtime", "Production Kotlin on Linux JVM; not physical Android")
            put("registration", "Owner-authorized Auth Probe registration; not phone registration")
            put("requests", requests)
            put("networkRequests", networkRequests)
            put("contentType", media)
            put("protocol", protocol)
            put("actualSubscriberInference", validated)
            put("inferenceAttempted", requests > 0)
            put("credentialWrittenToDisk", false)
            put("universityAccountUsed", false)
            if (hint.isNotEmpty()) put("privateServiceHint", hint)
            failure?.let { d ->
                put(
                    "diagnostic",
                    buildJsonObject {
                        put("operation", d.operation)
                        put("status", d.status)
                        put("code", d.code)
                        put("requestId", d.requestId)
                        put("bodyShape", d.bodyShape)
                        put("phase", d.phase)
                        put("parameter", d.parameter)
                        put("contentType", d.contentType)
                        put("contentEncoding", d.contentEncoding)
                        d.responseBytes?.let { put("responseBytes", it) }
                    }
                )
            }
        }
        println("UIT_NATIVE_REPORT $report")
    }
}
