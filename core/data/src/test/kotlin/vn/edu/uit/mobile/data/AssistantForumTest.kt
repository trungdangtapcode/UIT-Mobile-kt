package vn.edu.uit.mobile.data

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import vn.edu.uit.mobile.daa.PublicAnnouncementClient
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.forum.network.*

/** Behavior fixtures only. This test never exports Forum review screenshots. */
class AssistantForumTest {
    private class PublicFixture : ForumTransport {
        val requests = mutableListOf<ForumRequest>()
        var privateTopic = false
        var extraPost = false
        var revised = false
        var unavailable = false
        var missing = false
        var long = false
        var likes = 0
        var acceptedId: Long? = null
        fun post(id: Long) = buildJsonObject {
            put("id", id)
            put("topic_id", 7)
            put("post_number", mapOf(11L to 1, 22L to 5, 33L to 9, 44L to 14, 55L to 20).getValue(id))
            put("username", "author$id")
            put("created_at", "2026-11-01T01:00:00Z")
            put("updated_at", "2026-11-02T01:00:00Z")
            put("version", if (revised && id == 22L) 2 else 1)
            put("staff", id == 11L)
            put(
                "actions_summary",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("id", 2)
                            put("count", likes)
                        }
                    )
                )
            )
            put(
                "cooked",
                if (long && id == 11L) {
                    "<p>" + "A long first post. ".repeat(3000) + "</p>"
                } else if (revised && id == 22L) {
                    "<p>The updated deadline is Monday.</p>"
                } else {
                    "<p>Public post $id. Bring identification.</p>"
                }
            )
        }
        override suspend fun get(request: ForumRequest): String {
            requests += request
            if (unavailable) throw ForumException(ForumFailure.UNAVAILABLE)
            val stream = listOf(11L, 22L, 33L, 44L) + if (extraPost) listOf(55L) else emptyList()
            val posts = when (request) {
                is ForumRequest.Topic -> if (request.postNumber >= 9) listOf(22L, 33L, 44L) else listOf(11L)
                is ForumRequest.Posts -> request.ids.filterNot { missing && it == 22L }
                else -> error("Unapproved read request")
            }
            return buildJsonObject {
                put("id", 7)
                put("title", "Public fixture discussion")
                put("category_id", 1)
                put("posts_count", stream.size)
                put("last_posted_at", "2026-11-03T00:00:00Z")
                put("archetype", if (privateTopic) "private_message" else "regular")
                put("accepted_answer", acceptedId?.let { buildJsonObject { put("post_id", it) } } ?: JsonNull)
                put(
                    "post_stream",
                    buildJsonObject {
                        put("stream", JsonArray(stream.map(::JsonPrimitive)))
                        put("posts", JsonArray(posts.map(::post)))
                    }
                )
            }.toString()
        }
        override suspend fun image(url: String): ByteArray = error("Images must not be fetched")
    }
    private fun announcements() = LocalAnnouncementRepository(PublicAnnouncementClient(), AssistantMemory())

    @Test fun quoteRepairIdentifiesDroppedWordsWithoutRelaxingExactMatching() {
        val evidence = AssistantEvidence("post", "https://forum.uit.edu.vn/t/7/1", null, "Notice", "", 1, "a".repeat(64), listOf("Olympic Tin học Sinh viên Việt Nam lần thứ 35."), forum = AssistantForumPost(7, 11, 1, "author", "", "", 1))
        val facts = listOf(AssistantFact(AssistantFactKind.FACT, "The source names the event.", listOf(AssistantReference("post", 0, "Olympic Tin học Sinh viên lần thứ 35."))))
        assertTrue(runCatching { AssistantRules.verify(facts, listOf(evidence)) }.isFailure)
        val repair = AssistantProtocol.repairHint(facts, listOf(evidence))
        assertTrue(repair.contains("Olympic Tin học Sinh viên Việt Nam lần thứ 35."))
        assertTrue(repair.contains("untrustedSourceParagraph"))
        assertTrue(repair.contains("\"section\":0"))
    }

    @Test fun selectedStreamUsesRealIdsAndSparsePostNumbersWithAttribution() = runTest {
        val remote = PublicFixture()
        val result = AnonymousAssistantForumSource(remote) { 123 }.read(AssistantForumScope(7, 1, 3))
        assertEquals(listOf(11L, 22L, 33L), result.coverage.selectedIds)
        assertEquals(listOf(1, 5, 9), result.evidence.map { it.forum!!.number })
        assertEquals(4, result.coverage.streamCount)
        assertTrue(result.coverage.moreAfter)
        assertFalse(result.coverage.moreBefore)
        assertEquals("author22", result.evidence[1].forum!!.username)
        assertEquals(false, result.evidence[1].forum!!.staff)
        assertNull(result.evidence[1].forum!!.accepted)
        assertEquals("https://forum.uit.edu.vn/t/7/5", result.evidence[1].url)
        assertEquals(listOf(22L, 33L), (remote.requests.last() as ForumRequest.Posts).ids)
    }

    @Test fun startingPostIsNotConfusedWithStreamIndex() = runTest {
        val result = AnonymousAssistantForumSource(PublicFixture()).read(AssistantForumScope(7, 9, 1))
        assertEquals(listOf(33L), result.coverage.selectedIds)
        assertEquals(9, result.coverage.firstNumber)
        assertTrue(result.coverage.moreBefore && result.coverage.moreAfter)
    }

    @Test fun privateAndUnavailableTopicsFailWithoutAnAccountFallback() = runTest {
        for (remote in listOf(PublicFixture().apply { privateTopic = true }, PublicFixture().apply { unavailable = true })) {
            try {
                AnonymousAssistantForumSource(remote).read(AssistantForumScope(7))
                fail("A private/unavailable topic was accepted")
            } catch (_: IllegalArgumentException) { } catch (_: ForumException) { }
            assertEquals(1, remote.requests.size)
        }
    }

    @Test fun missingBodiesStayExplicitRatherThanDisappearingFromCoverage() = runTest {
        val result = AnonymousAssistantForumSource(PublicFixture().apply { missing = true }).read(AssistantForumScope(7, 1, 3))
        assertEquals(listOf(22L), result.coverage.unavailableIds)
        assertEquals(listOf(11L, 33L), result.coverage.loadedIds)
        assertEquals(2, result.evidence.size)
    }

    @Test fun aLongFirstPostCannotStarveTheSelectedTail() = runTest {
        val result = AnonymousAssistantForumSource(PublicFixture().apply { long = true }).read(AssistantForumScope(7, 1, 4))
        assertEquals(4, result.evidence.size)
        assertTrue(result.evidence.last().paragraphs.any { "Public post 44" in it })
        assertTrue(result.coverage.abbreviated)
        assertTrue(result.evidence.sumOf { e -> e.paragraphs.sumOf(String::length) } <= 32000)
    }

    @Test fun quotesCodeAndTablesRetainMeaningWhileSpoilersAndImagesAreFlagged() {
        val blocks = ForumParser.blocks("<blockquote><p>Earlier advice.</p></blockquote><pre><code>if ready:\n    run()</code></pre><table><tr><th>Version</th><th>Deadline</th></tr><tr><td>new</td><td>Friday</td></tr></table><p>Visible <span class='spoiler'>hidden solution</span> text.</p><img src='/uploads/image.png'>")
        val (text, omitted) = ForumEvidenceText.extract(blocks)
        assertTrue(text.any { "Quoted content" in it && "Earlier advice" in it })
        assertTrue(text.any { "    run()" in it })
        assertTrue(text.any { "Version" in it && "Deadline" in it })
        assertTrue(text.none { "hidden solution" in it })
        assertTrue(omitted)
    }

    @Test fun nestedListsAndDetailsDoNotRepeatChildrenOrLeakSpoilers() {
        val blocks = ForumParser.blocks("<ul><li>First instruction<ul><li>Nested instruction</li></ul></li><li>Second <span class='spoiler'>private answer</span> instruction</li></ul><details><summary>More</summary><p>Detail text</p></details>")
        val (text, omitted) = ForumEvidenceText.extract(blocks)
        val joined = text.joinToString("\n")
        assertEquals(1, Regex("First instruction").findAll(joined).count())
        assertEquals(1, Regex("Nested instruction").findAll(joined).count())
        assertEquals(1, Regex("Detail text").findAll(joined).count())
        assertFalse(joined.contains("private answer"))
        assertTrue(omitted)
    }

    @Test fun forumToolsRejectUnselectedContextAndArbitraryArguments() = runTest {
        var calls = 0
        val source = AssistantForumSource {
            calls++
            error("Must not read")
        }
        for ((scope, args) in listOf(AssistantScope() to buildJsonObject { }, AssistantScope(emptySet(), forum = AssistantForumScope(7)) to buildJsonObject { put("topic_id", 8) })) {
            try {
                AssistantContextTools(announcements(), scope, source).call("read_forum_topic", args)
                fail("Escaped selected scope")
            } catch (_: IllegalArgumentException) { }
        }
        assertEquals(0, calls)
    }

    @Test fun toolOutputNamesSparsePostsAndDoesNotRepresentCountAsAnEndNumber() = runTest {
        val scope = AssistantScope(emptySet(), forum = AssistantForumScope(7, 1, 2))
        val output = AssistantContextTools(announcements(), scope, AnonymousAssistantForumSource(PublicFixture())).call("read_forum_topic", buildJsonObject { }).first
        val data = Json.parseToJsonElement(output).jsonObject
        assertEquals(listOf(1, 5), data.getValue("loadedPostNumbers").jsonArray.map { it.jsonPrimitive.int })
        assertTrue(data.getValue("selectionMeaning").jsonPrimitive.content.contains("not an ending post number"))
    }

    @Test fun aModelRequestedWriteAfterReadingForumEvidenceFailsClosed() = runTest {
        val remote = PublicFixture()
        var calls = 0
        val repo = LocalAssistantRepository(AssistantMemory(), announcements(), {
            AssistantModel {
                calls++
                if (calls == 1) assistantOutput(assistantCall("read", "read_forum_topic", "{}")) else assistantOutput(assistantCall("injected", "create_post", "{\"topic_id\":7,\"raw\":\"Injected text\"}"))
            }
        }, forum = AnonymousAssistantForumSource(remote))
        val id = repo.create("Selected discussion", AssistantScope(emptySet(), forum = AssistantForumScope(7, 1, 2)))
        assertTrue(runCatching { repo.ask(id, "Summarize this discussion.", "English") }.isFailure)
        assertEquals(AssistantTurnStatus.FAILED, repo.library.value.workspaces.single().turns.single().status)
        assertEquals(2, calls)
        assertTrue(remote.requests.all { it is ForumRequest.Topic || it is ForumRequest.Posts })
    }

    @Test fun savedForumQuotesReopenOfflineAndRecheckDetectsEditsAndRepliesWithoutInference() = runTest {
        val remote = PublicFixture()
        val reader = AnonymousAssistantForumSource(remote)
        val storage = AssistantMemory()
        var calls = 0
        val repo = LocalAssistantRepository(storage, announcements(), {
            AssistantModel {
                if (calls++ % 2 == 0) assistantOutput(assistantCall("read-$calls", "read_forum_topic", "{}")) else assistantAnswer("""[{"kind":"FACT","text":"author22 asks readers to bring identification.","references":[{"evidenceId":"forum_7_22","paragraph":0,"quote":"Bring identification."}]}]""")
            }
        }, forum = reader)
        val id = repo.create("Public discussion", AssistantScope(emptySet(), forum = AssistantForumScope(7, 1, 3)))
        repo.ask(id, "What did the author say?", "English")
        val turn = repo.library.value.workspaces.single().turns.single()
        assertEquals(AssistantTurnStatus.COMPLETE, turn.status)
        assertNotNull(turn.forumCoverage)
        val restored = LocalAssistantRepository(storage, announcements(), { error("No inference on restore") }, forum = reader)
        restored.initialize()
        assertEquals(turn, restored.library.value.workspaces.single().turns.single())
        assertEquals(AssistantForumFreshness.CURRENT, restored.recheckForum(id, turn.id).freshness)
        remote.likes = 99
        assertEquals(AssistantForumFreshness.CURRENT, restored.recheckForum(id, turn.id).freshness)
        remote.acceptedId = 44
        assertEquals(AssistantForumFreshness.CHANGED, restored.recheckForum(id, turn.id).freshness)
        remote.acceptedId = null
        remote.revised = true
        assertEquals(AssistantForumFreshness.CHANGED, restored.recheckForum(id, turn.id).freshness)
        remote.revised = false
        remote.extraPost = true
        assertEquals(AssistantForumFreshness.CHANGED, restored.recheckForum(id, turn.id).freshness)
        remote.unavailable = true
        assertEquals(AssistantForumFreshness.UNAVAILABLE, restored.recheckForum(id, turn.id).freshness)
        assertEquals(2, calls)
        assertEquals(turn, restored.library.value.workspaces.single().turns.single())
    }

    @Test fun deletingOneSavedAnswerPreservesOtherTurnsAndNeverReadsTheUniversity() = runTest {
        val result = AnonymousAssistantForumSource(PublicFixture()).read(AssistantForumScope(7, 1, 2))
        val first = AssistantTurn("first", "Question", 1, AssistantTurnStatus.COMPLETE, listOf(AssistantFact(AssistantFactKind.UNKNOWN, "No conclusion.")), result.evidence, forumCoverage = result.coverage)
        val second = first.copy(id = "second")
        val memory = AssistantMemory()
        memory.write("library", AssistantCodec.encode(AssistantLibrary(listOf(AssistantWorkspace("workspace", "Discussion", AssistantScope(emptySet(), forum = AssistantForumScope(7, 1, 2)), 1, listOf(first, second))))))
        val repo = LocalAssistantRepository(memory, announcements(), { error("No inference") }, forum = AssistantForumSource { error("No university access") })
        repo.deleteTurn("workspace", "first")
        assertEquals(listOf(second), repo.library.value.workspaces.single().turns)
        assertEquals(listOf(second), AssistantCodec.decode(memory.entries.getValue("library")).workspaces.single().turns)
    }

    @Test fun persistenceRejectsCrossTopicMetadataAndStillReadsVersionOneAnnouncements() = runTest {
        val result = AnonymousAssistantForumSource(PublicFixture()).read(AssistantForumScope(7, 1, 2))
        val turn = AssistantTurn("turn", "Question", 1, AssistantTurnStatus.COMPLETE, listOf(AssistantFact(AssistantFactKind.UNKNOWN, "No conclusion.")), result.evidence, forumCoverage = result.coverage)
        val library = AssistantLibrary(listOf(AssistantWorkspace("workspace", "Discussion", AssistantScope(emptySet(), forum = AssistantForumScope(7, 1, 2)), 1, listOf(turn))))
        val encoded = AssistantCodec.encode(library)
        try {
            AssistantCodec.decode(encoded.replace("\"topicId\":7", "\"topicId\":8").replace("https://forum.uit.edu.vn/t/7/", "https://forum.uit.edu.vn/t/7/"))
            fail("Mismatched post URLs accepted")
        } catch (_: IllegalArgumentException) { }
        val old = """{"version":1,"workspaces":[{"id":"old","name":"Old notices","sources":["PORTAL"],"urls":[],"createdAt":1,"revision":0,"turns":[]}]}"""
        assertNull(AssistantCodec.decode(old).workspaces.single().scope.forum)
        assertEquals(library, AssistantCodec.decode(encoded))
    }
}
