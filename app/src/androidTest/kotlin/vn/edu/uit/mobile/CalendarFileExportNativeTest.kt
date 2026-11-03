package vn.edu.uit.mobile

import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.datetime.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import vn.edu.uit.mobile.common.*
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.model.*
import vn.edu.uit.mobile.schedule.*

/** Actual Android FileProvider read-back, synthetic schedule, no university/calendar-provider writes. */
@RunWith(AndroidJUnit4::class)
class CalendarFileExportNativeTest {
    @Test fun selectedCalendarFileCanBeReadThroughGrantedContentUri() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val day = LocalDate(2026, 10, 26)
        val snapshot = PortalSnapshot(events = listOf(CalendarEvent("private-account:1", 1, "private-account", null, "Synthetic deadline", dateAt(day, LocalTime(23, 0)), deadline = true)))
        val plan = CalendarFileExport().plan(snapshot, CalendarExportOptions(day, day))
        val file = CalendarFileShares.write(context, plan)
        try {
            val intent = CalendarFileShares.intent(context, file)

            @Suppress("DEPRECATION")
            val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
            assertEquals("content", uri.scheme)
            assertEquals("text/calendar", intent.type)
            assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, intent.flags)
            assertEquals(uri, intent.clipData!!.getItemAt(0).uri)
            val actual = context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
            assertEquals(file.readText(), actual)
            assertTrue(actual.contains("DTSTART:20261026T160000Z"))
            assertTrue(actual.contains("SUMMARY:Synthetic deadline"))
            assertFalse(actual.contains("private-account"))
            assertTrue(actual.endsWith("END:VCALENDAR\r\n"))
        } finally {
            file.delete()
        }
    }
}
