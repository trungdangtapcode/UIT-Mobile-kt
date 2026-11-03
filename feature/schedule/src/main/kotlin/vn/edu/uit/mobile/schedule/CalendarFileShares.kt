package vn.edu.uit.mobile.schedule

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.datetime.Clock
import vn.edu.uit.mobile.domain.CalendarExportPlan
import vn.edu.uit.mobile.domain.CalendarFileExport

/** Cache copies are created only after Share. No profile fields enter filenames or Intent text. */
object CalendarFileShares {
    private val names = Regex("calendar-[a-f0-9-]{36}\\.ics(?:\\.part)?")
    private fun folder(context: Context) = File(context.cacheDir, "calendar-shares")

    @Synchronized fun cleanup(context: Context, now: Long = System.currentTimeMillis()) {
        val files = folder(context).listFiles().orEmpty().filter { it.isFile && names.matches(it.name) }
        files.filter { now - it.lastModified() >= 3_600_000L }.forEach(File::delete)
        files.filter(File::exists).sortedByDescending(File::lastModified).drop(7).forEach(File::delete)
    }

    @Synchronized fun write(context: Context, plan: CalendarExportPlan): File {
        val bytes = CalendarFileExport().render(plan, Clock.System.now().epochSeconds).toByteArray(Charsets.UTF_8)
        require(bytes.size <= 8 * 1024 * 1024) { "Calendar file is too large to share." }
        cleanup(context)
        val root = folder(context).apply { check(mkdirs() || isDirectory) }
        val file = File(root, "calendar-${UUID.randomUUID()}.ics")
        val temporary = File(file.path + ".part")
        try {
            FileOutputStream(temporary).use { stream ->
                stream.write(bytes)
                stream.fd.sync()
            }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE)
            return file
        } finally {
            temporary.delete()
        }
    }
    fun intent(context: Context, file: File): Intent {
        require(file.isFile && file.canonicalFile.parentFile == folder(context).canonicalFile && names.matches(file.name) && file.extension == "ics")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/calendar"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(context.getString(vn.edu.uit.mobile.feature.schedule.R.string.exp_title), uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
