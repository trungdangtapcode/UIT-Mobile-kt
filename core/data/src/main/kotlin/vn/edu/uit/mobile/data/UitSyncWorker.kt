package vn.edu.uit.mobile.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.datetime.Clock
import vn.edu.uit.mobile.common.*
import vn.edu.uit.mobile.database.*
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.model.*

class UitSyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val graph = UitGraph.get(applicationContext)
        if (graph.accounts.accounts.first().isEmpty()) return Result.success()
        val old = graph.portal.snapshot.first()
        val sync = graph.portal.refresh()
        val fresh = graph.portal.snapshot.first()
        val dao = graph.database.dao()
        val delivered = dao.delivered().toSet()
        val now = Clock.System.now().epochSeconds
        suspend fun alert(key: String, title: String, body: String) {
            if (key in delivered) return
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            val manager = applicationContext.getSystemService(NotificationManager::class.java)
            if (!manager.areNotificationsEnabled()) return
            manager.createNotificationChannel(NotificationChannel("uit_updates", "UIT deadlines and academic updates", NotificationManager.IMPORTANCE_HIGH))
            val intent = applicationContext.packageManager.getLaunchIntentForPackage(applicationContext.packageName)
            val pending = intent?.let { PendingIntent.getActivity(applicationContext, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) }
            val notification = NotificationCompat.Builder(applicationContext, "uit_updates").setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title).setContentText(body).setAutoCancel(true).setContentIntent(pending).build()
            manager.notify(key.hashCode(), notification)
            dao.delivered(DeliveredAlert(key, now))
        }
        CalculateDeadlineRemindersUseCase()(fresh.events, delivered, now).forEach { alert(it.key, "Due in ${it.hours} hour(s)", it.event.name) }
        fresh.notices.filter { notice -> CourseCrossLinker().match(notice.classCode, fresh.courses) != null }.forEach {
            alert("notice:${it.nodeId}:${it.classCode}:${it.targetDate}", it.title, "${it.classCode} · ${it.targetDate} · ${it.room}")
        }
        // Establish a baseline before grade alerts, then notify on changed values.
        if (old.updatedAt > 0) {
            fresh.transcript.semesters.forEach { semester ->
                semester.courses.forEach { grade ->
                    val previous = old.transcript.semesters.firstOrNull { it.semesterName == semester.semesterName }?.courses?.firstOrNull { it.courseCode == grade.courseCode }
                    if (grade.totalScore != null && previous?.totalScore != grade.totalScore) {
                        alert("grade:${semester.semesterName}:${grade.courseCode}:${grade.totalScore}", "Grade published: ${grade.courseName}", "${grade.totalScore}")
                    }
                }
            }
        }
        if (old.updatedAt > 0) {
            fresh.grades.forEach { (course, grades) ->
                grades.forEach { grade ->
                    val previous = old.grades[course]?.firstOrNull { it.id == grade.id }
                    if (grade.grade.isNotBlank() && grade.grade != "-" && previous?.grade != grade.grade) {
                        alert("moodle-grade:$course:${grade.id}:${grade.grade}", "Grade published: ${grade.name}", grade.grade)
                    }
                }
            }
        }
        return if (sync is AppResult.Failure && sync.error == AppError.NetworkOffline && runAttemptCount < 3) Result.retry() else Result.success()
    }
    companion object {
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<UitSyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("uit-sync", ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
