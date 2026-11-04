package vn.edu.uit.mobile.data

import java.io.File
import java.net.URI
import java.security.MessageDigest
import kotlin.math.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.model.*

/** Actual selected source bytes and real embeddings; no UIT account session or university requests. */
class PassageRetrievalLiveTest {
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
    }

    @Test fun compareActualCoursePassagesWithBm25DenseAndHybrid() = runBlocking {
        assumeTrue(java.lang.Boolean.getBoolean("uit.retrieval.live"))
        val output = File(requireNotNull(System.getProperty("uit.retrieval.output"))).apply { mkdirs() }
        listOf("report.json", "index.json", "replay.json", "provider-calls.json", "failure.json").forEach { File(output, it).delete() }
        val input = File(requireNotNull(System.getenv("UIT_RETRIEVAL_CAPTURE")))
        val bytes = File(input, "document-2.ipynb").readBytes()
        val catalog = Json.parseToJsonElement(File(input, "catalog.json").readText()).jsonArray
        val metadata = catalog[13].jsonObject
        val source = Json.parseToJsonElement(File(input, "candidate-documents.json").readText()).jsonArray.last().jsonObject
        val uri = URI(source.getValue("fileurl").jsonPrimitive.content)
        val url = URI(uri.scheme, null, uri.host, uri.port, uri.path, null, null).toASCIIString()
        val owner = "read-only-material-capture"
        val course = Course(metadata.getValue("id").jsonPrimitive.long, owner, metadata.getValue("shortname").jsonPrimitive.content, metadata.getValue("fullname").jsonPrimitive.content)
        val file = CourseFile(source.getValue("filename").jsonPrimitive.content, url, bytes.size.toLong())
        val spec = OfflineDownloadSpec(course, "capture", "Previously authorized source capture", file)
        val owners = object : AccountRepository {
            override val accounts = MutableStateFlow(listOf(Account(owner, "https://courses.uit.edu.vn", 1, "Anonymized local capture owner", PortalType.MOODLE)))
            override suspend fun remove(accountId: String) = error("No account mutation")
            override suspend fun saveSessions(sessions: List<PortalSession>) = error("No session access")
        }
        val portal = FakePortalRepository().apply { snapshot.value = PortalSnapshot(courses = listOf(course), sections = mapOf("$owner:${course.id}" to listOf(CourseSection(1, spec.sectionName, listOf(CourseModule(2, course.id, "Lab 1", "resource", "", files = listOf(file))))))) }
        val actualSpec = savedDownloadSpecs(portal.snapshot.value, setOf(owner)).single()
        val rawHash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val materialRoot = File(output, "verified-materials")
        val destination = materialDestination(materialRoot, course, file).apply {
            requireNotNull(parentFile).mkdirs()
            writeBytes(bytes)
        }
        writeMaterialChecksum(destination, rawHash)
        val downloads = PrivateOfflineDownloadStorage(materialRoot, Memory())
        downloads.save(OfflineDownloadJournal(listOf(OfflineDownloadItem(actualSpec, OfflineDownloadStatus.SAVED))))
        val production = ConnectedStudyAiRepository(Memory(), owners)
        production.configureDirect(requireNotNull(System.getenv("OPENAI_API_KEY")), "gpt-6-luna")
        val calls = mutableListOf<JsonObject>()
        val cache = mutableMapOf<List<String>, PassageEmbeddingBatch>()
        val ai = object : StudyAiRepository by production {
            override suspend fun embed(course: Course, texts: List<String>, config: PassageEmbeddingConfig): PassageEmbeddingBatch {
                cache[texts]?.let { return it }
                val start = System.nanoTime()
                val result = try {
                    production.embed(course, texts, config)
                } catch (error: Exception) {
                    File(output, "failure.json").writeText(
                        buildJsonObject {
                            put("type", error.javaClass.simpleName)
                            put("message", error.message.orEmpty().take(240))
                            put("inputs", texts.size)
                        }.toString()
                    )
                    throw error
                }
                calls += buildJsonObject {
                    put("model", result.model)
                    put("dimensions", config.dimensions)
                    put("inputCount", texts.size)
                    put("inputCharacters", texts.sumOf(String::length))
                    put("tokens", result.tokens)
                    put("requestId", result.requestId)
                    put("milliseconds", (System.nanoTime() - start) / 1000000)
                }
                File(output, "provider-calls.json").writeText(JsonArray(calls).toString())
                cache[texts] = result
                return result
            }
        }
        val memory = Memory()
        val loader = createPassageDocumentLoader(downloads)
        val repo = LocalPassageSearchRepository(memory, owners, portal.snapshot, loader, downloads, ai)
        try {
            val started = System.nanoTime()
            repo.build(course, setOf(actualSpec.id), PassageConfig(), PassageEmbeddingConfig())
            val buildMs = (System.nanoTime() - started) / 1000000
            val index = repo.library.value.indexes.single()
            assertFalse("Real semantic indexing must succeed, not silently become a fixture/fallback", index.semanticFailed)
            assertEquals(index.passages.size, index.vectors.size)
            File(output, "index.json").writeText(PassageIndexCodec.encode(index))
            val queryRoot = Json.parseToJsonElement(javaClass.getResource("/retrieval/nt409-queries.json")!!.readText()).jsonObject
            val results = mutableListOf<JsonObject>()
            val replays = mutableMapOf<String, JsonObject>()
            for (value in queryRoot.getValue("queries").jsonArray) {
                val query = value.jsonObject
                val text = query.getValue("text").jsonPrimitive.content
                val grades = query.getValue("cells").jsonObject.mapKeys { it.key.toInt() }.mapValues { it.value.jsonPrimitive.int }
                val methods = mutableMapOf<String, Pair<List<Int>, Long>>()
                val words = PassageTokens.read(text).distinct()
                val old = index.passages.filter { p ->
                    val tokens = PassageTokens.read(p.text).toSet()
                    words.all { it in tokens }
                }.sortedBy { it.id }.map { it.ordinal }.distinct()
                methods["token-and"] = old to 0L
                for (mode in PassageSearchMode.entries) {
                    val at = System.nanoTime()
                    val result = repo.search(course, text, mode)
                    val millis = (System.nanoTime() - at) / 1000000
                    assertEquals(mode, result.actual)
                    methods[mode.name.lowercase()] = result.hits.map { it.passage.ordinal }.distinct() to millis
                    if (mode == PassageSearchMode.HYBRID) {
                        replays[query.getValue("id").jsonPrimitive.content] = buildJsonObject {
                            put("query", text)
                            put("passageIds", JsonArray(result.hits.map { JsonPrimitive(it.passage.id) }))
                        }
                    }
                }
                results += buildJsonObject {
                    put("id", query.getValue("id"))
                    put("split", query.getValue("split"))
                    put("query", text)
                    put("judgments", query.getValue("cells"))
                    put(
                        "methods",
                        buildJsonObject {
                            methods.forEach { (method, pair) ->
                                val (ranked, millis) = pair
                                fun gain(grade: Int) = 2.0.pow(grade) - 1
                                val ideal = grades.values.sortedDescending().take(5).mapIndexed { i, grade -> gain(grade) / (ln((i + 2).toDouble()) / ln(2.0)) }.sum()
                                val dcg = ranked.take(5).mapIndexed { i, cell -> gain(grades[cell] ?: 0) / (ln((i + 2).toDouble()) / ln(2.0)) }.sum()
                                put(
                                    method,
                                    buildJsonObject {
                                        put("cells", JsonArray(ranked.take(10).map(::JsonPrimitive)))
                                        put("recall5", ranked.take(5).count { it in grades }.toDouble() / grades.size)
                                        put("ndcg5", dcg / ideal)
                                        put("mrr10", ranked.take(10).indexOfFirst { it in grades }.let { if (it < 0) 0.0 else 1.0 / (it + 1) })
                                        put("milliseconds", millis)
                                    }
                                )
                            }
                        }
                    )
                }
            }
            File(output, "results.json").writeText(JsonArray(results).toString())
            File(output, "replay.json").writeText(JsonObject(replays).toString())
            val callsAfterAll = calls.toList()
            val summary = buildJsonObject {
                put("capturedAt", java.time.Instant.now().toString())
                put("sourceHash", rawHash)
                put("source", url)
                put("course", course.shortname)
                put("filename", file.name)
                put("sourceAcquired", "Previously owner-authorized read-only UIT capture, 2026-11-02; no new university request or session")
                put("runtime", "Production Kotlin extraction/repository/embedding transport on host JVM; not Android device latency")
                put("owner", "Anonymized local owner; not a university login")
                put("uniqueDocuments", 1)
                put("duplicateCaptureExcluded", true)
                put("passages", index.passages.size)
                put("buildMilliseconds", buildMs)
                put("buildTokens", index.embeddingTokens)
                put("model", index.embedding!!.model)
                put("dimensions", index.embedding!!.dimensions)
                put("providerCalls", JsonArray(callsAfterAll))
                put("queries", JsonArray(results))
                put("labelDefinition", queryRoot.getValue("labels"))
                put("latencyCaveat", "Semantic includes one fresh query embedding; subsequent hybrid reuses that exact query vector to avoid duplicate paid evaluation calls. BM25 includes local source verification. Cells are deduplicated before ranking metrics.")
            }
            File(output, "report.json").writeText(summary.toString())
            println("Actual material retrieval passed: ${index.passages.size} passages; ${results.size} labeled queries; ${calls.size} real embedding requests")
        } finally {
            repo.close()
        }
    }
}
