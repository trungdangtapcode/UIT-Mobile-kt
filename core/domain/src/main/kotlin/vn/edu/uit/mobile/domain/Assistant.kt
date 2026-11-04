package vn.edu.uit.mobile.domain

import kotlinx.coroutines.flow.StateFlow

/** Public-only initial scope. No account IDs or university credentials enter this contract. */
data class AssistantScope(val sources: Set<AnnouncementSource> = setOf(AnnouncementSource.PORTAL), val urls: Set<String> = emptySet(), val forum: AssistantForumScope? = null)
data class AssistantForumScope(val topicId: Long, val startNumber: Int = 1, val limit: Int = 20)
data class AssistantForumPost(val topicId: Long, val postId: Long, val number: Int, val username: String, val created: String, val updated: String, val revision: Int, val replyTo: Int? = null, val staff: Boolean? = null, val accepted: Boolean? = null)
data class AssistantForumCoverage(
    val topicId: Long,
    val title: String,
    val checkedAt: Long,
    val streamCount: Int,
    val selectedIds: List<Long>,
    val loadedIds: List<Long>,
    val unavailableIds: List<Long>,
    val firstNumber: Int,
    val lastNumber: Int,
    val moreBefore: Boolean,
    val moreAfter: Boolean,
    val latestAt: String,
    val streamVersion: String,
    val sourceVersion: String,
    val abbreviated: Boolean
)
data class AssistantForumRead(val evidence: List<AssistantEvidence>, val coverage: AssistantForumCoverage)
fun interface AssistantForumSource {
    suspend fun read(scope: AssistantForumScope): AssistantForumRead
}
enum class AssistantForumFreshness { CURRENT, CHANGED, UNAVAILABLE }
data class AssistantForumCheck(val at: Long, val freshness: AssistantForumFreshness, val coverage: AssistantForumCoverage? = null)
data class AssistantEvidence(
    val id: String,
    val url: String,
    val source: AnnouncementSource?,
    val title: String,
    val publishedDate: String,
    val fetchedAt: Long,
    val version: String,
    val paragraphs: List<String>,
    val offline: Boolean = false,
    val abbreviated: Boolean = false,
    val forum: AssistantForumPost? = null
) {
    val sourceLabel: String get() = forum?.let { "UIT Forum · @${it.username} · #${it.number}" } ?: requireNotNull(source).label
}
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
    val checklist: List<AssistantChecklistItem> = emptyList(),
    val forumCoverage: AssistantForumCoverage? = null
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

/** Draft text is transient and unverified; it never belongs in persisted turns. */
data class AssistantProgress(val tool: String, val results: Int = 0, val draft: String = "")
interface AssistantRepository {
    val library: StateFlow<AssistantLibrary>
    suspend fun initialize()
    suspend fun create(name: String, scope: AssistantScope): String
    suspend fun rename(id: String, name: String)
    suspend fun delete(id: String)
    suspend fun clear()
    suspend fun ask(id: String, question: String, language: String, progress: (AssistantProgress) -> Unit = {})
    suspend fun checklist(workspaceId: String, turnId: String, items: List<AssistantChecklistItem>)
    suspend fun deleteTurn(workspaceId: String, turnId: String): Unit = error("Individual answer deletion is unavailable")
    suspend fun inspectForum(scope: AssistantForumScope): AssistantForumCoverage = error("Forum context is unavailable")
    suspend fun recheckForum(workspaceId: String, turnId: String): AssistantForumCheck = error("Forum context is unavailable")
}

fun assistantSourceVersion(item: OfficialAnnouncement): String = practiceDigest(listOf("assistant-text-v2", item.url, item.source.name, item.title, item.publishedDate, item.bodyHtml))

object AssistantRules {
    const val WORKSPACES = 12
    const val TURNS = 12
    const val ANNOUNCEMENTS = 6
    const val FORUM_POSTS = 40
    const val EVIDENCE = ANNOUNCEMENTS + FORUM_POSTS
    const val MAX_CALLS = 8
    const val MAX_STEPS = 6
    const val MAX_PASSAGE = 1800
    const val MAX_PARAGRAPHS = 64
    fun scope(value: AssistantScope) {
        require((value.sources.isNotEmpty() || value.forum != null) && value.sources.size <= AnnouncementSource.entries.size)
        require(value.urls.size <= 8 && value.urls.all { url -> value.sources.any { AnnouncementUrls.article(url, it) == url } })
        value.forum?.let(::forum)
    }
    fun forum(value: AssistantForumScope) {
        require(value.topicId > 0 && value.startNumber in 1..1000000 && value.limit in 1..FORUM_POSTS)
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
        require(facts.size in 1..12 && evidence.size <= EVIDENCE)
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
