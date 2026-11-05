package vn.edu.uit.mobile.data

import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URI
import kotlinx.coroutines.*
import vn.edu.uit.mobile.domain.*

/** A short-lived listener on IPv4 loopback only; no exported code/token callback. */
internal class ChatGptLoopback(private val state: String, private val returnUri: String) : AutoCloseable {
    init {
        require(returnUri.matches(Regex("uitmobile-chatgpt://[A-Za-z0-9._]+/return")))
    }
    private val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 500 }
    val redirect = "http://127.0.0.1:${server.localPort}/auth/callback"
    override fun close() = server.close()
    suspend fun await(): Map<String, String> = withContext(Dispatchers.IO) {
        withTimeout(180000) {
            while (true) {
                ensureActive()
                val socket = try {
                    server.accept()
                } catch (_: SocketTimeoutException) {
                    continue
                }
                socket.use { peer ->
                    peer.soTimeout = 2000
                    val input = peer.getInputStream()
                    val header = StringBuilder()
                    var callback: Map<String, String>? = null
                    try {
                        val started = System.nanoTime()
                        while (!header.endsWith("\r\n\r\n") && header.length < 20000) {
                            ensureActive()
                            require(System.nanoTime() - started < 2000000000L)
                            val c = input.read()
                            if (c < 0) break
                            header.append(c.toChar())
                        }
                        require(header.endsWith("\r\n\r\n") && header.length < 20000)
                        val lines = header.toString().split("\r\n")
                        val request = lines.first().split(' ')
                        require(request.size == 3 && request[0] == "GET" && request[1].startsWith("/auth/callback?"))
                        require(lines.count { it.startsWith("Host:", true) } == 1)
                        require(lines.first { it.startsWith("Host:", true) }.substringAfter(':').trim() == "127.0.0.1:${server.localPort}")
                        val target = URI(request[1])
                        require(target.path == "/auth/callback" && target.rawFragment == null)
                        val values = ChatGptOAuth.parameters(target.rawQuery.orEmpty())
                        require(ChatGptOAuth.same(values["state"].orEmpty(), state))
                        require(values["error"] != null || values["code"]?.length in 1..4096)
                        callback = values
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // Ignore unrelated/malformed requests without consuming this OAuth attempt.
                    }
                    val ok = callback != null
                    val html = if (ok) "<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width'><title>UIT Mobile</title><h1>Return to UIT Mobile</h1><p>Authorization received. Finish connecting in the app.</p><a href='$returnUri'>Return to UIT Mobile</a>" else "Invalid callback. Return to UIT Mobile and try again."
                    val body = html.toByteArray(Charsets.UTF_8)
                    val response = "HTTP/1.1 ${if (ok) "200 OK" else "400 Bad Request"}\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\nCache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nContent-Security-Policy: default-src 'none'; style-src 'none'; base-uri 'none'; frame-ancestors 'none'\r\nConnection: close\r\n\r\n"
                    runCatching {
                        peer.getOutputStream().apply {
                            write(response.toByteArray(Charsets.US_ASCII))
                            write(body)
                            flush()
                        }
                    }
                    callback?.let { return@withTimeout it }
                }
            }
            @Suppress("UNREACHABLE_CODE")
            error("Callback closed")
        }
    }
}
