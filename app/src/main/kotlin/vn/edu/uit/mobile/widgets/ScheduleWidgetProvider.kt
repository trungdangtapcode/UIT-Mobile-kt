package vn.edu.uit.mobile.widgets

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle

open class ScheduleWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        WidgetRuntime.schedule(context)
    }
    override fun onAppWidgetOptionsChanged(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) {
        WidgetRuntime.schedule(context)
    }
    override fun onEnabled(context: Context) {
        WidgetRuntime.schedule(context)
    }
    override fun onDisabled(context: Context) {
        WidgetRuntime.schedule(context)
    }
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action in setOf(Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_DATE_CHANGED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_LOCALE_CHANGED, Intent.ACTION_BOOT_COMPLETED)) WidgetRuntime.schedule(context)
    }
}
class NextClassWidgetProvider : ScheduleWidgetProvider()
class TodayWidgetProvider : ScheduleWidgetProvider()

fun widgetComponents(context: Context): List<Pair<ComponentName, ScheduleWidgetKind>> = listOf(
    ComponentName(context, NextClassWidgetProvider::class.java) to ScheduleWidgetKind.NEXT,
    ComponentName(context, TodayWidgetProvider::class.java) to ScheduleWidgetKind.TODAY
)
