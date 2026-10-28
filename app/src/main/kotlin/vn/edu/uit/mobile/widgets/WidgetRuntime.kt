package vn.edu.uit.mobile.widgets

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.res.Configuration
import android.util.SizeF
import androidx.core.os.BundleCompat
import androidx.work.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import vn.edu.uit.mobile.data.UitGraph
import vn.edu.uit.mobile.domain.BuildScheduleWidgetUseCase

class WidgetRenderWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        WidgetRuntime.render(applicationContext)
        Result.success()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        if (runAttemptCount < 2) Result.retry() else Result.failure()
    }
}

/** Widgets read the existing cache; the existing UIT sync worker owns network refreshes. */
object WidgetRuntime {
    private const val PERIODIC = "uit-widget-periodic"
    private const val RENDER = "uit-widget-render"
    private const val BOUNDARY = "uit-widget-boundary"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var observation: Job? = null

    fun hasWidgets(context: Context): Boolean = widgetComponents(context).any { AppWidgetManager.getInstance(context).getAppWidgetIds(it.first).isNotEmpty() }

    fun schedule(context: Context) {
        val app = context.applicationContext
        val work = WorkManager.getInstance(app)
        if (!hasWidgets(app)) {
            observation?.cancel()
            observation = null
            work.cancelUniqueWork(PERIODIC)
            work.cancelUniqueWork(RENDER)
            work.cancelAllWorkByTag(BOUNDARY)
            return
        }
        observe(app)
        work.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<WidgetRenderWorker>(15, TimeUnit.MINUTES).build())
        requestRender(app)
    }

    @OptIn(FlowPreview::class)
    fun observe(context: Context) {
        if (!hasWidgets(context) || observation?.isActive == true) return
        observation = scope.launch {
            try {
                val graph = UitGraph.get(context)
                combine(graph.accounts.accounts, graph.portal.snapshot) { _, _ -> Unit }.debounce(250).collect {
                    try {
                        render(context)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        requestRender(context)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The periodic worker retries cache access without crashing the application.
            }
        }
    }

    fun requestRender(context: Context) {
        if (hasWidgets(context)) WorkManager.getInstance(context).enqueueUniqueWork(RENDER, ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<WidgetRenderWorker>().build())
    }

    suspend fun render(context: Context) = mutex.withLock {
        if (!hasWidgets(context)) return@withLock
        val graph = UitGraph.get(context)
        val snapshot = graph.portal.snapshot.first()
        val connected = graph.accounts.accounts.first().isNotEmpty()
        val now = Clock.System.now().epochSeconds
        val state = BuildScheduleWidgetUseCase()(snapshot, connected, now)
        val manager = AppWidgetManager.getInstance(context)
        widgetComponents(context).forEach { (component, kind) ->
            manager.getAppWidgetIds(component).forEach { id ->
                val options = manager.getAppWidgetOptions(id)
                val landscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                val width = options.getInt(if (landscape) AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH else AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 320).coerceAtLeast(220)
                val height = options.getInt(if (landscape) AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, if (kind == ScheduleWidgetKind.NEXT) 144 else 260).coerceAtLeast(96)
                if (android.os.Build.VERSION.SDK_INT >= 31) {
                    val sizes = BundleCompat.getParcelableArrayList(options, AppWidgetManager.OPTION_APPWIDGET_SIZES, SizeF::class.java).orEmpty().filter { it.width > 0 && it.height > 0 && it.width.isFinite() && it.height.isFinite() }.distinct().take(2)
                    if (sizes.isNotEmpty()) {
                        val layouts = sizes.associateWith { size -> ScheduleWidgetRenderer.render(context, kind, state, size.width.toInt(), size.height.toInt(), now) }
                        manager.updateAppWidget(id, android.widget.RemoteViews(layouts))
                    } else {
                        manager.updateAppWidget(id, ScheduleWidgetRenderer.render(context, kind, state, width, height, now))
                    }
                } else {
                    manager.updateAppWidget(id, ScheduleWidgetRenderer.render(context, kind, state, width, height, now))
                }
            }
        }
        val delay = (state.nextUpdateAt - now).coerceAtLeast(1)
        WorkManager.getInstance(context).enqueueUniqueWork(
            "$BOUNDARY:${state.nextUpdateAt}",
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<WidgetRenderWorker>().setInitialDelay(delay, TimeUnit.SECONDS).addTag(BOUNDARY).build()
        )
    }
}
