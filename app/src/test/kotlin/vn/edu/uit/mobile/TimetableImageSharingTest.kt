package vn.edu.uit.mobile

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.UUID
import kotlinx.datetime.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*
import vn.edu.uit.mobile.common.*
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.model.*
import vn.edu.uit.mobile.schedule.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TimetableImageSharingTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val day = LocalDate(2026, 10, 26)
    private val source = PortalSnapshot(timetable = listOf(DaaTimetableSlot("PRIVATE-CLASS", "IT004", "Private course", 3, 1, 1, 3, "PRIVATE-ROOM", "PRIVATE-TEACHER", "$day", "$day")), profile = StudentProfile("PRIVATE-ID", "PRIVATE-NAME"), updatedAt = dateAt(day, LocalTime(6, 0)))
    private val folder get() = File(context.cacheDir, "timetable-shares")

    @After fun cleanup() {
        folder.deleteRecursively()
    }

    @Test fun hiddenProfileRoomAndCourseChangesCannotChangeExportedPixels() {
        val options = TimetableImageOptions(day, day, courseNames = false)
        val first = TimetableImageRenderer.render(context, TimetableImageExport().plan(source, options))
        val changed = source.copy(profile = StudentProfile("OTHER-ID", "OTHER-NAME"), timetable = source.timetable.map { it.copy(classCode = "OTHER-CLASS", courseName = "Other name", room = "Other room", lecturerName = "Other teacher") })
        val second = TimetableImageRenderer.render(context, TimetableImageExport().plan(changed, options))
        try {
            assertEquals(1080, first.width)
            assertTrue(first.height in 1..7800)
            assertTrue(first.sameAs(second))
            assertFalse(folder.exists())
        } finally {
            first.recycle()
            second.recycle()
        }
    }

    @Test fun pngProviderRoundTripSharesExactPixelsWithReadOnlyGrants() {
        val bitmap = TimetableImageRenderer.render(context, TimetableImageExport().plan(source, TimetableImageOptions(day, day)))
        try {
            val file = TimetableImageShares.write(context, bitmap)
            val next = TimetableImageShares.write(context, bitmap)
            assertNotEquals(file.name, next.name)
            assertFalse(file.name.contains("PRIVATE"))
            val send = TimetableImageShares.intent(context, file)
            val uri = send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
            assertEquals(Intent.ACTION_SEND, send.action)
            assertEquals("image/png", send.type)
            assertEquals("content", uri.scheme)
            assertEquals(uri, send.clipData!!.getItemAt(0).uri)
            assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, send.flags)
            assertNull(send.getStringExtra(Intent.EXTRA_TEXT))
            val restored = context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it) }!!
            try {
                assertTrue(bitmap.sameAs(restored))
            } finally {
                restored.recycle()
            }
            val unrelated = File(context.cacheDir, "private.png").apply { writeText("private") }
            assertThrows(IllegalArgumentException::class.java) { TimetableImageShares.intent(context, unrelated) }
            unrelated.delete()
        } finally {
            bitmap.recycle()
        }
    }

    @Test fun cleanupRemovesExpiredCopiesAndPartialFilesWithoutDeletingOtherCacheData() {
        folder.mkdirs()
        val now = System.currentTimeMillis()
        val old = File(folder, "timetable-${UUID.randomUUID()}.png").apply {
            writeText("old")
            setLastModified(now - 3_600_001)
        }
        val part = File(folder, "timetable-${UUID.randomUUID()}.png.part").apply {
            writeText("partial")
            setLastModified(now - 3_600_001)
        }
        val unrelated = File(folder, "unrelated.txt").apply { writeText("keep") }
        repeat(10) { number ->
            File(folder, "timetable-${UUID.randomUUID()}.png").apply {
                writeText("recent")
                setLastModified(now - number)
            }
        }
        TimetableImageShares.cleanup(context, now)
        assertFalse(old.exists())
        assertFalse(part.exists())
        assertEquals("keep", unrelated.readText())
        assertEquals(7, folder.listFiles()!!.count { it.extension == "png" })
    }

    @Test fun oversizedLargeTextImagesFailBeforeCreatingACacheFile() {
        val many = source.copy(timetable = (1..30).map { source.timetable.single().copy(classCode = "CLASS$it", courseName = "Long course name ".repeat(18)) })
        val plan = TimetableImageExport().plan(many, TimetableImageOptions(day, day))
        assertThrows(IllegalArgumentException::class.java) { TimetableImageRenderer.render(context, plan, 2f) }
        assertFalse(folder.exists())
    }
}
