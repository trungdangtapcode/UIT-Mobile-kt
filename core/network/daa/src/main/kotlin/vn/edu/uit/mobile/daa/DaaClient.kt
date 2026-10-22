package vn.edu.uit.mobile.daa

import vn.edu.uit.mobile.model.*
import vn.edu.uit.mobile.network.*

interface DaaScheduleDataSource {
    suspend fun timetable(): List<DaaTimetableSlot>
    suspend fun exams(): List<DaaExamSlot>
    suspend fun changes(page: Int = 0): List<ClassScheduleChangeNotice>
}
interface DaaAcademicDataSource {
    suspend fun profile(): StudentProfile
    suspend fun transcript(): AcademicTranscript
    suspend fun announcements(page: Int = 0): List<DaaAnnouncement>
}
class DaaClient(private val transport: PortalTransport) :
    DaaScheduleDataSource,
    DaaAcademicDataSource {
    private suspend fun page(path: String): String = transport.request(path).toString(Charsets.UTF_8)
    override suspend fun timetable() = DaaTimetableParser().parse(page("/ajax-block/tkb/ajax"))
    suspend fun semesterTimetable(semester: String, year: String) = DaaTimetableParser().parse(page("/sinhvien/thoikhoabieu?hocky=${vn.edu.uit.mobile.common.encode(semester)}&namhoc=${vn.edu.uit.mobile.common.encode(year)}"))
    override suspend fun exams() = DaaExamParser().parse(page("/ajax-block/lichthi/ajax"))
    override suspend fun changes(page: Int): List<ClassScheduleChangeNotice> {
        require(page >= 0)
        return DaaNoticeParser().changes(page("/thong-bao-nghi-bu?page=$page"))
    }
    override suspend fun announcements(page: Int): List<DaaAnnouncement> {
        require(page >= 0)
        return DaaNoticeParser().announcements(page("/thong-bao-chung?page=$page"))
    }
    override suspend fun profile() = DaaAcademicParser().profile(page("/sinhvien/bang-dieu-khien"))
    override suspend fun transcript() = DaaAcademicParser().transcript(page("/sinhvien/kqht"))
}
