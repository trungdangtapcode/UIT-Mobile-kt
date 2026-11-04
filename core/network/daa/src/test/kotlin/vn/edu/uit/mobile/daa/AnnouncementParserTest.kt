package vn.edu.uit.mobile.daa

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import vn.edu.uit.mobile.domain.*

class AnnouncementParserTest {
    private val source = AnnouncementSource.PORTAL
    private val html = """<main></main><div hidden><a href="/bai-viet/scholarship"><span>Thông báo chung</span><h3>Đăng ký học bổng</h3><p>Excerpt</p><p>03/11/2026</p></a><a href="/bai-viet?category=thong-bao&amp;page=2">2</a></div>"""

    @Test fun observedPublicListFragmentsParseForEverySupportedSource() {
        val names = listOf("portal", "ctsv", "oep", "youth", "research", "faculty")
        AnnouncementSource.entries.zip(names).forEach { (source, file) ->
            val raw = javaClass.getResource("/announcements/$file.html")!!.readText()
            val result = AnnouncementParser.list(source, 0, raw)
            assertTrue(result.items.isNotEmpty(), source.name)
            assertTrue(result.items.all { it.title.isNotBlank() && it.source == source && it.publishedDate.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) }, source.name)
        }
    }

    @Test fun streamedPortalHtmlPreservesDateTitleAndPaginationWithoutJavaScript() {
        val page = AnnouncementParser.list(source, 0, html)
        assertEquals("2026-11-03", page.items.single().publishedDate)
        assertEquals(AnnouncementTopic.SCHOLARSHIP, page.items.single().topic)
        assertTrue(page.more)
        assertEquals("Excerpt", page.items.single().excerpt)
    }

    @Test fun unknownTemplateFailsInsteadOfReplacingGoodCacheWithAnEmptyList() {
        assertThrows(IllegalStateException::class.java) { AnnouncementParser.list(source, 0, "<h1>Sign in</h1>") }
    }

    @Test fun articleKeepsFormattingTablesImagesAndAttachmentsAndRemovesExecutableMarkup() {
        val article = AnnouncementParser.article(AnnouncementParser.list(source, 0, html).items.single(), """<article><h1>Scholarship</h1><time>03/11/2026</time><div class="prose-content"><p><b>Deadline</b> <a href="/uploads/rules.pdf">Rules</a></p><table><tr><th>Type</th><th>Due</th></tr><tr><td>One</td><td>Tomorrow</td></tr></table><img src="/uploads/poster.png" onerror="alert(1)"><script>bad()</script><form action="/change">Submit</form></div></article>""")
        assertFalse(article.bodyHtml.contains("script"))
        assertFalse(article.bodyHtml.contains("onerror"))
        assertFalse(article.bodyHtml.contains("form"))
        assertTrue(article.bodyHtml.contains("https://portal.uit.edu.vn/uploads/rules.pdf"))
        val blocks = AnnouncementParser.blocks(article)
        assertTrue(blocks.any { it.kind == ForumBlockKind.TABLE })
        assertTrue(blocks.any { it.kind == ForumBlockKind.IMAGE })
    }

    @Test fun observedPortalMediaRouteIsAllowedWithoutAllowingPersonalProfileApis() {
        assertTrue(AnnouncementUrls.publicAsset("https://portal.uit.edu.vn/media/ir.attachment/36566701/file"))
        assertFalse(AnnouncementUrls.publicAsset("https://portal.uit.edu.vn/media/ir.attachment/36566701/file?token=secret"))
        assertFalse(AnnouncementUrls.publicAsset("https://ctsv.uit.edu.vn/media/ir.attachment/36566701/file"))
        assertNotEquals(announcementText("学費"), announcementText("奨学金"))
    }

    @Test fun urlsRejectCredentialsUnsafeSchemesAndAccountEndpoints() {
        for (url in listOf("javascript:alert(1)", "http://portal.uit.edu.vn/bai-viet/a", "https://x:secret@portal.uit.edu.vn/bai-viet/a", "https://portal.uit.edu.vn/sinh-vien/hoc-bong", "https://evil.example/bai-viet/a")) assertNull(AnnouncementUrls.article(url, source))
        assertFalse(AnnouncementUrls.publicAsset("https://portal.uit.edu.vn/api/sinh-vien/anh-the"))
        assertFalse(AnnouncementUrls.publicAsset("https://evil.example/uploads/a.png"))
    }

    @Test fun transportUsesAnonymousGetAndRejectsExternalRedirectsAndOversizedBodies() = runTest {
        var calls = 0
        val client = HttpClient(
            MockEngine { request ->
                calls++
                assertEquals(HttpMethod.Get, request.method)
                assertNull(request.headers[HttpHeaders.Cookie])
                assertNull(request.headers[HttpHeaders.Authorization])
                respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://evil.example/"))
            }
        ) { followRedirects = false }
        try {
            PublicAnnouncementClient(client).list(source, 0)
            fail<Unit>("Expected rejection")
        } catch (_: IllegalArgumentException) { }
        assertEquals(1, calls)
        val oversized = HttpClient(MockEngine { respond("x", headers = headersOf(HttpHeaders.ContentType to listOf("text/html"), HttpHeaders.ContentLength to listOf("9999999"))) })
        try {
            PublicAnnouncementClient(oversized).list(source, 0)
            fail<Unit>("Expected size rejection")
        } catch (_: IllegalStateException) { }
    }
}
