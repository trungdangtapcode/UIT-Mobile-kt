package vn.edu.uit.mobile.domain

import java.net.URI
import kotlinx.coroutines.flow.StateFlow
import vn.edu.uit.mobile.model.UmaSession

enum class UmaSource(val wire: String) { GENERAL("chung"), REGULATIONS("van_ban"), PERSONAL("ca_nhan"), ANNOUNCEMENTS("thong_bao") }
enum class UmaFailure { NETWORK, EXPIRED, CSRF, RATE_LIMIT, SERVER, RESPONSE, STORAGE }
class UmaException(val reason: UmaFailure) : Exception("UMA ${reason.name.lowercase()}")
data class UmaIdentity(val id: String, val name: String)
data class UmaCitation(val number: Int, val title: String, val location: String, val url: String?, val original: String?, val appliesTo: String, val lowConfidence: Boolean, val scanned: Boolean)
data class UmaSuggestion(val question: String, val id: Long? = null, val approved: Boolean = true)
data class UmaLinkItem(val title: String, val url: String?, val detail: String = "", val summary: String = "")
data class UmaCell(val text: String, val bold: Boolean = false, val code: Boolean = false, val tone: String = "")
data class UmaDisplay(val kind: String, val title: String = "", val note: String = "", val columns: List<String> = emptyList(), val rows: List<List<UmaCell>> = emptyList(), val children: List<UmaDisplay> = emptyList(), val expanded: Boolean = false)
data class UmaLesson(val date: String, val code: String, val subject: String, val periods: String, val time: String, val room: String, val lecturer: String, val note: String)
data class UmaWeek(val title: String, val lessons: List<UmaLesson>, val navigation: List<UmaSuggestion>)
data class UmaAnswer(
    val result: String,
    val text: String,
    val blocks: List<ForumBlock>,
    val citations: List<UmaCitation> = emptyList(),
    val warnings: List<String> = emptyList(),
    val suggestions: List<UmaSuggestion> = emptyList(),
    val links: List<UmaLinkItem> = emptyList(),
    val display: List<UmaDisplay> = emptyList(),
    val week: UmaWeek? = null,
    val understood: String = "",
    val followUp: Boolean = false,
    val unsupported: Boolean = false
)
enum class UmaTurnStatus { RUNNING, COMPLETE, FAILED, CANCELLED }
data class UmaTurn(val id: String, val question: String, val status: UmaTurnStatus, val answer: UmaAnswer? = null, val sources: Set<UmaSource> = emptySet())
data class UmaState(
    val loading: Boolean = true,
    val identity: UmaIdentity? = null,
    val available: Set<UmaSource> = emptySet(),
    val selected: Set<UmaSource> = setOf(UmaSource.GENERAL),
    val turns: List<UmaTurn> = emptyList(),
    val busy: Boolean = false,
    val error: UmaFailure? = null,
    val revision: Long = 0
)
interface UmaRepository {
    val state: StateFlow<UmaState>
    suspend fun initialize()
    suspend fun connect(session: UmaSession)
    suspend fun disconnect()
    suspend fun sources(sources: Set<UmaSource>)
    suspend fun clear()
    suspend fun ask(question: String, suggestionId: Long? = null)
}
object UmaUrls {
    const val ORIGIN = "https://uma.uit.edu.vn"
    const val EMBED = "$ORIGIN/uma/nhung/?site=portal&trang=%2Fsinh-vien%2Ftkb"
    const val LOGIN = "$ORIGIN/oidc/authenticate/?next=%2Fuma%2Fnhung%2F%3Fsite%3Dportal%26trang%3D%252Fsinh-vien%252Ftkb"

    /** Browser-only links: no application cookies are forwarded when opening a citation. */
    fun link(raw: String): String? = runCatching {
        if (raw.isBlank() || raw.startsWith("//") || raw.contains('\\') || raw.any { it.isISOControl() }) return null
        val u = URI(ORIGIN).resolve(raw.trim())
        if (u.scheme != "https" || u.host.isNullOrBlank() || u.rawUserInfo != null || u.port !in setOf(-1, 443)) return null
        if (u.rawQuery.orEmpty().contains(Regex("(?i)(?:session|token|password|csrf|ticket|code)="))) return null
        if (u.path.orEmpty().contains(Regex("(?i)(?:logout|dang-xuat|delete|revoke)"))) return null
        u.toASCIIString()
    }.getOrNull()
}
