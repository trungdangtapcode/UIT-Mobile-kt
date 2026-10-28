package vn.edu.uit.mobile.widgets

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import android.widget.RemoteViews
import androidx.compose.ui.graphics.toArgb
import kotlinx.datetime.*
import vn.edu.uit.mobile.R
import vn.edu.uit.mobile.common.*
import vn.edu.uit.mobile.designsystem.*
import vn.edu.uit.mobile.domain.ScheduleWidgetState
import vn.edu.uit.mobile.model.*

enum class ScheduleWidgetKind { NEXT, TODAY }

/** Real launcher RemoteViews, also inflated by the screenshot tests. */
object ScheduleWidgetRenderer {
    fun render(context: Context, kind: ScheduleWidgetKind, state: ScheduleWidgetState, width: Int, height: Int, now: Long): RemoteViews {
        val preferences = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
        val colors = uitColorScheme(context, preferences.getBoolean("dark", true), preferences.getBoolean("dynamic", true))
        val views = RemoteViews(context.packageName, if (kind == ScheduleWidgetKind.NEXT) R.layout.widget_next else R.layout.widget_today)
        val ink = colors.onSurface.toArgb()
        val muted = colors.onSurfaceVariant.toArgb()
        val accent = colors.primary.toArgb()
        views.setImageViewBitmap(android.R.id.background, frame(width, height, colors.surface.toArgb(), colors.outline.toArgb()))
        views.setTextViewText(R.id.widget_date, "${state.date.dayOfWeek.name.take(3).lowercase().replaceFirstChar(Char::uppercase)} ${state.date.dayOfMonth}")
        views.setTextColor(R.id.widget_date, muted)
        views.setTextColor(R.id.widget_heading, accent)
        views.setTextColor(R.id.widget_saved, muted)
        views.setTextViewText(R.id.widget_saved, if (state.updatedAt > 0) "Saved ${dateText(state.updatedAt).takeLast(5)} ${clockText(state.updatedAt)} · GMT+07" else "GMT+07 · No saved schedule")
        val root = WidgetRoute(if (state.connected) "schedule" else "profile", state.date.toString()).pendingIntent(context)
        views.setOnClickPendingIntent(R.id.widget_root, root)
        val scale = context.resources.configuration.fontScale
        val verticalPadding = if (height < 200 * scale) 8 else 12
        views.setViewPadding(R.id.widget_content, dp(context, 16), dp(context, verticalPadding), dp(context, 20), dp(context, verticalPadding))
        val entries = if (kind == ScheduleWidgetKind.NEXT) {
            listOfNotNull(state.next)
        } else {
            state.today.take(
                if (height >= 335 * scale) {
                    3
                } else if (height >= 220 * scale) {
                    2
                } else {
                    1
                }
            ).ifEmpty { listOfNotNull(state.next) }
        }
        val rowIds = listOf(
            intArrayOf(R.id.widget_row_0, R.id.widget_row_0_meta, R.id.widget_row_0_title, R.id.widget_row_0_room),
            intArrayOf(R.id.widget_row_1, R.id.widget_row_1_meta, R.id.widget_row_1_title, R.id.widget_row_1_room),
            intArrayOf(R.id.widget_row_2, R.id.widget_row_2_meta, R.id.widget_row_2_title, R.id.widget_row_2_room)
        )
        repeat(if (kind == ScheduleWidgetKind.NEXT) 1 else 3) { index ->
            val (row, meta, title, room) = rowIds[index]
            val entry = entries.getOrNull(index)
            views.setViewVisibility(row, if (index == 0 || entry != null) View.VISIBLE else View.GONE)
            views.setTextColor(meta, accent)
            views.setTextColor(title, ink)
            views.setTextColor(room, muted)
            val titleLines = if (index > 0 || kind == ScheduleWidgetKind.NEXT && height < 176 * scale || kind == ScheduleWidgetKind.TODAY && height < 245 * scale) 1 else 2
            views.setInt(title, "setMaxLines", titleLines)
            views.setViewPadding(row, 0, dp(context, 6), 0, dp(context, 2))
            if (entry != null) {
                val status = when {
                    entry.kind == ScheduleKind.EXAM -> "Exam"
                    entry.start <= now && entry.end > now -> "In progress"
                    entry.kind == ScheduleKind.MAKE_UP -> "Make-up class"
                    else -> "Next class"
                }
                views.setTextViewText(meta, "$status · ${time(entry, state.date)}")
                views.setTextViewText(title, entry.title)
                views.setTextViewText(room, listOf(entry.classCode, entry.room.ifBlank { "Room not provided" }).filter(String::isNotBlank).joinToString(" · "))
                views.setOnClickPendingIntent(row, WidgetRoute("schedule", dateText(entry.start)).pendingIntent(context))
                views.setContentDescription(row, "$status, ${entry.title}, ${time(entry, state.date)}, ${entry.room.ifBlank { "room not provided" }}")
            } else {
                views.setTextViewText(
                    meta,
                    when {
                        !state.connected -> "Connect your account"
                        state.updatedAt == 0L -> "Sync your timetable"
                        else -> "Your saved schedule"
                    }
                )
                views.setTextViewText(
                    title,
                    when {
                        !state.connected -> "Sign in with UIT SSO"
                        state.updatedAt == 0L -> "Open UIT Mobile to sync"
                        else -> "No upcoming classes"
                    }
                )
                views.setTextViewText(room, if (!state.connected || state.updatedAt == 0L) "Tap to open UIT Mobile" else "No meetings found in the next 14 days")
                views.setOnClickPendingIntent(row, root)
            }
        }
        val deadline = state.deadline
        val emptyDeadline = when {
            !state.connected -> "Sign in to load deadlines"
            state.updatedAt == 0L -> "Sync to load deadlines"
            else -> "No pending deadlines in saved data"
        }
        val deadlineTime = deadline?.let {
            when {
                it.start < now -> "Overdue"
                dateText(it.start) == state.date.toString() -> "Due ${clockText(it.start)}"
                else -> "Due ${dateText(it.start).takeLast(5)} ${clockText(it.start)}"
            }
        }
        val deadlineRoute = when {
            !state.connected -> WidgetRoute("profile")
            deadline?.key?.startsWith("assignment:") == true -> WidgetRoute("schedule", dateText(deadline.start))
            deadline != null && deadline.start < now -> WidgetRoute("overdue")
            else -> WidgetRoute("deadlines")
        }.pendingIntent(context)
        if (kind == ScheduleWidgetKind.NEXT) {
            views.setViewVisibility(R.id.widget_compact_deadline, if (height < 144 * scale) View.GONE else View.VISIBLE)
            views.setTextColor(R.id.widget_compact_deadline, muted)
            views.setTextViewText(R.id.widget_compact_deadline, if (deadline == null) emptyDeadline else "${if (deadline.needsAction) "" else "Check status · "}$deadlineTime · ${deadline.title}")
            views.setOnClickPendingIntent(R.id.widget_compact_deadline, deadlineRoute)
        } else {
            views.setTextColor(R.id.widget_deadline_meta, accent)
            views.setTextColor(R.id.widget_deadline_title, ink)
            views.setInt(R.id.widget_deadline_title, "setMaxLines", if (height < 230) 1 else 2)
            views.setTextViewText(R.id.widget_deadline_meta, if (deadline == null) "Closest deadline" else "$deadlineTime${if (deadline.needsAction) "" else " · Check status"}")
            views.setTextViewText(R.id.widget_deadline_title, deadline?.title ?: emptyDeadline)
            views.setOnClickPendingIntent(R.id.widget_deadline, deadlineRoute)
        }
        return views
    }

    private fun time(entry: ScheduleEntry, today: LocalDate): String {
        val prefix = if (dateText(entry.start) == today.toString()) "" else "${dateText(entry.start).takeLast(5)} "
        return prefix + clockText(entry.start) + if (entry.kind == ScheduleKind.EXAM) "" else "–${clockText(entry.end)}"
    }
    private fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    internal fun frame(width: Int, height: Int, fill: Int, outline: Int): Bitmap {
        val w = width.coerceIn(120, 800).toFloat()
        val h = height.coerceIn(80, 800).toFloat()
        val bitmap = Bitmap.createBitmap(w.toInt(), h.toInt(), Bitmap.Config.ARGB_8888)
        val path = Path().apply {
            moveTo(8f, 0.5f)
            lineTo(w - 8, 0.5f)
            lineTo(w - 0.5f, 8f)
            lineTo(w - 0.5f, h - 28)
            lineTo(w - 28, h - 0.5f)
            lineTo(8f, h - 0.5f)
            lineTo(0.5f, h - 8)
            lineTo(0.5f, 8f)
            close()
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill }
        Canvas(bitmap).apply {
            drawPath(path, paint)
            paint.color = outline
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1f
            drawPath(path, paint)
        }
        return bitmap
    }
}
