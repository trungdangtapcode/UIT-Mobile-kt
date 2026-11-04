package vn.edu.uit.mobile.data

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import vn.edu.uit.mobile.daa.*
import vn.edu.uit.mobile.domain.*

class AssistantEvidenceTest {
    private val one = OfficialAnnouncement("https://portal.uit.edu.vn/bai-viet/one", AnnouncementSource.PORTAL, "Thông báo học bổng", "2026-11-03")
    private val two = one.copy(url = "https://portal.uit.edu.vn/bai-viet/two", title = "Thông báo học phí")
    private suspend fun repository(body: String = "<p>Apply before Friday.</p>"): AnnouncementRepository {
        val repo = LocalAnnouncementRepository(
            object : AnnouncementRemote {
                override suspend fun list(source: AnnouncementSource, page: Int) = AnnouncementPage(listOf(one, two), false)
                override suspend fun article(item: OfficialAnnouncement) = item.copy(bodyHtml = body)
                override suspend fun image(url: String): ByteArray = error("No media reads")
            },
            AssistantMemory()
        )
        repo.refresh()
        return repo
    }

    @Test fun pinnedScopeFiltersSearchAndCannotReadAnotherArticle() = runTest {
        val tools = AssistantEvidenceTools(repository(), AssistantScope(setOf(AnnouncementSource.PORTAL), setOf(one.url)))
        val result = tools.call("search_announcements", buildJsonObject { put("query", "") })
        val hits = Json.parseToJsonElement(result.first).jsonObject.getValue("hits").jsonArray
        assertEquals(1, hits.size)
        val wrong = "notice_" + announcementHash(two.url).take(24)
        try {
            tools.call("read_announcement", buildJsonObject { put("source_id", wrong) })
            fail("Crossed pinned scope")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun diacriticInsensitiveSearchFindsTheOriginalTitleAndKeepsCoverageExplicit() = runTest {
        val tools = AssistantEvidenceTools(repository(), AssistantScope())
        val result = tools.call("search_announcements", buildJsonObject { put("query", "hoc bong") })
        val output = Json.parseToJsonElement(result.first).jsonObject
        assertEquals("Thông báo học bổng", output.getValue("hits").jsonArray.first().jsonObject.getValue("title").jsonPrimitive.content)
        assertEquals(2, output.getValue("coverage").jsonObject.getValue("cachedCount").jsonPrimitive.int)
        assertTrue(output.getValue("coverage").jsonObject.containsKey("asOfVietnam"))
    }

    @Test fun disabledSourcesCannotBeReadAfterDiscovery() = runTest {
        val repo = repository()
        val tools = AssistantEvidenceTools(repo, AssistantScope())
        tools.call("search_announcements", buildJsonObject { put("query", "") })
        repo.configure(AnnouncementSource.PORTAL, false)
        try {
            tools.call("read_announcement", buildJsonObject { put("source_id", "notice_" + announcementHash(one.url).take(24)) })
            fail("Read disabled source")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun unprocessedImagesAndTruncatedBodiesRemainExplicitAndAreNotFetched() = runTest {
        val tools = AssistantEvidenceTools(repository("<p>Visible source text.</p><img src='/media/image'><p>" + "Long public source text. ".repeat(2000) + "</p>"), AssistantScope())
        tools.call("search_announcements", buildJsonObject { put("query", "") })
        tools.call("read_announcement", buildJsonObject { put("source_id", "notice_" + announcementHash(one.url).take(24)) })
        val item = tools.evidence.values.single()
        assertTrue(item.abbreviated)
        assertEquals(16, item.paragraphs.size)
        assertTrue(item.paragraphs.all { it.length <= AssistantRules.MAX_PASSAGE })
    }

    @Test fun sourceVersionIncludesMetadataAndExtractorIdentity() {
        assertNotEquals(assistantSourceVersion(one), assistantSourceVersion(one.copy(title = "Corrected notice")))
        assertNotEquals(assistantSourceVersion(one), assistantSourceVersion(one.copy(publishedDate = "2026-11-04")))
        assertNotEquals(assistantSourceVersion(one), assistantSourceVersion(one.copy(bodyHtml = "Corrected body")))
    }

    @Test fun failedStorageNeverClaimsThatAWorkspaceWasCreated() = runTest {
        val memory = AssistantMemory().apply { failed = true }
        val repo = LocalAssistantRepository(memory, repository(), { error("No provider call") })
        try {
            repo.create("Workspace", AssistantScope())
            fail("Saved to failed storage")
        } catch (_: IllegalStateException) { }
        assertTrue(repo.library.value.workspaces.isEmpty())
        assertTrue(repo.library.value.storageFailed)
    }
}
