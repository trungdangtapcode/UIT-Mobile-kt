package vn.edu.uit.mobile

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import java.io.ByteArrayOutputStream
import vn.edu.uit.mobile.authui.ProfileScanState
import vn.edu.uit.mobile.model.ProfileDocument

/** A generated test document, containing no real student record or university seal. */
internal object ScanPreviewData {
    val bytes: ByteArray by lazy {
        val bitmap = Bitmap.createBitmap(840, 1188, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(16, 42, 67)
            textSize = 26f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
        fun line(text: String, y: Float) {
            canvas.drawText(text, 56f, y, paint)
        }
        paint.color = Color.rgb(7, 104, 181)
        line("SYNTHETIC DOCUMENT", 88f)
        paint.color = Color.rgb(16, 42, 67)
        paint.textSize = 38f
        line("Academic record", 164f)
        paint.textSize = 26f
        line("Alex Morgan", 228f)
        line("Student ID: TEST-2026001", 272f)
        paint.strokeWidth = 2f
        canvas.drawLine(56f, 312f, 784f, 312f, paint)
        line("Module", 364f)
        canvas.drawText("Credits", 660f, 364f, paint)
        listOf("Programming fundamentals", "Discrete mathematics", "Computer networks", "Database systems", "Operating systems", "Software engineering").forEachIndexed { index, title ->
            val y = 432f + index * 64f
            line(title, y)
            canvas.drawText("4", 700f, y, paint)
        }
        canvas.drawLine(56f, 830f, 784f, 830f, paint)
        paint.textSize = 22f
        line("For interface testing only.", 908f)
        line("This is not an official academic document.", 946f)
        line("No real student data is included.", 984f)
        ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            bitmap.recycle()
            output.toByteArray()
        }
    }
    val state get() = ProfileScanState(ProfileDocument("transcript", "Transcript", true), bytes, loading = false)
}
