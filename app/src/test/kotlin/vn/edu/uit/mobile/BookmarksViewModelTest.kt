package vn.edu.uit.mobile

import android.app.Application
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import vn.edu.uit.mobile.common.*
import vn.edu.uit.mobile.courses.*
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.model.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class BookmarksViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = ViewModelStore()
    private val course = Course(1, "one", "IT004", "Databases")
    private val file = CourseFile("Notes.pdf", "https://courses.uit.edu.vn/pluginfile.php/1/notes.pdf")
    private val snapshot = PortalSnapshot(courses = listOf(course), sections = mapOf("one:1" to listOf(CourseSection(1, "Week 1", listOf(CourseModule(1, 1, "Notes", "resource", "", files = listOf(file)))))))
    private class Accounts : AccountRepository {
        override val accounts = MutableStateFlow(listOf(Account("one", "https://courses.uit.edu.vn", 1, "First", PortalType.MOODLE)))
        override suspend fun remove(accountId: String) {
            accounts.value = accounts.value.filterNot { it.id == accountId }
        }
        override suspend fun saveSessions(sessions: List<PortalSession>) = Unit
    }
    private class Repository : BookmarkRepository {
        override val collection = MutableStateFlow(BookmarkCollection())
        var reject = false
        override suspend fun save(bookmark: Bookmark) {
            check(!reject) { "Synthetic storage failure" }
            collection.value = collection.value.copy(items = collection.value.items.filterNot { it.id == bookmark.id } + bookmark.copy(savedAt = 123))
        }
        override suspend fun remove(accountId: String, id: String) {
            collection.value = collection.value.copy(items = collection.value.items.filterNot { it.id == id && it.accountId == accountId })
        }
    }
    private val accounts = Accounts()
    private val repository = Repository()
    private val portal = FakePortalRepository().apply { snapshot.value = this@BookmarksViewModelTest.snapshot }
    private fun model() = BookmarksViewModel(repository, portal, accounts).also { models.put("bookmarks", it) }

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    @After fun cleanup() {
        models.clear()
        dispatcher.scheduler.runCurrent()
        Dispatchers.resetMain()
    }

    @Test fun pinningAndOpeningSurvivesFileRenameAndSectionMove() = runTest(dispatcher) {
        val vm = model()
        runCurrent()
        val hit = vm.uiState.value.sources.first { it.file != null }
        vm.toggleHit(hit.key)
        runCurrent()
        val saved = vm.uiState.value.items.single()
        val moved = CourseModule(2, 1, "Moved", "resource", "", files = listOf(file.copy(name = "Renamed.pdf")))
        portal.snapshot.value = snapshot.copy(sections = mapOf("one:1" to listOf(CourseSection(2, "Week 2", listOf(moved)))))
        runCurrent()
        assertEquals("Renamed.pdf", vm.source(saved.id)?.title)
        assertNotEquals(hit.key, vm.source(saved.id)?.key)
        vm.toggleHit(requireNotNull(vm.source(saved.id)).key)
        runCurrent()
        assertTrue(vm.uiState.value.items.isEmpty())
    }

    @Test fun removedSourcesStayExplicitlyUnavailableUntilTheirMetadataReturns() = runTest(dispatcher) {
        val vm = model()
        runCurrent()
        vm.toggleHit(vm.uiState.value.sources.first { it.file != null }.key)
        runCurrent()
        val saved = vm.uiState.value.items.single()
        portal.snapshot.value = PortalSnapshot()
        runCurrent()
        assertNull(vm.source(saved.id))
        assertFalse(vm.uiState.value.available(saved))
        assertEquals(1, vm.uiState.value.items.size)
        portal.snapshot.value = snapshot
        runCurrent()
        assertNotNull(vm.source(saved.id))
    }

    @Test fun accountRemovalHidesStaleSavedItemsAndRejectsOriginalLinks() = runTest(dispatcher) {
        val vm = model()
        runCurrent()
        vm.addLink("one", "Forum", "https://forum.uit.edu.vn/")
        runCurrent()
        val id = vm.uiState.value.items.single().id
        assertNotNull(vm.originalUrl(id))
        accounts.remove("one")
        runCurrent()
        assertTrue(vm.uiState.value.items.isEmpty())
        assertNull(vm.originalUrl(id))
        assertNull(vm.source(id))
    }

    @Test fun linkDialogOnlyReportsSuccessAfterPersistenceAndRejectsSessionUrls() = runTest(dispatcher) {
        val vm = model()
        runCurrent()
        var completed = false
        vm.addLink("one", "Forum", "https://forum.uit.edu.vn/?token=private") { completed = true }
        runCurrent()
        assertEquals(BookmarkProblem.LINK, vm.uiState.value.problem)
        assertFalse(completed)
        repository.reject = true
        vm.addLink("one", "Forum", "https://forum.uit.edu.vn/") { completed = true }
        runCurrent()
        assertEquals(BookmarkProblem.SAVE, vm.uiState.value.problem)
        assertFalse(completed)
        repository.reject = false
        vm.addLink("one", "Forum", "https://forum.uit.edu.vn/") { completed = true }
        runCurrent()
        assertTrue(completed)
        assertEquals(BookmarkKind.LINK, vm.uiState.value.items.single().kind)
    }

    @Test fun discussionBookmarksUseOriginalUrlsAndNeverActAsRemoteForumBookmarks() = runTest(dispatcher) {
        val vm = model()
        runCurrent()
        vm.toggleDiscussion(course, Discussion(8, 1, "Discussion", "Synthetic", 0))
        runCurrent()
        val item = vm.uiState.value.items.single()
        assertEquals(BookmarkKind.DISCUSSION, item.kind)
        assertEquals("https://courses.uit.edu.vn/mod/forum/discuss.php?d=8", vm.originalUrl(item.id))
        assertNull(vm.source(item.id))
    }

    @Test fun lateDiscussionResponseCannotBeShownOrBookmarkedUnderAnotherCourse() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val forum = CourseModule(10, 1, "Forum", "forum", "", instanceId = 1)
        val other = course.copy(id = 2, accountId = "two", fullname = "Other course")
        val racing = object : FakePortalRepository() {
            override suspend fun courseDetails(course: Course) = AppResult.Success(if (course.id == 1L) listOf(CourseSection(1, "General", listOf(forum))) else emptyList())
            override suspend fun discussions(course: Course, module: CourseModule) = withContext(NonCancellable) {
                gate.await()
                AppResult.Success(listOf(Discussion(8, 1, "Old account discussion", "Synthetic", 0)))
            }
        }.apply { snapshot.value = PortalSnapshot(courses = listOf(course, other)) }
        val courses = CoursesViewModel(racing).also { models.put("courses", it) }
        runCurrent()
        courses.onIntent(CoursesIntent.Select(course))
        runCurrent()
        courses.onIntent(CoursesIntent.Forum(forum))
        runCurrent()
        courses.onIntent(CoursesIntent.Select(other))
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertEquals(other, courses.uiState.value.selected)
        assertTrue(courses.uiState.value.discussions.isEmpty())
    }
}
