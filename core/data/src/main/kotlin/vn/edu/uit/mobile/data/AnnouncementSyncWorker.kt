package vn.edu.uit.mobile.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.sync.withLock
import vn.edu.uit.mobile.domain.*

class AnnouncementSyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val repository = UitGraph.get(applicationContext).announcements
        repository.initialize()
        if (inputData.getBoolean("refresh", true)) repository.refresh()
        AnnouncementNotifier(applicationContext).deliver(repository)
        return Result.success()
    }
    companion object {
        const val EXTRA_ANNOUNCEMENT = "vn.edu.uit.mobile.announcement"
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<AnnouncementSyncWorker>(30, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("uit-public-announcements", ExistingPeriodicWorkPolicy.KEEP, request)
            // Separate unconstrained local pass: a saved reminder does not require internet.
            val reminders = PeriodicWorkRequestBuilder<AnnouncementSyncWorker>(15, TimeUnit.MINUTES)
                .setInputData(workDataOf("refresh" to false)).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("uit-announcement-reminders", ExistingPeriodicWorkPolicy.KEEP, reminders)
        }
    }
}

/** A serialized delivery pass prevents the offline reminder and sync workers duplicating alerts. */
class AnnouncementNotifier(private val applicationContext: Context) {
    suspend fun deliver(repository: AnnouncementRepository) = deliveryMutex.withLock {
        deliverLocked(repository)
    }
    private suspend fun deliverLocked(repository: AnnouncementRepository) {
        val state = repository.state.value
        val now = System.currentTimeMillis() / 1000
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled() || Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val configuration = android.content.res.Configuration(applicationContext.resources.configuration)
        configuration.setLocale(java.util.Locale.forLanguageTag(applicationContext.getSharedPreferences("appearance", Context.MODE_PRIVATE).getString("language", "vi") ?: "vi"))
        val localized = applicationContext.createConfigurationContext(configuration)
        val channel = "uit_announcements"
        manager.createNotificationChannel(NotificationChannel(channel, localized.getString(vn.edu.uit.mobile.core.data.R.string.announcement_channel), NotificationManager.IMPORTANCE_DEFAULT))
        if (manager.getNotificationChannel(channel)?.importance == NotificationManager.IMPORTANCE_NONE) return
        fun notify(items: List<OfficialAnnouncement>, reminder: Boolean): Boolean {
            val first = items.firstOrNull() ?: return false
            val intent = applicationContext.packageManager.getLaunchIntentForPackage(applicationContext.packageName)?.apply {
                putExtra(AnnouncementSyncWorker.EXTRA_ANNOUNCEMENT, first.url)
                action = "vn.edu.uit.mobile.ANNOUNCEMENT"
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            } ?: return false
            val pending = PendingIntent.getActivity(applicationContext, if (reminder) 8302 else 8301, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val title = localized.getString(if (reminder) vn.edu.uit.mobile.core.data.R.string.announcement_reminder else vn.edu.uit.mobile.core.data.R.string.announcement_new, items.size)
            manager.notify(if (reminder) 8302 else 8301, NotificationCompat.Builder(applicationContext, channel).setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title).setContentText(first.title).setStyle(NotificationCompat.BigTextStyle().bigText(items.take(5).joinToString("\n") { "${it.source.label} · ${it.title}" })).setContentIntent(pending).setAutoCancel(true).build())
            return true
        }
        val pending = state.items.filter { it.url in state.pending && state.sources.getValue(it.source).enabled }
        if (state.alertsEnabled && pending.isNotEmpty()) {
            if (notify(pending.distinctBy { it.groupKey }, false)) repository.acknowledge(pending.map { it.url }.toSet())
        }
        // Explicit local reminders survive a source being disabled; no academic event is created.
        val reminders = state.items.filter { it.reminderAt in 1..now && !it.reminderDelivered }
        if (reminders.isNotEmpty()) {
            if (notify(reminders, true)) repository.acknowledge(reminders.map { it.url }.toSet(), reminders = true)
        }
    }
    companion object {
        private val deliveryMutex = kotlinx.coroutines.sync.Mutex()
    }
}
