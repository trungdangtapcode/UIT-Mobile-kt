package vn.edu.uit.mobile.domain

import java.net.URI
import java.security.MessageDigest
import java.text.Normalizer
import kotlinx.coroutines.flow.StateFlow

/** Public source identity; never an authenticated university service. */
enum class AnnouncementSource(val origin: String, val path: String, val label: String) {
    PORTAL("https://portal.uit.edu.vn", "/bai-viet?category=thong-bao", "Portal UIT"),
    CTSV("https://ctsv.uit.edu.vn", "/", "CTSV"),
    OEP("https://oep.uit.edu.vn", "/vi/thong-bao-chung", "OEP"),
    YOUTH("https://tuoitre.uit.edu.vn", "/", "Tuổi trẻ UIT"),
    RESEARCH("https://khcn.uit.edu.vn", "/thong-bao", "KHCN"),
    FACULTY_IS("https://httt.uit.edu.vn", "/category/thong-bao-hoc-vu/", "Khoa HTTT");

    fun page(index: Int): String {
        require(index in 0..100)
        if (index == 0) return origin + path
        return when (this) {
            PORTAL -> "$origin$path&page=${index + 1}"
            CTSV -> "$origin/node?page=$index"
            FACULTY_IS -> "$origin${path}page/${index + 1}/"
            else -> "$origin$path?page=$index"
        }
    }
}

enum class AnnouncementTopic { SCHOLARSHIP, TUITION, REGISTRATION, OTHER }

data class OfficialAnnouncement(
    val url: String,
    val source: AnnouncementSource,
    val title: String,
    val publishedDate: String = "",
    val excerpt: String = "",
    val labels: List<String> = emptyList(),
    val bodyHtml: String = "",
    val bodySavedAt: Long = 0,
    val readRevision: String = "",
    val saved: Boolean = false,
    val reminderAt: Long = 0,
    val reminderDelivered: Boolean = false,
    val changed: Boolean = false
) {
    val revision: String = announcementHash("$title\n$publishedDate\n$excerpt\n$bodyHtml")
    val unread: Boolean get() = readRevision != revision

    // Title/date equality is a grouping hint, never evidence that bodies are identical.
    val groupKey: String get() = if (publishedDate.isBlank() || announcementText(title).isBlank()) url else "$publishedDate:${announcementText(title)}"
    val topic: AnnouncementTopic get() {
        val text = announcementText(title + " " + labels.joinToString(" "))
        return when {
            "hoc bong" in text -> AnnouncementTopic.SCHOLARSHIP
            "hoc phi" in text -> AnnouncementTopic.TUITION
            "dang ky hoc phan" in text || "dkhp" in text -> AnnouncementTopic.REGISTRATION
            else -> AnnouncementTopic.OTHER
        }
    }
}

data class AnnouncementSourceState(val enabled: Boolean = false, val checkedAt: Long = 0, val failed: Boolean = false, val nextPage: Int = 0, val more: Boolean = true)
data class AnnouncementInbox(
    val items: List<OfficialAnnouncement> = emptyList(),
    val sources: Map<AnnouncementSource, AnnouncementSourceState> = AnnouncementSource.entries.associateWith { AnnouncementSourceState(enabled = it == AnnouncementSource.PORTAL) },
    val alertsEnabled: Boolean = false,
    val pending: Set<String> = emptySet(),
    val storageFailed: Boolean = false
)
data class AnnouncementPage(val items: List<OfficialAnnouncement>, val more: Boolean)
data class AnnouncementDocument(val item: OfficialAnnouncement, val blocks: List<ForumBlock>, val offline: Boolean = false)

interface AnnouncementRepository {
    val state: StateFlow<AnnouncementInbox>
    suspend fun initialize()
    suspend fun refresh()
    suspend fun more(source: AnnouncementSource)
    suspend fun configure(source: AnnouncementSource, enabled: Boolean)
    suspend fun alerts(enabled: Boolean)
    suspend fun open(url: String): AnnouncementDocument
    suspend fun markRead(url: String, revision: String? = null)
    suspend fun save(url: String, saved: Boolean)
    suspend fun remind(url: String, at: Long)
    suspend fun acknowledge(urls: Set<String>, reminders: Boolean = false)
    suspend fun clear()
    suspend fun image(url: String): ByteArray
}

fun announcementText(value: String): String = Normalizer.normalize(value.lowercase().replace('đ', 'd'), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
fun announcementHash(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

object AnnouncementUrls {
    fun safe(raw: String, base: String): String? = runCatching {
        require(raw.length <= 2048 && raw.none { it.isISOControl() || it == '\\' })
        val uri = URI(base).resolve(raw.trim()).normalize()
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.port in listOf(-1, 443))
        uri.toASCIIString()
    }.getOrNull()
    fun article(raw: String, source: AnnouncementSource): String? = safe(raw, source.origin)?.takeIf {
        val uri = URI(it)
        uri.host == URI(source.origin).host && uri.rawQuery == null && uri.rawFragment == null && uri.path != "/" && !Regex("/(user|login|sinh-vien|api|wp-admin|sites|category)(/|$)").containsMatchIn(uri.path)
    }
    fun publicAsset(raw: String): Boolean = runCatching {
        val uri = URI(raw)
        safe(raw, raw) == raw &&
            AnnouncementSource.entries.any { URI(it.origin).host == uri.host } &&
            (uri.path.startsWith("/sites/") || uri.path.startsWith("/wp-content/uploads/") || uri.path.startsWith("/uploads/") || uri.host == "portal.uit.edu.vn" && Regex("/media/ir\\.attachment/[1-9][0-9]*/file").matches(uri.path) && uri.rawQuery == null)
    }.getOrDefault(false)
}
