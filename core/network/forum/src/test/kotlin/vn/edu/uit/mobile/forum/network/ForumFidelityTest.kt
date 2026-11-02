package vn.edu.uit.mobile.forum.network

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import vn.edu.uit.mobile.domain.*

class ForumFidelityTest {
    private fun walk(blocks: List<ForumBlock>): List<ForumBlock> = blocks.flatMap { listOf(it) + walk(it.children) + it.cells.flatMap { row -> row.flatMap(::walk) } }

    @Test fun inlineFormattingAndLinkRangesRetainSourceOrder() {
        val b = ForumParser.blocks("<p>Read <strong>bold <em>and italic</em></strong> then <code>x &lt; y</code><br><a href='/t/123/4'>reply</a>.</p>").single()
        assertEquals("Read bold and italic then x < y\nreply.", b.text)
        assertTrue(b.inline.any { it.text == "and italic" && it.bold && it.italic })
        assertTrue(b.inline.any { it.code && it.text == "x < y" })
        assertEquals("https://forum.uit.edu.vn/t/123/4", b.inline.single { it.text == "reply" }.link)
    }

    @Test fun nestedImagesSurviveQuotesDetailsListsAndTableCells() {
        val b = walk(ForumParser.blocks("<aside class='quote' data-topic='123' data-post='4'><div class='title'>Reader:</div><blockquote><p>Quote<img src='/uploads/q.png'></p></blockquote></aside><details><summary>More</summary><ul><li>A<img src='/uploads/l.png'></li></ul></details><table><tr><td><strong>B</strong><img src='/uploads/t.png'></td></tr></table>"))
        assertEquals(3, b.count { it.kind == ForumBlockKind.IMAGE })
        assertEquals("More", b.first { it.kind == ForumBlockKind.DETAILS }.label)
        val quote = b.first { it.kind == ForumBlockKind.QUOTE }
        assertEquals("Reader:", quote.label)
        assertEquals("https://forum.uit.edu.vn/t/123/4", quote.links.single().url)
        assertTrue(b.any { x -> x.inline.any { it.bold && it.text == "B" } })
    }

    @Test fun hiddenContentHasAnExplicitConcealmentBoundary() {
        val b = ForumParser.blocks("<p>Visible <span class='spoiler'>Secret<img src='/uploads/secret.png'></span> end</p>")
        assertEquals(listOf(ForumBlockKind.TEXT, ForumBlockKind.SPOILER, ForumBlockKind.TEXT), b.map { it.kind })
        assertFalse(b.filter { it.kind != ForumBlockKind.SPOILER }.any { "Secret" in it.text })
        assertEquals(1, walk(b.single { it.kind == ForumBlockKind.SPOILER }.children).count { it.kind == ForumBlockKind.IMAGE })
    }

    @Test fun lightboxOriginalIsDistinctFromThumbnailAndActiveUrlsAreRejected() {
        val b = ForumParser.blocks("<div class='lightbox-wrapper'><a class='lightbox' href='/uploads/original.png'><img src='/uploads/preview.png' alt='Chart'><div class='meta'><svg class='svg-icon'></svg>decoration</div></a></div>")
        assertEquals(1, b.size)
        assertEquals("https://forum.uit.edu.vn/uploads/original.png", b.single().original)
        assertEquals("https://forum.uit.edu.vn/uploads/preview.png", b.single().links.single().url)
        val unsafe = ForumParser.blocks("<a class='lightbox' href='javascript:bad()'><img src='/uploads/a.png'></a>").single()
        assertNull(unsafe.original)
    }

    @Test fun revisionUsesServerRevisionIdsRatherThanDisplayVersion() {
        val request = ForumRequest.Revision(123, 456, 9)
        val json = """{"post_id":456,"current_revision":9,"current_version":3,"previous_revision":4,"next_revision":12,"body_changes":{"inline":"<p>Before <del>old</del><ins>new</ins></p>"},"title_changes":{"inline":"<ins>Title</ins>"}}"""
        val revision = ForumParser.parse(request, json).revision!!
        assertEquals(9, revision.version)
        assertEquals(3, revision.displayVersion)
        assertEquals(4, revision.previous)
        assertEquals(12, revision.next)
        assertTrue(revision.blocks.flatMap { it.inline }.any { it.text == "old" && it.change == -1 })
        assertTrue(revision.blocks.flatMap { it.inline }.any { it.text == "new" && it.change == 1 })
        assertFalse(revision.title.isEmpty())
    }

    @Test fun mismatchedAndHiddenHistoryNeverRenders() {
        val request = ForumRequest.Revision(123, 456, 2)
        for (extra in listOf("\"current_hidden\":true", "\"previous_hidden\":true")) {
            val e = assertThrows(ForumException::class.java) { ForumParser.parse(request, """{"post_id":456,"current_revision":2,$extra,"body_changes":{"inline":"private"}}""") }
            assertEquals(ForumFailure.UNAVAILABLE, e.reason)
        }
        assertThrows(ForumException::class.java) { ForumParser.parse(request, """{"post_id":999,"current_revision":2}""") }
        assertThrows(ForumException::class.java) { ForumParser.parse(request, """{"post_id":456,"current_revision":3}""") }
    }

    @Test fun diffLimitFailureIsExplicitAndDangerousHtmlNeverSurvives() {
        val r = ForumParser.parse(ForumRequest.Revision(1, 2, 3), """{"post_id":2,"current_revision":3,"diff_error":true}""").revision!!
        assertTrue(r.diffUnavailable)
        val b = walk(ForumParser.blocks("<details><summary>Open</summary><script>secret()</script><form>password</form><p><a href='javascript:bad()'>Text</a><img src='data:image/svg+xml,bad'></p></details>"))
        assertFalse(b.any { "secret" in it.text || "password" in it.text })
        assertTrue(b.flatMap { it.inline }.all { it.link == null })
        assertFalse(b.any { it.kind == ForumBlockKind.IMAGE })
    }
}
