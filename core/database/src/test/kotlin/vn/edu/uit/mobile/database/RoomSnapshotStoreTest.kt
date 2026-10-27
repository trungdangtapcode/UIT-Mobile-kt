package vn.edu.uit.mobile.database

import androidx.room.Room
import app.cash.turbine.test
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import vn.edu.uit.mobile.model.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class RoomSnapshotStoreTest {
    private lateinit var db: UitDatabase
    private lateinit var store: RoomSnapshotStore

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), UitDatabase::class.java).allowMainThreadQueries().build()
        store = RoomSnapshotStore(db)
    }

    @After fun close() {
        db.close()
    }

    @Test fun cachedSnapshotEmitsBeforeTransactionUpdate() = runTest {
        val course = Course(42, "account", "IT004.O21", "Cached")
        store.write("account", PortalSnapshot(courses = listOf(course), updatedAt = 1))
        store.observe("account").test {
            assertEquals("Cached", awaitItem().courses.single().fullname)
            store.write("account", PortalSnapshot(courses = listOf(course.copy(fullname = "Synced")), updatedAt = 2))
            assertEquals("Synced", awaitItem().courses.single().fullname)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun accountRemovalAndReplacementClearNormalizedRows() = runTest {
        val one = Course(42, "one", "IT004.O21", "First")
        val two = one.copy(accountId = "two", fullname = "Second")
        store.write("one", PortalSnapshot(courses = listOf(one)))
        store.write("two", PortalSnapshot(courses = listOf(two)))
        assertEquals(1, db.dao().searchCourses("one", "First").first().size)
        store.write("one", PortalSnapshot())
        assertTrue(db.dao().searchCourses("one", "").first().isEmpty())
        store.delete("one")
        assertTrue(store.read("one").courses.isEmpty())
        assertEquals("Second", store.read("two").courses.single().fullname)
    }

    @Test fun studentServiceCacheSurvivesRoundTripAndAccountRemoval() = runTest {
        val services = StudentServicesSnapshot(tuition = TuitionSummary(emptyList(), fetchedAt = 123), procedures = listOf(ProcedureSection(ProcedureKind.TRANSCRIPTS, emptyList(), fetchedAt = 124)))
        store.write("portal", PortalSnapshot(studentServices = services))
        assertEquals(services, store.read("portal").studentServices)
        assertNull(store.read("other").studentServices)
        store.delete("portal")
        assertNull(store.read("portal").studentServices)
    }

    @Test fun oldSnapshotWithoutStudentServicesRemainsReadable() = runTest {
        store.write("old", PortalSnapshot(courses = listOf(Course(1, "old", "IT001", "Programming"))))
        val entity = requireNotNull(db.dao().snapshot("old"))
        val payload = com.google.gson.JsonParser.parseString(entity.payload).asJsonObject.apply {
            remove("studentServices")
            remove("portalLinked")
        }
        db.dao().save(entity.copy(payload = payload.toString()))
        val old = store.read("old")
        assertNull(old.studentServices)
        assertFalse(old.portalLinked)
        assertEquals("IT001", old.courses.single().shortname)
    }
}
