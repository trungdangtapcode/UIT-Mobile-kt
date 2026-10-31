package vn.edu.uit.mobile.model

enum class PortalType { MOODLE, DAA, UIT_PORTAL }
enum class SessionHealthState { CHECKING, CONNECTED, EXPIRED, UNAVAILABLE }
data class Account(val id: String, val baseUrl: String, val userId: Long, val displayName: String, val portal: PortalType)
data class PortalSession(val account: Account, val cookie: String, val sesskey: String = "")
data class Course(
    val id: Long,
    val accountId: String,
    val shortname: String,
    val fullname: String,
    val category: String = "",
    val classification: String = "all",
    val hidden: Boolean = false
)
data class CourseFile(val name: String, val url: String, val size: Long = 0)
data class CourseModule(
    val id: Long,
    val courseId: Long,
    val name: String,
    val type: String,
    val url: String,
    val description: String = "",
    val files: List<CourseFile> = emptyList(),
    val instanceId: Long = 0
)
data class CourseSection(val id: Long, val name: String, val modules: List<CourseModule>)
data class Assignment(
    val id: Long,
    val courseId: Long,
    val cmid: Long,
    val name: String,
    val intro: String = "",
    val due: Long = 0,
    val cutoff: Long = 0,
    val opens: Long = 0,
    val files: List<CourseFile> = emptyList(),
    val accountId: String = ""
)
data class SubmissionStatus(val status: String, val modified: Long = 0, val files: List<CourseFile> = emptyList(), val grade: String = "") {
    val submitted: Boolean get() = status == "submitted"
}
data class Discussion(
    val id: Long,
    val forumId: Long,
    val name: String,
    val author: String,
    val created: Long,
    val bodyHtml: String = "",
    val files: List<CourseFile> = emptyList(),
    val postId: Long = 0
)
data class GradeItem(val id: String, val name: String, val grade: String, val range: String = "", val percentage: String = "", val feedback: String = "")
data class Participant(val id: Long, val name: String, val roles: String = "", val groups: String = "", val lastAccess: String = "")
data class CalendarEvent(
    val key: String,
    val id: Long,
    val accountId: String,
    val courseId: Long?,
    val name: String,
    val start: Long,
    val end: Long = start,
    val deadline: Boolean = false,
    val needsAction: Boolean = false,
    val url: String = "",
    val assignmentId: Long? = null,
    val submitted: Boolean? = null
)
data class Message(val id: Long, val text: String, val author: String = "", val created: Long = 0, val read: Boolean = false)
data class Conversation(val id: Long, val name: String, val unread: Int = 0)
data class DaaTimetableSlot(
    val classCode: String,
    val courseCode: String,
    val courseName: String,
    val credits: Int,
    val dayOfWeek: Int,
    val lessonStart: Int,
    val lessonEnd: Int,
    val room: String,
    val lecturerName: String,
    val startDate: String,
    val endDate: String,
    val weekInterval: Int = 1
)
data class DaaExamSlot(
    val classCode: String,
    val courseName: String,
    val examShift: Int,
    val examDate: String,
    val room: String,
    val seatNumber: String = "",
    val startTime: String? = null
)
enum class ScheduleChangeType { CANCELED, MAKE_UP }
data class ClassScheduleChangeNotice(
    val nodeId: Long,
    val type: ScheduleChangeType,
    val classCode: String,
    val title: String,
    val room: String,
    val targetDate: String,
    val lessonStart: Int,
    val lessonEnd: Int,
    val createdAt: Long = 0
)
data class DaaAnnouncement(val nodeId: Long, val title: String, val url: String, val createdAt: Long, val bodyHtml: String)
data class StudentProfile(
    val studentId: String = "",
    val fullName: String = "",
    val birthDate: String = "",
    val faculty: String = "",
    val trainingProgram: String = "",
    val avatarUrl: String = ""
)
data class CourseGradeRow(
    val courseCode: String,
    val courseName: String,
    val credits: Int,
    val processScore: Double?,
    val midtermScore: Double?,
    val practiceScore: Double?,
    val finalScore: Double?,
    val totalScore: Double?,
    val status: String
)
data class SemesterGradeSummary(
    val semesterName: String,
    val semesterGpa: Double?,
    val creditsAttempted: Int,
    val creditsEarned: Int,
    val courses: List<CourseGradeRow>
)
data class AcademicTranscript(
    val semesters: List<SemesterGradeSummary> = emptyList(),
    val cumulativeGpa: Double? = null,
    val totalCreditsEarned: Int = 0,
    val graduationProgress: Double? = null
)
enum class ScheduleKind { CLASS, CANCELED, MAKE_UP, EXAM, DEADLINE, EVENT }
data class ScheduleEntry(
    val key: String,
    val title: String,
    val start: Long,
    val end: Long,
    val kind: ScheduleKind,
    val classCode: String = "",
    val room: String = "",
    val course: Course? = null,
    val needsAction: Boolean = false
)
data class DeadlineReminder(val key: String, val event: CalendarEvent, val hours: Int)
data class H5pEntry(val title: String, val text: List<String>, val media: List<String>)
data class H5pSummary(val title: String, val library: String, val entries: List<H5pEntry>)
data class DownloadedFile(val path: String, val sha256: String)
data class DaaSearchSource(val accountId: String, val announcements: List<DaaAnnouncement>, val notices: List<ClassScheduleChangeNotice>)

data class PortalSnapshot(
    val courses: List<Course> = emptyList(),
    val timetable: List<DaaTimetableSlot> = emptyList(),
    val notices: List<ClassScheduleChangeNotice> = emptyList(),
    val exams: List<DaaExamSlot> = emptyList(),
    val events: List<CalendarEvent> = emptyList(),
    val announcements: List<DaaAnnouncement> = emptyList(),
    val transcript: AcademicTranscript = AcademicTranscript(),
    val profile: StudentProfile = StudentProfile(),
    val sections: Map<String, List<CourseSection>> = emptyMap(),
    val assignments: List<Assignment> = emptyList(),
    val grades: Map<String, List<GradeItem>> = emptyMap(),
    val updatedAt: Long = 0,
    val courseNews: List<Discussion> = emptyList(),
    val studentServices: StudentServicesSnapshot? = null,
    val portalLinked: Boolean = false,
    val daaSearchSources: List<DaaSearchSource> = emptyList()
)
