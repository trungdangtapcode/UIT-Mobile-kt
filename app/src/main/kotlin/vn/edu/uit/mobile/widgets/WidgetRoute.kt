package vn.edu.uit.mobile.widgets

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.datetime.LocalDate
import vn.edu.uit.mobile.MainActivity

data class WidgetRoute(val destination: String, val date: String? = null) {
    fun intent(context: Context): Intent = Intent(context, MainActivity::class.java).apply {
        action = Intent.ACTION_VIEW
        data = Uri.Builder().scheme("uit-mobile").authority("widget").appendPath(destination).apply { date?.let { appendQueryParameter("date", it) } }.build()
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
    }
    fun pendingIntent(context: Context): PendingIntent = PendingIntent.getActivity(context, 0, intent(context), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    companion object {
        fun read(intent: Intent?): WidgetRoute? {
            if (intent?.action != Intent.ACTION_VIEW) return null
            val uri = intent.data ?: return null
            if (uri.scheme != "uit-mobile" || uri.host != "widget" || uri.userInfo != null || uri.port != -1) return null
            val destination = uri.path?.removePrefix("/") ?: return null
            if (destination !in setOf("schedule", "deadlines", "overdue", "profile")) return null
            val date = uri.getQueryParameter("date")
            if (date != null && runCatching { LocalDate.parse(date) }.isFailure) return null
            return WidgetRoute(destination, date)
        }
    }
}
