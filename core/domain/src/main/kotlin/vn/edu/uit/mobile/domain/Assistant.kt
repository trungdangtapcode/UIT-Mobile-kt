package vn.edu.uit.mobile.domain

import kotlinx.coroutines.flow.StateFlow

/** Public-only initial scope. No account IDs or university credentials enter this contract. */
data class AssistantScope(val sources: Set<AnnouncementSource> = setOf(AnnouncementSource.PORTAL), val urls: Set<String> = emptySet())
data class AssistantEvidence(
    val id: String,
    val url: String,
    val source: AnnouncementSource,
    val title: String,
    val publishedDate: String,
    val fetchedAt: Long,
    val version: String,
    val paragraphs: List<String>,
    val offline: Boolean = false,
    val abbreviated: Boolean = false
)
data class AssistantReference(val evidenceId: String, val paragraph: Int, val quote: String)
enum class AssistantFactKind { FACT, SUGGESTION, UNKNOWN }
data class AssistantFact(val kind: AssistantFactKind, val text: String, val references: List<AssistantReference> = emptyList())
enum class AssistantTurnStatus { RUNNING, COMPLETE, CANCELLED, FAILED, INTERRUPTED }
data class AssistantChecklistItem(val id: String, val text: String, val checked: Boolean = false)
data class AssistantToolEvent(val tool: String, val results: Int = 0)
data class AssistantTurn(
    val id: String,
    val question: String,
    val at: Long,
    val status: AssistantTurnStatus,
    val facts: List<AssistantFact> = emptyList(),
    val evidence: List<AssistantEvidence> = emptyList(),
    val events: List<AssistantToolEvent> = emptyList(),
    val model: String = "",
    val coverage: String = "",
    val checklist: List<AssistantChecklistItem> = emptyList()
)
data class AssistantWorkspace(
    val id: String,
    val name: String,
    val scope: AssistantScope,
    val createdAt: Long,
    val turns: List<AssistantTurn> = emptyList(),
    val revision: Long = 0
)
data class AssistantLibrary(val workspaces: List<AssistantWorkspace> = emptyList(), val storageFailed: Boolean = false)
data class AssistantProgress(val tool: String, val results: Int = 0)
interface AssistantRepository {
    val library: StateFlow<AssistantLibrary>
    suspend fun initialize()
    suspend fun create(name: String, scope: AssistantScope): String
    suspend fun rename(id: String, name: String)
    suspend fun delete(id: String)
    suspend fun clear()
    suspend fun ask(id: String, question: String, language: String, progress: (AssistantProgress) -> Unit = {})
    suspend fun checklist(workspaceId: String, turnId: String, items: List<AssistantChecklistItem>)
}

fun assistantSourceVersion(item: OfficialAnnouncement): String = practiceDigest(listOf("assistant-text-v1", item.url, item.source.name, item.title, item.publishedDate, item.bodyHtml))

object AssistantRules {
    const val WORKSPACES = 12
    const val TURNS = 12
    const val EVIDENCE = 6
    const val MAX_CALLS = 8
    const val MAX_STEPS = 6
    const val MAX_PASSAGE = 1800
    fun scope(value: AssistantScope) {
        require(value.sources.isNotEmpty() && value.sources.size <= AnnouncementSource.entries.size)
        require(value.urls.size <= 8 && value.urls.all { url -> value.sources.any { AnnouncementUrls.article(url, it) == url } })
    }
    fun name(value: String) {
        require(value.isNotBlank() && value.length <= 80)
    }
    fun question(value: String, language: String) {
        require(value.isNotBlank() && value.length <= 1200)
        require(language in setOf("English", "Vietnamese"))
    }
    fun normalize(value: String): String = value.replace(Regex("[\\s\\p{Z}]+"), " ").trim()
    fun verify(facts: List<AssistantFact>, evidence: List<AssistantEvidence>) {
        require(facts.size in 1..8 && evidence.size <= EVIDENCE)
        val sources = evidence.associateBy { it.id }
        facts.forEach { fact ->
            require(fact.text.isNotBlank() && fact.text.length <= 1800 && fact.references.size <= 4)
            require(fact.kind != AssistantFactKind.FACT || fact.references.isNotEmpty())
            fact.references.forEach { ref ->
                val source = requireNotNull(sources[ref.evidenceId])
                require(ref.paragraph in source.paragraphs.indices)
                val quote = normalize(ref.quote)
                require(quote.length in 8..600 && normalize(source.paragraphs[ref.paragraph]).contains(quote))
            }
        }
    }
}
