package vn.edu.uit.mobile.courses

import kotlinx.coroutines.*
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
