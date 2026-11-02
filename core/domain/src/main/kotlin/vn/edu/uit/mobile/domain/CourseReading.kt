package vn.edu.uit.mobile.domain

import kotlinx.coroutines.flow.Flow
import vn.edu.uit.mobile.model.*

data class ReadingIdentity(val accountId: String, val documentId: String, val version: String)
data class ReadingPosition(val page: Int = 1, val zoom: Float = 1f, val panX: Float = 0f, val panY: Float = 0f)
data class ReadingNote(val id: String, val page: Int, val text: String, val x: Float = .5f, val y: Float = .5f, val createdAt: Long, val updatedAt: Long)
data class ReadingRecord(val identity: ReadingIdentity, val title: String, val courseId: Long, val pageCount: Int, val position: ReadingPosition = ReadingPosition(), val bookmarks: Set<Int> = emptySet(), val notes: List<ReadingNote> = emptyList(), val revision: Long = 0)
data class ReadingCollection(val accountId: String, val records: List<ReadingRecord> = emptyList(), val failed: Boolean = false)
data class VerifiedReadingDocument(val course: Course, val file: CourseFile, val bytes: ByteArray, val identity: ReadingIdentity)
fun interface ReadingDocumentLoader {
    suspend fun load(course: Course, file: CourseFile): VerifiedReadingDocument
}
class ReadingLimitException : IllegalStateException("Reading storage limits reached")

interface ReadingRepository {
    val collections: Flow<List<ReadingCollection>>
    suspend fun load(identity: ReadingIdentity, title: String, courseId: Long, pageCount: Int): ReadingRecord
    suspend fun save(expected: ReadingRecord, value: ReadingRecord): ReadingRecord
    suspend fun export(record: ReadingRecord): String
    suspend fun remove(expected: ReadingRecord)
}

object ReadingRules {
    fun identity(value: ReadingIdentity) {
        require(value.accountId.isNotBlank() && value.accountId.length <= 512)
        require(value.documentId.matches(Regex("[0-9a-f]{64}")) && value.version.matches(Regex("[0-9a-f]{64}")))
    }
    fun position(value: ReadingPosition, pageCount: Int) {
        require(value.page in 1..pageCount && value.zoom.isFinite() && value.zoom in 1f..4f)
        require(value.panX.isFinite() && value.panX in -1f..1f && value.panY.isFinite() && value.panY in -1f..1f)
    }
    fun note(value: ReadingNote, pageCount: Int) {
        require(value.id.matches(Regex("[A-Za-z0-9_-]{1,100}")) && value.page in 1..pageCount)
        require(value.text.isNotBlank() && value.text.length <= 4000)
        require(value.x.isFinite() && value.x in 0f..1f && value.y.isFinite() && value.y in 0f..1f)
        require(value.createdAt in 1..4102444800L && value.updatedAt in value.createdAt..4102444800L)
    }
    fun record(value: ReadingRecord) {
        identity(value.identity)
        require(value.title.isNotBlank() && value.title.length <= 200 && value.courseId > 0 && value.pageCount in 1..5000 && value.revision in 0..Long.MAX_VALUE - 1)
        position(value.position, value.pageCount)
        require(value.bookmarks.size <= 200 && value.bookmarks.all { it in 1..value.pageCount })
        require(value.notes.size <= 100 && value.notes.map { it.id }.distinct().size == value.notes.size)
        value.notes.forEach { note(it, value.pageCount) }
    }
    fun collection(accountId: String, values: List<ReadingRecord>) {
        require(values.size <= 50 && values.sumOf { r -> r.notes.sumOf { it.text.length } } <= 500_000)
        require(values.map { it.identity }.distinct().size == values.size)
        values.forEach {
            require(it.identity.accountId == accountId)
            record(it)
        }
    }
}
