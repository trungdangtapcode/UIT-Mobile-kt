package vn.edu.uit.mobile.designsystem

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File
import kotlin.math.*

/** Native PdfRenderer snapshot, shared by course reading and the existing profile scan viewer. */
class NativePdfDocument(bytes: ByteArray, cacheDirectory: File) : AutoCloseable {
    private val lock = Any()
    private var closed = false
    private val renderer: PdfRenderer
    val pageCount: Int
    init {
        require(bytes.size in 5..25 * 1024 * 1024 && bytes.copyOfRange(0, 5).toString(Charsets.US_ASCII) == "%PDF-")
        renderer = createRenderer(bytes, cacheDirectory)
        pageCount = renderer.pageCount
        if (pageCount !in 1..5000) {
            renderer.close()
            error("Unsupported PDF page count")
        }
    }
    fun render(page: Int, maximumScale: Double = 2.0): Bitmap = synchronized(lock) {
        require(maximumScale.isFinite() && maximumScale in .25..4.0)
        check(!closed)
        require(page in 1..pageCount)
        renderer.openPage(page - 1).use { source ->
            require(source.width > 0 && source.height > 0)
            val width = source.width.toDouble()
            val height = source.height.toDouble()
            require(width / height in (1.0 / 32)..32.0)
            val scale = minOf(maximumScale, 4096.0 / max(width, height), sqrt(8_388_608.0 / (width * height)))
            val image = Bitmap.createBitmap(max(1, (width * scale).toInt()), max(1, (height * scale).toInt()), Bitmap.Config.ARGB_8888)
            try {
                Canvas(image).drawColor(Color.WHITE)
                source.render(image, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                image
            } catch (e: Exception) {
                image.recycle()
                throw e
            }
        }
    }
    override fun close() = synchronized(lock) {
        if (!closed) {
            renderer.close()
            closed = true
        }
    }
}

private fun createRenderer(bytes: ByteArray, cacheDirectory: File): PdfRenderer {
    val temporary = File.createTempFile("uit-pdf-", ".pdf", cacheDirectory)
    var descriptor: ParcelFileDescriptor? = null
    try {
        temporary.writeBytes(bytes)
        descriptor = ParcelFileDescriptor.open(temporary, ParcelFileDescriptor.MODE_READ_ONLY)
        check(temporary.delete())
        return PdfRenderer(descriptor).also { descriptor = null }
    } finally {
        descriptor?.close()
        temporary.delete()
    }
}
