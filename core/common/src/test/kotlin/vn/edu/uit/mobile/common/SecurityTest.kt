package vn.edu.uit.mobile.common

import kotlinx.datetime.LocalTime
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import vn.edu.uit.mobile.model.*

class SecurityTest {
    @Test fun `root relative Moodle URLs preserve graduate prefix`() {
        assertEquals("https://coursesold.uit.edu.vn/sdh/course/view.php?id=2", MoodleUrlResolver.resolve("https://coursesold.uit.edu.vn/sdh", "/course/view.php?id=2"))
        assertEquals("https://coursesold.uit.edu.vn/sdh/mod/resource/view.php?id=2", MoodleUrlResolver.resolve("https://coursesold.uit.edu.vn/sdh", "/sdh/mod/resource/view.php?id=2"))
    }

    @Test fun `relative redirect preserves current directory and prefix`() {
        assertEquals("https://coursesold.uit.edu.vn/sdh/mod/resource/next.php", MoodleUrlResolver.redirect("https://coursesold.uit.edu.vn/sdh", "https://coursesold.uit.edu.vn/sdh/mod/resource/view.php", "next.php"))
        assertEquals("https://external.example/file", MoodleUrlResolver.resolve("https://coursesold.uit.edu.vn/sdh", "https://external.example/file"))
    }

    @Test fun `stored file URLs remove credentials and signed paths`() {
        val clean = UrlSanitizer.credentialFreeUrl("https://student:pass@courses.uit.edu.vn/tokenpluginfile.php/abc123/42/mod_resource/content/0/slides%20one.pdf?token=x&session=y&file=one&authkey=z#frag")
        assertEquals("https://courses.uit.edu.vn/pluginfile.php/42/mod_resource/content/0/slides%20one.pdf?file=one", clean)
        assertNull(UrlSanitizer.credentialFreeUrl("javascript:alert(1)"))
        assertNull(UrlSanitizer.credentialFreeUrl("https://courses.uit.edu.vn/tokenpluginfile.php/invalid"))
    }

    @Test fun `session classification traverses causes and honors auth errors`() {
        assertEquals(SessionHealthState.EXPIRED, SessionHealthClassifier.classifySessionError(Exception("wrapper", PortalException("invalid_sesskey", "Unavailable"))))
        assertEquals(SessionHealthState.EXPIRED, SessionHealthClassifier.classifySessionError(Exception("Phiên đăng nhập đã hết hạn")))
        assertEquals(SessionHealthState.UNAVAILABLE, SessionHealthClassifier.classifySessionError(Exception("HTTP 503")))
    }

    @Test fun `lesson clock handles both breaks and compact period ten`() {
        assertEquals(6..10, UitLessonTimeCalculator.parsePeriods("67890"))
        assertEquals(10..10, UitLessonTimeCalculator.parsePeriods("10"))
        assertEquals(1..3, UitLessonTimeCalculator.parsePeriods("1-3"))
        assertEquals(LocalTime(10, 0), UitLessonTimeCalculator.start(4))
        assertEquals(LocalTime(17, 0), UitLessonTimeCalculator.end(10))
        assertEquals(LocalTime(15, 30), UitLessonTimeCalculator.examStart(4))
        assertThrows(IllegalArgumentException::class.java) { UitLessonTimeCalculator.parsePeriods("321") }
    }
}
