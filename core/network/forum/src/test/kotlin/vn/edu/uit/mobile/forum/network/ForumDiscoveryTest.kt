package vn.edu.uit.mobile.forum.network

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import vn.edu.uit.mobile.domain.*

class ForumDiscoveryTest {
    @Test fun guestFiltersNeverInheritAuthenticatedCapabilities() {
        val doc = ForumParser.parse(ForumRequest.Categories, """{"categories":[],"filters":["latest","votes","bookmarks"],"anonymous_list_filters":["latest","top","hot","bookmarks","injected"]}""")
        assertEquals(listOf(ForumFilter.LATEST, ForumFilter.TOP, ForumFilter.HOT), doc.filters)
        assertEquals(listOf(ForumFilter.LATEST), ForumParser.parse(ForumRequest.Categories, """{"categories":[]}""").filters)
    }

    @Test fun tagsRetainServerIdsAndCountsWithoutPrivateOrInvalidEntries() {
        val doc = ForumParser.parse(ForumRequest.Tags, """{"tags":[{"id":29,"name":"<b>systems</b>","count":5,"description":"<em>Study</em>"},{"id":29,"name":"duplicate"},{"id":30,"name":"private","pm_only":true},{"id":0,"name":"invalid"},{"id":31,"text":"fallback","count":-1}]}""")
        assertEquals(listOf(ForumTag(29, "systems", 5, "Study"), ForumTag(31, "fallback", 0)), doc.tags)
        assertFalse(doc.tagsTruncated)
        assertThrows(ForumException::class.java) { ForumParser.parse(ForumRequest.Tags, """{"errors":["denied"]}""") }
    }

    @Test fun tagDirectoryIsBoundedAndReportsTruncation() {
        val entries = (1..10001).joinToString(",") { """{"id":$it,"name":"tag-$it"}""" }
        val doc = ForumParser.parse(ForumRequest.Tags, """{"tags":[$entries]}""")
        assertEquals(10000, doc.tags.size)
        assertTrue(doc.tagsTruncated)
    }

    @Test fun fixedDiscoveryRoutesEncodeSelectionAndRemainAnonymous() = runTest {
        val requests = listOf(ForumRequest.Tags, ForumRequest.Feed(ForumFilter.TOP, 2, ForumPeriod.MONTHLY, 29), ForumRequest.Feed(ForumFilter.HOT, 1), ForumRequest.Feed(ForumFilter.VOTES), ForumRequest.Feed(ForumFilter.LATEST, tagId = 29))
        val paths = listOf("/tags.json", "/tag/29/l/top.json", "/hot.json", "/votes.json", "/tag/29/l/latest.json")
        var count = 0
        val client = HttpClient(
            MockEngine { req ->
                val request = requests[count]
                assertEquals(paths[count++], req.url.encodedPath)
                assertEquals("forum.uit.edu.vn", req.url.host)
                assertEquals(HttpMethod.Get, req.method)
                assertNull(req.headers["Cookie"])
                assertNull(req.headers["Authorization"])
                if (request is ForumRequest.Feed) {
                    assertEquals(request.page.toString(), req.url.parameters["page"])
                    assertEquals(if (request.filter == ForumFilter.TOP) request.period.route else null, req.url.parameters["period"])
                }
                respond("{}", headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
        )
        try {
            val remote = AnonymousForumClient(client)
            requests.forEach { remote.get(it) }
            assertThrows(IllegalArgumentException::class.java) { kotlinx.coroutines.runBlocking { remote.get(ForumRequest.Feed(ForumFilter.TOP, tagId = -1)) } }
            assertEquals(requests.size, count)
            assertEquals(requests.size, requests.map(ForumUrls::key).distinct().size)
        } finally {
            client.close()
        }
    }
}
