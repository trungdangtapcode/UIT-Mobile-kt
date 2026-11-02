package vn.edu.uit.mobile.domain

import kotlinx.coroutines.flow.Flow

data class KnowledgeNode(val id: String, val label: String, val definition: String, val citations: List<StudyCitation>)
data class KnowledgeEdge(val id: String, val from: String, val to: String, val relation: String, val citations: List<StudyCitation>)
data class KnowledgeGraph(val title: String, val focus: String, val language: String, val nodes: List<KnowledgeNode>, val edges: List<KnowledgeEdge>)
data class KnowledgeGeneration(val model: String, val generatedAt: Long, val status: String, val message: String, val graph: KnowledgeGraph)
data class KnowledgeRecord(val id: String, val accountId: String, val courseId: Long, val courseName: String, val sources: List<StudySource>, val graph: KnowledgeGraph, val model: String, val generatedAt: Long, val revision: Long = 0) {
    val sourceVersion get() = practiceDigest(sources.sortedBy { it.id }.flatMap { listOf(it.id, it.title, it.text) })
}
data class KnowledgeCollection(val accountId: String, val records: List<KnowledgeRecord> = emptyList(), val failed: Boolean = false)
interface KnowledgeRepository {
    val collections: Flow<List<KnowledgeCollection>>
    suspend fun save(expected: KnowledgeRecord?, value: KnowledgeRecord): KnowledgeRecord
    suspend fun remove(expected: KnowledgeRecord)
}
object KnowledgeRules {
    private fun text(value: String, max: Int) {
        require(value.isNotBlank() && value.length <= max)
    }
    fun request(sources: List<StudySource>, focus: String, language: String) {
        StudyAiRules.validateSources(sources)
        text(focus, 400)
        require(language in setOf("English", "Vietnamese"))
    }
    fun evidence(citations: List<StudyCitation>, sources: List<StudySource>) {
        require(citations.size in 1..3)
        val lookup = sources.associate { it.id to PracticeRules.normalize(it.text) }
        citations.forEach {
            require(it.quote.length <= 2000)
            val quote = PracticeRules.normalize(it.quote)
            require(quote.length in 6..500 && lookup[it.sourceId]?.contains(quote) == true)
        }
    }
    fun graph(graph: KnowledgeGraph, sources: List<StudySource>) {
        request(sources, graph.focus, graph.language)
        text(graph.title, 160)
        require(graph.nodes.size in 2..12 && graph.edges.size in 1..20)
        val ids = graph.nodes.map { it.id }.toSet()
        require(ids.size == graph.nodes.size && graph.nodes.map { PracticeRules.normalize(it.label).lowercase() }.distinct().size == graph.nodes.size)
        graph.nodes.forEach {
            require(it.id.matches(Regex("[A-Za-z0-9_-]{1,40}")))
            text(it.label, 100)
            text(it.definition, 800)
            evidence(it.citations, sources)
        }
        require(graph.edges.map { it.id }.distinct().size == graph.edges.size)
        require(graph.edges.map { Triple(it.from, it.to, PracticeRules.normalize(it.relation).lowercase()) }.distinct().size == graph.edges.size)
        graph.edges.forEach {
            require(it.id.matches(Regex("[A-Za-z0-9_-]{1,40}")) && it.from in ids && it.to in ids && it.from != it.to)
            text(it.relation, 100)
            evidence(it.citations, sources)
        }
        // All concepts belong to the same inspectable map; general relation cycles are valid.
        val visited = mutableSetOf(ids.first())
        repeat(ids.size) {
            graph.edges.forEach {
                if (it.from in visited || it.to in visited) {
                    visited += it.from
                    visited += it.to
                }
            }
        }
        require(visited == ids)
    }
    fun generation(result: KnowledgeGeneration, sources: List<StudySource>, focus: String, language: String) {
        text(result.model, 100)
        require(result.generatedAt in 1..4102444800L && result.message.length <= 500)
        require(result.graph.focus == focus && result.graph.language == language)
        when (result.status) {
            "ready" -> graph(result.graph, sources)
            "insufficient_evidence" -> require(result.message.isNotBlank() && result.graph.nodes.isEmpty() && result.graph.edges.isEmpty())
            else -> error("Unsupported map result")
        }
    }
    fun record(record: KnowledgeRecord) {
        require(record.id.matches(Regex("[A-Za-z0-9_-]{1,80}")) && record.accountId.isNotBlank() && record.courseId > 0 && record.revision in 0 until Long.MAX_VALUE)
        text(record.courseName, 300)
        text(record.model, 100)
        require(record.generatedAt in 1..4102444800L)
        graph(record.graph, record.sources)
    }
}
