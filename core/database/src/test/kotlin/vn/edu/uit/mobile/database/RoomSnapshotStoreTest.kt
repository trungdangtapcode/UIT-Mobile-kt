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
}
