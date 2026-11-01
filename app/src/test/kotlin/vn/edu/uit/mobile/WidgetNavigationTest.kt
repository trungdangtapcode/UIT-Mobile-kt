package vn.edu.uit.mobile

import android.app.Application
import android.os.Bundle
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import java.io.File
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
@OptIn(ExperimentalRoborazziApi::class)
class WidgetNavigationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val repository = FakePortalRepository().apply { snapshot.value = WorkspacePreviewData.snapshot }
    private val owners = MutableStateFlow(emptyList<Account>())
    private val bookmarks = object : BookmarkRepository {
        override val collection = MutableStateFlow(BookmarkCollection())
        override suspend fun save(bookmark: Bookmark) {
            collection.value = collection.value.copy(items = collection.value.items.filterNot { it.id == bookmark.id } + bookmark)
        }
        override suspend fun remove(accountId: String, id: String) {
            collection.value = collection.value.copy(items = collection.value.items.filterNot { it.accountId == accountId && it.id == id })
        }
    }

    @Before fun inject() {
        // Assertions name English interface text, so pin the language before the Activity starts.
        LanguagePreference.write(ApplicationProvider.getApplicationContext(), AppLanguage.ENGLISH)
        startKoin {
            modules(
                module {
                    single<PortalRepository> { repository }
                    single<BookmarkRepository> { bookmarks }
                    single<AccountRepository> {
                        object : AccountRepository {
                            override val accounts = owners
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

    @Test fun savedItemRetainsFiltersThroughDetailCourseAndActivityRecreation() {
        val course = repository.snapshot.value.courses.first()
        val owner = Account(course.accountId, "https://courses.uit.edu.vn", 1, "Synthetic", PortalType.MOODLE)
        owners.value = listOf(owner)
        val hit = SearchWorkspaceUseCase().index(repository.snapshot.value, listOf(owner)).first { it.kind == WorkspaceSearchKind.COURSES }
        bookmarks.collection.value = BookmarkCollection(listOf(requireNotNull(bookmarkFor(hit))))
        var activity = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        try {
            compose.onNodeWithText("Courses", substring = false).performClick()
            compose.onNodeWithText("Saved items").performClick()
            compose.onNodeWithText("Find a saved item").performTextInput(hit.title)
            courseFilter().performClick().assertIsSelected()
            compose.onNodeWithText("Open saved item").performScrollTo().performClick()
            compose.onNodeWithText("Open course workspace").performScrollTo().performClick()
            compose.onNodeWithText("All courses").performScrollTo().performClick()
            compose.onNodeWithText("Open course workspace").assertExists()
            compose.onNodeWithText("Saved items", substring = false).performScrollTo().performClick()
            compose.onNodeWithText("Your local study collection").assertExists()
            assertSavedFilters(hit.title)
            val saved = Bundle()
            activity.saveInstanceState(saved).pause().stop().destroy()
            activity = Robolectric.buildActivity(MainActivity::class.java).setup(saved).visible()
            assertSavedFilters(hit.title)
            if (java.lang.Boolean.getBoolean("roborazzi.test.record")) {
                compose.waitForIdle()
                compose.mainClock.advanceTimeBy(64)
                compose.waitForIdle()
                captureScreenRoboImage(File(System.getProperty("uit.preview.dir"), "bookmarks-return-390-phone.png").absolutePath)
            }
            compose.onNodeWithText("Workspace", substring = false).performScrollTo().performClick()
            compose.onNodeWithText("Course directory").assertExists()
        } finally {
            activity.pause().stop().destroy()
        }
    }

    @Test fun savedItemRetainsItsScrollPositionAfterReturningFromDetail() {
        val original = repository.snapshot.value.courses.first()
        val courses = (1..20).map { number -> original.copy(id = number.toLong(), shortname = "SAVED$number", fullname = "Saved course $number") }
        repository.snapshot.value = repository.snapshot.value.copy(courses = courses)
        val owner = Account(original.accountId, "https://courses.uit.edu.vn", 1, "Synthetic", PortalType.MOODLE)
        owners.value = listOf(owner)
        val hits = SearchWorkspaceUseCase().index(repository.snapshot.value, listOf(owner)).filter { it.kind == WorkspaceSearchKind.COURSES }
        val items = hits.map { requireNotNull(bookmarkFor(it)) }
        bookmarks.collection.value = BookmarkCollection(items)
        val target = items.last()
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        try {
            compose.onNodeWithText("Courses", substring = false).performClick()
            compose.onNodeWithText("Saved items").performClick()
            val list = compose.onNodeWithTag("bookmarks-content")
            list.performScrollToNode(hasTestTag("bookmark:${target.id}"))
            val open = compose.onNode(hasText("Open saved item") and hasAnyAncestor(hasTestTag("bookmark:${target.id}")))
            open.performScrollTo()
            val position = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
            assertTrue(position > 0f)
            open.performClick()
            compose.onNodeWithText("Saved items", substring = false).performScrollTo().performClick()
            compose.onNodeWithTag("bookmark:${target.id}").assertIsDisplayed()
            assertEquals(position, list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value(), 0.5f)
        } finally {
            activity.pause().stop().destroy()
        }
    }

    private fun courseFilter() = compose.onNode(hasText("Courses") and hasAnyAncestor(SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange)))

    private fun assertSavedFilters(query: String) {
        compose.onNodeWithTag("bookmarks-content").performScrollToNode(hasSetTextAction())
        assertEquals(query, compose.onNode(hasSetTextAction()).fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        courseFilter().assertIsSelected()
        compose.onNodeWithText("All", substring = false).assertIsNotSelected()
    }
}
