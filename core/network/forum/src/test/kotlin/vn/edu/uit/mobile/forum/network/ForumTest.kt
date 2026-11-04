package vn.edu.uit.mobile.forum.network

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import vn.edu.uit.mobile.domain.*

class ForumTest {
    @Test fun categoryTreeExcludesRestrictedNodes() {
        val c = ForumParser.parse(ForumRequest.Categories, ForumFixtures.CATEGORIES).categories
        assertEquals(listOf(8L, 29L), c.map { it.id })
        assertEquals(8L, c.last().parentId)
    }

    @Test fun searchPreservesReplyTargetAndDecodesMarkup() {
        val result = ForumParser.parse(ForumRequest.Search("small input"), ForumFixtures.SEARCH)
        assertEquals(99, result.hits.single().postNumber)
        assertEquals("Try a small input before the full dataset.", result.hits.single().excerpt)
        assertFalse(result.more)
    }

    @Test fun longTopicRetainsIdsWithoutInventingPostNumbers() {
        val r = ForumRequest.Topic(901, 99)
        val d = ForumParser.parse(r, ForumFixtures.response(r))
        assertEquals(125, d.stream.size)
        assertEquals(20, d.posts.size)
        assertTrue(d.posts.any { it.number == 99 })
        assertFalse(d.posts.any { it.number % 2 == 0 })
    }

    @Test fun batchIsOwnedRequestedDeduplicatedAndOrdered() {
        val r = ForumRequest.Posts(901, listOf(1022, 1021))
        val d = ForumParser.parse(r, ForumFixtures.response(r))
        assertEquals(listOf(1022L, 1021L), d.posts.map { it.id })
        val spoof = """{"id":901,"post_stream":{"posts":[{"id":1022,"topic_id":999,"post_number":1,"cooked":"secret"},{"id":1050,"topic_id":901,"post_number":99,"cooked":"unrequested"}]}}"""
        assertTrue(ForumParser.parse(r, spoof).posts.isEmpty())
    }

    @Test fun activeHtmlNeverBecomesExecutableContent() {
        val blocks = ForumParser.blocks("<script>secret()</script><form>password<input></form><iframe src='https://evil.example'></iframe><p>Hello &amp; goodbye <a href='javascript:alert(1)'>bad</a></p><pre>  x\n\ty</pre><img src='data:text/html,bad'><a href='/t/example/901/99'>reply</a>")
        assertFalse(blocks.any { "secret" in it.text || "password" in it.text })
        assertEquals("  x\n\ty", blocks.first { it.kind == ForumBlockKind.CODE }.text)
        assertTrue(blocks.flatMap { it.links }.none { it.url.startsWith("javascript:") || it.url.startsWith("data:") })
        assertEquals(ForumTarget(901, 99), ForumUrls.target(blocks.flatMap { it.links }.single().url))
    }

    @Test fun authoredRichContentIncludesQuotesCodeListsTablesAndDetails() {
        val r = ForumRequest.Topic(901)
        val blocks = ForumParser.parse(r, ForumFixtures.response(r)).posts.first().blocks
        assertTrue(blocks.map { it.kind }.containsAll(listOf(ForumBlockKind.QUOTE, ForumBlockKind.CODE, ForumBlockKind.TABLE, ForumBlockKind.DETAILS, ForumBlockKind.IMAGE)))
        assertTrue(blocks.any { it.text.startsWith("• ") })
    }

    @Test fun discourseEmojiStayInlineWithoutOfferingRemoteImageLoads() {
        val inline = ForumParser.blocks("<p>Thanks <img class='emoji' src='https://external.example/emoji.png' alt=':+1:'></p>")
        assertEquals("Thanks :+1:", inline.single().text)
        assertFalse(inline.any { it.kind == ForumBlockKind.IMAGE })
        assertEquals(":smile:", ForumParser.blocks("<img class='emoji' src='/images/emoji/smile.png' alt=':smile:'>").single().text)
    }

    @Test fun deepOrOversizedMarkupIsBoundedAndOffersSourceFallback() {
        val nested = "<div>".repeat(1000) + "payload" + "</div>".repeat(1000)
        assertTrue(ForumParser.blocks(nested).any { it.kind == ForumBlockKind.OMITTED })
        assertTrue(ForumParser.blocks("<p>" + "text".repeat(20000) + "</p>").any { it.kind == ForumBlockKind.OMITTED })
    }

    @Test fun linksEnforceHttpsAndImagesEnforceOriginAndUploadPath() {
        listOf("javascript:alert(1)", "http://forum.uit.edu.vn/t/1", "https://name:password@forum.uit.edu.vn/t/1", "https://forum.uit.edu.vn:8080/t/1", "https://evil.example/\n").forEach { assertNull(ForumUrls.safe(it)) }
        assertNull(ForumUrls.image("https://evil.example/uploads/a.png"))
        assertNull(ForumUrls.image("/uploads/../session/current"))
        assertNull(ForumUrls.image("/uploads/%2e%2e/session/current"))
        assertNull(ForumUrls.image("/uploads/x.png?token=secret"))
        assertNotNull(ForumUrls.image("/uploads/default/x.png"))
        assertNull(ForumUrls.target("https://evil.example/t/901/3"))
    }

    @Test fun requestsRemainAnonymousGetAndEncodeSearch() = runTest {
        var count = 0
        val engine = MockEngine { req ->
            count++
            assertEquals(HttpMethod.Get, req.method)
            assertEquals("forum.uit.edu.vn", req.url.host)
            listOf("Cookie", "Authorization", "Api-Key", "Api-Username", "User-Api-Key").forEach { assertNull(req.headers[it]) }
            assertEquals("linux & C++", req.url.parameters["q"])
            respond(ForumFixtures.SEARCH, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType to listOf("application/json"), HttpHeaders.SetCookie to listOf("session=ignored; Secure")))
        }
        val client = HttpClient(engine) { followRedirects = false }
        val remote = AnonymousForumClient(client)
        repeat(2) { remote.get(ForumRequest.Search("linux & C++")) }
        assertEquals(2, count)
        client.close()
    }

    @Test fun topicDoesNotOptIntoVisitTrackingAndBatchIsBounded() = runTest {
        val client = HttpClient(
            MockEngine { req ->
                assertNull(req.url.parameters["track_visit"])
                respond("{}", headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
        )
        AnonymousForumClient(client).get(ForumRequest.Topic(901, 7000))
        assertThrows(IllegalArgumentException::class.java) { kotlinx.coroutines.runBlocking { AnonymousForumClient(client).get(ForumRequest.Posts(1, (1L..21L).toList())) } }
        client.close()
    }

    @Test fun categoryCanonicalRewriteIsValidatedAndBounded() = runTest {
        for ((location, accepted) in listOf(
            "https://forum.uit.edu.vn/c/campus/support/59.json?page=0" to true,
            "https://evil.example/c/support/59.json?page=0" to false,
            "/c/support/60.json?page=0" to false,
            "/c/support/59?page=0" to false,
            "/c/support/59.json?page=0&token=ignored" to false
        )) {
            var calls = 0
            val client = HttpClient(
                MockEngine {
                    calls++
                    assertEquals(HttpMethod.Get, it.method)
                    assertNull(it.headers["Cookie"])
                    if (calls == 1) {
                        respond("", HttpStatusCode.MovedPermanently, headersOf(HttpHeaders.Location, location))
                    } else {
                        respond(ForumFixtures.LATEST, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                    }
                }
            ) { followRedirects = false }
            try {
                AnonymousForumClient(client).get(ForumRequest.Latest(59))
                assertTrue(accepted)
                assertEquals(2, calls)
            } catch (e: ForumException) {
                assertFalse(accepted)
                assertEquals(1, calls)
            }
            client.close()
        }
    }

    @Test fun redirectsAndAuthErrorsAreNotFollowedOrParsed() = runTest {
        for (status in listOf(302, 401, 403, 404, 410, 429, 503)) {
            val client = HttpClient(MockEngine { respond("{}", HttpStatusCode.fromValue(status), headersOf(HttpHeaders.ContentType to listOf("application/json"), HttpHeaders.RetryAfter to listOf("120"), HttpHeaders.Location to listOf("https://evil.example"))) }) { followRedirects = false }
            val error = try {
                AnonymousForumClient(client).get(ForumRequest.Categories)
                null
            } catch (e: ForumException) {
                e
            }
            assertNotNull(error)
            if (status == 429) {
                assertEquals(ForumFailure.RATE_LIMIT, error!!.reason)
                assertEquals(120L, error.retryAfterSeconds)
            }
            client.close()
        }
    }

    @Test fun oversizedAndHtmlResponsesAreRejected() = runTest {
        for ((body, type) in listOf("<html>Login</html>" to "text/html", "x".repeat(4 * 1024 * 1024 + 1) to "application/json")) {
            val client = HttpClient(MockEngine { respond(body, headers = headersOf(HttpHeaders.ContentType, type)) })
            try {
                AnonymousForumClient(client).get(ForumRequest.Categories)
                fail("Unsafe response accepted")
            } catch (e: ForumException) {
                assertEquals(ForumFailure.INVALID, e.reason)
            }
            client.close()
        }
    }
}
