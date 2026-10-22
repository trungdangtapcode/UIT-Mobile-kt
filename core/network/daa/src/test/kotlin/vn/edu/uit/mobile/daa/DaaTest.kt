package vn.edu.uit.mobile.daa

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import vn.edu.uit.mobile.common.*
import vn.edu.uit.mobile.model.*

class DaaTest {
    private fun fixture(name: String) = javaClass.getResource("/$name")!!.readText()

    @Test fun `parses Drupal AJAX timetable compact periods and semester dates`() {
        val slot = DaaTimetableParser().parse(fixture("timetable.json")).single()
        assertEquals("IT004.O21.1", slot.classCode)
        assertEquals(1, slot.dayOfWeek)
        assertEquals(6, slot.lessonStart)
        assertEquals(10, slot.lessonEnd)
        assertEquals("2026-09-07", slot.startDate)
        assertEquals("2026-12-28", slot.endDate)
    }

    @Test fun `exam shifts dates and seat number survive parsing`() {
        val exam = DaaExamParser().parse(fixture("exams.html")).single()
        assertEquals(4, exam.examShift)
        assertEquals("2026-12-25", exam.examDate)
        assertEquals("42", exam.seatNumber)
    }

    @Test fun `notices extract class cancellation date periods and created timestamp`() {
        val notices = DaaNoticeParser().changes(fixture("notices.html"))
        assertEquals(2, notices.size)
        assertEquals(ScheduleChangeType.CANCELED, notices[0].type)
        assertEquals("2026-10-26", notices[0].targetDate)
        assertEquals(3, notices[0].lessonEnd)
        assertEquals(ScheduleChangeType.MAKE_UP, notices[1].type)
        assertEquals("B3.14", notices[1].room)
        assertTrue(notices[0].createdAt > 0)
    }

    @Test fun `announcements preserve title body and canonical URL`() {
        val notice = DaaNoticeParser().announcements(fixture("notices.html")).first()
        assertEquals("https://daa.uit.edu.vn/thongbao/nghi", notice.url)
        assertTrue(notice.bodyHtml.contains("IT004"))
    }

    @Test fun `academic profile and transcript retain missing scores as null`() {
        val parser = DaaAcademicParser()
        val profile = parser.profile(fixture("profile.html"))
        assertEquals("22520001", profile.studentId)
        assertEquals("2004-02-03", profile.birthDate)
        val transcript = parser.transcript(fixture("transcript.html"))
        assertEquals(8.25, transcript.cumulativeGpa)
        assertEquals(3, transcript.totalCreditsEarned)
        val grade = transcript.semesters.single().courses.single()
        assertNull(grade.practiceScore)
        assertEquals(8.5, grade.totalScore)
    }

    @Test fun `unexpected pages and Drupal login fragments fail closed`() {
        assertThrows(InvalidResponseException::class.java) { DaaTimetableParser().parse("<html>Maintenance</html>") }
        assertThrows(SessionExpiredException::class.java) { unwrapDrupalHtml("""[{"command":"insert","data":"<form id='user-login'><input name='password'></form>"}]""") }
    }
}
