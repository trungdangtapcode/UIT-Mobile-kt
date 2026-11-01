package vn.edu.uit.mobile

import android.app.Application
import android.os.Bundle
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import vn.edu.uit.mobile.designsystem.AppLanguage
import vn.edu.uit.mobile.designsystem.LanguagePreference
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.model.*
import vn.edu.uit.mobile.widgets.WidgetRoute

/** Exercise widget intents through the production Activity with synthetic cached data. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetNavigationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val repository = FakePortalRepository().apply { snapshot.value = WorkspacePreviewData.snapshot }

    @Before fun inject() {
        // Assertions name English interface text, so pin the language before the Activity starts.
        LanguagePreference.write(ApplicationProvider.getApplicationContext(), AppLanguage.ENGLISH)
        startKoin {
            modules(
                module {
                    single<PortalRepository> { repository }
                    single<AccountRepository> {
                        object : AccountRepository {
                            override val accounts = MutableStateFlow(emptyList<Account>())
                            override suspend fun remove(accountId: String) {}
                            override suspend fun saveSessions(sessions: List<PortalSession>) {}
                        }
                    }
                }
            )
        }
    }

    @After fun cleanup() {
        stopKoin()
    }

    @Test fun scheduleWidgetOpensItsDateAndConsumesTheLaunchIntent() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val launch = WidgetRoute("schedule", "2026-10-26").intent(context)
        var activity = Robolectric.buildActivity(MainActivity::class.java, launch).setup().visible()
        try {
            compose.onNodeWithContentDescription("Show schedule for 2026-10-26").assertIsSelected()
            compose.onNodeWithText("Advanced Database Systems").assertIsDisplayed()
            assertNull(WidgetRoute.read(activity.get().intent))
            compose.onNodeWithText("Courses", substring = false).performClick()
            compose.onNodeWithText("Course directory").assertIsDisplayed()
            val saved = Bundle()
            activity.saveInstanceState(saved).pause().stop().destroy()
            activity = Robolectric.buildActivity(MainActivity::class.java, launch).setup(saved).visible()
            compose.onNodeWithText("Course directory").assertIsDisplayed()
        } finally {
            activity.pause().stop().destroy()
        }
    }

    @Test fun overdueWidgetOpensTheCorrectInboxFilter() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val activity = Robolectric.buildActivity(MainActivity::class.java, WidgetRoute("overdue").intent(context)).setup().visible()
        try {
            compose.onNodeWithText("Deadlines & inbox").assertIsDisplayed()
            compose.onNodeWithText("Overdue (", substring = true).assertIsSelected()
        } finally {
            activity.pause().stop().destroy()
        }
    }

    @Test fun tappingAWidgetClosesThePinChooserAndAccountSheet() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        try {
            compose.onNodeWithContentDescription("Open student profile").performClick()
            compose.onNodeWithContentDescription("Manage accounts and appearance").performClick()
            compose.onNodeWithText("Home screen widgets").performScrollTo().performClick()
            compose.onNodeWithText("See your saved timetable without opening UIT Mobile.").assertIsDisplayed()
            activity.newIntent(WidgetRoute("schedule", "2026-10-26").intent(context))
            compose.onNodeWithContentDescription("Show schedule for 2026-10-26").assertIsSelected()
            compose.onNodeWithText("See your saved timetable without opening UIT Mobile.").assertDoesNotExist()
            compose.onNodeWithText("Accounts & appearance").assertDoesNotExist()
        } finally {
            activity.pause().stop().destroy()
        }
    }
}
