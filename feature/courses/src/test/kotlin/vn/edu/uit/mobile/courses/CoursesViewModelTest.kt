package vn.edu.uit.mobile.courses

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import vn.edu.uit.mobile.common.*
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.model.*

@OptIn(ExperimentalCoroutinesApi::class)
class CoursesViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun main() {
        Dispatchers.setMain(dispatcher)
    }

    @After fun reset() {
        Dispatchers.resetMain()
    }

    @Test fun selectedCourseShowsCacheImmediatelyAndKeepsItWhenOffline() = runTest(dispatcher) {
        val course = Course(1, "one", "IT004.O21", "Database")
        val section = CourseSection(1, "Cached section", emptyList())
        val repository = object : FakePortalRepository() {
            override suspend fun courseDetails(course: Course): AppResult<List<CourseSection>, AppError> = AppResult.Failure(AppError.NetworkOffline)
        }
        repository.snapshot.value = PortalSnapshot(courses = listOf(course), sections = mapOf("one:1" to listOf(section)))
        val vm = CoursesViewModel(repository)
        runCurrent()
        vm.onIntent(CoursesIntent.Select(course))
        assertEquals(listOf(section), vm.uiState.value.sections)
        assertTrue(vm.uiState.value.loading)
        runCurrent()
        assertFalse(vm.uiState.value.loading)
        assertEquals(AppError.NetworkOffline, vm.uiState.value.error)
        assertEquals(listOf(section), vm.uiState.value.sections)
    }

    @Test fun switchingCoursesCancelsObsoleteLoads() = runTest(dispatcher) {
        val one = Course(1, "one", "A", "First")
        val two = one.copy(id = 2, fullname = "Second")
        val gate = CompletableDeferred<Unit>()
        val repository = object : FakePortalRepository() {
            override suspend fun courseDetails(course: Course): AppResult<List<CourseSection>, AppError> {
                if (course.id == 1L) gate.await()
                return AppResult.Success(listOf(CourseSection(course.id, course.fullname, emptyList())))
            }
        }
        val vm = CoursesViewModel(repository)
        runCurrent()
        vm.onIntent(CoursesIntent.Select(one))
        runCurrent()
        vm.onIntent(CoursesIntent.Select(two))
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertEquals(two, vm.uiState.value.selected)
        assertEquals("Second", vm.uiState.value.sections.single().name)
    }

    @Test fun nativeReaderEffectsKeepExactCourseMetadataAndRejectAnObsoleteDownload() = runTest(dispatcher) {
        val course = Course(1, "owner", "IT003", "First")
        val other = course.copy(id = 2, shortname = "IT004", fullname = "Second")
        val file = CourseFile("Lecture.pdf", "https://example.invalid/lecture.pdf")
        val pending = CompletableDeferred<Unit>()
        val repository = object : FakePortalRepository() {
            override suspend fun download(course: Course, file: CourseFile): AppResult<DownloadedFile, AppError> {
                pending.await()
                return AppResult.Success(DownloadedFile("/synthetic/lecture.pdf", "synthetic"))
            }
        }
        val vm = CoursesViewModel(repository)
        val models = ViewModelStore().apply { put("courses", vm) }
        val events = mutableListOf<CoursesEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiEffect.collect { events += it } }
        try {
            runCurrent()
            vm.onIntent(CoursesIntent.Select(course))
            runCurrent()
            vm.onIntent(CoursesIntent.Download(file))
            runCurrent()
            vm.onIntent(CoursesIntent.Select(other))
            runCurrent()
            pending.complete(Unit)
            runCurrent()
            assertTrue(events.none { it is CoursesEffect.OpenFile })
            vm.onIntent(CoursesIntent.Download(file))
            runCurrent()
            val event = events.filterIsInstance<CoursesEffect.OpenFile>().single()
            assertEquals(other, event.course)
            assertEquals(file, event.file)
        } finally {
            models.clear()
            runCurrent()
        }
    }

    @Test fun unconfirmedSubmissionNeverCallsRepository() = runTest(dispatcher) {
        val repository = FakePortalRepository()
        val course = Course(1, "one", "A", "First")
        val assignment = Assignment(2, 1, 3, "Exercise")
        assertTrue(SubmitAssignmentUseCase(repository)(course, assignment, "answer.pdf", byteArrayOf(1), false) is AppResult.Failure)
        assertEquals(0, repository.submits)
        assertTrue(SubmitAssignmentUseCase(repository)(course, assignment, "answer.pdf", byteArrayOf(1), true) is AppResult.Success)
        assertEquals(1, repository.submits)
    }
}
