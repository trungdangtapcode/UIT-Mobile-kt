package vn.edu.uit.mobile

import android.app.Application
import android.content.Intent
import android.net.Uri
import java.io.File
import java.util.UUID
import kotlinx.datetime.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import vn.edu.uit.mobile.common.*
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.model.*
import vn.edu.uit.mobile.schedule.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CalendarFileSharingTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val folder get() = File(context.cacheDir, "calendar-shares")
    private val day = LocalDate(2026, 10, 26)
    private val source = PortalSnapshot(events = listOf(CalendarEvent("private-account:1", 1, "private-account", null, "Synthetic deadline", dateAt(day, LocalTime(23, 0)), deadline = true)))

    @Before fun provider() {
        // Robolectric reuses FileProvider static state across sandbox contexts.
        // Attach the real manifest provider to this test's application directory.
        androidx.core.content.FileProvider().attachInfo(context, context.packageManager.resolveContentProvider("${context.packageName}.files", android.content.pm.PackageManager.GET_META_DATA)!!)
    }

    @After fun cleanup() {
        folder.deleteRecursively()
    }

    @Test fun fileProviderSharesCompleteCalendarWithReadOnlyGrantAndOpaqueFileName() {
        val plan = CalendarFileExport().plan(source, CalendarExportOptions(day, day))
        val file = CalendarFileShares.write(context, plan)
        val next = CalendarFileShares.write(context, plan)
        val send = CalendarFileShares.intent(context, file)
        val uri = send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("text/calendar", send.type)
        assertEquals("content", uri.scheme)
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, send.flags)
        assertEquals(uri, send.clipData!!.getItemAt(0).uri)
        assertNull(send.getStringExtra(Intent.EXTRA_TEXT))
        assertNotEquals(file.name, next.name)
        assertFalse(file.name.contains("private-account"))
        val content = context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
        assertEquals(file.readText(), content)
        assertTrue(content.endsWith("END:VCALENDAR\r\n"))
        assertTrue(content.contains("SUMMARY:Synthetic deadline"))
        assertFalse(content.contains("private-account"))
        val unrelated = File(context.cacheDir, "outside.ics").apply { writeText("not shared") }
        try {
            assertThrows(IllegalArgumentException::class.java) { CalendarFileShares.intent(context, unrelated) }
        } finally {
            unrelated.delete()
        }
    }

    @Test fun cleanupExpiresCalendarCopiesAndBoundsCacheWithoutTouchingOtherFiles() {
        folder.mkdirs()
        val now = System.currentTimeMillis()
        val expired = File(folder, "calendar-${UUID.randomUUID()}.ics").apply {
            writeText("old")
            setLastModified(now - 3_600_001)
        }
        val partial = File(folder, "calendar-${UUID.randomUUID()}.ics.part").apply {
            writeText("partial")
            setLastModified(now - 3_600_001)
        }
        val unrelated = File(folder, "unrelated.txt").apply { writeText("keep") }
        repeat(10) { n ->
            File(folder, "calendar-${UUID.randomUUID()}.ics").apply {
                writeText("recent")
                setLastModified(now - n)
            }
        }
        CalendarFileShares.cleanup(context, now)
        assertFalse(expired.exists())
        assertFalse(partial.exists())
        assertEquals(7, folder.listFiles()!!.count { it.extension == "ics" })
        assertEquals("keep", unrelated.readText())
    }
}
