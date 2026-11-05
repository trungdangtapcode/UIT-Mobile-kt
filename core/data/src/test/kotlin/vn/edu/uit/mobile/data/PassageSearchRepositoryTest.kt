package vn.edu.uit.mobile.data

import java.lang.reflect.Proxy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.model.*

class PassageSearchRepositoryTest {
    private class Memory : StudyStorage {
        val data = mutableMapOf<String, String>()
        override fun read(key: String) = data[key]
        override fun write(key: String, value: String) {
            data[key] = value
        }
        override fun remove(key: String) {
            data.remove(key)
        }
        override fun removeAccount(accountId: String) = Unit
        override fun clear() {
            data.clear()
        }
    }
    private class Owners : AccountRepository {
        override val accounts = MutableStateFlow(listOf(Account("one", "https://courses.example.org", 1, "Synthetic owner", PortalType.MOODLE), Account("two", "https://courses.example.org", 2, "Other synthetic owner", PortalType.MOODLE)))
        override suspend fun remove(accountId: String) {
            accounts.value = accounts.value.filterNot { it.id == accountId }
        }
        override suspend fun saveSessions(sessions: List<PortalSession>) = Unit
    }
    private class Lab {
        val owners = Owners()
        val storage = Memory()
        val portal = FakePortalRepository()
        val one = Course(1, "one", "IT004", "Databases")
        val two = one.copy(accountId = "two")
        val file = CourseFile("lesson.md", "https://courses.example.org/pluginfile.php/1/lesson.md")
        val docs = mutableMapOf<String, PassageDocument>()
        var calls = 0
        var fail = false
        val ai = Proxy.newProxyInstance(StudyAiRepository::class.java.classLoader, arrayOf(StudyAiRepository::class.java)) { _, method, args ->
            if (method.name == "embed") {
                calls++
                if (fail) error("Quota unavailable")
                @Suppress("UNCHECKED_CAST")
                val texts = args[1] as List<String>
                val config = args[2] as PassageEmbeddingConfig
                PassageEmbeddingBatch(texts.map { List(config.dimensions) { i -> if (i == 0) 1f else 0f } }, texts.size.toLong(), config.model)
            } else {
                error("No generation or university mutation")
            }
        } as StudyAiRepository
        val downloads = object : OfflineDownloadStorage {
            override suspend fun load() = OfflineDownloadJournal(docs.values.map { OfflineDownloadItem(it.spec, OfflineDownloadStatus.SAVED) })
            override suspend fun save(journal: OfflineDownloadJournal) = error("No download mutation")
            override suspend fun cached(course: Course, file: CourseFile) = error("Loader is independently injected")
            override suspend fun remove(course: Course, file: CourseFile) = error("Original files must remain")
            override suspend fun usedBytes(accountIds: Set<String>) = 0L
        }
        init {
            for (course in listOf(one, two)) {
                val spec = OfflineDownloadSpec(course, "section:1", "Week 1", file)
                docs[spec.id] = PassageDocument(spec, "a".repeat(64), listOf(PassagePart(1, PassagePartKind.TEXT, "Transaction isolation preserves consistency.")))
            }
            portal.snapshot.value = PortalSnapshot(courses = listOf(one, two), sections = listOf(one, two).associate { course -> "${course.accountId}:${course.id}" to listOf(CourseSection(1, "Week 1", listOf(CourseModule(2, course.id, "Lesson", "resource", "", files = listOf(file))))) })
        }
        fun repo(loader: PassageDocumentLoader = PassageDocumentLoader { requireNotNull(docs[it.id]) }, service: StudyAiRepository = ai) = LocalPassageSearchRepository(storage, owners, portal.snapshot, loader, downloads, service)
        fun spec(course: Course) = docs.values.first { it.spec.course.accountId == course.accountId }.spec
    }

    @Test fun localBuildAndSearchNeverRequireAnAiCallAndReopenWithoutCrossingAccounts() = runTest {
        val lab = Lab()
        val repo = lab.repo()
        try {
            repo.build(lab.one, setOf(lab.spec(lab.one).id), PassageConfig(), null)
            assertEquals(0, lab.calls)
            assertEquals(1, repo.search(lab.one, "isolation", PassageSearchMode.BM25).hits.size)
            assertTrue(repo.search(lab.two, "isolation", PassageSearchMode.BM25).hits.isEmpty())
            val restored = lab.repo()
            try {
                restored.initialize()
                assertEquals(1, restored.library.value.indexes.size)
                assertEquals(1, restored.search(lab.one, "isolation", PassageSearchMode.BM25).hits.size)
            } finally {
                restored.close()
            }
            repo.clear(lab.one.accountId)
            assertTrue(repo.library.value.indexes.isEmpty())
            assertEquals(2, lab.docs.size)
        } finally {
            repo.close()
        }
    }

    @Test fun failedSemanticBuildAndQueryKeepTheLexicalIndexUsable() = runTest {
        val lab = Lab()
        val repo = lab.repo()
        try {
            lab.fail = true
            repo.build(lab.one, setOf(lab.spec(lab.one).id), PassageConfig(), PassageEmbeddingConfig())
            assertTrue(repo.library.value.indexes.single().semanticFailed)
            assertEquals(PassageSearchMode.BM25, repo.search(lab.one, "isolation", PassageSearchMode.HYBRID).actual)
            lab.fail = false
            repo.build(lab.one, setOf(lab.spec(lab.one).id), PassageConfig(), PassageEmbeddingConfig())
            assertEquals(PassageSearchMode.HYBRID, repo.search(lab.one, "isolation", PassageSearchMode.HYBRID).actual)
            lab.fail = true
            val result = repo.search(lab.one, "isolation", PassageSearchMode.HYBRID)
            assertEquals(PassageSearchMode.BM25, result.actual)
            assertEquals(1, result.hits.size)
        } finally {
            repo.close()
        }
    }

    @Test fun replacementsAndDeletedDownloadsAreInvalidatedBeforeResultsAndInspection() = runTest {
        val lab = Lab()
        val repo = lab.repo()
        try {
            val spec = lab.spec(lab.one)
            repo.build(lab.one, setOf(spec.id), PassageConfig(), null)
            val hit = repo.search(lab.one, "isolation", PassageSearchMode.BM25).hits.single()
            lab.docs[spec.id] = lab.docs.getValue(spec.id).copy(version = "b".repeat(64))
            assertTrue(runCatching { repo.inspect(lab.one, hit) }.isFailure)
            val result = repo.search(lab.one, "isolation", PassageSearchMode.BM25)
            assertEquals(1, result.invalidated)
            assertTrue(result.hits.isEmpty())
            assertTrue(repo.library.value.indexes.isEmpty())
            repo.build(lab.one, setOf(spec.id), PassageConfig(), null)
            lab.docs.remove(spec.id)
            assertEquals(1, repo.search(lab.one, "isolation", PassageSearchMode.BM25).invalidated)
        } finally {
            repo.close()
        }
    }

    @Test fun accountRemovalDuringCaptureCannotSaveOrReturnItsSource() = runTest {
        val lab = Lab()
        var reads = 0
        val repo = lab.repo(
            PassageDocumentLoader { spec ->
                if (++reads == 2) lab.owners.remove("one")
                lab.docs.getValue(spec.id)
            }
        )
        try {
            assertTrue(runCatching { repo.build(lab.one, setOf(lab.spec(lab.one).id), PassageConfig(), null) }.isFailure)
            assertTrue(repo.library.value.indexes.isEmpty())
        } finally {
            repo.close()
        }
    }

    @Test fun selectedTailReceivesSpaceWhenTheFirstDocumentIsLong() = runTest {
        val lab = Lab()
        val first = lab.spec(lab.one)
        lab.docs[first.id] = lab.docs.getValue(first.id).copy(parts = listOf(PassagePart(1, PassagePartKind.TEXT, "Long material. ".repeat(8000))))
        val second = first.copy(file = first.file.copy(name = "tail.md", url = first.file.url + "-tail"))
        lab.docs[second.id] = PassageDocument(second, "b".repeat(64), listOf(PassagePart(1, PassagePartKind.TEXT, "Tail evidence uses unique_tail_identifier.")))
        val current = lab.portal.snapshot.value
        lab.portal.snapshot.value = current.copy(sections = current.sections + ("one:1" to listOf(CourseSection(1, "Week 1", listOf(CourseModule(2, 1, "Lesson", "resource", "", files = listOf(first.file, second.file)))))))
        val repo = lab.repo()
        try {
            repo.build(lab.one, setOf(first.id, second.id), PassageConfig(400, 100), null)
            assertTrue(repo.library.value.indexes.single().passages.any { it.documentId == second.id })
            assertEquals(second.id, repo.search(lab.one, "unique_tail_identifier", PassageSearchMode.BM25).hits.first().document.spec.id)
        } finally {
            repo.close()
        }
    }

    @Test fun removedAccountIndexesArePrunedAfterRestartAndCorruptStorageNeedsExplicitReset() = runTest {
        val lab = Lab()
        val first = lab.repo()
        first.build(lab.one, setOf(lab.spec(lab.one).id), PassageConfig(), null)
        first.close()
        lab.owners.remove("one")
        val restored = lab.repo()
        try {
            restored.initialize()
            assertTrue(restored.library.value.indexes.isEmpty())
            assertTrue(lab.storage.data.keys.none { it.endsWith(":index") })
        } finally {
            restored.close()
        }
        lab.storage.data["owners"] = "corrupt"
        val broken = lab.repo()
        try {
            broken.initialize()
            assertTrue(broken.library.value.storageFailed)
            assertTrue(runCatching { broken.build(lab.two, setOf(lab.spec(lab.two).id), PassageConfig(), null) }.isFailure)
            broken.reset()
            assertFalse(broken.library.value.storageFailed)
            broken.build(lab.two, setOf(lab.spec(lab.two).id), PassageConfig(), null)
            assertEquals("two", broken.library.value.indexes.single().owner)
        } finally {
            broken.close()
        }
    }

    @Test fun clearDuringAnEmbeddingCallCannotRecreateTheIndex() = runTest {
        val lab = Lab()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val service = object : StudyAiRepository by lab.ai {
            override suspend fun embed(course: Course, texts: List<String>, config: PassageEmbeddingConfig): PassageEmbeddingBatch {
                entered.complete(Unit)
                withContext(NonCancellable) { release.await() }
                return lab.ai.embed(course, texts, config)
            }
        }
        val repo = lab.repo(service = service)
        try {
            val build = async { runCatching { repo.build(lab.one, setOf(lab.spec(lab.one).id), PassageConfig(), PassageEmbeddingConfig()) } }
            entered.await()
            repo.clear("one")
            release.complete(Unit)
            assertTrue(build.await().isFailure)
            assertTrue(repo.library.value.indexes.isEmpty())
            assertTrue(lab.storage.data.keys.none { it.endsWith(":index") })
        } finally {
            repo.close()
        }
    }

    @Test fun forgedInspectionCannotLoadAFileFromAnotherAccountEvenWithIdenticalContent() = runTest {
        val lab = Lab()
        var foreignReads = 0
        val repo = lab.repo(
            PassageDocumentLoader { spec ->
                if (spec.course.accountId == "two") foreignReads++
                lab.docs.getValue(spec.id)
            }
        )
        try {
            repo.build(lab.one, setOf(lab.spec(lab.one).id), PassageConfig(), null)
            val hit = repo.search(lab.one, "isolation", PassageSearchMode.BM25).hits.single()
            val forged = hit.copy(document = PassageDocumentInfo(lab.spec(lab.two), hit.document.version, false))
            assertTrue(runCatching { repo.inspect(lab.one, forged) }.isFailure)
            assertEquals(0, foreignReads)
            assertEquals(hit, repo.inspect(lab.one, hit).hit)
        } finally {
            repo.close()
        }
    }

    @Test fun codecRejectsWrongOwnershipMalformedVectorsAndOffsets() = runTest {
        val lab = Lab()
        val repo = lab.repo()
        try {
            repo.build(lab.one, setOf(lab.spec(lab.one).id), PassageConfig(), PassageEmbeddingConfig())
            val encoded = PassageIndexCodec.encode(repo.library.value.indexes.single())
            assertTrue(runCatching { PassageIndexCodec.decode(encoded, "two") }.isFailure)
            assertTrue(runCatching { PassageIndexCodec.decode(encoded.replace("\"start\":0", "\"start\":999"), "one") }.isFailure)
            val root = Json.parseToJsonElement(encoded).jsonObject
            val bad = JsonObject(root + ("vectors" to JsonArray(listOf(JsonPrimitive("AAAA")))))
            assertTrue(runCatching { PassageIndexCodec.decode(bad.toString(), "one") }.isFailure)
        } finally {
            repo.close()
        }
    }
}
