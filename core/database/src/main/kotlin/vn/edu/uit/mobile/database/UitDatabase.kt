package vn.edu.uit.mobile.database

import android.content.Context
import androidx.room.*
import com.google.gson.Gson
import kotlinx.coroutines.flow.*
import vn.edu.uit.mobile.domain.SnapshotStore
import vn.edu.uit.mobile.model.*

@Entity(tableName = "snapshots")
data class SnapshotEntity(@PrimaryKey val accountId: String, val payload: String, val updatedAt: Long)

@Entity(tableName = "courses", primaryKeys = ["accountId", "id"], indices = [Index("name")])
data class CourseEntity(val accountId: String, val id: Long, val name: String, val payload: String)

@Entity(tableName = "modules", primaryKeys = ["accountId", "id"])
data class ModuleEntity(val accountId: String, val id: Long, val courseId: Long, val payload: String)

@Entity(tableName = "assignments", primaryKeys = ["accountId", "id"])
data class AssignmentEntity(val accountId: String, val id: Long, val courseId: Long, val due: Long, val payload: String)

@Entity(tableName = "timetable", primaryKeys = ["accountId", "key"])
data class TimetableEntity(val accountId: String, val key: String, val payload: String)

@Entity(tableName = "notices", primaryKeys = ["accountId", "key"])
data class NoticeEntity(val accountId: String, val key: String, val payload: String)

@Entity(tableName = "exams", primaryKeys = ["accountId", "key"])
data class ExamEntity(val accountId: String, val key: String, val payload: String)

@Entity(tableName = "grades", primaryKeys = ["accountId", "key"])
data class GradeEntity(val accountId: String, val key: String, val payload: String)

@Entity(tableName = "delivered_alerts")
data class DeliveredAlert(@PrimaryKey val key: String, val deliveredAt: Long)

@Dao
interface UitDao {
    @Query("SELECT * FROM snapshots WHERE accountId = :id")
    fun observe(id: String): Flow<SnapshotEntity?>

    @Query("SELECT * FROM snapshots WHERE accountId = :id")
    suspend fun snapshot(id: String): SnapshotEntity?

    @Upsert suspend fun save(snapshot: SnapshotEntity)

    @Upsert suspend fun courses(rows: List<CourseEntity>)

    @Upsert suspend fun modules(rows: List<ModuleEntity>)

    @Upsert suspend fun assignments(rows: List<AssignmentEntity>)

    @Upsert suspend fun timetable(rows: List<TimetableEntity>)

    @Upsert suspend fun notices(rows: List<NoticeEntity>)

    @Upsert suspend fun exams(rows: List<ExamEntity>)

    @Upsert suspend fun grades(rows: List<GradeEntity>)

    @Query("SELECT * FROM courses WHERE accountId = :id AND name LIKE '%' || :query || '%'")
    fun searchCourses(id: String, query: String): Flow<List<CourseEntity>>

    @Query("DELETE FROM snapshots WHERE accountId = :id")
    suspend fun deleteSnapshot(id: String)

    @Query("DELETE FROM courses WHERE accountId = :id")
    suspend fun deleteCourses(id: String)

    @Query("DELETE FROM modules WHERE accountId = :id")
    suspend fun deleteModules(id: String)

    @Query("DELETE FROM assignments WHERE accountId = :id")
    suspend fun deleteAssignments(id: String)

    @Query("DELETE FROM timetable WHERE accountId = :id")
    suspend fun deleteTimetable(id: String)

    @Query("DELETE FROM notices WHERE accountId = :id")
    suspend fun deleteNotices(id: String)

    @Query("DELETE FROM exams WHERE accountId = :id")
    suspend fun deleteExams(id: String)

    @Query("DELETE FROM grades WHERE accountId = :id")
    suspend fun deleteGrades(id: String)

    @Query("SELECT `key` FROM delivered_alerts")
    suspend fun delivered(): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun delivered(alert: DeliveredAlert)
}

@Database(
    entities = [
        SnapshotEntity::class, CourseEntity::class, ModuleEntity::class, AssignmentEntity::class,
        TimetableEntity::class, NoticeEntity::class, ExamEntity::class, GradeEntity::class, DeliveredAlert::class
    ],
    version = 1,
    exportSchema = true
)
abstract class UitDatabase : RoomDatabase() {
    abstract fun dao(): UitDao
    companion object {
        fun create(context: Context): UitDatabase = Room.databaseBuilder(context, UitDatabase::class.java, "uit.db").build()
    }
}
class RoomSnapshotStore(private val db: UitDatabase) : SnapshotStore {
    private val gson = Gson()
    private val dao = db.dao()
    private fun decode(entity: SnapshotEntity?): PortalSnapshot = entity?.let { gson.fromJson(it.payload, PortalSnapshot::class.java) } ?: PortalSnapshot()
    override fun observe(accountId: String): Flow<PortalSnapshot> = dao.observe(accountId).map(::decode)
    override suspend fun read(accountId: String): PortalSnapshot = decode(dao.snapshot(accountId))
    override suspend fun write(accountId: String, snapshot: PortalSnapshot) = db.withTransaction {
        clear(accountId)
        dao.save(SnapshotEntity(accountId, gson.toJson(snapshot), snapshot.updatedAt))
        dao.courses(snapshot.courses.map { CourseEntity(accountId, it.id, "${it.shortname} ${it.fullname}", gson.toJson(it)) })
        dao.modules(snapshot.sections.values.flatten().flatMap { it.modules }.distinctBy { it.id }.map { ModuleEntity(accountId, it.id, it.courseId, gson.toJson(it)) })
        dao.assignments(snapshot.assignments.map { AssignmentEntity(accountId, it.id, it.courseId, it.due, gson.toJson(it)) })
        dao.timetable(snapshot.timetable.mapIndexed { i, it -> TimetableEntity(accountId, "$i", gson.toJson(it)) })
        dao.notices(snapshot.notices.map { NoticeEntity(accountId, "${it.nodeId}:${it.classCode}", gson.toJson(it)) })
        dao.exams(snapshot.exams.mapIndexed { i, it -> ExamEntity(accountId, "$i", gson.toJson(it)) })
        dao.grades(snapshot.transcript.semesters.flatMap { semester -> semester.courses.map { GradeEntity(accountId, "${semester.semesterName}:${it.courseCode}", gson.toJson(it)) } })
    }
    override suspend fun delete(accountId: String) = db.withTransaction {
        clear(accountId)
        dao.deleteSnapshot(accountId)
    }
    private suspend fun clear(id: String) {
        dao.deleteCourses(id)
        dao.deleteModules(id)
        dao.deleteAssignments(id)
        dao.deleteTimetable(id)
        dao.deleteNotices(id)
        dao.deleteExams(id)
        dao.deleteGrades(id)
    }
}
