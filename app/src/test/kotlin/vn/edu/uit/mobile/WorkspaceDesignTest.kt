package vn.edu.uit.mobile

import android.app.Application
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import kotlinx.datetime.LocalDate
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import vn.edu.uit.mobile.academic.*
import vn.edu.uit.mobile.courses.*
import vn.edu.uit.mobile.dashboard.*
import vn.edu.uit.mobile.designsystem.*
import vn.edu.uit.mobile.schedule.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WorkspaceDesignTest {
    @get:Rule val compose = createComposeRule()
    private val data = WorkspacePreviewData
    private fun capture(name: String) {
        if (java.lang.Boolean.getBoolean("roborazzi.test.record")) {
            val path = File(System.getProperty("uit.preview.dir"), "$name.png")
            path.parentFile!!.mkdirs()
            compose.onRoot().captureRoboImage(path.absolutePath)
        }
    }

    @Composable private fun Frame(selected: Int, dark: Boolean = false, onSelect: (Int) -> Unit = {}, body: @Composable () -> Unit) {
        UitTheme(dark = dark, dynamic = false) { WorkspaceFrame(selected, onSelect, {}, "Alex Morgan", true, false, data.now - 300, 3, content = body) }
    }

    @Test fun phoneDashboardNavigatesToSchedule() {
        var opened = false
        compose.setContent { Frame(0) { DashboardContent(DashboardState(data.snapshot), now = data.now, onSchedule = { opened = true }) } }
        compose.onNodeWithText("Monday").assertIsDisplayed()
        capture("dashboard-phone")
        compose.onNodeWithText("Full schedule").performClick()
        assertTrue(opened)
    }

    @Test fun lightDashboardUsesUitBranding() {
        compose.setContent { Frame(0, dark = false) { DashboardContent(DashboardState(data.snapshot), now = data.now) } }
        compose.onNodeWithTag("uit-brand-mark").assertIsDisplayed()
        compose.onNodeWithText("Monday").assertIsDisplayed()
        capture("dashboard-light-phone")
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-mdpi")
    fun smallPhoneKeepsTimetableAndNavigationAccessible() {
        compose.setContent { Frame(0, dark = false) { DashboardContent(DashboardState(data.snapshot), now = data.now) } }
        compose.onNodeWithText("Due soon").assertIsDisplayed()
        capture("dashboard-360-phone")
    }

    @Test fun nextSessionActionOpensMatchingMoodleCourse() {
        var courseId: Long? = null
        compose.setContent { Frame(0) { DashboardContent(DashboardState(data.snapshot), now = data.now, onCourse = { courseId = it.id }) } }
        compose.onNode(hasText("Advanced Database Systems") and hasAnyAncestor(hasTestTag("dashboard-agenda"))).performClick()
        assertEquals(5L, courseId)
    }

    @Test fun emptyDashboardOffersUitSignIn() {
        var requested = false
        compose.setContent {
            UitTheme(dark = true) {
                WorkspaceFrame(0, {}, {}, "", false, false, 0) {
                    DashboardContent(DashboardState(), now = data.now, onConnect = { requested = true })
                }
            }
        }
        compose.onNodeWithTag("uit-brand-mark").assertIsDisplayed()
        capture("dashboard-connect-phone")
        compose.onNodeWithText("Connect UIT account").performClick()
        assertTrue(requested)
    }

    @Test fun darkDashboardRemainsReadable() {
        compose.setContent { Frame(0, dark = true) { DashboardContent(DashboardState(data.snapshot), now = data.now) } }
        compose.onNodeWithText("Monday").assertIsDisplayed()
        capture("dashboard-dark")
    }

    @Test
    @Config(qualifiers = "w412dp-h915dp-mdpi")
    fun largerPhoneShowsRoomAndUpcomingSubmission() {
        compose.setContent { Frame(0) { DashboardContent(DashboardState(data.snapshot), now = data.now) } }
        compose.onNodeWithTag("uit-brand-mark").assertIsDisplayed()
        compose.onNode(hasText("Timetable") and hasAnyAncestor(hasTestTag("dashboard-agenda"))).assertIsDisplayed()
        compose.onNodeWithText("B3.14").assertIsDisplayed()
        compose.onNodeWithText("Architecture review & design patterns").assertIsDisplayed()
        capture("dashboard-412-phone")
    }

    @Test fun scheduleShowsDayAndOpensClassDetails() {
        compose.setContent { Frame(1) { ScheduleContent(ScheduleState(data.snapshot, LocalDate(2026, 10, 26))) } }
        capture("schedule-phone")
        compose.onNodeWithText("Advanced Database Systems").performScrollTo().performClick()
        compose.onNodeWithText("Lecturer").assertIsDisplayed()
        compose.onNodeWithText("Dr. Taylor Nguyen").assertIsDisplayed()
    }

    @Test fun monthCalendarSelectsADateAndReturnsToAgenda() {
        compose.setContent {
            var state by remember { mutableStateOf(ScheduleState(data.snapshot, LocalDate(2026, 10, 26))) }
            Frame(1) {
                ScheduleContent(state, onIntent = { intent ->
                    state = when (intent) {
                        is ScheduleIntent.Date -> state.copy(anchor = intent.date)
                        is ScheduleIntent.Semester -> state.copy(anchor = intent.date)
                        ScheduleIntent.Toggle -> state.copy(monthly = !state.monthly)
                        is ScheduleIntent.Move -> state
                    }
                })
            }
        }
        compose.onNodeWithText("Month").performClick()
        capture("schedule-month-phone")
        compose.onNodeWithContentDescription("2026-10-27, 1 events").performScrollTo().performClick()
        compose.onNodeWithText("Machine Learning").assertIsDisplayed()
    }

    @Test fun phoneWeekViewGroupsClassesByDate() {
        compose.setContent { Frame(1) { ScheduleContent(ScheduleState(data.snapshot, LocalDate(2026, 10, 26))) } }
        compose.onNodeWithText("Week").performClick()
        compose.onNodeWithText("October 26, 2026").assertIsDisplayed()
        capture("schedule-week-phone")
    }

    @Test fun phoneCourseSearchFiltersRealCourses() {
        compose.setContent {
            var state by remember { mutableStateOf(CoursesState(snapshot = data.snapshot)) }
            Frame(2) { CoursesContent(state, onIntent = { if (it is CoursesIntent.Query) state = state.copy(query = it.text) }) }
        }
        capture("courses-phone")
        compose.onNode(hasSetTextAction()).performTextInput("Database")
        compose.onNodeWithText("Advanced Database Systems").assertIsDisplayed()
        compose.onNodeWithText("Machine Learning").assertDoesNotExist()
    }

    @Test fun materialSectionCanCollapse() {
        compose.setContent { Frame(2) { CoursesContent(data.courseState) } }
        capture("course-materials-phone")
        compose.onNodeWithText("Getting started").performScrollTo().performClick()
        compose.onNodeWithText("Your course roadmap").assertDoesNotExist()
    }

    @Test fun academicPlannerAndTranscriptRender() {
        compose.setContent { Frame(4) { AcademicContent(AcademicState(data.snapshot)) } }
        compose.onNodeWithText("Cumulative GPA").assertIsDisplayed()
        capture("academic-phone")
        compose.onNodeWithTag("academic-content").performScrollToNode(hasText("Academic transcript"))
        compose.onNodeWithText("Advanced Database Systems").performScrollTo().performClick()
        compose.onNodeWithText("Practice").assertIsDisplayed()
        compose.onNodeWithTag("academic-content").performScrollToNode(hasText("GPA simulator"))
        compose.onNode(hasSetTextAction()).performTextClearance()
        compose.onNode(hasSetTextAction()).performTextInput("6")
        compose.onNode(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsActions.SetProgress)).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(9f) }
        compose.onNodeWithText("8.22").assertIsDisplayed()
        capture("academic-planner-phone")
    }

    @Test fun phoneAcademicSelectsSemesterPerformance() {
        compose.setContent { Frame(4) { AcademicContent(AcademicState(data.snapshot)) } }
        compose.onNodeWithText("Semester performance").assertIsDisplayed()
        compose.onNode(hasText("Spring 2026") and hasAnyAncestor(hasTestTag("semester-performance-selector"))).performClick().assertIsSelected()
        compose.onNodeWithText("Spring 2026 · GPA 8.10 / 10").assertIsDisplayed()
        capture("academic-semester-phone")
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-mdpi")
    fun compactPhoneWithLargeTextKeepsNavigationAccessible() {
        compose.setContent {
            val density = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density, 1.3f)) {
                Frame(0) { DashboardContent(DashboardState(data.snapshot), now = data.now) }
            }
        }
        compose.onNodeWithText("Academic").assertIsDisplayed()
        capture("dashboard-compact-large-text")
        compose.onNodeWithText("In progress").assertIsDisplayed()
    }

    @Test fun teachingWeekShowsCancellationAndClassDetails() {
        compose.setContent { Frame(0) { DashboardContent(DashboardState(data.snapshot), now = data.now) } }
        compose.onNodeWithContentDescription("Show timetable for 2026-10-27").performClick()
        compose.onNodeWithText("Tuesday").assertIsDisplayed()
        compose.onNodeWithText("Canceled").assertIsDisplayed()
        compose.onNodeWithText("B4.12").assertIsDisplayed()
        capture("dashboard-canceled-phone")
        compose.onNode(hasText("Machine Learning") and hasAnyAncestor(hasTestTag("dashboard-agenda"))).performClick()
        compose.onNodeWithText("Room B4.12").assertIsDisplayed()
        compose.onNodeWithText("Done").performClick()
    }

    @Test fun emptyTeachingDayKeepsSubmissionsAvailable() {
        var openedInbox = false
        compose.setContent { Frame(0) { DashboardContent(DashboardState(data.snapshot), now = data.now, onInbox = { openedInbox = true }) } }
        compose.onNodeWithContentDescription("Show timetable for 2026-10-31").performClick()
        compose.onNodeWithText("No classes or exams on this day.").assertIsDisplayed()
        compose.onNodeWithText("Due soon").assertIsDisplayed()
        capture("dashboard-empty-day-phone")
        compose.onNodeWithText("View all").performClick()
        assertTrue(openedInbox)
    }

    @Test fun assignmentDatesStayVisibleWhenCalendarDataIsMissing() {
        val assignment = vn.edu.uit.mobile.model.Assignment(17, 5, 170, "Database lab submission", due = data.now + 7200, accountId = "preview")
        val snapshot = data.snapshot.copy(events = emptyList(), assignments = listOf(assignment))
        compose.setContent { Frame(0) { DashboardContent(DashboardState(snapshot), now = data.now) } }
        compose.onNodeWithText("Database lab submission").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Deadline", substring = false).assertIsDisplayed()
    }

    @Test fun overdueWorkIsNotHiddenFromTheDashboardQueue() {
        val overdue = data.events.first().copy(key = "overdue", name = "Unsubmitted database report", start = data.now - 86400)
        val snapshot = data.snapshot.copy(events = listOf(overdue))
        compose.setContent { Frame(0) { DashboardContent(DashboardState(snapshot), now = data.now) } }
        compose.onNodeWithTag("dashboard-content").performScrollToNode(hasText("Unsubmitted database report"))
        compose.onNodeWithText("Overdue").assertIsDisplayed()
        capture("dashboard-overdue-phone")
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-mdpi")
    fun smallDarkPhoneKeepsRoomAndDeadlinesReadable() {
        compose.setContent { Frame(0, dark = true) { DashboardContent(DashboardState(data.snapshot), now = data.now) } }
        compose.onNodeWithText("Due soon").assertIsDisplayed()
        capture("dashboard-dark-360-phone")
    }

    @Test fun focusFiltersConfirmedSubmissions() {
        compose.setContent { Frame(3) { InboxContent(data.inboxState, now = data.now) } }
        capture("focus-phone")
        compose.onNodeWithText("Submitted (1)").performClick()
        compose.onNodeWithText("Sorting algorithms analysis").assertIsDisplayed()
        compose.onNodeWithText("Architecture review & design patterns").assertDoesNotExist()
    }

    @Test fun phoneConversationsOpenAndReturnToTheList() {
        compose.setContent {
            var state by remember { mutableStateOf(data.chatState.copy(selected = null, messages = emptyList())) }
            Frame(3) {
                InboxContent(state, onIntent = { intent ->
                    state = when (intent) {
                        is InboxIntent.Open -> state.copy(selected = intent.conversation, messages = data.chatState.messages)
                        InboxIntent.Back -> state.copy(selected = null, messages = emptyList())
                        else -> state
                    }
                }, now = data.now)
            }
        }
        capture("inbox-phone")
        compose.onNodeWithText("Dr. Taylor Nguyen").performClick()
        compose.onNodeWithContentDescription("Back to conversations").assertIsDisplayed().performClick()
        compose.onNodeWithText("Search conversations").assertIsDisplayed()
    }

    @Test fun phoneBottomNavigationSwitchesAllDestinations() {
        compose.setContent {
            var selected by remember { mutableIntStateOf(0) }
            Frame(selected, onSelect = { selected = it }) {
                when (selected) {
                    0 -> DashboardContent(DashboardState(data.snapshot), now = data.now)
                    1 -> ScheduleContent(ScheduleState(data.snapshot, LocalDate(2026, 10, 26)))
                    2 -> CoursesContent(CoursesState(snapshot = data.snapshot))
                    3 -> InboxContent(data.inboxState, now = data.now)
                    else -> AcademicContent(AcademicState(data.snapshot))
                }
            }
        }
        fun select(label: String) = compose.onNode(hasText(label) and hasAnyAncestor(hasTestTag("mobile-bottom-navigation"))).performClick()
        select("Courses")
        compose.onNodeWithText("Course directory").assertIsDisplayed()
        select("Academic")
        compose.onNodeWithText("Cumulative GPA").assertIsDisplayed()
        select("Inbox")
        compose.onNodeWithText("Deadlines & inbox").assertIsDisplayed()
        select("Schedule")
        compose.onNodeWithText("Class schedule").assertIsDisplayed()
        select("Today")
        compose.onNodeWithText("Monday").assertIsDisplayed()
    }

    @Test fun phoneMessageComposerKeepsDraftWhenSending() {
        var sent: String? = null
        compose.setContent {
            var draft by remember { mutableStateOf("Thanks! I'll bring the comparison to our lab session.") }
            Frame(3) { InboxContent(data.chatState, onIntent = { if (it is InboxIntent.Send) sent = it.text }, now = data.now, draft = draft, onDraftChange = { draft = it }) }
        }
        capture("inbox-conversation-phone")
        compose.onNodeWithContentDescription("Send message").performClick()
        assertNotNull(sent)
        compose.onNodeWithText("Thanks! I'll bring the comparison to our lab session.").assertIsDisplayed()
    }
}
