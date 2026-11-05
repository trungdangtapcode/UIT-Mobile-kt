package vn.edu.uit.mobile.domain

import java.text.Normalizer
import java.util.Locale
import kotlin.math.*
import kotlinx.coroutines.flow.StateFlow
import vn.edu.uit.mobile.model.*

data class PassageConfig(val characters: Int = 1400, val overlap: Int = 180, val k1: Double = 1.2, val b: Double = .75, val candidates: Int = 40, val fusionK: Int = 60) {
    fun validate() {
        require(characters in 400..2400 && overlap in 0..characters / 3)
        require(k1.isFinite() && k1 in .1..3.0 && b.isFinite() && b in 0.0..1.0 && candidates in 10..100 && fusionK in 1..200)
    }
}
data class PassageEmbeddingConfig(val model: String = "text-embedding-3-large", val dimensions: Int = 512) {
    fun validate() {
        require(model in setOf("text-embedding-3-small", "text-embedding-3-large") && dimensions in 128..1024)
    }
}
data class PassageEmbeddingBatch(val vectors: List<List<Float>>, val tokens: Long, val model: String, val requestId: String = "")
enum class PassagePartKind { TEXT, NOTEBOOK_MARKDOWN, NOTEBOOK_CODE, PDF_EXTRACT }
data class PassagePart(val ordinal: Int, val kind: PassagePartKind, val text: String)
data class PassageDocument(val spec: OfflineDownloadSpec, val version: String, val parts: List<PassagePart>, val partial: Boolean = false)
fun interface PassageDocumentLoader {
    suspend fun load(spec: OfflineDownloadSpec): PassageDocument
}
data class PassageDocumentInfo(val spec: OfflineDownloadSpec, val version: String, val partial: Boolean)
data class CoursePassage(val id: String, val documentId: String, val ordinal: Int, val kind: PassagePartKind, val start: Int, val end: Int, val firstLine: Int, val lastLine: Int, val text: String)
data class PassageIndex(
    val owner: String,
    val courseId: Long,
    val revision: String,
    val builtAt: Long,
    val config: PassageConfig,
    val documents: List<PassageDocumentInfo>,
    val passages: List<CoursePassage>,
    val embedding: PassageEmbeddingConfig? = null,
    val vectors: List<List<Float>> = emptyList(),
    val embeddingTokens: Long = 0,
    val semanticFailed: Boolean = false,
    val limited: Boolean = false,
    val update: PassageUpdateReceipt? = null
)

/** Local-byte verification only: never a claim about the current Moodle server version. */
enum class PassageFileStatus { NEW, CHANGED, KEYWORDS_READY, SMART_READY, UNAVAILABLE }
data class PassageFileCheck(val spec: OfflineDownloadSpec, val status: PassageFileStatus, val passages: Int, val reusable: Int, val partial: Boolean)
data class PassageUpdateReceipt(val reused: Int, val embedded: Int, val requests: Int, val removed: Int)
data class PassageUpdatePlan(
    val owner: String,
    val courseId: Long,
    val fingerprint: String,
    val checkedAt: Long,
    val config: PassageConfig,
    val embedding: PassageEmbeddingConfig?,
    val files: List<PassageFileCheck>,
    val removed: Int,
    val passages: Int,
    val reused: Int,
    val transfers: Int,
    val characters: Int
) {
    val canUpdate: Boolean get() = passages > 0 && files.none { it.status == PassageFileStatus.UNAVAILABLE }
}
data class PassageLibrary(val indexes: List<PassageIndex> = emptyList(), val storageFailed: Boolean = false)
data class PassageCandidate(val spec: OfflineDownloadSpec, val downloaded: Boolean)
enum class PassageSearchMode { BM25, SEMANTIC, HYBRID }
data class PassageHit(val passage: CoursePassage, val document: PassageDocumentInfo, val score: Double)
data class PassageSearchResult(val hits: List<PassageHit>, val requested: PassageSearchMode, val actual: PassageSearchMode, val invalidated: Int = 0, val queryTokens: Long = 0)
data class PassageInspection(val hit: PassageHit, val part: PassagePart)
interface PassageSearchRepository {
    val library: StateFlow<PassageLibrary>
    suspend fun initialize()
    suspend fun candidates(course: Course): List<PassageCandidate>
    suspend fun build(course: Course, ids: Set<String>, config: PassageConfig, embedding: PassageEmbeddingConfig?, progress: (Int, Int) -> Unit = { _, _ -> })
    suspend fun check(course: Course, ids: Set<String>, config: PassageConfig, embedding: PassageEmbeddingConfig?): PassageUpdatePlan = error("Local checks are not supported")
    suspend fun update(course: Course, plan: PassageUpdatePlan, progress: (Int, Int) -> Unit = { _, _ -> }): Unit = error("Reviewed updates are not supported")
    suspend fun search(course: Course, query: String, mode: PassageSearchMode): PassageSearchResult
    suspend fun inspect(course: Course, hit: PassageHit): PassageInspection
    suspend fun clear(owner: String)
    suspend fun reset()
    fun close() {}
}

object PassageRules {
    const val EXTRACTOR = "course-passages-v1"
    const val CATALOG = 200
    const val DOCUMENTS = 8
    const val PASSAGES = 400
    const val DOCUMENT_CHARACTERS = 120000
    const val BATCH = 16
    fun query(value: String) {
        require(value.isNotBlank() && value.length <= 400 && PassageTokens.read(value).isNotEmpty())
    }
    fun chunks(document: PassageDocument, config: PassageConfig): List<CoursePassage> {
        config.validate()
        return buildList {
            for (part in document.parts) {
                var start = 0
                while (start < part.text.length && size <= PASSAGES) {
                    var end = minOf(part.text.length, start + config.characters)
                    if (end < part.text.length) {
                        val boundary = part.text.lastIndexOfAny(charArrayOf('\n', ' ', '\t'), end - 1)
                        if (boundary > start + config.characters / 2) end = boundary + 1
                        if (end > start && part.text[end - 1].isHighSurrogate() && part.text[end].isLowSurrogate()) end--
                    }
                    val text = part.text.substring(start, end)
                    if (text.isNotBlank()) add(CoursePassage(practiceDigest(listOf(EXTRACTOR, document.spec.id, document.version, part.ordinal.toString(), start.toString(), end.toString())), document.spec.id, part.ordinal, part.kind, start, end, 1 + part.text.take(start).count { it == '\n' }, 1 + part.text.take(end - 1).count { it == '\n' }, text))
                    if (end == part.text.length) break
                    var next = maxOf(start + 1, end - config.overlap)
                    while (next < end && !part.text[next - 1].isWhitespace()) next++
                    start = next
                }
            }
        }
    }
    fun normalize(vector: List<Float>, dimensions: Int): List<Float> {
        require(vector.size == dimensions && vector.all(Float::isFinite))
        val norm = sqrt(vector.sumOf { it.toDouble() * it })
        require(norm.isFinite() && norm > 1e-12)
        return vector.map { (it / norm).toFloat() }
    }
}

/** Fold accents consistently, retain complete identifiers and add camel/snake components. No synonym dictionary. */
object PassageTokens {
    private val words = Regex("[\\p{L}][\\p{L}\\p{N}_]*(?:\\+\\+|#)|[\\p{L}\\p{N}]+(?:[_.][\\p{L}\\p{N}]+)*")
    private fun fold(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").replace('đ', 'd').replace('Đ', 'D').lowercase(Locale.ROOT)
    fun read(text: String): List<String> = words.findAll(text).flatMap { m ->
        val whole = fold(m.value)
        val parts = m.value.replace(Regex("([a-z\\p{Ll}0-9])([A-Z\\p{Lu}])"), "$1 $2").replace(Regex("([A-Z]+)([A-Z][a-z])"), "$1 $2").split(Regex("[_.\\s]+"))
        (listOf(whole) + parts.map(::fold)).filter { it.isNotBlank() }.distinct()
    }.toList()
}

/** Positive-IDF BM25; statistics are computed only over the authorized, current search scope. */
class PassageBm25(private val passages: List<CoursePassage>, private val config: PassageConfig, titles: Map<String, String> = emptyMap()) {
    private val terms = passages.map { PassageTokens.read(titles[it.documentId].orEmpty() + "\n" + it.text) }
    private val lengths = terms.map { it.size }
    private val average = lengths.average().takeIf { it.isFinite() && it > 0 } ?: 1.0
    private val postings = mutableMapOf<String, MutableMap<Int, Int>>().apply {
        terms.forEachIndexed { index, tokens -> tokens.groupingBy { it }.eachCount().forEach { (term, count) -> getOrPut(term) { mutableMapOf() }[index] = count } }
    }
    init {
        config.validate()
    }
    fun search(query: String, limit: Int = config.candidates): List<Pair<String, Double>> {
        PassageRules.query(query)
        val scores = mutableMapOf<Int, Double>()
        PassageTokens.read(query).distinct().forEach { term ->
            val found = postings[term] ?: return@forEach
            val idf = ln(1.0 + (passages.size - found.size + .5) / (found.size + .5))
            found.forEach { (i, frequency) ->
                val saturation = frequency * (config.k1 + 1) / (frequency + config.k1 * (1 - config.b + config.b * lengths[i] / average))
                scores[i] = scores.getOrDefault(i, 0.0) + idf * saturation
            }
        }
        return scores.entries.sortedWith(compareByDescending<Map.Entry<Int, Double>> { it.value }.thenBy { passages[it.key].id }).take(limit).map { passages[it.key].id to it.value }
    }
}

object PassageRanking {
    fun dense(passages: List<CoursePassage>, vectors: List<List<Float>>, query: List<Float>, limit: Int): List<Pair<String, Double>> {
        require(passages.size == vectors.size)
        return passages.indices.map { i ->
            require(vectors[i].size == query.size)
            passages[i].id to vectors[i].indices.sumOf { j -> vectors[i][j].toDouble() * query[j] }
        }.sortedWith(compareByDescending<Pair<String, Double>> { it.second }.thenBy { it.first }).take(limit)
    }
    fun fusion(rankings: List<List<Pair<String, Double>>>, k: Int): List<Pair<String, Double>> {
        require(k in 1..200)
        val scores = mutableMapOf<String, Double>()
        rankings.forEach { ranking -> ranking.distinctBy { it.first }.forEachIndexed { i, (id, _) -> scores[id] = scores.getOrDefault(id, 0.0) + 1.0 / (k + i + 1) } }
        return scores.entries.sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key }).map { it.key to it.value }
    }
}
