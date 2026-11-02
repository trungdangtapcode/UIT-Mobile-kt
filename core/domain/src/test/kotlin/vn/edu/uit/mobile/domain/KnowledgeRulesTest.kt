package vn.edu.uit.mobile.domain

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KnowledgeRulesTest {
    private val graph = KnowledgeFixtures.graph
    private val sources = KnowledgeFixtures.sources

    @Test fun connectedGraphsAndGeneralRelationCyclesAreAllowed() {
        KnowledgeRules.graph(graph, sources)
        KnowledgeRules.graph(graph.copy(edges = graph.edges + graph.edges.first().copy(id = "cycle", from = "bfs", to = "traversal", relation = "belongs to")), sources)
    }

    @Test fun danglingSelfDuplicateAndDisconnectedEdgesAreRejected() {
        for (edge in listOf(graph.edges.first().copy(to = "missing"), graph.edges.first().copy(to = "traversal"))) {
            assertTrue(runCatching { KnowledgeRules.graph(graph.copy(edges = listOf(edge)), sources) }.isFailure)
        }
        assertTrue(runCatching { KnowledgeRules.graph(graph.copy(edges = graph.edges + graph.edges.first().copy(id = "duplicate")), sources) }.isFailure)
        assertTrue(runCatching { KnowledgeRules.graph(graph.copy(edges = graph.edges.dropLast(1)), sources) }.isFailure)
        assertTrue(runCatching { KnowledgeRules.graph(graph.copy(nodes = graph.nodes + graph.nodes.first()), sources) }.isFailure)
    }

    @Test fun everyDefinitionAndRelationshipRequiresSelectedLiteralEvidence() {
        val bad = listOf(emptyList(), listOf(StudyCitation("outside", sources.first().text.take(20))), listOf(StudyCitation(sources.first().id, "Invented source quote")))
        for (citations in bad) {
            assertTrue(runCatching { KnowledgeRules.graph(graph.copy(nodes = graph.nodes.mapIndexed { i, n -> if (i == 0) n.copy(citations = citations) else n }), sources) }.isFailure)
            assertTrue(runCatching { KnowledgeRules.graph(graph.copy(edges = graph.edges.mapIndexed { i, e -> if (i == 0) e.copy(citations = citations) else e }), sources) }.isFailure)
        }
        val markdown = listOf(StudySource("m", "Code", "Use **BFS** with a `queue`."))
        assertTrue(runCatching { KnowledgeRules.evidence(listOf(StudyCitation("m", "Use BFS with a queue.")), markdown) }.isFailure)
        KnowledgeRules.evidence(listOf(StudyCitation("m", "Use **BFS** with a `queue`.")), markdown)
    }

    @Test fun limitsAndInsufficientEvidenceCannotSmuggleAPartialMap() {
        assertTrue(runCatching { KnowledgeRules.request(sources, "", "English") }.isFailure)
        assertTrue(runCatching { KnowledgeRules.request(sources, "x".repeat(401), "English") }.isFailure)
        assertTrue(runCatching { KnowledgeRules.request(sources, "Focus", "Other") }.isFailure)
        val result = KnowledgeFixtures.generation.copy(status = "insufficient_evidence", message = "Missing text", graph = graph.copy(title = "", nodes = emptyList(), edges = emptyList()))
        KnowledgeRules.generation(result, sources, graph.focus, graph.language)
        assertTrue(runCatching { KnowledgeRules.generation(result.copy(graph = graph), sources, graph.focus, graph.language) }.isFailure)
    }
}
