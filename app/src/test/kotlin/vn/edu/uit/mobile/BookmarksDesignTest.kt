package vn.edu.uit.mobile

import android.app.Application
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
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
import vn.edu.uit.mobile.feature.courses.R as CourseStrings
import vn.edu.uit.mobile.model.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalRoborazziApi::class)
class BookmarksDesignTest {
    @get:Rule val compose = createComposeRule()
    private val owner = Account("one", "https://courses.uit.edu.vn", 1, "Sample student", PortalType.MOODLE)
    private val course = Course(1, "one", "IT004.O21", "Database Systems")
    private val file = CourseFile("SQL introduction.pdf", "https://courses.uit.edu.vn/pluginfile.php/1/notes.pdf")
    private val snapshot = PortalSnapshot(courses = listOf(course), sections = mapOf("one:1" to listOf(CourseSection(1, "Week 1", listOf(CourseModule(1, 1, "SQL notes", "resource", "", files = listOf(file)))))))
    private val hits = SearchWorkspaceUseCase().index(snapshot, listOf(owner))
    private val fileHit = hits.first { it.file != null }
    private val saved = requireNotNull(bookmarkFor(fileHit))
    private val missing = Bookmark("one", BookmarkKind.ASSIGNMENT, "assignment:1:7", "Archived assignment", "IT004.O21", 1)
    private val link = Bookmark("one", BookmarkKind.LINK, "link:${bookmarkDigest("https://forum.uit.edu.vn/")}", "UIT Forum", "forum.uit.edu.vn", url = "https://forum.uit.edu.vn/")
    private val state = BookmarksState(listOf(saved, missing, link), hits, listOf(owner), ready = true)

    @After fun reset() {
        RuntimeEnvironment.setFontScale(1f)
    }

    @Test fun openingAndRemovingOperateOnTheExactSavedResource() {
        var opened: String? = null
        var removed: String? = null
        compose.setContent { Frame { BookmarksContent(state, onSource = { opened = it }, onRemove = { removed = it }) } }
        compose.onNodeWithTag("bookmarks-content").performScrollToNode(hasText("SQL introduction.pdf"))
        capture("bookmarks-390-phone")
        compose.onAllNodesWithText("Open saved item").onFirst().performScrollTo().performClick()
        assertEquals(saved.id, opened)
        compose.onNodeWithContentDescription("Remove bookmark for SQL introduction.pdf").performScrollTo().performClick()
        assertEquals(saved.id, removed)
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-mdpi")
    fun unavailableContentKeepsRemovalAccessibleWithoutAnOpenAction() {
        var removed = false
        compose.setContent { Frame { BookmarksContent(state.copy(items = listOf(missing), sources = emptyList()), onRemove = { removed = true }) } }
        compose.onNodeWithTag("bookmarks-content").performScrollToNode(hasText("Open saved item"))
        compose.onNodeWithText("Open saved item").assertIsNotEnabled()
        capture("bookmarks-unavailable-360-phone")
        compose.onNodeWithContentDescription("Remove bookmark for Archived assignment").performScrollTo().performClick()
        assertTrue(removed)
    }

    @Test
    @Config(qualifiers = "vi-w412dp-h915dp-mdpi")
    fun vietnameseLargeTextKeepsSavedActionsReachable() {
        RuntimeEnvironment.setFontScale(1.3f)
        val title = RuntimeEnvironment.getApplication().getString(CourseStrings.string.bm_title)
        val open = RuntimeEnvironment.getApplication().getString(CourseStrings.string.bm_open)
        compose.setContent { Frame { BookmarksContent(state.copy(items = listOf(saved))) } }
        compose.onNodeWithText(title).assertExists()
        compose.onNodeWithTag("bookmarks-content").performScrollToNode(hasText(open))
        compose.onNodeWithText(open).assertIsDisplayed()
        capture("bookmarks-412-vietnamese-large-text-phone")
    }

    @Test fun coursePinAndSavedItemsEntryDoNotTriggerCourseNavigation() {
        var navigated = false
        var pinned = false
        var selected = false
        compose.setContent { Frame { CoursesContent(CoursesState(snapshot = snapshot), onIntent = { selected = true }, onBookmarks = { navigated = true }, onBookmarkCourse = { pinned = it == course }) } }
        compose.onNodeWithContentDescription("Bookmark Database Systems").performScrollTo().performClick()
        assertTrue(pinned)
        assertFalse(selected)
        compose.onNodeWithText("Saved items").performScrollTo().performClick()
        assertTrue(navigated)
    }

    @Test fun searchPinUpdatesItsStateWithoutOpeningTheResult() {
        var selected: String? = null
        var pinned: String? = null
        var ids by mutableStateOf(emptySet<String>())
        compose.setContent {
            Frame {
                WorkspaceSearchContent(WorkspaceSearchState(hits, "SQL"), onOpen = { selected = it }, savedBookmarkIds = ids, onBookmark = {
                    pinned = it
                    ids = setOf(saved.id)
                })
            }
        }
        compose.onNodeWithTag("workspace-search-content").performScrollToNode(hasContentDescription("Bookmark SQL introduction.pdf"))
        compose.onNodeWithContentDescription("Bookmark SQL introduction.pdf").performClick()
        assertEquals(fileHit.key, pinned)
        assertNull(selected)
        compose.onNodeWithContentDescription("Remove bookmark for SQL introduction.pdf").assertExists()
        capture("bookmarks-search-390-phone")
    }

    @Test fun failedLinkSaveKeepsTheDraftUntilSuccess() {
        var current by mutableStateOf(state)
        var attempts = 0
        compose.setContent {
            Frame {
                BookmarksContent(current, onAddLink = { accountId, title, url, completed ->
                    assertEquals("one", accountId)
                    assertEquals("Forum", title)
                    attempts++
                    if (attempts == 1) {
                        current = current.copy(problem = BookmarkProblem.LINK)
                    } else {
                        assertEquals("https://forum.uit.edu.vn/", url)
                        completed()
                    }
                })
            }
        }
        compose.onNodeWithText("Add UIT link").performClick()
        compose.onNodeWithText("Title").performScrollTo().performTextInput("Forum")
        compose.onNodeWithText("UIT HTTPS address").performScrollTo().performTextInput("https://example.org/")
        compose.onNodeWithText("Save link").performClick()
        compose.onNodeWithText("Forum", substring = false).assertExists()
        compose.onNodeWithText("UIT HTTPS address").performTextReplacement("https://forum.uit.edu.vn/")
        compose.onNodeWithText("Save link").performClick()
        compose.onNodeWithText("Save link").assertDoesNotExist()
        assertEquals(2, attempts)
    }

    @Test fun bookmarksFromDisconnectedAccountsAreNeverRendered() {
        compose.setContent { Frame { BookmarksContent(state.copy(owners = emptyList())) } }
        compose.onNodeWithText("SQL introduction.pdf").assertDoesNotExist()
        compose.onNodeWithText("UIT Forum").assertDoesNotExist()
    }

    @Composable private fun Frame(content: @Composable () -> Unit) {
        UitTheme(dark = true, dynamic = false) { WorkspaceFrame(2, {}, {}, "Synthetic", true, false, 1, onSearch = {}) { content() } }
    }
    private fun capture(name: String) {
        if (java.lang.Boolean.getBoolean("roborazzi.test.record")) {
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(64)
            compose.waitForIdle()
            captureScreenRoboImage(File(System.getProperty("uit.preview.dir"), "$name.png").absolutePath)
        }
    }
}
