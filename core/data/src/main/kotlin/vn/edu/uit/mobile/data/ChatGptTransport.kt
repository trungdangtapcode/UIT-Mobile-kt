package vn.edu.uit.mobile.data

import io.ktor.client.plugins.HttpRequestTimeoutException
import java.io.*
import java.net.*
import javax.net.ssl.SSLException
import vn.edu.uit.mobile.domain.*

/** Classify transport failures without publishing exception text, URLs or headers. */
internal object ChatGptTransport {
    fun failure(error: IOException, operation: String, status: Int = 0, requestId: String = "", responseBytes: Int? = null, protocol: String = ""): ChatGptException {
        val causes = generateSequence(error as Throwable) { it.cause }.take(8).toList()
        val code = when {
            causes.any { it is HttpRequestTimeoutException } -> "request_timeout"
            causes.any { it is UnknownHostException } -> "dns_failure"
            causes.any { it is SSLException } -> "tls_failure"
            causes.any { it is SocketTimeoutException } -> "network_timeout"
            causes.any { it is NoRouteToHostException } -> "network_unreachable"
            causes.any { it is ConnectException } -> "connect_failure"
            causes.any { it.javaClass.name == "okhttp3.internal.http2.StreamResetException" } -> "http2_stream_reset"
            causes.any { it.javaClass.name == "okhttp3.internal.http2.ConnectionShutdownException" } -> "http2_shutdown"
            causes.any { it is EOFException } -> "unexpected_eof"
            causes.any { it.message?.startsWith("unexpected end of stream") == true } -> "unexpected_eof"
            causes.any { it is ProtocolException } -> "http_protocol"
            causes.any { it is SocketException } -> "socket_failure"
            causes.any { it is InterruptedIOException } -> "network_interrupted"
            else -> "network_io"
        }
        val types = causes.mapNotNull {
            val name = it.javaClass.name
            name.takeIf { value ->
                listOf("java.", "javax.", "okhttp3.", "io.ktor.", "android.", "org.conscrypt.").any(value::startsWith) &&
                    value.length <= 160 &&
                    value.all { c -> c.isLetterOrDigit() || c in "._$" }
            }
        }.distinct().joinToString(">")
        return ChatGptException(
            if (code == "request_timeout") ChatGptProblem.TIMEOUT else ChatGptProblem.NETWORK,
            ChatGptDiagnostic(
                status,
                code,
                requestId,
                operation = operation,
                responseBytes = responseBytes,
                phase = if (status == 0) "connect_or_headers" else "response_body",
                exceptionTypes = types,
                protocol = protocol
            )
        )
    }

    fun retryCatalog(error: ChatGptException): Boolean = error.problem == ChatGptProblem.NETWORK &&
        error.diagnostic?.code in setOf(
            "http2_stream_reset",
            "http2_shutdown",
            "unexpected_eof",
            "http_protocol",
            "socket_failure"
        )
}
