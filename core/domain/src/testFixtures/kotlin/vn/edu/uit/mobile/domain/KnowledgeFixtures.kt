package vn.edu.uit.mobile.domain

import vn.edu.uit.mobile.model.Course

/** Entirely authored teaching text; no university account content. */
object KnowledgeFixtures {
    val course = Course(1, "owner", "IT003", "Data structures and algorithms", "")
    const val TEXT = "Graph traversal includes BFS and DFS. BFS uses a FIFO queue and visits one distance layer at a time. DFS can use an explicit stack or recursion. A FIFO queue removes the earliest inserted item first. An explicit stack removes the most recently inserted item first."
    val sources = listOf(StudySource("excerpt:graphs", "Authored graph traversal lesson", TEXT))
    private fun evidence(quote: String) = listOf(StudyCitation(sources.first().id, quote))
    val graph = KnowledgeGraph(
        "Graph traversal",
        "How do BFS and DFS explore a graph?",
        "English",
        listOf(
            KnowledgeNode("traversal", "Graph traversal", "Explore graph vertices using BFS or DFS.", evidence("Graph traversal includes BFS and DFS.")),
            KnowledgeNode("bfs", "BFS", "Visit successive distance layers using a FIFO queue.", evidence("BFS uses a FIFO queue and visits one distance layer at a time.")),
            KnowledgeNode("dfs", "DFS", "Explore using an explicit stack or recursion.", evidence("DFS can use an explicit stack or recursion.")),
            KnowledgeNode("queue", "FIFO queue", "Remove the earliest inserted item first.", evidence("A FIFO queue removes the earliest inserted item first.")),
            KnowledgeNode("stack", "Explicit stack", "Remove the most recently inserted item first.", evidence("An explicit stack removes the most recently inserted item first.")),
            KnowledgeNode("layers", "Distance layers", "BFS visits one distance layer at a time.", evidence("BFS uses a FIFO queue and visits one distance layer at a time."))
        ),
        listOf(
            KnowledgeEdge("e1", "traversal", "bfs", "includes", evidence("Graph traversal includes BFS and DFS.")),
            KnowledgeEdge("e2", "traversal", "dfs", "includes", evidence("Graph traversal includes BFS and DFS.")),
            KnowledgeEdge("e3", "bfs", "queue", "uses", evidence("BFS uses a FIFO queue and visits one distance layer at a time.")),
            KnowledgeEdge("e4", "bfs", "layers", "visits", evidence("BFS uses a FIFO queue and visits one distance layer at a time.")),
            KnowledgeEdge("e5", "dfs", "stack", "can use", evidence("DFS can use an explicit stack or recursion."))
        )
    )
    val generation = KnowledgeGeneration("synthetic-map-model", 1793580000, "ready", "", graph)
    val record = KnowledgeRecord("map-one", "owner", 1, course.fullname, sources, graph, generation.model, generation.generatedAt)
}
