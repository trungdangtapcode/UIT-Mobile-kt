package vn.edu.uit.mobile.forum.network

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import vn.edu.uit.mobile.domain.*

class ForumParityRegressionTest {
    private fun post(bounds: String = "", cooked: String = "<p>Before</p><div class='poll' data-poll-name='poll'><ul><li>Choice A</li><li>Choice B</li></ul></div><p>After</p>"): ForumPost {
        val source = """{"id":9,"post_stream":{"stream":[7],"posts":[{"id":7,"topic_id":9,"post_number":1,"cooked":${JsonPrimitive(cooked)},"polls":[{"name":"poll","type":"multiple","status":"open","title":"Question",$bounds"options":[{"id":"a","html":"Choice A"},{"id":"b","html":"Choice B"},{"id":"c","html":"Choice C"}]}]}]}}"""
        return ForumParser.parse(ForumRequest.Topic(9), source, true).posts.single()
    }

    @Test fun multiplePollBoundsFollowUpstreamDefaultsIncludingZeroMinimum() {
        assertEquals(1 to 3, post().polls.single().let { it.min to it.max })
        assertEquals(0 to 3, post("\"min\":0,\"max\":null,").polls.single().let { it.min to it.max })
        assertEquals(2 to 2, post("\"min\":\"2\",\"max\":\"2\",").polls.single().let { it.min to it.max })
        assertEquals(1 to 3, post("\"min\":-2,\"max\":100,").polls.single().let { it.min to it.max })
    }

    @Test fun pollReplacesCookedPlaceholderAtExactSourcePositionWithoutDuplicatingOptions() {
        val post = post()
        assertEquals(listOf(ForumBlockKind.TEXT, ForumBlockKind.POLL, ForumBlockKind.TEXT), post.blocks.map { it.kind })
        assertEquals(listOf("Before", "", "After"), post.blocks.map { it.text })
        assertEquals("Question", post.blocks[1].poll?.title)
        assertEquals(listOf("Choice A", "Choice B", "Choice C"), post.blocks[1].poll!!.options.map { it.blocks.single().text })
    }

    @Test fun pollInsideSpoilerStaysInsideAndUnknownMarkupRemainsReadable() {
        val post = post(cooked = "<details><summary>Hidden</summary><div class='poll' data-poll-name='poll'>Cooked choices</div></details>")
        assertEquals(1, post.blocks.size)
        assertEquals(ForumBlockKind.DETAILS, post.blocks.single().kind)
        assertEquals(ForumBlockKind.POLL, post.blocks.single().children.single().kind)
        val fallback = ForumParser.blocks("<div class='poll' data-poll-name='unknown'><p>Question</p><ul><li>Choice A</li></ul></div>")
        assertTrue(fallback.toString().contains("Choice A"))
    }

    @Test fun quotedPollDoesNotBindToCurrentPostAndCookedTitleIsRetained() {
        val poll = ForumPoll("poll", "multiple", "open", emptyList(), emptyList(), null)
        val blocks = ForumHtml.parse("<blockquote><div class='poll' data-poll-name='poll'><p>Quoted options</p></div></blockquote><div class='poll spoiler' data-poll-name='poll'><div class='poll-title'>Actual question</div></div>", mapOf("poll" to poll))
        assertEquals(listOf(ForumBlockKind.QUOTE, ForumBlockKind.SPOILER), blocks.map { it.kind })
        assertTrue(blocks.first().children.none { it.kind == ForumBlockKind.POLL })
        assertEquals("Actual question", blocks.last().children.single().poll?.title)
        val quotedOnly = ForumHtml.parse("<blockquote><div class='poll' data-poll-name='poll'>Quoted options</div></blockquote>", mapOf("poll" to poll))
        assertEquals(1, quotedOnly.size)
    }

    @Test fun bookmarksKeepChatThreadAndUnknownTargetsWithoutTrustingExternalChatRoutes() = runTest {
        val http = HttpClient(
            MockEngine {
                respond("""{"user_bookmark_list":{"bookmarks":[{"id":1,"topic_id":9,"linked_post_number":2,"title":"Topic"},{"id":2,"bookmarkable_type":"Chat::Message","bookmarkable_url":"/chat/c/-/3/20","title":"Chat"},{"id":3,"bookmarkable_type":"Chat::Message","bookmarkable_url":"/chat/c/-/3/t/8/21"},{"id":4,"bookmarkable_type":"FutureType","bookmarkable_url":"javascript:alert(1)"},{"id":5,"bookmarkable_type":"Chat::Message","bookmarkable_url":"https://example.org/chat/c/-/3/20"}]}}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
        )
        try {
            val bookmarks = ForumMemberApi(ForumSessionClient(http)).read(ForumUser(7, "member", ""), ForumMemberRequest.Bookmarks()).bookmarks
            assertEquals(5, bookmarks.size)
            assertEquals(ForumBookmarkTarget.Topic(9, 2), bookmarks[0].target)
            assertEquals(ForumBookmarkTarget.Chat(3, 20), bookmarks[1].target)
            assertEquals(ForumBookmarkTarget.Chat(3, 21, 8), bookmarks[2].target)
            assertNull((bookmarks[3].target as ForumBookmarkTarget.Other).url)
            assertTrue(bookmarks[4].target is ForumBookmarkTarget.Other)
        } finally {
            http.close()
        }
    }

    @Test fun chatRequestsUseIndependentPastFutureAndAroundAnchors() = runTest {
        val requests = mutableListOf<Parameters>()
        val http = HttpClient(
            MockEngine {
                requests += it.url.parameters
                respond("""{"messages":[],"meta":{"can_load_more_past":true,"can_load_more_future":true}}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
        )
        try {
            val api = ForumMemberApi(ForumSessionClient(http))
            val user = ForumUser(7, "member", "", canChat = true)
            val channel = ForumChannel(3, "Channel", true)
            for (request in listOf(ForumMemberRequest.Messages(channel), ForumMemberRequest.Messages(channel, before = 50), ForumMemberRequest.Messages(channel, after = 100), ForumMemberRequest.Messages(channel, target = 75))) {
                val page = api.read(user, request)
                assertTrue(page.more && page.moreNewer)
            }
            assertEquals("true", requests[0]["fetch_from_last_message"])
            assertEquals("past", requests[1]["direction"])
            assertEquals("50", requests[1]["target_message_id"])
            assertEquals("future", requests[2]["direction"])
            assertEquals("100", requests[2]["target_message_id"])
            assertNull(requests[3]["direction"])
            assertNull(requests[3]["fetch_from_last_message"])
            assertEquals("75", requests[3]["target_message_id"])
        } finally {
            http.close()
        }
    }
}
