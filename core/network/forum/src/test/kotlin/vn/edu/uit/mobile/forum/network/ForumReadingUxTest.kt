package vn.edu.uit.mobile.forum.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import vn.edu.uit.mobile.domain.*

class ForumReadingUxTest {
    @Test fun nonContiguousPostNumbersRemainSourceNumbers() {
        val numbers = listOf(1, 16, 17, 19, 20)
        val posts = numbers.mapIndexed { i, n -> """{"id":${i + 1},"topic_id":9,"post_number":$n,"username":"fixture","cooked":"<p>Post $n</p>"}""" }
        val raw = """{"id":9,"title":"Sparse fixture","posts_count":5,"post_stream":{"stream":[1,2,3,4,5],"posts":[${posts.joinToString()}]}}"""
        val result = ForumParser.parse(ForumRequest.Topic(9, 20), raw)
        assertEquals(numbers, result.posts.map { it.number })
        assertEquals(5, result.stream.size)
    }

    @Test fun previewGeometryAndCaptionRemainDistinctFromImageAltText() {
        val block = ForumParser.blocks("""<figure><a class="lightbox" href="/uploads/original.jpg"><img src="/uploads/preview.jpg" alt="Accessible image description" width="640" height="360"></a><figcaption>A human caption</figcaption></figure>""").first { it.kind == ForumBlockKind.IMAGE }
        assertEquals(640, block.imageWidth)
        assertEquals(360, block.imageHeight)
        assertEquals("Accessible image description", block.text)
        assertTrue(block.label.isEmpty())
        assertEquals(1, ForumParser.blocks("<figure><img src='/uploads/preview.jpg' alt='Accessible image description'><figcaption>A human caption</figcaption></figure>").count { it.text == "A human caption" })
        assertEquals("https://forum.uit.edu.vn/uploads/original.jpg", block.original)
        val hostile = ForumParser.blocks("""<img src="/uploads/preview.jpg" width="999999999" height="-1">""").single()
        assertEquals(0, hostile.imageWidth)
        assertEquals(0, hostile.imageHeight)
    }

    @Test fun networkCategoriesNeverReturnExceptionMessagesOrSecrets() {
        assertEquals(ForumNetworkIssue.TLS, forumNetworkIssue(IOException("private wrapper", SSLHandshakeException("sensitive certificate details"))))
        assertEquals(ForumNetworkIssue.DNS, forumNetworkIssue(UnknownHostException("private host")))
        assertEquals(ForumNetworkIssue.TIMEOUT, forumNetworkIssue(SocketTimeoutException("private URL")))
        assertEquals(ForumNetworkIssue.IO, forumNetworkIssue(IOException("private response")))
    }

    @Test fun serverFailureKeepsItsCategoryWithoutReturningItsResponseBody() = runTest {
        val engine = MockEngine { respond("private diagnostic body", HttpStatusCode.ServiceUnavailable) }
        val http = HttpClient(engine)
        try {
            val failure = runCatching { AnonymousForumClient(http).get(ForumRequest.Topic(9)) }.exceptionOrNull() as ForumException
            assertEquals(ForumFailure.NETWORK, failure.reason)
            assertEquals(ForumNetworkIssue.SERVER, failure.networkIssue)
            assertTrue(failure.detail.isEmpty())
        } finally {
            http.close()
        }
    }
}
