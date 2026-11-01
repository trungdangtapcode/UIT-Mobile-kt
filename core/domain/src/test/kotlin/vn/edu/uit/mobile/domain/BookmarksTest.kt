package vn.edu.uit.mobile.domain

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import vn.edu.uit.mobile.model.*

class BookmarksTest {
    private val course = Course(1, "one", "IT004", "Databases")

    @Test fun `file identity survives renaming and moving between modules`() {
        val file = CourseFile("Original.pdf", "https://courses.uit.edu.vn/pluginfile.php/1/notes.pdf")
        fun hit(module: Long, name: String) = WorkspaceSearchHit("file:$module", WorkspaceSearchKind.MATERIALS, "one", name, "Week $module", course, CourseModule(module, 1, "Files", "resource", ""), file.copy(name = name))
        val original = requireNotNull(bookmarkFor(hit(1, file.name)))
        val moved = requireNotNull(bookmarkFor(hit(2, "Renamed.pdf")))
        assertEquals(original.id, moved.id)
        assertEquals("", original.url)
        assertNotEquals(original.id, original.copy(accountId = "two").id)
        validateBookmark(original)
    }

    @Test fun `unattributed schedule search rows are not bookmark targets`() {
        for (kind in listOf(WorkspaceSearchKind.CLASSES, WorkspaceSearchKind.EXAMS)) assertNull(bookmarkFor(WorkspaceSearchHit("test", kind, "one", "Class", "Room", course)))
    }

    @Test fun `canonical UIT links retain escaped paths and ordinary query parameters`() {
        assertEquals("https://forum.uit.edu.vn/t/topic/42?q=hello%20world#post-2", officialBookmarkUrl("https://FORUM.UIT.EDU.VN:443/t/topic/42?q=hello%20world#post-2"))
        assertEquals("https://www.uit.edu.vn/a%2Fb", officialBookmarkUrl("https://www.uit.edu.vn/a%2Fb"))
        assertEquals("https://uit.edu.vn/", officialBookmarkUrl("https://uit.edu.vn"))
    }

    @Test fun `credentials authentication redirects foreign origins and malformed links are rejected`() {
        for (value in listOf("http://uit.edu.vn", "https://uit.edu.vn.evil.test/", "https://eviluit.edu.vn/", "https://user:password@uit.edu.vn/", "https://uit.edu.vn:444/", "https://sso.uit.edu.vn/", "https://portal.uit.edu.vn/api/auth/login", "https://courses.uit.edu.vn/login/index.php", "https://uit.edu.vn/?token=hidden", "https://uit.edu.vn/?%73esskey=hidden", "https://uit.edu.vn/#access_token=hidden", "https://uit.edu.vn/?code=hidden", "https://courses.uit.edu.vn/tokenpluginfile.php/secret/file.pdf", "https://uit.edu.vn/%ZZ")) {
            assertThrows(Exception::class.java, { officialBookmarkUrl(value) }, "Unsafe link should be rejected")
        }
    }

    @Test fun `stored references reject mismatched owners and raw source URLs`() {
        val valid = Bookmark("one", BookmarkKind.COURSE, "course:1", "Databases", "IT004", 1)
        validateBookmark(valid)
        assertThrows(IllegalArgumentException::class.java) { validateBookmark(valid.copy(url = "https://uit.edu.vn/")) }
        assertThrows(IllegalArgumentException::class.java) { validateBookmark(valid.copy(target = "course:2")) }
        assertThrows(IllegalArgumentException::class.java) { validateBookmark(Bookmark("two", BookmarkKind.NOTICE, "notice:one:7", "Notice", "DAA")) }
    }
}
