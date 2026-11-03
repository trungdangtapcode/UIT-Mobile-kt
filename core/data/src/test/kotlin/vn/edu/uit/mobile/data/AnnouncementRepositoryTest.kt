package vn.edu.uit.mobile.data

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import vn.edu.uit.mobile.daa.*
import vn.edu.uit.mobile.domain.*

class AnnouncementRepositoryTest {
    private val now = java.time.Instant.parse("2026-11-03T10:00:00Z").epochSecond
    private fun item(slug: String, source: AnnouncementSource = AnnouncementSource.PORTAL, date: String = "2026-11-03") = OfficialAnnouncement("${source.origin}/bai-viet/$slug", source, "Notice $slug", date)
    private class Memory : StudyStorage {
        val values = mutableMapOf<String, String>()
        var failed = false
        override fun read(key: String) = values[key]
        override fun write(key: String, value: String) {
            check(!failed)
            values[key] = value
        }
        override fun remove(key: String) {
            values.remove(key)
        }
        override fun removeAccount(accountId: String) = error("No university account operation")
    }
    private class Remote : AnnouncementRemote {
        var items = emptyList<OfficialAnnouncement>()
        val failed = mutableSetOf<AnnouncementSource>()
        var body = "<p>Original article</p>"
        var more = false
        var failPage: Int? = null
        var gate: CompletableDeferred<Unit>? = null
        var entered: CompletableDeferred<Unit>? = null
        override suspend fun list(source: AnnouncementSource, page: Int): AnnouncementPage {
            entered?.complete(Unit)
            gate?.await()
            check(source !in failed && page != failPage)
            return AnnouncementPage(items.filter { it.source == source }, more)
        }
        override suspend fun article(item: OfficialAnnouncement): OfficialAnnouncement {
            check(item.source !in failed)
            return item.copy(bodyHtml = body)
        }
        override suspend fun image(url: String): ByteArray = error("No automatic image fetch")
    }

    @Test fun firstSyncAndNewlyEnabledSourcesAreSilentButNewArticlesNotifyOnce() = runTest {
        val remote = Remote().apply { items = listOf(item("initial")) }
        val repo = LocalAnnouncementRepository(remote, Memory()) { now }
        repo.alerts(true)
        repo.refresh()
        assertTrue(repo.state.value.pending.isEmpty())
        remote.items += item("new")
        repo.refresh()
        assertEquals(setOf(item("new").url), repo.state.value.pending)
        repo.acknowledge(repo.state.value.pending)
        repo.refresh()
        assertTrue(repo.state.value.pending.isEmpty())
        remote.items += item("ctsv", AnnouncementSource.CTSV)
        repo.configure(AnnouncementSource.CTSV, true)
        repo.refresh()
        assertTrue(repo.state.value.pending.isEmpty())
    }

    @Test fun partialFailureKeepsCachedArticlesAndOtherSourcesStillAdvance() = runTest {
        val remote = Remote().apply { items = listOf(item("portal"), item("ctsv", AnnouncementSource.CTSV)) }
        val repo = LocalAnnouncementRepository(remote, Memory()) { now }
        repo.configure(AnnouncementSource.CTSV, true)
        repo.refresh()
        remote.failed += AnnouncementSource.PORTAL
        remote.items += item("ctsv2", AnnouncementSource.CTSV)
        repo.refresh()
        assertEquals(3, repo.state.value.items.size)
        assertTrue(repo.state.value.sources.getValue(AnnouncementSource.PORTAL).failed)
        assertFalse(repo.state.value.sources.getValue(AnnouncementSource.CTSV).failed)
    }

    @Test fun overlapFailureStillKeepsTheSuccessfullyDownloadedLatestPage() = runTest {
        val remote = Remote().apply {
            items = listOf(item("notice"))
            more = true
            failPage = 1
        }
        val repo = LocalAnnouncementRepository(remote, Memory()) { now }
        repo.refresh()
        assertEquals(1, repo.state.value.items.size)
        assertTrue(repo.state.value.sources.getValue(AnnouncementSource.PORTAL).failed)
        assertEquals(1, repo.state.value.sources.getValue(AnnouncementSource.PORTAL).nextPage)
        remote.failPage = null
        repo.refresh()
        assertFalse(repo.state.value.sources.getValue(AnnouncementSource.PORTAL).failed)
    }

    @Test fun oldBackfillAndCrossPostsDoNotGenerateNewAlerts() = runTest {
        val remote = Remote().apply { items = listOf(item("same"), item("baseline", AnnouncementSource.CTSV)) }
        val repo = LocalAnnouncementRepository(remote, Memory()) { now }
        repo.configure(AnnouncementSource.CTSV, true)
        repo.alerts(true)
        repo.refresh()
        remote.items += item("old", date = "2025-01-01")
        remote.items += item("same", AnnouncementSource.CTSV)
        repo.refresh()
        assertEquals(4, repo.state.value.items.size)
        assertTrue(repo.state.value.pending.isEmpty())
    }

    @Test fun articleReadSavedReminderAndOfflineBodySurviveRestart() = runTest {
        val memory = Memory()
        val remote = Remote().apply { items = listOf(item("notice")) }
        val repo = LocalAnnouncementRepository(remote, memory) { now }
        repo.refresh()
        val original = repo.open(item("notice").url)
        assertFalse(original.offline)
        repo.markRead(original.item.url)
        repo.save(original.item.url, true)
        repo.remind(original.item.url, now + 3600)
        remote.failed += AnnouncementSource.PORTAL
        val restored = LocalAnnouncementRepository(remote, memory) { now }
        val offline = restored.open(original.item.url)
        assertTrue(offline.offline)
        assertTrue(offline.item.saved)
        assertFalse(offline.item.unread)
        assertEquals(now + 3600, offline.item.reminderAt)
        restored.acknowledge(setOf(offline.item.url), reminders = true)
        assertTrue(restored.state.value.items.single().reminderDelivered)
    }

    @Test fun changedBodyBecomesUnreadAndPreservesLocalActions() = runTest {
        val remote = Remote().apply { items = listOf(item("notice")) }
        val repo = LocalAnnouncementRepository(remote, Memory()) { now }
        repo.refresh()
        repo.open(item("notice").url)
        repo.markRead(item("notice").url)
        repo.save(item("notice").url, true)
        remote.body = "<p>Deadline correction</p>"
        val update = repo.open(item("notice").url)
        assertTrue(update.item.unread)
        assertTrue(update.item.changed)
        assertTrue(update.item.saved)
    }

    @Test fun backgroundRevalidationDetectsOldBodyOnlyCorrections() = runTest {
        val remote = Remote().apply { items = listOf(item("notice", date = "2026-10-01")) }
        val repo = LocalAnnouncementRepository(remote, Memory()) { now }
        repo.alerts(true)
        repo.refresh()
        repo.open(item("notice").url)
        repo.markRead(item("notice").url)
        remote.body = "<p>Corrected deadline on an older notice</p>"
        repo.refresh()
        assertTrue(repo.state.value.items.single().changed)
        assertTrue(repo.state.value.items.single().unread)
        assertEquals(setOf(item("notice").url), repo.state.value.pending)
    }

    @Test fun markingAnOlderRenderedRevisionReadDoesNotAcknowledgeANewerCorrection() = runTest {
        val remote = Remote().apply { items = listOf(item("notice")) }
        val repo = LocalAnnouncementRepository(remote, Memory()) { now }
        repo.alerts(true)
        repo.refresh()
        val rendered = repo.open(item("notice").url)
        remote.body = "<p>New correction arriving while the student reads</p>"
        repo.refresh()
        repo.markRead(rendered.item.url, rendered.item.revision)
        assertTrue(repo.state.value.items.single().unread)
        assertEquals(setOf(rendered.item.url), repo.state.value.pending)
    }

    @Test fun unreadableStorageIsNotOverwrittenByRefreshUntilExplicitReset() = runTest {
        val memory = Memory().apply { values["inbox"] = "broken encrypted-store payload" }
        val remote = Remote().apply { items = listOf(item("notice")) }
        val repo = LocalAnnouncementRepository(remote, memory) { now }
        repo.refresh()
        assertTrue(repo.state.value.storageFailed)
        assertEquals("broken encrypted-store payload", memory.values["inbox"])
        repo.clear()
        repo.refresh()
        assertFalse(repo.state.value.storageFailed)
        assertEquals(1, repo.state.value.items.size)
    }

    @Test fun clearDuringNetworkFetchCannotResurrectDeletedState() = runTest {
        val remote = Remote().apply {
            items = listOf(item("notice"))
            gate = CompletableDeferred()
            entered = CompletableDeferred()
        }
        val repo = LocalAnnouncementRepository(remote, Memory()) { now }
        val request = launch { repo.refresh() }
        remote.entered!!.await()
        repo.clear()
        remote.gate!!.complete(Unit)
        request.join()
        assertTrue(repo.state.value.items.isEmpty())
    }

    @Test fun failedPersistenceDoesNotClaimSaveSucceeded() = runTest {
        val remote = Remote().apply { items = listOf(item("notice")) }
        val memory = Memory()
        val repo = LocalAnnouncementRepository(remote, memory) { now }
        repo.refresh()
        memory.failed = true
        try {
            repo.save(item("notice").url, true)
            fail("Expected storage failure")
        } catch (_: IllegalStateException) { }
        assertFalse(repo.state.value.items.single().saved)
        assertTrue(repo.state.value.storageFailed)
    }

    @Test fun titleRevisionIsUnreadAndQueuesUpdateButRefreshAloneNeverMarksRead() = runTest {
        val remote = Remote().apply { items = listOf(item("notice")) }
        val repo = LocalAnnouncementRepository(remote, Memory()) { now }
        repo.alerts(true)
        repo.refresh()
        repo.markRead(item("notice").url)
        remote.items = listOf(item("notice").copy(title = "Corrected notice"))
        repo.refresh()
        assertTrue(repo.state.value.items.single().changed)
        assertTrue(repo.state.value.items.single().unread)
        assertEquals(setOf(item("notice").url), repo.state.value.pending)
    }
}
