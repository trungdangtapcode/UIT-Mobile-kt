package vn.edu.uit.mobile.data

import java.io.File
import kotlin.math.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import vn.edu.uit.mobile.domain.*

/** Real captured source and fixed judgments; no provider, university client or account session. */
class PassageRetrievalReplayTest {
    @Test fun localParameterAblationAndBaselineAudit() = runBlocking {
        assumeTrue(java.lang.Boolean.getBoolean("uit.retrieval.replay"))
        val output = File(requireNotNull(System.getProperty("uit.retrieval.output")))
        val raw = File(output, "index.json").readText()
        val owner = Json.parseToJsonElement(raw).jsonObject.getValue("owner").jsonPrimitive.content
        val index = PassageIndexCodec.decode(raw, owner)
        val storage = object : StudyStorage {
            override fun read(key: String): String? = null
            override fun write(key: String, value: String) = error("No mutation")
            override fun remove(key: String) = error("No mutation")
            override fun removeAccount(accountId: String) = error("No account mutation")
        }
        val loader = createPassageDocumentLoader(PrivateOfflineDownloadStorage(File(output, "verified-materials"), storage))
        val document = loader.load(index.documents.single().spec)
        val queries = Json.parseToJsonElement(javaClass.getResource("/retrieval/nt409-queries.json")!!.readText()).jsonObject.getValue("queries").jsonArray.map { it.jsonObject }
        val title = "${document.spec.course.shortname} ${document.spec.file.name}"
        // The live all-token baseline used body-only text. Audit metadata parity explicitly.
        queries.forEach { q ->
            val words = PassageTokens.read(q.getValue("text").jsonPrimitive.content).distinct()
            val before = index.passages.filter { p -> words.all { it in PassageTokens.read(p.text).toSet() } }.map { it.id }
            val after = index.passages.filter { p -> words.all { it in PassageTokens.read(title + "\n" + p.text).toSet() } }.map { it.id }
            assertEquals(before, after)
        }
        val rows = mutableListOf<JsonObject>()
        for ((size, overlap) in listOf(800 to 100, 1400 to 180, 2000 to 250)) {
            for (k1 in listOf(.8, 1.2)) {
                for (b in listOf(.25, .75)) {
                    val config = PassageConfig(size, overlap, k1, b)
                    val passages = PassageRules.chunks(document, config).take(PassageRules.PASSAGES)
                    val engine = PassageBm25(passages, config, mapOf(document.spec.id to title))
                    val scores = queries.map { query ->
                        val grades = query.getValue("cells").jsonObject.mapKeys { it.key.toInt() }.mapValues { it.value.jsonPrimitive.int }
                        val cells = engine.search(query.getValue("text").jsonPrimitive.content).take(20).map { (id, _) -> passages.first { it.id == id }.ordinal }.distinct().take(5)
                        val dcg = cells.mapIndexed { i, cell -> (2.0.pow(grades[cell] ?: 0) - 1) / (ln((i + 2).toDouble()) / ln(2.0)) }.sum()
                        val ideal = grades.values.sortedDescending().take(5).mapIndexed { i, grade -> (2.0.pow(grade) - 1) / (ln((i + 2).toDouble()) / ln(2.0)) }.sum()
                        query.getValue("split").jsonPrimitive.content to dcg / ideal
                    }
                    rows += buildJsonObject {
                        put("characters", size)
                        put("overlap", overlap)
                        put("k1", k1)
                        put("b", b)
                        put("passages", passages.size)
                        for (split in listOf("dev", "test")) put("${split}Ndcg5", scores.filter { it.first == split }.map { it.second }.average())
                    }
                }
            }
        }
        File(output, "local-ablation.json").writeText(
            buildJsonObject {
                put("capturedAt", java.time.Instant.now().toString())
                put("sourceHash", document.version)
                put("baselineMetadataAudit", "Body-only and metadata-augmented all-token matching returned identical sets for all sixteen diagnostic queries.")
                put("interpretation", "Descriptive sensitivity sweep, not hyperparameter selection. Defaults were fixed before the original real embedding run. Do not tune on these test labels.")
                put("providerCalls", 0)
                put("configurations", JsonArray(rows))
            }.toString()
        )
        assertEquals(12, rows.size)
    }
}
