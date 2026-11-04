package vn.edu.uit.mobile.daa

import com.fleeksoft.ksoup.Ksoup
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import java.io.ByteArrayOutputStream
import java.net.URI
import kotlinx.datetime.LocalDate
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.forum.network.ForumParser

interface AnnouncementRemote {
    suspend fun list(source: AnnouncementSource, page: Int): AnnouncementPage
    suspend fun article(item: OfficialAnnouncement): OfficialAnnouncement
    suspend fun image(url: String): ByteArray
}

/** Dedicated anonymous GET client. No SSO transport, cookie jar or JavaScript. */
class PublicAnnouncementClient(
    private val client: HttpClient = HttpClient(OkHttp) {
        followRedirects = false
        install(HttpTimeout) {
            requestTimeoutMillis = 20000
            connectTimeoutMillis = 8000
            socketTimeoutMillis = 15000
        }
        engine {
            config {
                followRedirects(false)
                followSslRedirects(false)
            }
        }
    }
) : AnnouncementRemote {
    override suspend fun list(source: AnnouncementSource, page: Int): AnnouncementPage = AnnouncementParser.list(source, page, get(source.page(page), 2 * 1024 * 1024).toString(Charsets.UTF_8))
    override suspend fun article(item: OfficialAnnouncement): OfficialAnnouncement {
        require(AnnouncementUrls.article(item.url, item.source) == item.url)
        return AnnouncementParser.article(item, get(item.url, 2 * 1024 * 1024).toString(Charsets.UTF_8))
    }
    override suspend fun image(url: String): ByteArray {
        require(AnnouncementUrls.publicAsset(url))
        return get(url, 3 * 1024 * 1024, image = true)
    }
    private suspend fun get(url: String, limit: Int, redirects: Int = 0, image: Boolean = false): ByteArray {
        require(AnnouncementSource.entries.any { URI(it.origin).host == URI(url).host })
        return client.prepareGet(url) {
            header(HttpHeaders.Accept, if (image) "image/png,image/jpeg,image/webp,image/gif" else "text/html")
            header(HttpHeaders.UserAgent, "UIT-Mobile/0.1 (public announcement reader)")
            headers.remove(HttpHeaders.Cookie)
            headers.remove(HttpHeaders.Authorization)
        }.execute { response ->
            if (response.status.value in listOf(301, 302, 307, 308)) {
                val target = response.headers[HttpHeaders.Location]?.let { AnnouncementUrls.safe(it, url) }
                require(redirects < 3 && target != null && URI(target).host == URI(url).host)
                require(!Regex("/(user|login|sinh-vien|api|wp-admin)(/|$)").containsMatchIn(URI(target).path))
                if (image) require(AnnouncementUrls.publicAsset(target))
                response.bodyAsChannel().cancel()
                return@execute get(target, limit, redirects + 1, image)
            }
            check(response.status.value == 200) { "Public source unavailable (${response.status.value})" }
            val type = response.contentType()?.withoutParameters()?.toString().orEmpty()
            check(if (image) type in setOf("image/png", "image/jpeg", "image/webp", "image/gif") else type == "text/html" || type == "application/xhtml+xml")
            check((response.contentLength() ?: 0) <= limit)
            val input = response.bodyAsChannel()
            val output = ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            while (true) {
                val count = input.readAvailable(chunk)
                if (count < 0) break
                check(output.size() + count <= limit)
                output.write(chunk, 0, count)
            }
            output.toByteArray()
        }
    }
}

object AnnouncementParser {
    private fun date(raw: String): String {
        val match = Regex("(?<!\\d)(\\d{1,2})[/-](\\d{1,2})[/-](\\d{4})(?!\\d)").find(raw) ?: return ""
        return runCatching { LocalDate(match.groupValues[3].toInt(), match.groupValues[2].toInt(), match.groupValues[1].toInt()).toString() }.getOrDefault("")
    }
    fun list(source: AnnouncementSource, page: Int, html: String): AnnouncementPage {
        require(html.length <= 2 * 1024 * 1024)
        val doc = Ksoup.parse(html, source.origin)
        val rows = when (source) {
            AnnouncementSource.PORTAL -> doc.select("a[href^=/bai-viet/]").filter { it.selectFirst("h3") != null }
            AnnouncementSource.YOUTH -> doc.select("#block-system-main .node, #content .info")
            AnnouncementSource.FACULTY_IS -> doc.select("section article.thumb").filter { it.selectFirst("h3 a, h2 a") != null }
            else -> doc.select("#block-system-main .node, #block-system-main .views-row")
        }
        val items = rows.mapNotNull { row ->
            val link = if (source == AnnouncementSource.PORTAL) row else row.selectFirst("h2 a, h3 a, a:has(h2), a:has(h3)") ?: row.selectFirst("a") ?: return@mapNotNull null
            val title = if (source == AnnouncementSource.PORTAL) row.selectFirst("h3")!!.text() else link.text()
            val url = AnnouncementUrls.article(link.attr("href"), source) ?: return@mapNotNull null
            if (title.isBlank()) return@mapNotNull null
            val rawDate = if (source == AnnouncementSource.PORTAL) row.select("p").lastOrNull()?.text().orEmpty() else row.selectFirst(".submitted, .published, .date, time, .views-field-created")?.text().orEmpty()
            val excerpt = if (source == AnnouncementSource.PORTAL) row.select("p").firstOrNull()?.takeIf { date(it.text()).isBlank() }?.text().orEmpty() else row.selectFirst(".field-name-body, .views-field-body")?.text().orEmpty()
            OfficialAnnouncement(url, source, title.take(1000), date(rawDate), excerpt.take(2000), if (source == AnnouncementSource.PORTAL) row.select("span").map { it.text() }.filter { it.isNotBlank() }.take(15) else emptyList())
        }.distinctBy { it.url }.take(60)
        // A changed template/login/error page must not erase or silently baseline a feed.
        check(items.isNotEmpty()) { "Public announcement list did not match the verified template" }
        val next = source.page((page + 1).coerceAtMost(100))
        val more = page < 100 && doc.select("a[href]").any { AnnouncementUrls.safe(it.attr("href"), source.page(page)) == next }
        return AnnouncementPage(items, more)
    }
    fun article(item: OfficialAnnouncement, html: String): OfficialAnnouncement {
        val doc = Ksoup.parse(html, item.url)
        val title = doc.select("article h1, h1#page-title, h1[itemprop=name], h1").map { it.text() }.firstOrNull { it.isNotBlank() } ?: error("Article title missing")
        val body = when (item.source) {
            AnnouncementSource.PORTAL -> doc.selectFirst(".prose-content")
            AnnouncementSource.FACULTY_IS -> doc.selectFirst(".article-post-content, .entry-content")
            else -> doc.selectFirst("#block-system-main .field-name-body")
        } ?: error("Article body missing")
        body.select("script,style,form,input,button,link,meta").remove()
        body.select("a[href], img[src]").forEach { element ->
            val attr = if (element.tagName() == "a") "href" else "src"
            val safe = AnnouncementUrls.safe(element.attr(attr), item.url)
            if (safe == null) {
                element.removeAttr(attr)
                if (element.tagName() == "img") element.tagName("iframe") // Visible unsupported-content fallback in the semantic reader.
            } else {
                element.attr(attr, safe)
            }
        }
        // Strip presentation/tracking attributes; the native semantic reader owns rendering.
        body.getAllElements().forEach { node ->
            node.attributes().asList().map { it.key }.filterNot { it in setOf("href", "src", "alt", "colspan", "rowspan") }.forEach(node::removeAttr)
        }
        val rawDate = doc.selectFirst("article time, #block-system-main .submitted, .published")?.text().orEmpty()
        return item.copy(title = title.take(1000), publishedDate = date(rawDate).ifBlank { item.publishedDate }, bodyHtml = body.html().take(128000))
    }
    fun blocks(item: OfficialAnnouncement): List<ForumBlock> = ForumParser.blocks(item.bodyHtml)
}
