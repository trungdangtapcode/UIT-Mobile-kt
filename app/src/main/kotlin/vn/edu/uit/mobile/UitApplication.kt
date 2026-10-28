package vn.edu.uit.mobile

import android.app.Application
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.dsl.module
import vn.edu.uit.mobile.data.*
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.widgets.WidgetRuntime

class UitApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@UitApplication)
            modules(
                module {
                    single { UitGraph.get(get()) }
                    single<PortalRepository> { get<UitGraph>().portal }
                    single<AccountRepository> { get<UitGraph>().accounts }
                }
            )
        }
        UitSyncWorker.schedule(this)
        WidgetRuntime.observe(this)
    }
}
