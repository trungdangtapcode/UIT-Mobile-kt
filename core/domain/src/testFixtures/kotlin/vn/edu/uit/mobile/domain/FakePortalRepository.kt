package vn.edu.uit.mobile.domain

import kotlinx.coroutines.flow.*
import vn.edu.uit.mobile.common.*
import vn.edu.uit.mobile.model.*

open class FakePortalRepository : PortalRepository {
    override val snapshot = MutableStateFlow(PortalSnapshot())
    override val syncState = MutableStateFlow(SyncState())
    var submits = 0
    override suspend fun calendar(year: Int, month: Int): AppResult<Unit, AppError> = AppResult.Success(Unit)
    override suspend fun refresh(): AppResult<Unit, AppError> = AppResult.Success(Unit)
    override suspend fun refreshStudentServices(): AppResult<Unit, AppError> = AppResult.Success(Unit)
    override suspend fun studentProfile(accountId: String): AppResult<StudentProfileDetails, AppError> = AppResult.Failure(AppError.NetworkOffline)
    override suspend fun studentProfileScan(accountId: String, documentId: String): AppResult<ProfileScan, AppError> = AppResult.Failure(AppError.NetworkOffline)
    override suspend fun courseDetails(course: Course): AppResult<List<CourseSection>, AppError> = AppResult.Success(emptyList())
    override suspend fun assignments(course: Course): AppResult<List<Assignment>, AppError> = AppResult.Success(emptyList())
    override suspend fun submission(course: Course, assignment: Assignment): AppResult<SubmissionStatus, AppError> = AppResult.Success(SubmissionStatus("new"))
    override suspend fun submitFile(course: Course, assignment: Assignment, name: String, bytes: ByteArray, confirmed: Boolean): AppResult<Unit, AppError> {
        submits++
        return AppResult.Success(Unit)
    }
    override suspend fun discussions(course: Course, module: CourseModule): AppResult<List<Discussion>, AppError> = AppResult.Success(emptyList())
    override suspend fun reply(course: Course, postId: Long, message: String): AppResult<Unit, AppError> = AppResult.Success(Unit)
    override suspend fun grades(course: Course): AppResult<List<GradeItem>, AppError> = AppResult.Success(emptyList())
    override suspend fun participants(course: Course): AppResult<List<Participant>, AppError> = AppResult.Success(emptyList())
    override suspend fun notifications(): AppResult<List<Message>, AppError> = AppResult.Success(emptyList())
    override suspend fun markRead(id: Long?): AppResult<Unit, AppError> = AppResult.Success(Unit)
    override suspend fun conversations(): AppResult<List<Conversation>, AppError> = AppResult.Success(emptyList())
    override suspend fun messages(conversationId: Long): AppResult<List<Message>, AppError> = AppResult.Success(emptyList())
    override suspend fun sendMessage(conversationId: Long, text: String): AppResult<Unit, AppError> = AppResult.Success(Unit)
    override suspend fun download(course: Course, file: CourseFile): AppResult<DownloadedFile, AppError> = AppResult.Success(DownloadedFile("/test/file", "sha"))
    override suspend fun inspectH5p(course: Course, file: CourseFile): AppResult<H5pSummary, AppError> = AppResult.Success(H5pSummary("Title", "", emptyList()))
}
