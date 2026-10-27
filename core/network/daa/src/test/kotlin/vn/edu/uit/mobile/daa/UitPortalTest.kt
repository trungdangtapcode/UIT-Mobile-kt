package vn.edu.uit.mobile.daa

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import vn.edu.uit.mobile.common.InvalidResponseException

class UitPortalTest {
    @Test fun `current portal rows retain fortnightly meetings and flatten multiple slots`() {
        val data = Json.parseToJsonElement("""{"rows":[{"code":"IT001.Q11","subjectCode":"IT001","subjectName":"Programming","credit":4,"lecturer":"Test Lecturer","weekly2":2,"startDate":"2026-09-07","endDate":"2026-12-28","slots":[{"thu":2,"tietBatDau":1,"tietKetThuc":3,"room":"A101"},{"thu":4,"tietBatDau":4,"tietKetThuc":5,"room":"A102"}]}]}""")
        val slots = UitPortalParser.timetable(data)
        assertEquals(listOf(1, 3), slots.map { it.dayOfWeek })
        assertTrue(slots.all { it.weekInterval == 2 })
        assertEquals("IT001.Q11", slots.first().classCode)
        assertEquals("2026-09-07", slots.first().startDate)
    }

    @Test fun `invalid JSON schema cannot erase an offline timetable`() {
        assertThrows(InvalidResponseException::class.java) { UitPortalParser.timetable(Json.parseToJsonElement("""{"error":"expired"}""")) }
        assertTrue(UitPortalParser.timetable(Json.parseToJsonElement("""{"rows":[]}""")).isEmpty())
    }

    @Test fun `transcript maps scores without inventing grades for missing components`() {
        val data = Json.parseToJsonElement("""{"bySemester":{"semester_groups":[{"semester_label":"HK1","year_name":"2026","average_point":8.5,"total_credit":3,"subjects":[{"subject_code":"IT001","subject_name":"Programming","number_of_credit":3,"process_point":"","midterm_score":"7.5","practice_point":null,"final_point":"9","course_point":"8.5","status_point":"passed"}]}],"summary":{"accumulated_credits":12,"gpa_accumulated":8.2}},"byCtdt":{"statistics":{"total_program_credit":120}}}""")
        val result = UitPortalParser.transcript(data)
        assertEquals(8.2, result.cumulativeGpa)
        assertEquals(10.0, result.graduationProgress)
        val semester = result.semesters.single()
        assertEquals("HK1 · 2026", semester.semesterName)
        assertEquals(3, semester.creditsEarned)
        assertNull(semester.courses.single().processScore)
        assertNull(semester.courses.single().practiceScore)
    }

    @Test fun `server profile records are decoded without running remote scripts`() {
        val profile = """{"studentCode":"TEST001","fullName":"Test Student","personal":{"date_of_birth":"2005-01-01"},"academic":{"faculty_name":"Computer Science","training_program":"Standard"}}"""
        val frame = JsonArray(listOf(JsonPrimitive(1), JsonPrimitive("1:[\"$\",\"div\",null,{\"data\":$profile}]\n")))
        val result = UitPortalParser.profile("<html><script>self.__next_f.push($frame)</script></html>")
        assertEquals("TEST001", result.studentId)
        assertEquals("Computer Science", result.faculty)
        assertEquals("2005-01-01", result.birthDate)
    }

    @Test fun `exam times use portal clock time rather than interpreting lesson periods as shifts`() {
        val exams = UitPortalParser.exams(Json.parseToJsonElement("""{"items":[{"section_class_code":"IT001.Q11","subject_name":"Programming","date":"12/12/2026","shift":"7-9","start_time":"13:30:00","room":"A101"}]}"""))
        assertEquals("13:30", exams.single().startTime)
        assertEquals("2026-12-12", exams.single().examDate)
    }
}
