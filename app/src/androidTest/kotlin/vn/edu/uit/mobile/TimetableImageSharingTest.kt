package vn.edu.uit.mobile

import android.content.Intent
import android.graphics.BitmapFactory
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

/** Native Canvas, PNG and FileProvider round trip. Synthetic data only; no university requests. */
@RunWith(AndroidJUnit4::class)
class TimetableImageSharingTest {
    @Test fun nativePngMatchesThePreviewAndOnlyGrantsReadAccess() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val day = LocalDate(2026, 10, 26)
        val source = PortalSnapshot(timetable = listOf(DaaTimetableSlot("SYNTHETIC", "IT004", "Synthetic course", 3, 1, 1, 3, "Hidden room", "Hidden teacher", "$day", "$day")), profile = StudentProfile("Hidden ID", "Hidden name"), updatedAt = dateAt(day, LocalTime(6, 0)))
        val plan = TimetableImageExport().plan(source, TimetableImageOptions(day, day.plus(1, DateTimeUnit.DAY), courseNames = false))
        assertFalse(plan.toString().contains("Hidden"))
        val bitmap = TimetableImageRenderer.render(context, plan, 1.3f)
        val file = TimetableImageShares.write(context, bitmap)
        try {
            val send = TimetableImageShares.intent(context, file)

            @Suppress("DEPRECATION")
            val uri = send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
            assertEquals("content", uri.scheme)
            assertEquals("image/png", send.type)
            assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, send.flags)
            assertEquals(uri, send.clipData!!.getItemAt(0).uri)
            assertNull(send.getStringExtra(Intent.EXTRA_TEXT))
            val decoded = context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it) }!!
            try {
                assertEquals(1080, decoded.width)
                assertTrue(decoded.height in 1..7800)
                assertTrue(bitmap.sameAs(decoded))
            } finally {
                decoded.recycle()
            }
        } finally {
            file.delete()
            bitmap.recycle()
        }
    }
}
