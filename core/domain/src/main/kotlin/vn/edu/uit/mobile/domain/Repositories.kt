package vn.edu.uit.mobile.domain

import kotlinx.coroutines.flow.Flow
import vn.edu.uit.mobile.common.*
import vn.edu.uit.mobile.model.*

data class SyncState(val syncing: Boolean = false, val error: AppError? = null)
interface PortalRepository {
    val snapshot: Flow<PortalSnapshot>
    val syncState: Flow<SyncState>
    suspend fun calendar(year: Int, month: Int): AppResult<Unit, AppError>
    suspend fun refresh(): AppResult<Unit, AppError>
    suspend fun refreshStudentServices(): AppResult<Unit, AppError>
    suspend fun studentProfile(accountId: String): AppResult<StudentProfileDetails, AppError>
    suspend fun studentProfileScan(accountId: String, documentId: String): AppResult<ProfileScan, AppError>
    suspend fun courseDetails(course: Course): AppResult<List<CourseSection>, AppError>
    suspend fun assignments(course: Course): AppResult<List<Assignment>, AppError>
    suspend fun submission(course: Course, assignment: Assignment): AppResult<SubmissionStatus, AppError>
    suspend fun submitFile(course: Course, assignment: Assignment, name: String, bytes: ByteArray, confirmed: Boolean): AppResult<Unit, AppError>
    suspend fun discussions(course: Course, module: CourseModule): AppResult<List<Discussion>, AppError>
    suspend fun reply(course: Course, postId: Long, message: String): AppResult<Unit, AppError>
    suspend fun grades(course: Course): AppResult<List<GradeItem>, AppError>
    suspend fun participants(course: Course): AppResult<List<Participant>, AppError>
    suspend fun notifications(): AppResult<List<Message>, AppError>
    suspend fun markRead(id: Long?): AppResult<Unit, AppError>
    suspend fun conversations(): AppResult<List<Conversation>, AppError>
    suspend fun messages(conversationId: Long): AppResult<List<Message>, AppError>
    suspend fun sendMessage(conversationId: Long, text: String): AppResult<Unit, AppError>
    suspend fun download(course: Course, file: CourseFile): AppResult<DownloadedFile, AppError>
    suspend fun inspectH5p(course: Course, file: CourseFile): AppResult<H5pSummary, AppError>
}
interface AccountRepository {
    val accounts: Flow<List<Account>>
    suspend fun remove(accountId: String)
    suspend fun saveSessions(sessions: List<PortalSession>)
}
interface SnapshotStore {
    fun observe(accountId: String): Flow<PortalSnapshot>
    suspend fun read(accountId: String): PortalSnapshot
    suspend fun write(accountId: String, snapshot: PortalSnapshot)
    suspend fun delete(accountId: String)
}
