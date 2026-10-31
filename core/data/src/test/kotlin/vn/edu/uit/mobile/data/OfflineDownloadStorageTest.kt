package vn.edu.uit.mobile.data

import kotlinx.coroutines.test.runTest
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.model.*

class OfflineDownloadStorageTest {
    @get:Rule val folder = TemporaryFolder()
    private class Journal : StudyStorage {
        val data = mutableMapOf<String, String>()
        override fun read(key: String) = data[key]
        override fun write(key: String, value: String) {
            data[key] = value
        }
        override fun remove(key: String) {
            data.remove(key)
        }
        override fun removeAccount(accountId: String) = Unit
    }
    private val course = Course(1, "one", "IT004", "Databases")
    private val file = CourseFile("Notes.pdf", "https://courses.example.org/notes.pdf")

    @Test fun checksumsGateAvailabilityAndDeletionIsScopedToTheExactResource() = runTest {
        val store = PrivateOfflineDownloadStorage(folder.root, Journal())
        val destination = materialDestination(folder.root, course, file)
        destination.parentFile!!.mkdirs()
        destination.writeText("Synthetic PDF bytes")
        assertNull(store.cached(course, file))
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(destination.readBytes()).joinToString("") { "%02x".format(it) }
        writeMaterialChecksum(destination, hash)
        assertNotNull(store.cached(course, file))
        assertNull(store.cached(course.copy(accountId = "other"), file))
        assertTrue(store.usedBytes(setOf("one")) >= destination.length())
        destination.appendText("corrupt")
        assertNull(store.cached(course, file))
        store.remove(course, file)
        assertFalse(destination.exists())
        assertFalse(java.io.File(destination.path + ".sha256").exists())
    }

    @Test fun privateJournalRoundTripsAndLongUnicodeNamesStayInsideTheAccountFolder() = runTest {
        val store = PrivateOfflineDownloadStorage(folder.root, Journal())
        val item = OfflineDownloadItem(OfflineDownloadSpec(course, "section:1", "Week 1", file), OfflineDownloadStatus.WAITING_WIFI)
        store.save(OfflineDownloadJournal(listOf(item), false))
        val loaded = store.load()
        assertFalse(loaded.wifiOnly)
        assertEquals(item.id, loaded.items.single().id)
        assertEquals(OfflineDownloadStatus.WAITING_WIFI, loaded.items.single().status)
        val destination = materialDestination(folder.root, course, file.copy(name = "../" + "Tài liệu ".repeat(70) + ".pdf"))
        assertTrue(destination.name.toByteArray().size <= 213)
        assertTrue(destination.name.endsWith(".pdf"))
        assertTrue(destination.canonicalPath.startsWith(folder.root.canonicalPath + "/"))
    }
}
