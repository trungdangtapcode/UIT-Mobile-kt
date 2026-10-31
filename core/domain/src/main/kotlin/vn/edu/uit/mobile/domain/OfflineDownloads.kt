package vn.edu.uit.mobile.domain

import java.security.MessageDigest
import vn.edu.uit.mobile.model.*

enum class OfflineDownloadStatus { QUEUED, DOWNLOADING, WAITING_WIFI, SAVED, FAILED, CANCELED, REMOVING }
data class OfflineDownloadSpec(val course: Course, val section: String, val sectionName: String, val file: CourseFile) {
    val id: String get() = offlineDownloadId(course, file)
}
data class OfflineDownloadItem(val spec: OfflineDownloadSpec, val status: OfflineDownloadStatus = OfflineDownloadStatus.QUEUED, val bytes: Long = 0, val message: String? = null) {
    val id: String get() = spec.id
}
data class OfflineDownloadJournal(val items: List<OfflineDownloadItem> = emptyList(), val wifiOnly: Boolean = true)
data class VerifiedOfflineFile(val path: String, val bytes: Long)
interface OfflineDownloadStorage {
    suspend fun load(): OfflineDownloadJournal
    suspend fun save(journal: OfflineDownloadJournal)
    suspend fun cached(course: Course, file: CourseFile): VerifiedOfflineFile?
    suspend fun remove(course: Course, file: CourseFile)
    suspend fun usedBytes(accountIds: Set<String>): Long
}
fun offlineDownloadId(course: Course, file: CourseFile): String = MessageDigest.getInstance("SHA-256").digest("${course.accountId}:${course.id}:${file.url}".toByteArray()).joinToString("") { "%02x".format(it) }
fun savedDownloadSpecs(snapshot: PortalSnapshot, accountIds: Set<String>): List<OfflineDownloadSpec> = buildList {
    snapshot.courses.filter { it.accountId in accountIds }.forEach { course ->
        snapshot.sections["${course.accountId}:${course.id}"].orEmpty().forEach { section ->
            section.modules.filter { it.courseId == course.id }.flatMap { it.files }.forEach { add(OfflineDownloadSpec(course, "section:${section.id}", section.name.ifBlank { "Course section" }, it)) }
        }
        snapshot.assignments.filter { it.accountId == course.accountId && it.courseId == course.id }.forEach { assignment ->
            assignment.files.forEach { add(OfflineDownloadSpec(course, "assignments", "Assignment attachments", it)) }
        }
    }
}.filter { it.file.name.isNotBlank() && it.file.url.isNotBlank() }.distinctBy { it.id to it.section }
