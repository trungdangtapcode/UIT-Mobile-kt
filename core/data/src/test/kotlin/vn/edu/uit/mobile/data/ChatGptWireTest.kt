package vn.edu.uit.mobile.data

import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.http.*
import java.io.EOFException
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Real OkHttp engine over localhost sockets; no subscriber or university calls. */
class ChatGptWireTest {
    private class Server(private val truncate: (Int) -> Boolean) : AutoCloseable {
        val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 10000 }
        val calls = AtomicInteger()
        private val thread = Thread {
            while (!socket.isClosed) {
                val connection = try {
                    socket.accept()
                } catch (_: IOException) {
                    break
                }
                connection.use {
                    it.soTimeout = 5000
                    val input = it.getInputStream().bufferedReader()
                    val request = input.readLine()
                    check(request == "GET /v1/models HTTP/1.1")
                    while (!input.readLine().isNullOrEmpty()) { /* Consume test-only headers without logging them. */ }
                    val call = calls.incrementAndGet()
                    val body = """{"models":[{"slug":"wire-model","visibility":"list","capabilities":"${"x".repeat(700000)}"}]}""".toByteArray()
                    val output = it.getOutputStream()
                    output.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nx-request-id: wire-request\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    output.write(if (truncate(call)) body.copyOf(32768) else body)
                    output.flush()
                }
            }
        }.apply {
            isDaemon = true
            start()
        }

        override fun close() {
            socket.close()
            thread.join(2000)
        }
    }

    private suspend fun withSession(server: Server, block: suspend (ChatGptSession) -> Unit) {
        ChatGptTestSupport().use { f ->
            f.connect(f.session())
            studyAiHttpClient().use { client ->
                client.plugin(HttpSend).intercept { request ->
                    check(request.url.host == "api.openai.com" && request.url.encodedPath == "/v1/models")
                    request.url.protocol = URLProtocol.HTTP
                    request.url.host = "127.0.0.1"
                    request.url.port = server.socket.localPort
                    execute(request)
                }
                val session = ChatGptSession(f.storage, client)
                session.initialize()
                withTimeout(15000) { block(session) }
            }
        }
    }

    @Test fun productionEngineLoadsALargeCatalogWithoutMockEngine() = runBlocking {
        Server { false }.use { server ->
            withSession(server) { session ->
                session.reloadModels()
                assertNull(session.state.value.problem)
                assertEquals("wire-model", session.state.value.models.single().id)
                assertEquals(1, server.calls.get())
            }
        }
    }

    @Test fun oneInterruptedCatalogReadRecoversWithOneBoundedGetRetry() = runBlocking {
        Server { it == 1 }.use { server ->
            withSession(server) { session ->
                session.reloadModels()
                assertNull(session.state.value.problem)
                assertEquals("wire-model", session.state.value.models.single().id)
                assertEquals(2, server.calls.get())
                assertTrue(session.state.value.accounts.single().signedIn)
            }
        }
    }

    @Test fun repeatedBodyFailurePreservesHttpMetadataInsteadOfReportingStatusZero() = runBlocking {
        Server { true }.use { server ->
            withSession(server) { session ->
                session.reloadModels()
                val details = session.state.value.diagnostic!!
                assertEquals(200, details.status)
                assertEquals("response_body", details.phase)
                assertEquals("wire-request", details.requestId)
                assertEquals(2, details.attempt)
                assertTrue(details.responseBytes!! <= 32768)
                assertTrue(details.exceptionTypes.contains("java.net.ProtocolException"))
                assertFalse(details.protocol.isEmpty())
                assertTrue(session.state.value.accounts.single().signedIn)
                assertEquals(2, server.calls.get())
            }
        }
    }

    @Test fun wrappedIoCausesAreClassifiedWithoutPublishingExceptionMessages() {
        for ((cause, expected) in listOf(
            java.net.UnknownHostException("private-host") to "dns_failure",
            javax.net.ssl.SSLException("private-certificate") to "tls_failure",
            SocketException("private-address") to "socket_failure",
            EOFException("private-body") to "unexpected_eof"
        )) {
            val error = ChatGptTransport.failure(IOException("private-authorization", cause), "models")
            assertEquals(expected, error.diagnostic!!.code)
            assertFalse(error.diagnostic.toString().contains("private-"))
            assertEquals("connect_or_headers", error.diagnostic!!.phase)
        }
    }

    /** Explicit keyless GET only. This proves host transport, never phone/subscriber acceptance. */
    @Test fun publicOpenAiEndpointWithProductionEngineWhenExplicitlyEnabled() = runBlocking {
        assumeTrue(System.getenv("UIT_CHATGPT_NETWORK_LIVE") == "1")
        studyAiHttpClient().use { client ->
            withTimeout(30000) {
                client.prepareGet("https://api.openai.com/v1/models").execute { response ->
                    val body = ChatGptSession.boundedJson(response)
                    assertEquals(401, response.status.value)
                    assertTrue(body["error"] is JsonObject || body["detail"] is JsonPrimitive)
                    val record = buildJsonObject {
                        put("runtime", "Production Ktor OkHttp engine on Linux JVM; not Android")
                        put("endpoint", "https://api.openai.com/v1/models")
                        put("authorizationSent", false)
                        put("inferencePerformed", false)
                        put("status", response.status.value)
                        put("protocol", response.version.toString())
                    }
                    val output = Path.of("build/outputs/chatgpt-network")
                    Files.createDirectories(output)
                    Files.write(output.resolve("public-host.json"), record.toString().toByteArray())
                    Unit
                }
            }
        }
    }
}
