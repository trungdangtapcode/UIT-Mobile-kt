package vn.edu.uit.mobile.data

import java.io.File
import java.lang.reflect.Proxy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.model.*

/** Actual saved source + actual provider vectors. Network calls are forbidden in this verification. */
class PassageUpdateReplayTest {
    @Test fun unchangedRealNotebookReusesEverySavedVectorWithoutAnApiCall() = runBlocking {
        assumeTrue(java.lang.Boolean.getBoolean("uit.retrieval.replay"))
        val output = File(requireNotNull(System.getProperty("uit.retrieval.output")))
        val raw = File(output, "index.json").readText()
        val owner = Json.parseToJsonElement(raw).jsonObject.getValue("owner").jsonPrimitive.content
        val index = PassageIndexCodec.decode(raw, owner)
        val spec = index.documents.single().spec
        val memory = mutableMapOf("owners" to JsonArray(listOf(JsonPrimitive(owner))).toString(), practiceDigest(listOf(owner)) + ":index" to raw)
        val storage = object : StudyStorage {
            override fun read(key: String) = memory[key]
            override fun write(key: String, value: String) {
                memory[key] = value
            }
            override fun remove(key: String) {
                memory.remove(key)
            }
            override fun removeAccount(accountId: String) = error("No account mutation")
        }
        val accounts = object : AccountRepository {
            override val accounts = MutableStateFlow(listOf(Account(owner, "https://courses.uit.edu.vn", 1, "Anonymized capture owner", PortalType.MOODLE)))
            override suspend fun remove(accountId: String) = error("No account mutation")
            override suspend fun saveSessions(sessions: List<PortalSession>) = error("No university sessions")
        }
        var calls = 0
        val ai = Proxy.newProxyInstance(StudyAiRepository::class.java.classLoader, arrayOf(StudyAiRepository::class.java)) { _, _, _ ->
            calls++
            error("No provider call allowed")
        } as StudyAiRepository
        val snapshot = MutableStateFlow(PortalSnapshot(courses = listOf(spec.course), sections = mapOf("$owner:${spec.course.id}" to listOf(CourseSection(spec.section.removePrefix("section:").toLong(), spec.sectionName, listOf(CourseModule(1, spec.course.id, spec.file.name, "resource", "", files = listOf(spec.file))))))))
        val downloads = PrivateOfflineDownloadStorage(File(output, "verified-materials"), storage)
        val repo = LocalPassageSearchRepository(storage, accounts, snapshot, createPassageDocumentLoader(downloads), downloads, ai)
        try {
            val plan = repo.check(spec.course, setOf(spec.id), index.config, index.embedding)
            assertTrue(plan.canUpdate)
            assertEquals(index.passages.size, plan.reused)
            assertEquals(0, plan.transfers)
            assertEquals(PassageFileStatus.SMART_READY, plan.files.single().status)
            repo.update(spec.course, plan)
            assertEquals(0, calls)
            val updated = repo.library.value.indexes.single()
            assertEquals(index.passages.map { it.text }, updated.passages.map { it.text })
            assertEquals(index.vectors, updated.vectors)
            assertEquals(PassageUpdateReceipt(index.passages.size, 0, 0, 0), updated.update)
            File(output, "sync-index.json").writeText(PassageIndexCodec.encode(updated))
            File(output, "sync-report.json").writeText(
                buildJsonObject {
                    put("verifiedAt", java.time.Instant.now().toString())
                    put("sourceHash", index.documents.single().version)
                    put("source", "Authorized NT409.R11 Lab01-student.ipynb capture; source text and actual saved OpenAI vectors are unchanged.")
                    put("runtime", "Production repository/codec/verified-byte loader on JVM host; no university session or physical Android device.")
                    put("passages", index.passages.size)
                    put("reused", plan.reused)
                    put("transfers", plan.transfers)
                    put("providerCalls", calls)
                    put("universityRequests", 0)
                    put("previousCodecVersion", Json.parseToJsonElement(raw).jsonObject.getValue("version"))
                    put("savedCodecVersion", 2)
                }.toString()
            )
        } finally {
            repo.close()
        }
    }
}
