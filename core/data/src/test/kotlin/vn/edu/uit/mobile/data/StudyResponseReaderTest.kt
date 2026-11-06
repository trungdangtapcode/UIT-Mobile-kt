package vn.edu.uit.mobile.data

import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import vn.edu.uit.mobile.domain.*

class StudyResponseReaderTest {
    private val answer = assistantAnswer("""[{"kind":"UNKNOWN","text":"Hello.","references":[]}]""")
    private fun metadata(type: String? = null) = ChatGptDiagnostic(200, requestId = "fixture-request", operation = "responses", protocol = "HTTP/2.0", contentType = StudyResponseReader.mediaType(type))

    @Test fun validSseUsesOriginalBytesEvenWithJsonMissingOrMixedCaseHeaders() = runTest {
        for (header in listOf(null, "application/json", "TEXT/EVENT-STREAM; charset=UTF-8", "application/octet-stream")) {
            val result = StudyResponseReader.read(ByteReadChannel(assistantSseComplete(answer)), metadata(header), "fixture-model") {}
            assertEquals(answer.getValue("output"), result.getValue("output"))
        }
        assertEquals("text/event-stream", metadata("TEXT/EVENT-STREAM; charset=UTF-8").contentType)
    }

    @Test fun sniffingPreservesFragmentedBomUtf8AndLiveStreamCompletion() = runTest {
        val channel = ByteChannel(autoFlush = true)
        val work = async { StudyResponseReader.read(channel, metadata("text/plain"), "fixture-model") {} }
        channel.writeFully(byteArrayOf(0xef.toByte()))
        yield()
        assertFalse(work.isCompleted)
        channel.writeFully(byteArrayOf(0xbb.toByte(), 0xbf.toByte()))
        channel.writeStringUtf8(assistantSseComplete(assistantAnswer("""[{"kind":"UNKNOWN","text":"Tiếng Việt 🌸","references":[]}]""")))
        channel.close()
        val result = work.await()
        assertEquals("Tiếng Việt 🌸", AssistantProtocol.facts(OpenAiStudyProtocol.directPayload(result).toString()).single().text)
    }

    @Test fun completeJsonIsValidatedWithoutInventingStreamingPreviews() = runTest {
        var previews = 0
        val result = StudyResponseReader.read(ByteReadChannel(answer.toString()), metadata("application/json"), "fixture-model") { previews++ }
        assertEquals(answer, result)
        assertEquals(0, previews)
    }

    @Test fun jsonErrorsInsideHttp200RetainSafeCodesAndMediaWithoutMessages() = runTest {
        val body = """{"error":{"code":"subscription_sharing_usage_limit_exceeded","param":"tools[0]","message":"fixture-private-text"}}"""
        val error = runCatching { StudyResponseReader.read(ByteReadChannel(body), metadata("application/json"), "fixture-model") {} }.exceptionOrNull() as StudyAiException
        val diagnostic = error.diagnostic!!
        assertEquals(200, diagnostic.status)
        assertEquals("subscription_sharing_usage_limit_exceeded", diagnostic.code)
        assertEquals("application/json", diagnostic.contentType)
        assertEquals("json_error", diagnostic.phase)
        assertEquals("tools[0]", diagnostic.parameter)
        assertEquals(body.toByteArray().size, diagnostic.responseBytes)
        assertFalse(diagnostic.report().contains("fixture-private-text"))
        assertEquals(ChatGptProblem.QUOTA, ChatGptSession.responseProblem(diagnostic))
    }

    @Test fun documentsDetailsAndPartialJsonAreNotAcceptedAsAnswers() = runTest {
        val bodies = listOf(
            "<html>fixture-private-page</html>" to "unexpected_response_document",
            "{\"detail\":\"fixture-private-detail\"}" to "json_detail_response",
            "{\"status\":\"in_progress\",\"output\":[]}" to "json_response_not_completed",
            "{\"facts\":[]}" to "json_response_not_completed",
            "" to "empty_response_body"
        )
        for ((body, code) in bodies) {
            val error = runCatching { StudyResponseReader.read(ByteReadChannel(body), metadata("text/plain"), "fixture-model") {} }.exceptionOrNull() as StudyAiException
            assertEquals(code, error.diagnostic!!.code)
            assertFalse(error.diagnostic!!.report().contains("fixture-private"))
        }
    }

    @Test fun malformedJsonIsBoundedAndCancellationStopsTheReplayPump() = runTest {
        val error = runCatching { StudyResponseReader.read(ByteReadChannel("{\"x\":\"${"x".repeat(262144)}\"}"), metadata(), "fixture-model") {} }.exceptionOrNull() as StudyAiException
        assertEquals("response_too_large", error.diagnostic!!.code)
        val channel = ByteChannel(autoFlush = true)
        val reader = async { StudyResponseReader.read(channel, metadata(), "fixture-model") {} }
        channel.writeStringUtf8(": keepalive\n\n")
        yield()
        reader.cancelAndJoin()
        assertTrue(reader.isCancelled)
        channel.cancel(null)
    }
}
