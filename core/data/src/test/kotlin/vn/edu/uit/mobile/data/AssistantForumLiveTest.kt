package vn.edu.uit.mobile.data

import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import vn.edu.uit.mobile.daa.PublicAnnouncementClient
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.forum.network.*

/** Explicit owner-authorized real inference over a bounded public non-support topic; no UIT session. */
class AssistantForumLiveTest {
    @Test fun publicForumContextAndFollowupsUseActualProductionSse() = runBlocking {
        assumeTrue(java.lang.Boolean.getBoolean("uit.assistant.live"))
        val key = requireNotNull(System.getenv("OPENAI_API_KEY"))
        val model = System.getenv("OPENAI_MODEL")?.takeIf(String::isNotBlank) ?: "gpt-6-luna"
        val output = File(requireNotNull(System.getProperty("uit.assistant.live.output")), "forum").apply { mkdirs() }
        val requests = mutableListOf<String>()
        val anonymous = AnonymousForumClient()
        val source = AnonymousAssistantForumSource(object : ForumTransport {
            override suspend fun get(request: ForumRequest): String {
                require(request is ForumRequest.Topic || request is ForumRequest.Posts)
                requests += ForumUrls.key(request)
                return anonymous.get(request)
            }
            override suspend fun image(url: String): ByteArray = error("No image fetch")
        })
        // Two public staff announcements; later posts containing team/student details are outside the selected window.
        val scope = AssistantForumScope(163707, 1, 2)
        val initial = source.read(scope)
        assertEquals(listOf(1, 16), initial.evidence.map { requireNotNull(it.forum).number })
        assertTrue(initial.coverage.moreAfter)
        val notices = LocalAnnouncementRepository(PublicAnnouncementClient(), AssistantMemory())
        val storage = AssistantMemory()
        val study = ConnectedStudyAiRepository(AssistantMemory(), AssistantNoAccounts())
        study.configureDirect(key, model)
        val calls = mutableListOf<JsonObject>()
        val repo = LocalAssistantRepository(storage, notices, {
            val production = study.assistantModel()
            object : AssistantModel {
                override suspend fun step(payload: JsonObject) = stream(payload) {}
                override suspend fun stream(payload: JsonObject, onText: (String) -> Unit): JsonObject {
                    File(output, "provider-input-${calls.size + 1}.json").writeText(payload.toString())
                    val start = System.nanoTime()
                    var previews = 0
                    var sample = ""
                    val response = production.stream(payload) { raw ->
                        previews++
                        val text = AssistantDraft.text(raw)
                        if (sample.isBlank() && text.length >= 160) sample = text
                        onText(raw)
                    }
                    calls += buildJsonObject {
                        put("responseId", response.getValue("id"))
                        put("model", response.getValue("model"))
                        put("status", response.getValue("status"))
                        put("usage", response.getValue("usage"))
                        put("milliseconds", (System.nanoTime() - start) / 1000000)
                        put("previewUpdates", previews)
                        put("previewSample", sample)
                    }
                    File(output, "provider-response-${calls.size}.json").writeText(response.toString())
                    File(output, "provider-calls.json").writeText(JsonArray(calls).toString())
                    return response
                }
            }
        }, forum = source)
        val id = repo.create(initial.coverage.title.take(80), AssistantScope(emptySet(), forum = scope))
        val questions = listOf(
            "Tóm tắt các bài Forum công khai đã chọn: câu hỏi/chủ đề chính, nội dung do từng tác giả nêu, cập nhật và mốc thời gian. Nêu rõ phạm vi thực sự đã đọc, phần chưa đọc; đừng coi đây là toàn bộ topic. Trích nguồn cho các kết luận.",
            "Trong phạm vi này, có hướng dẫn hoặc cập nhật nào mới hơn? Nêu đúng tác giả và phân biệt điều bài viết thực sự nói với gợi ý chuẩn bị của bạn. Không suy đoán nội dung các bài chưa đọc.",
            "Các bài đã chọn có xác nhận chính xác giờ đăng ký học phần năm 2030 không? Nếu không có bằng chứng thì nói rõ chưa xác định."
        )
        var draftUpdates = 0
        try {
            questions.forEach { question -> repo.ask(id, question, "Vietnamese") { if (it.draft.isNotBlank()) draftUpdates++ } }
        } finally {
            storage.entries["library"]?.let { File(output, "workspaces.json").writeText(it) }
        }
        val workspace = repo.library.value.workspaces.single()
        assertEquals(3, workspace.turns.size)
        workspace.turns.forEach { turn ->
            assertEquals(AssistantTurnStatus.COMPLETE, turn.status)
            assertTrue(turn.events.any { it.tool == "read_forum_topic" })
            assertTrue(turn.forumCoverage!!.moreAfter)
            assertEquals(listOf(1, 16), turn.evidence.map { it.forum!!.number })
            AssistantRules.verify(turn.facts, turn.evidence)
        }
        assertTrue(workspace.turns.take(2).all { it.facts.any { f -> f.references.isNotEmpty() } })
        assertTrue(workspace.turns.last().facts.any { it.kind == AssistantFactKind.UNKNOWN })
        assertTrue(draftUpdates > 0)
        val callsBeforeCheck = calls.size
        val check = repo.recheckForum(id, workspace.turns.first().id)
        assertNotEquals(AssistantForumFreshness.UNAVAILABLE, check.freshness)
        assertEquals(callsBeforeCheck, calls.size)
        File(output, "report.json").writeText(
            buildJsonObject {
                put("capturedAt", java.time.Instant.now().toString())
                put("requestedModel", model)
                put("runtime", "Production Kotlin/Ktor SSE on host JVM; not physical Android")
                put("source", "https://forum.uit.edu.vn/t/163707")
                put("scope", AssistantForumCodec.scope(scope))
                put("coverage", AssistantForumCodec.coverage(initial.coverage))
                put("providerCalls", JsonArray(calls))
                put("draftUpdates", draftUpdates)
                put("recheck", check.freshness.name)
                put("requests", JsonArray(requests.map(::JsonPrimitive)))
                put("universityAccountUsed", false)
                put("universityWrites", false)
            }.toString()
        )
        println("Actual public Forum AI passed: 3 completed turns; model=$model; provider calls=${calls.size}; transient drafts=$draftUpdates; no university account")
    }
}
