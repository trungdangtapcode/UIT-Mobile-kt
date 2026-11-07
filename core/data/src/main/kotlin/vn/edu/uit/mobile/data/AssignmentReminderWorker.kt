package vn.edu.uit.mobile.data

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit
import vn.edu.uit.mobile.database.*
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.model.*

class RoomAssignmentReminderLedger(private val dao: UitDao) : AssignmentReminderLedger {
    override suspend fun lastAttempt(accountId: String) = dao.deliveryTime(assignmentReminderKey(accountId))
    override suspend fun reserve(accountId: String, at: Long) = dao.reserveDelivery(DeliveredAlert(assignmentReminderKey(accountId), at))
}

class AssignmentReminderNotifier(private val context: Context) : AssignmentReminderSink {
    private val manager = context.getSystemService(NotificationManager::class.java)
    private fun localized(): Context {
        val config = android.content.res.Configuration(context.resources.configuration)
        config.setLocale(Locale.forLanguageTag(context.getSharedPreferences("appearance", Context.MODE_PRIVATE).getString("language", "vi") ?: "vi"))
        return context.createConfigurationContext(config)
    }
    override fun available(): Boolean {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED || !manager.areNotificationsEnabled()) return false
        manager.createNotificationChannel(NotificationChannel(CHANNEL, localized().getString(vn.edu.uit.mobile.core.data.R.string.assignment_reminder_channel), NotificationManager.IMPORTANCE_DEFAULT))
        return manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    }
    override fun post(account: Account, items: List<AssignmentReminderItem>, now: Long): Boolean {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        if (items.isEmpty() || !available()) return false
        val localized = localized()
        val tag = assignmentReminderKey(account.id)
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("uit-mobile://widget/deadlines").buildUpon().appendQueryParameter("reminder", tag).build()
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        } ?: return false
        val pending = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val date = DateTimeFormatter.ofPattern("dd/MM HH:mm", Locale.forLanguageTag("vi")).withZone(ZoneId.of("Asia/Ho_Chi_Minh"))
        fun label(text: String, limit: Int) = text.replace('\n', ' ').replace('\r', ' ').take(limit)
        val lines = items.take(5).map { "${label(it.course.shortname, 80)} · ${label(it.assignment.name, 160)} · ${date.format(Instant.ofEpochSecond(it.due))}" }.toMutableList()
        if (items.size > 5) lines += localized.getString(vn.edu.uit.mobile.core.data.R.string.assignment_reminder_more, items.size - 5)
        val title = localized.getString(vn.edu.uit.mobile.core.data.R.string.assignment_reminder_title, items.size)
        val public = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(localized.getString(vn.edu.uit.mobile.core.data.R.string.assignment_reminder_channel)).build()
        val notification = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title).setContentText(lines.first()).setStyle(NotificationCompat.BigTextStyle().bigText(lines.joinToString("\n")))
            .setContentIntent(pending).setAutoCancel(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(public).setWhen(now * 1000).build()
        manager.notify(tag, ID, notification)
        return true
    }
    override fun cancel(accountId: String) = manager.cancel(assignmentReminderKey(accountId), ID)
    companion object {
        const val CHANNEL = "uit_assignment_reminders"
        const val ID = 8403
    }
}

class AssignmentReminderWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val graph = UitGraph.get(applicationContext)
        val completed = AssignmentReminderDelivery(MoodleAssignmentReminderSource(graph.vault), RoomAssignmentReminderLedger(graph.database.dao()), AssignmentReminderNotifier(applicationContext)).deliver()
        return if (!completed && runAttemptCount < 2) Result.retry() else Result.success()
    }
    companion object {
        const val WORK = "uit-assignment-reminders"

        // Local ticks avoid skipping a whole period when background work runs early.
        // Successful university reads/notification attempts remain eight hours apart.
        fun request(): PeriodicWorkRequest = PeriodicWorkRequestBuilder<AssignmentReminderWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES).build()
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request())
        }
    }
}
