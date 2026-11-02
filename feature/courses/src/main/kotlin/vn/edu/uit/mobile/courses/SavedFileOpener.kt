package vn.edu.uit.mobile.courses

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File
import vn.edu.uit.mobile.model.*

fun openSavedCourseFile(context: Context, path: String, course: Course? = null, source: CourseFile? = null, onOpenPdf: ((Course, CourseFile) -> Unit)? = null): Boolean {
    if (course != null && source != null && source.name.endsWith(".pdf", true) && onOpenPdf != null) {
        onOpenPdf(course, source)
        return true
    }
    val file = File(path)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"
    return try {
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}
