package vn.edu.uit.mobile.data

import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import vn.edu.uit.mobile.daa.PublicAnnouncementClient
import vn.edu.uit.mobile.domain.*

/** Opt-in owner-authorized API usage and anonymous UIT reads; no SSO or academic mutations. */
class AssistantLiveTest {
    @Test fun actualNoticesThroughProductionToolsAndResponsesLoop() = runBlocking {
        assumeTrue(java.lang.Boolean.getBoolean("uit.assistant.live"))
        val key = requireNotNull(System.getenv("OPENAI_API_KEY")).also { require(it.isNotBlank()) }
        val model = System.getenv("OPENAI_MODEL")?.takeIf { it.isNotBlank() } ?: "gpt-6-luna"
        val output = File(requireNotNull(System.getProperty("uit.assistant.live.output"))).apply { mkdirs() }
        val noticeStore = AssistantMemory()
        val announcements = LocalAnnouncementRepository(PublicAnnouncementClient(), noticeStore)
        announcements.refresh()
        check(!announcements.state.value.sources.getValue(AnnouncementSource.PORTAL).failed) { "Live Portal refresh failed" }
        val target = "https://portal.uit.edu.vn/bai-viet/thong-bao-dang-ky-xet-cap-cac-loai-hoc-bong-hk-1-nh-2026-2027"
        // Bounded archive discovery only if the historical policy has left the overlap window.
        repeat(4) {
            if (announcements.state.value.items.none { item -> item.url == target } && announcements.state.value.sources.getValue(AnnouncementSource.PORTAL).more) announcements.more(AnnouncementSource.PORTAL)
        }
        check(announcements.state.value.items.any { it.url == target }) { "Research notice is no longer in the bounded cached archive" }
        val study = ConnectedStudyAiRepository(AssistantMemory(), AssistantNoAccounts())
        study.configureDirect(key, model)
        val workspaceStore = AssistantMemory()
        val providerCalls = mutableListOf<JsonObject>()
        val repo = LocalAssistantRepository(workspaceStore, announcements, {
            val production = study.assistantModel()
            object : AssistantModel {
                override suspend fun step(payload: JsonObject): JsonObject = stream(payload) {}
                override suspend fun stream(payload: JsonObject, onText: (String) -> Unit): JsonObject {
                    val started = System.nanoTime()
                    var deltaEvents = 0
                    var firstDeltaMillis: Long? = null
                    var previewSample = ""
                    val response = production.stream(payload) { text ->
                        deltaEvents++
                        if (firstDeltaMillis == null) firstDeltaMillis = (System.nanoTime() - started) / 1000000
                        val draft = AssistantDraft.text(text)
                        if (previewSample.isEmpty() && draft.length >= 200) previewSample = draft
                        onText(text)
                    }
                    val callNumber = providerCalls.size + 1
                    File(output, "provider-response-$callNumber.json").writeText(response.toString())
                    providerCalls += buildJsonObject {
                        put("call", callNumber)
                        put("transport", "Responses SSE")
                        put("previewUpdates", deltaEvents)
                        put("firstTextMillis", firstDeltaMillis?.let(::JsonPrimitive) ?: JsonNull)
                        put("previewSample", previewSample)
                        put("responseId", response.getValue("id"))
                        put("model", response.getValue("model"))
                        put("status", response.getValue("status"))
                        put("usage", response["usage"] ?: JsonNull)
                        put("milliseconds", (System.nanoTime() - started) / 1000000)
                        put("outputTypes", JsonArray(response.getValue("output").jsonArray.map { it.jsonObject.getValue("type") }))
                    }
                    File(output, "provider-calls.json").writeText(Json { prettyPrint = true }.encodeToString(JsonArray.serializer(), JsonArray(providerCalls)))
                    return response
                }
            }
        })
        val id = repo.create("Scholarship notice research", AssistantScope(setOf(AnnouncementSource.PORTAL), setOf(target)))
        var visibleDrafts = 0
        val questions = listOf(
            "Tóm tắt đối tượng, yêu cầu và các mốc thời gian được nêu trong thông báo học bổng đã chọn. Đây là thông báo lịch sử; không coi các hạn trong bài là hạn sắp tới.",
            "Tôi nên chuẩn bị những gì? Phân biệt yêu cầu được nêu trong bài với gợi ý của bạn; trích nguồn cho các yêu cầu.",
            "Thông báo này có xác định chính xác giờ mở đăng ký học phần năm 2030 không? Nếu không có bằng chứng thì nói rõ chưa xác định."
        )
        try {
            questions.forEach { question -> repo.ask(id, question, "Vietnamese") { progress -> if (progress.draft.isNotBlank()) visibleDrafts++ } }
        } finally {
            // Keep an inspectable local record even if a later turn fails validation.
            workspaceStore.entries["library"]?.let { File(output, "workspaces.json").writeText(it) }
            noticeStore.entries["inbox"]?.let { File(output, "notices.json").writeText(it) }
        }
        val workspace = repo.library.value.workspaces.single()
        assertEquals(3, workspace.turns.size)
        assertTrue("Actual SSE answer previews must reach repository progress", visibleDrafts > 0)
        workspace.turns.forEach { turn ->
            assertEquals(AssistantTurnStatus.COMPLETE, turn.status)
            AssistantRules.verify(turn.facts, turn.evidence)
            assertTrue(turn.events.any { it.tool == "search_announcements" })
        }
        assertTrue(workspace.turns.take(2).all { it.evidence.isNotEmpty() && it.facts.any { fact -> fact.references.isNotEmpty() } })
        assertTrue(workspace.turns.last().facts.any { it.kind == AssistantFactKind.UNKNOWN })
        assertTrue("The deadline beyond the former prefix must be available", workspace.turns.first().evidence.any { it.paragraphs.any { p -> "đến hết ngày 01/11/2026" in p } })
        assertTrue(announcements.state.value.items.first { it.url == target }.unread)
        // Public source snapshots only. The connection credential is in a different in-memory store.
        File(output, "workspaces.json").writeText(requireNotNull(workspaceStore.entries["library"]))
        File(output, "notices.json").writeText(requireNotNull(noticeStore.entries["inbox"]))
        val report = buildJsonObject {
            put("capturedAt", java.time.Instant.now().toString())
            put("mode", "Production Kotlin client and runtime; public UIT GET only; explicit owner API key; no university credentials")
            put("source", target)
            put("requestedModel", model)
            put("transport", "Responses SSE; transient draft; final citation validation")
            put("visibleDraftUpdates", visibleDrafts)
            put("providerCalls", JsonArray(providerCalls))
            put("runtime", "Host JVM unit test, not physical Android execution")
            put(
                "turns",
                JsonArray(
                    workspace.turns.map { turn ->
                        buildJsonObject {
                            put("model", turn.model)
                            put("status", turn.status.name)
                            put("question", turn.question)
                            put("toolCalls", JsonArray(turn.events.map { JsonPrimitive(it.tool) }))
                            put("evidenceVersions", JsonArray(turn.evidence.map { JsonPrimitive(it.version) }))
                            put(
                                "facts",
                                JsonArray(
                                    turn.facts.map { fact ->
                                        buildJsonObject {
                                            put("kind", fact.kind.name)
                                            put("text", fact.text)
                                            put("citations", fact.references.size)
                                        }
                                    }
                                )
                            )
                        }
                    }
                )
            )
        }
        File(output, "report.json").writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), report))
        println("Assistant live evaluation passed: 3 turns, actual public notice, inspectable evidence; model=$model")
    }
}
