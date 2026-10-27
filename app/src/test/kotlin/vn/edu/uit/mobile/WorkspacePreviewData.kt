package vn.edu.uit.mobile

import kotlinx.datetime.Instant
import vn.edu.uit.mobile.common.*
import vn.edu.uit.mobile.courses.*
import vn.edu.uit.mobile.model.*

/** Synthetic UI fixtures. These records never enter the production repository. */
object WorkspacePreviewData {
    val now = Instant.parse("2026-10-26T01:00:00Z").epochSeconds
    private fun at(day: String, hour: String): Long = dateAt(kotlinx.datetime.LocalDate.parse(day), kotlinx.datetime.LocalTime.parse(hour))
    val courses = listOf(
        Course(5, "preview", "IT004.O21", "Advanced Database Systems", "Computer Science", "inprogress"),
        Course(6, "preview", "SE104.O22", "Software Design & Architecture", "Software Engineering", "inprogress"),
        Course(7, "preview", "IT003.O21", "Data Structures & Algorithms", "Computer Science", "inprogress"),
        Course(8, "preview", "CS114.O21", "Machine Learning", "Artificial Intelligence", "inprogress")
    )
    val sections = listOf(
        CourseSection(
            1,
            "Getting started",
            listOf(
                CourseModule(11, 5, "Your course roadmap", "resource", "https://courses.uit.edu.vn/mod/resource/view.php?id=11", "An introduction to the course, learning outcomes and how we'll work together this semester.", listOf(CourseFile("Course handbook.pdf", "https://courses.uit.edu.vn/pluginfile.php/1/handbook.pdf", 2400000))),
                CourseModule(12, 5, "Course announcements", "forum", "https://courses.uit.edu.vn/mod/forum/view.php?id=12", instanceId = 20)
            )
        ),
        CourseSection(
            2,
            "Week 04 · Query optimization",
            listOf(
                CourseModule(13, 5, "Understanding query execution plans", "resource", "", "Explore how a database engine turns a declarative query into an efficient execution plan.", listOf(CourseFile("04_Query_Optimization.pdf", "https://courses.uit.edu.vn/pluginfile.php/1/optimization.pdf", 4800000))),
                CourseModule(14, 5, "Interactive walkthrough", "h5pactivity", "", files = listOf(CourseFile("Query planning.h5p", "https://courses.uit.edu.vn/pluginfile.php/1/planning.h5p", 8200000)))
            )
        )
    )
    val events = listOf(
        CalendarEvent("due:1", 1, "preview", 6, "Architecture review & design patterns", at("2026-10-26", "23:59"), deadline = true, needsAction = true),
        CalendarEvent("due:2", 2, "preview", 5, "Query optimization lab report", at("2026-10-28", "17:00"), deadline = true, needsAction = true),
        CalendarEvent("due:3", 3, "preview", 8, "Model evaluation notebook", at("2026-10-30", "23:59"), deadline = true, needsAction = true),
        CalendarEvent("due:4", 4, "preview", 7, "Sorting algorithms analysis", at("2026-10-24", "17:00"), deadline = true, submitted = true)
    )
    private fun grade(code: String, name: String, score: Double) = CourseGradeRow(code, name, 3, 9.0, 8.5, 8.0, score, score, "Passed")
    val snapshot = PortalSnapshot(
        courses = courses,
        profile = StudentProfile("22520001", "Alex Morgan", faculty = "Software Engineering", trainingProgram = "Bachelor of Software Engineering"),
        timetable = listOf(
            DaaTimetableSlot("IT004.O21", "IT004", "Advanced Database Systems", 3, 1, 1, 3, "B3.14", "Dr. Taylor Nguyen", "2026-09-01", "2026-12-31"),
            DaaTimetableSlot("SE104.O22", "SE104", "Software Design & Architecture", 3, 1, 6, 8, "A2.06", "Dr. Jamie Tran", "2026-09-01", "2026-12-31"),
            DaaTimetableSlot("CS114.O21", "CS114", "Machine Learning", 3, 2, 4, 5, "B4.12", "Dr. Sam Le", "2026-09-01", "2026-12-31"),
            DaaTimetableSlot("IT003.O21", "IT003", "Data Structures & Algorithms", 3, 3, 1, 3, "A1.09", "Dr. Chris Pham", "2026-09-01", "2026-12-31"),
            DaaTimetableSlot("IT004.O21", "IT004", "Database practice", 1, 4, 6, 10, "Lab C.02", "Dr. Taylor Nguyen", "2026-09-01", "2026-12-31")
        ),
        notices = listOf(ClassScheduleChangeNotice(1, ScheduleChangeType.CANCELED, "CS114.O21", "Machine Learning: Tuesday class canceled", "B4.12", "2026-10-27", 4, 5)),
        exams = listOf(DaaExamSlot("IT004.O21", "Database Systems · Midterm", 2, "2026-11-06", "A2.04", "24"), DaaExamSlot("SE104.O22", "Software Architecture · Midterm", 3, "2026-11-12", "B3.09", "18")),
        events = events,
        sections = mapOf("preview:5" to sections),
        transcript = AcademicTranscript(
            listOf(
                SemesterGradeSummary("Fall 2025", 7.6, 24, 24, listOf(grade("IT001", "Introduction to Programming", 7.6))),
                SemesterGradeSummary("Spring 2026", 8.1, 25, 25, listOf(grade("IT002", "Object-Oriented Programming", 8.1))),
                SemesterGradeSummary("Fall 2026", 8.65, 27, 27, listOf(grade("IT004", "Advanced Database Systems", 8.8), grade("SE104", "Software Design & Architecture", 9.0), grade("IT003", "Data Structures & Algorithms", 8.2)))
            ),
            8.16,
            76,
            63.3
        ),
        announcements = listOf(DaaAnnouncement(2, "Your midterm exam timetable is now available", "https://daa.uit.edu.vn/node/2", now - 3600, "Check your exam dates and rooms in the academic hub.")),
        courseNews = listOf(Discussion(1, 20, "Week 5 materials are ready to explore", "Dr. Taylor Nguyen", now - 7200, "This week we'll look at indexing strategies and execution plans.")),
        updatedAt = now - 300
    )
    val courseState = CoursesState(snapshot = snapshot, selected = courses[0], sections = sections)
    val inboxState = InboxState(events = events)
    val conversations = listOf(Conversation(1, "Dr. Taylor Nguyen", 2), Conversation(2, "Software Architecture Group", 0), Conversation(3, "Jamie Chen", 1))
    val chatState = InboxState(
        tab = 2,
        conversations = conversations,
        selected = conversations[0],
        messages = listOf(
            Message(1, "Hi Alex, I've reviewed your project proposal. The indexing strategy looks promising.", "Dr. Taylor Nguyen", now - 3600),
            Message(2, "For your next iteration, include a comparison of the query execution plans before and after adding the index.", "Dr. Taylor Nguyen", now - 3500),
            Message(3, "We can go through the results during Thursday's lab session.", "Dr. Taylor Nguyen", now - 3400)
        )
    )
}
