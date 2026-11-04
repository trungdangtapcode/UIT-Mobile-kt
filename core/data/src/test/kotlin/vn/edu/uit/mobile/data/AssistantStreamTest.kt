package vn.edu.uit.mobile.data

import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

internal fun assistantSseComplete(response: JsonObject) = "event: response.completed\ndata: " + buildJsonObject {
    put("type", "response.completed")
    put("response", response)
} + "\n\n"

class AssistantStreamTest {
    private fun delta(text: String) = "data: " + buildJsonObject {
        put("type", "response.output_text.delta")
        put("delta", text)
    } + "\n\n"

    @Test fun textArrivesBeforeCompletionAndCompletedToolItemsArePreserved() = runTest {
        val channel = ByteChannel(autoFlush = true)
        val drafts = mutableListOf<String>()
        val received = CompletableDeferred<Unit>()
        val output = assistantOutput(assistantCall("call", "search_announcements", "{\"query\":\"\"}"))
        val reader = async {
            AssistantStream.read(channel) {
                drafts += it
                received.complete(Unit)
            }
        }
        channel.writeStringUtf8(": keepalive\r\n\r\n" + delta("Tiếng Việt 🌸"))
        received.await()
        assertFalse(reader.isCompleted)
        channel.writeStringUtf8(assistantSseComplete(output))
        channel.close()
        assertEquals(output, reader.await())
        assertEquals("Tiếng Việt 🌸", drafts.first())
    }

    @Test fun multilineDataAndCrLfFramesAreAccepted() = runTest {
        val output = assistantAnswer("""[{"kind":"UNKNOWN","text":"Not available.","references":[]}]""")
        val event = "data: {\"type\":\"response.completed\",\r\ndata: \"response\":$output}\r\n\r\n"
        assertEquals(output, AssistantStream.read(ByteReadChannel(event)) {})
    }

    @Test fun truncatedFailedIncompleteAndRefusedStreamsCannotProduceAnAnswer() = runTest {
        val invalid = listOf(
            delta("unfinished"),
            "data: {\"type\":\"error\"}\n\n",
            "data: {\"type\":\"response.failed\"}\n\n",
            "data: {\"type\":\"response.incomplete\"}\n\n",
            "data: {\"type\":\"response.refusal.delta\"}\n\n",
            "data: invalid-json\n\n"
        )
        for (event in invalid) {
            try {
                AssistantStream.read(ByteReadChannel(event)) {}
                fail("Invalid stream accepted")
            } catch (_: IllegalStateException) { } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun oversizedStreamAndDraftAreBounded() = runTest {
        for (event in listOf(delta("x".repeat(65537)), (": heartbeat\n").repeat(180000))) {
            try {
                AssistantStream.read(ByteReadChannel(event)) {}
                fail("Unbounded stream accepted")
            } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun cancellationInterruptsAWaitingStream() = runTest {
        val channel = ByteChannel(autoFlush = true)
        val reader = async { AssistantStream.read(channel) {} }
        yield()
        reader.cancelAndJoin()
        assertTrue(reader.isCancelled)
        channel.cancel(null)
    }

    @Test fun incompleteJsonPreviewPreservesEscapesAndHidesReferences() {
        assertEquals("Hello", AssistantDraft.text("""{"facts":[{"kind":"FACT","text":"Hello"""))
        assertEquals("Hello", AssistantDraft.text("""{"facts":[{"text":"Hello\u00"""))
        assertEquals("Line\n\"quoted\"", AssistantDraft.text("""{"facts":[{"text":"Line\n\"quoted\"","references":[{"quote":"Private reference"}]}]}"""))
        assertEquals("First\n\nSecond", AssistantDraft.text("""{"facts":[{"text":"First"},{"text":"Second"}]}"""))
        assertEquals("", AssistantDraft.text("""{"facts":[{"kind":"FACT","references":[{"quote":"Never preview quotes"}]}]}"""))
    }
}
