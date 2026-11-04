package vn.edu.uit.mobile.data

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import vn.edu.uit.mobile.daa.*
import vn.edu.uit.mobile.domain.*

internal class AssistantMemory : StudyStorage {
    val entries = mutableMapOf<String, String>()
    var failed = false
    override fun read(key: String) = entries[key]
    override fun write(key: String, value: String) {
        check(!failed)
        entries[key] = value
    }
    override fun remove(key: String) {
        entries.remove(key)
    }
    override fun removeAccount(accountId: String) = error("No university account access")
}
internal fun assistantOutput(vararg items: JsonElement) = buildJsonObject {
    put("status", "completed")
    put("model", "fixture-model")
    put("output", JsonArray(items.toList()))
}
internal fun assistantCall(id: String, name: String, args: String) = buildJsonObject {
    put("type", "function_call")
    put("call_id", id)
    put("name", name)
    put("arguments", args)
}
internal fun assistantAnswer(facts: String) = assistantOutput(
    buildJsonObject {
        put("type", "message")
        put("role", "assistant")
        put(
            "content",
            JsonArray(
                listOf(
                    buildJsonObject {
                        put("type", "output_text")
                        put("text", "{\"facts\":$facts}")
                    }
                )
            )
        )
    }
)

class AssistantRepositoryTest {
    private val url = "https://portal.uit.edu.vn/bai-viet/fixture"
    private val sourceId = "notice_" + announcementHash(url).take(24)
    private val scope = AssistantScope()
    private val notice = OfficialAnnouncement(url, AnnouncementSource.PORTAL, "Fixture scholarship", "2026-11-03")
    private suspend fun notices(): AnnouncementRepository {
        val repo = LocalAnnouncementRepository(
            object : AnnouncementRemote {
                override suspend fun list(source: AnnouncementSource, page: Int) = AnnouncementPage(listOf(notice), false)
                override suspend fun article(item: OfficialAnnouncement) = item.copy(bodyHtml = "<p>Submit the application before Friday.</p>")
                override suspend fun image(url: String): ByteArray = error("No media read")
            },
            AssistantMemory()
        ) { 1793700000 }
        repo.refresh()
        return repo
    }
    private fun successfulModel(inputs: MutableList<JsonObject> = mutableListOf()): AssistantModel {
        var step = 0
        return AssistantModel { input ->
            inputs += input
            when (step++ % 3) {
                0 -> assistantOutput(assistantCall("search-${inputs.size}", "search_announcements", "{\"query\":\"scholarship\"}"))
                1 -> assistantOutput(assistantCall("read-${inputs.size}", "read_announcement", "{\"source_id\":\"$sourceId\"}"))
                else -> assistantAnswer("""[{"kind":"FACT","text":"The notice asks for an application before Friday.","references":[{"evidenceId":"$sourceId","paragraph":0,"quote":"Submit the application before Friday."}]}]""")
            }
        }
    }

    @Test fun toolsRetrieveEvidenceAndHistoryReopensOfflineWithoutUniversityMutations() = runTest {
        val memory = AssistantMemory()
        val notices = notices()
        val input = mutableListOf<JsonObject>()
        val repo = LocalAssistantRepository(memory, notices, { successfulModel(input) })
        val id = repo.create("Scholarships", scope)
        repo.ask(id, "What is required?", "English")
        val turn = repo.library.value.workspaces.single().turns.single()
        assertEquals(AssistantTurnStatus.COMPLETE, turn.status)
        assertEquals(listOf("search_announcements", "read_announcement"), turn.events.map { it.tool })
        assertEquals(1, turn.evidence.size)
        assertTrue(notices.state.value.items.single().unread)
        assertTrue(input[2].getValue("input").jsonArray.any { it.jsonObject["type"]?.jsonPrimitive?.content == "function_call_output" })
        val restored = LocalAssistantRepository(memory, notices, { error("No model should run on restore") })
        restored.initialize()
        assertEquals(repo.library.value, restored.library.value)
    }

    @Test fun citationsFromEarlierTurnsAreNotAcceptedAsCurrentEvidence() = runTest {
        val memory = AssistantMemory()
        val notices = notices()
        val repo = LocalAssistantRepository(memory, notices, { successfulModel() })
        val id = repo.create("Notices", scope)
        repo.ask(id, "What?", "English")
        val forged = LocalAssistantRepository(memory, notices, { AssistantModel { assistantAnswer("""[{"kind":"FACT","text":"An unsupported answer.","references":[{"evidenceId":"$sourceId","paragraph":0,"quote":"Submit the application before Friday."}]}]""") } })
        forged.initialize()
        try {
            forged.ask(id, "And next?", "English")
            fail("Reused old evidence")
        } catch (_: IllegalStateException) { }
        assertEquals(AssistantTurnStatus.FAILED, forged.library.value.workspaces.single().turns.last().status)
    }

    @Test fun unsupportedToolsFailBeforeAnyToolDispatch() = runTest {
        val repo = LocalAssistantRepository(AssistantMemory(), notices(), { AssistantModel { assistantOutput(assistantCall("bad", "submit_application", "{}")) } })
        val id = repo.create("Notices", scope)
        try {
            repo.ask(id, "Apply", "English")
            fail("Unexpected tool")
        } catch (_: IllegalArgumentException) { }
        assertEquals(AssistantTurnStatus.FAILED, repo.library.value.workspaces.single().turns.single().status)
    }

    @Test fun arbitraryUrlsCannotBeReadEvenWhenSuggestedByTheModel() = runTest {
        var step = 0
        val model = AssistantModel {
            if (step++ == 0) {
                assistantOutput(assistantCall("bad-url", "read_announcement", "{\"source_id\":\"https://portal.uit.edu.vn/sinh-vien/ho-so\"}"))
            } else {
                assistantAnswer("""[{"kind":"UNKNOWN","text":"No allowed evidence was available.","references":[]}]""")
            }
        }
        val repo = LocalAssistantRepository(AssistantMemory(), notices(), { model })
        val id = repo.create("Notices", scope)
        repo.ask(id, "Read profile", "English")
        assertTrue(repo.library.value.workspaces.single().turns.single().evidence.isEmpty())
    }

    @Test fun callAndStepBudgetsTerminateAnUnproductiveLoop() = runTest {
        var number = 0
        val repo = LocalAssistantRepository(AssistantMemory(), notices(), { AssistantModel { assistantOutput(assistantCall("id-${number++}", "search_announcements", "{\"query\":\"\"}")) } })
        val id = repo.create("Notices", scope)
        try {
            repo.ask(id, "Find everything", "English")
            fail("Unbounded loop")
        } catch (_: IllegalStateException) { }
        assertEquals(AssistantRules.MAX_STEPS, number)
    }

    @Test fun cancelledRequestsArePersistedAndCanBeRetriedExplicitly() = runTest {
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val repo = LocalAssistantRepository(AssistantMemory(), notices(), {
            AssistantModel {
                entered.complete(Unit)
                gate.await()
                error("Cancelled")
            }
        })
        val id = repo.create("Notices", scope)
        val job = launch { repo.ask(id, "What?", "English") }
        entered.await()
        job.cancelAndJoin()
        assertEquals(AssistantTurnStatus.CANCELLED, repo.library.value.workspaces.single().turns.single().status)
    }

    @Test fun deletingAWorkspaceDuringGenerationCannotResurrectIt() = runTest {
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val repo = LocalAssistantRepository(AssistantMemory(), notices(), {
            AssistantModel {
                entered.complete(Unit)
                gate.await()
                assistantAnswer("""[{"kind":"UNKNOWN","text":"No evidence.","references":[]}]""")
            }
        })
        val id = repo.create("Notices", scope)
        val job = launch { runCatching { repo.ask(id, "What?", "English") } }
        entered.await()
        repo.delete(id)
        gate.complete(Unit)
        job.join()
        assertTrue(repo.library.value.workspaces.isEmpty())
    }

    @Test fun restartMarksAnUnfinishedTurnInterruptedAndDoesNotAutomaticallyCallTheProvider() = runTest {
        val memory = AssistantMemory()
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val repo = LocalAssistantRepository(memory, notices(), {
            AssistantModel {
                entered.complete(Unit)
                gate.await()
                error("No auto resume")
            }
        })
        val id = repo.create("Notices", scope)
        val job = launch { repo.ask(id, "What?", "English") }
        entered.await()
        val restored = LocalAssistantRepository(memory, notices(), { error("Must not contact provider") })
        restored.initialize()
        assertEquals(AssistantTurnStatus.INTERRUPTED, restored.library.value.workspaces.single().turns.single().status)
        job.cancelAndJoin()
    }

    @Test fun sourceScopeIsImmutableAndChecklistRequiresAnExplicitLocalSave() = runTest {
        val repo = LocalAssistantRepository(AssistantMemory(), notices(), { successfulModel() })
        val id = repo.create("Notices", AssistantScope(setOf(AnnouncementSource.PORTAL), setOf(url)))
        repo.ask(id, "What?", "English")
        val turn = repo.library.value.workspaces.single().turns.single()
        assertTrue(turn.checklist.isEmpty())
        repo.checklist(id, turn.id, listOf(AssistantChecklistItem("task", "Read the notice")))
        assertEquals("Read the notice", repo.library.value.workspaces.single().turns.single().checklist.single().text)
        repo.rename(id, "Reviewed notices")
        assertEquals("Reviewed notices", repo.library.value.workspaces.single().name)
        repo.clear()
        assertTrue(repo.library.value.workspaces.isEmpty())
    }

    @Test fun corruptedPersistenceIsProtectedUntilExplicitDeletion() = runTest {
        val memory = AssistantMemory().apply { entries["library"] = "corrupted" }
        val repo = LocalAssistantRepository(memory, notices(), { error("No model") })
        repo.initialize()
        assertTrue(repo.library.value.storageFailed)
        try {
            repo.create("New", scope)
            fail("Overwrote corruption")
        } catch (_: IllegalStateException) { }
        assertEquals("corrupted", memory.entries["library"])
        repo.clear()
        repo.create("New", scope)
        assertFalse(repo.library.value.storageFailed)
    }

    @Test fun invalidQuoteGetsOneBoundedRepairAndUnknownIsAnAllowedOutcome() = runTest {
        var calls = 0
        val repo = LocalAssistantRepository(AssistantMemory(), notices(), {
            AssistantModel {
                calls++
                if (calls == 1) {
                    assistantAnswer("""[{"kind":"FACT","text":"Unsupported claim","references":[]}]""")
                } else {
                    assistantAnswer("""[{"kind":"UNKNOWN","text":"The available sources do not establish that.","references":[]}]""")
                }
            }
        })
        val id = repo.create("Notices", scope)
        repo.ask(id, "What?", "English")
        assertEquals(2, calls)
        assertEquals(AssistantFactKind.UNKNOWN, repo.library.value.workspaces.single().turns.single().facts.single().kind)
    }

    @Test fun missingConnectionDoesNotEraseTheExistingHistory() = runTest {
        val repo = LocalAssistantRepository(AssistantMemory(), notices(), { error("Configure AI") })
        val id = repo.create("Notices", scope)
        try {
            repo.ask(id, "What?", "English")
            fail("Missing setup")
        } catch (_: IllegalStateException) { }
        assertTrue(repo.library.value.workspaces.single().turns.isEmpty())
    }
}
