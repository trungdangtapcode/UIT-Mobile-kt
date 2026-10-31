package vn.edu.uit.mobile

import android.app.Application
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.*
import vn.edu.uit.mobile.courses.*
import vn.edu.uit.mobile.designsystem.*
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.model.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WorkspaceSearchDesignTest {
    @get:Rule val compose = createComposeRule()

    @After fun reset() {
        RuntimeEnvironment.setFontScale(1f)
    }
    private val course = Course(1, "one", "IT004.O21", "Database Systems")
    private val assignment = Assignment(2, 1, 3, "Database project", "Submit a report and SQL tests.", due = 1793577600, accountId = "one")
    private val hits = SearchWorkspaceUseCase().index(PortalSnapshot(courses = listOf(course), assignments = listOf(assignment)), listOf(Account("one", "https://courses.example.org", 1, "Synthetic", PortalType.MOODLE)))

    @Test fun groupedResultsOpenTheExactAssignmentAndBackPreservesTheQuery() {
        var state by mutableStateOf(WorkspaceSearchState(hits, "database"))
        compose.setContent { Frame { WorkspaceSearchContent(state, { state = state.copy(selected = null) }, { state = state.copy(query = it) }, { state = state.copy(selected = it) }, onCourse = {}) } }
        capture("search-390-phone")
        compose.onNodeWithTag("workspace-search-content").performScrollToNode(hasText("Assignments · 1"))
        compose.onAllNodesWithText("Open saved result").onLast().performScrollTo().performClick()
        compose.onNodeWithText("Submit a report and SQL tests.").assertExists()
        capture("search-assignment-390-phone")
        compose.onNodeWithText("Search results", substring = false).performClick()
        compose.onNodeWithTag("workspace-search-content").performScrollToNode(hasSetTextAction())
        compose.onNode(hasSetTextAction()).assertTextContains("database")
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-mdpi")
    fun emptyCacheAndNoMatchesRemainDifferent() {
        var state by mutableStateOf(WorkspaceSearchState(query = "database"))
        compose.setContent { Frame { WorkspaceSearchContent(state) } }
        compose.onNodeWithText("No saved searchable data").performScrollTo().assertIsDisplayed()
        capture("search-unavailable-360-phone")
        compose.runOnIdle { state = state.copy(hits = hits, query = "missing") }
        compose.onNodeWithText("No matching saved results").performScrollTo().assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w412dp-h915dp-mdpi")
    fun largeTextKeepsSearchAndOpeningActionsReachable() {
        RuntimeEnvironment.setFontScale(1.3f)
        compose.setContent { Frame { WorkspaceSearchContent(WorkspaceSearchState(hits, "database")) } }
        capture("search-412-large-text-phone")
        compose.onNodeWithTag("workspace-search-content").performScrollToNode(hasText("Assignments · 1"))
        compose.onAllNodesWithText("Open saved result").onLast().performScrollTo().assertIsDisplayed()
    }

    @Test fun courseWorkspaceNavigationUsesTheOwnedCourse() {
        var opened: Course? = null
        val courseHit = hits.first { it.kind == WorkspaceSearchKind.COURSES }
        compose.setContent { Frame { WorkspaceSearchContent(WorkspaceSearchState(hits, "database", courseHit.key), onCourse = { opened = it }) } }
        compose.onNodeWithText("Open course workspace").performScrollTo().performClick()
        assertEquals(course, opened)
    }

    @Composable private fun Frame(content: @Composable () -> Unit) {
        UitTheme(dark = true, dynamic = false) { WorkspaceFrame(2, {}, {}, "Synthetic", true, false, 1, onSearch = {}) { content() } }
    }
    private fun capture(name: String) {
        if (java.lang.Boolean.getBoolean("roborazzi.test.record")) {
            compose.waitForIdle()
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            compose.onRoot().captureRoboImage(File(System.getProperty("uit.preview.dir"), "$name.png").absolutePath)
        }
    }
}
