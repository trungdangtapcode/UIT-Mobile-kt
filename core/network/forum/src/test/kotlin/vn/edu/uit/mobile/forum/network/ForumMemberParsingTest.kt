package vn.edu.uit.mobile.forum.network

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import vn.edu.uit.mobile.domain.*

class ForumMemberParsingTest {
    @Test fun privateCategoriesAndPersonalFiltersRequireAuthenticatedParsing() {
        val raw = """{"categories":[{"id":1,"name":"Public","permission":1},{"id":2,"name":"Private","read_restricted":true,"permission":2}],"anonymous_list_filters":["latest","unread"],"filters":["latest","unread","posted","bookmarks"]}"""
        val guest = ForumParser.parse(ForumRequest.Categories, raw)
        assertEquals(listOf(1L), guest.categories.map { it.id })
        assertTrue(guest.categories.none { it.canCreate })
        assertEquals(listOf(ForumFilter.LATEST), guest.filters)
        val member = ForumParser.parse(ForumRequest.Categories, raw, true)
        assertEquals(listOf(1L, 2L), member.categories.map { it.id })
        assertTrue(member.categories.first().canCreate)
        assertFalse(member.categories.last().canCreate)
        assertTrue(member.filters.containsAll(listOf(ForumFilter.UNREAD, ForumFilter.POSTED, ForumFilter.BOOKMARKS)))
    }

    @Test fun badgeNotificationsDoNotBecomeBlankWhenThereIsNoTopicTitle() = runTest {
        val http = HttpClient(
            MockEngine {
                respond("""{"notifications":[{"id":7,"notification_type":12,"data":{"badge_id":4,"badge_name":"First &amp; useful"},"read":false,"created_at":"2026-11-03T00:00:00Z"},{"id":8,"notification_type":999,"data":{},"read":true}]}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
        )
        try {
            val page = ForumMemberApi(ForumSessionClient(http)).read(ForumUser(1, "member", ""), ForumMemberRequest.Notices())
            assertEquals("First & useful", page.notices.first().title)
            assertEquals("${ForumLimits.ORIGIN}/badges/4", page.notices.first().source)
            assertNull(page.notices.first().topicId)
            assertEquals(999, page.notices.last().type) // UI supplies the localized unknown-type label.
        } finally {
            http.close()
        }
    }

    @Test fun channelReadsStartAtLatestAndThreadsNeverMixOtherChannelMessages() = runTest {
        val http = HttpClient(
            MockEngine { req ->
                val raw = if (req.url.encodedPath.endsWith("/threads/8.json")) {
                    """{"thread":{"id":8,"channel_id":3,"status":"open","title":"Thread"}}"""
                } else {
                    assertEquals("true", req.url.parameters["fetch_from_last_message"])
                    assertEquals("/chat/api/channels/3/threads/8/messages.json", req.url.encodedPath)
                    """{"messages":[{"id":1,"chat_channel_id":3,"thread_id":8,"cooked":"<p>Correct</p>"},{"id":2,"chat_channel_id":4,"thread_id":8,"cooked":"Wrong channel"},{"id":3,"chat_channel_id":3,"thread_id":9,"cooked":"Wrong thread"}],"meta":{"can_load_more_past":true}}"""
                }
                respond(raw, headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
        )
        try {
            val page = ForumMemberApi(ForumSessionClient(http)).read(ForumUser(1, "member", "", canChat = true), ForumMemberRequest.Messages(ForumChannel(3, "Channel", true), threadId = 8))
            assertEquals(listOf(1L), page.messages.map { it.id })
            assertEquals(8L, page.thread?.id)
            assertTrue(page.more)
        } finally {
            http.close()
        }
    }

    @Test fun hiddenPollResultsRemainUnknownRatherThanZeroVotes() {
        val raw = """{"id":9,"post_stream":{"stream":[7],"posts":[{"id":7,"topic_id":9,"post_number":1,"cooked":"<p>Poll</p>","polls":[{"name":"poll","type":"multiple","status":"closed","results":"on_close","min":1,"max":2,"options":[{"id":"a","html":"<strong>Choice</strong>"}]}],"polls_votes":{"poll":["a"]}}]}}"""
        val poll = ForumParser.parse(ForumRequest.Topic(9), raw, true).posts.single().polls.single()
        assertNull(poll.options.single().votes)
        assertNull(poll.voters)
        assertEquals(listOf("a"), poll.votes)
        assertEquals("closed", poll.status)
        assertEquals(2, poll.max)
    }
}
