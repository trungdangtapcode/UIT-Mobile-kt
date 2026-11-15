package vn.edu.uit.mobile.data

import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.model.UmaSession

/** Explicit authenticated read-only chat test. Never enabled by routine builds/CI. */
class UmaOwnerLiveTest {
    @Test fun actualOwnerSessionQuestionsUseTheProductionKotlinClient() = runBlocking {
        assumeTrue(System.getenv("UIT_UMA_OWNER_LIVE") == "1")
        val bytes = System.`in`.readNBytes(16385)
        require(bytes.size <= 16384)
        val grant = runCatching { Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject }.getOrElse { error("Invalid private grant") }
        val session = UmaSession(grant.getValue("cookie").jsonPrimitive.content, grant.getValue("csrf").jsonPrimitive.content)
        val output = File(requireNotNull(System.getenv("UIT_UMA_PRIVATE_OUTPUT"))).apply { mkdirs() }
        val statuses = mutableListOf<Int>()
        val methods = mutableListOf<String>()
        var capture: String? = null
        val client = HttpClient(OkHttp) {
            followRedirects = false
            install(HttpTimeout) {
                requestTimeoutMillis = 90000
                connectTimeoutMillis = 15000
                socketTimeoutMillis = 90000
            }
            engine {
                config {
                    followRedirects(false)
                    followSslRedirects(false)
                    retryOnConnectionFailure(false)
                    addNetworkInterceptor { chain ->
                        val r = chain.request()
                        check(r.url.scheme == "https" && r.url.host == "uma.uit.edu.vn" && r.url.port == 443)
                        check(r.method == "GET" && r.url.encodedPath == "/uma/nhung/" || r.method == "POST" && r.url.encodedPath == "/uma/api/hoi/")
                        methods += "${r.method} ${r.url.encodedPath}"
                        val response = chain.proceed(r)
                        statuses += response.code
                        if (r.method == "POST") capture = response.peekBody(1024 * 1024).string()
                        response
                    }
                }
            }
        }
        val remote = UmaClient(client)
        val connected = remote.connect(session)
        assertTrue(connected.bootstrap.available.containsAll(setOf(UmaSource.REGULATIONS, UmaSource.PERSONAL)))
        val question = "Điều kiện xét học bổng khuyến khích học tập là gì?"
        val first = remote.ask(connected.session, UmaProtocol.request(question, setOf(UmaSource.REGULATIONS), emptyList(), null))
        assertTrue(first.answer.text.isNotBlank())
        File(output, "regulation-response-private.json").writeText(requireNotNull(capture))
        val personal = remote.ask(first.session, UmaProtocol.request("Điểm trung bình tích lũy hiện tại của tôi là bao nhiêu?", setOf(UmaSource.PERSONAL), emptyList(), null))
        assertTrue(personal.answer.text.isNotBlank())
        File(output, "personal-response-private.json").writeText(requireNotNull(capture))
        val report = buildJsonObject {
            put("recordedAt", java.time.Instant.now().toString())
            put("runtime", "Production Kotlin HTTP/parser on Linux JVM; not physical Android")
            put("ownerAccountUsed", true)
            put("identityVerifiedFromBootstrap", connected.bootstrap.identity.id.isNotBlank())
            put("personalRecordsReturned", personal.answer.result == "answered")
            put("regulationResult", first.answer.result)
            put("regulationCitationCount", first.answer.citations.size)
            put("personalStructuredDisplayPresent", personal.answer.display.isNotEmpty())
            put("academicAccountMutations", false)
            put("umaQuestionLoggingPossible", true)
            put("openAiCredentialsUsed", false)
            put("fixturesUsed", false)
            put("networkStatuses", JsonArray(statuses.map(::JsonPrimitive)))
            put("routes", JsonArray(methods.distinct().map(::JsonPrimitive)))
        }
        File(output, "safe-report.json").writeText(report.toString())
        println("UIT_UMA_LIVE_REPORT $report")
        client.close()
    }
}
