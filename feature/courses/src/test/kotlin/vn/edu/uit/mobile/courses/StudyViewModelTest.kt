package vn.edu.uit.mobile.courses

import android.app.Application
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.model.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class StudyViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val course = Course(1, "account", "IT004", "Database")
    private val assignment = Assignment(2, course.id, 3, "Synthetic project", "<p>Deliver a report and three tests.</p>", due = 12345, accountId = course.accountId)
    private val portal = FakePortalRepository()
    private class Ai : StudyAiRepository {
        var generated = 0
        var saved = emptyList<StudyTask>()
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun connection() = StudyConnection("https://study.example.org", true)
        override suspend fun configure(endpoint: String, token: String) = Unit
        override suspend fun disconnect() = Unit
        override suspend fun tasks(course: Course, assignmentId: Long) = saved
        override suspend fun saveTasks(course: Course, assignmentId: Long, tasks: List<StudyTask>) {
            saved = tasks
        }
        override suspend fun generate(course: Course, mode: String, sources: List<StudySource>, question: String, language: String): StudyAnswer {
            generated++
            gate?.let { withContext(NonCancellable) { it.await() } }
            return StudyAnswer("synthetic", 1, listOf(StudyItem(if (mode == "ask") "answer" else "requirement", "Write a report.", listOf(StudyCitation(sources.first().id, "Deliver a report")))))
        }
    }

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        portal.snapshot.value = PortalSnapshot(courses = listOf(course))
    }

    @After fun cleanup() {
        Dispatchers.resetMain()
    }

    @Test fun openingAndEditingNeverSendsTextUntilTheStudentChoosesGenerate() = runTest(dispatcher) {
        val ai = Ai()
        val vm = StudyViewModel(ai, portal)
        vm.open(course, emptyList(), listOf(assignment, assignment.copy(accountId = "other")), assignment)
        runCurrent()
        assertEquals(1, vm.uiState.value.sources.size)
        assertEquals("Deliver a report and three tests.", vm.uiState.value.sources.single().text)
        assertEquals(0, ai.generated)
        vm.generate()
        runCurrent()
        assertEquals(1, ai.generated)
        assertNotNull(vm.uiState.value.answer)
        assertEquals(12345L, vm.uiState.value.assignment?.due)
    }

    @Test fun generatedTasksAreEditableDeduplicatedAndOnlySavedExplicitly() = runTest(dispatcher) {
        val ai = Ai()
        val vm = StudyViewModel(ai, portal)
        vm.open(course, emptyList(), listOf(assignment), assignment)
        runCurrent()
        vm.addTask("Draft report")
        vm.addTask("Draft report")
        vm.addTask("Write tests")
        assertTrue(ai.saved.isEmpty())
        vm.task(0, "Review report", true)
        vm.moveTask(1, -1)
        assertEquals(listOf("Write tests", "Review report"), vm.uiState.value.tasks.map { it.text })
        vm.saveTasks()
        runCurrent()
        assertEquals(vm.uiState.value.tasks, ai.saved)
        assertFalse(vm.uiState.value.dirty)
        vm.close()
        vm.open(course, emptyList(), listOf(assignment), assignment)
        runCurrent()
        assertEquals(ai.saved, vm.uiState.value.tasks)
    }

    @Test fun aCanceledProviderResponseCannotPopulateTheNextCourseScreen() = runTest(dispatcher) {
        val ai = Ai().apply { gate = CompletableDeferred() }
        val vm = StudyViewModel(ai, portal)
        vm.open(course, emptyList(), listOf(assignment), assignment)
        runCurrent()
        vm.generate()
        runCurrent()
        vm.close()
        ai.gate!!.complete(Unit)
        runCurrent()
        assertNull(vm.uiState.value.course)
        assertNull(vm.uiState.value.answer)
    }

    @Test fun removingTheAccountClosesPrivateSourcesAndUnsavedTasks() = runTest(dispatcher) {
        val vm = StudyViewModel(Ai(), portal)
        vm.open(course, emptyList(), listOf(assignment), assignment)
        runCurrent()
        vm.addTask("Personal draft")
        portal.snapshot.value = PortalSnapshot()
        runCurrent()
        assertNull(vm.uiState.value.course)
        assertTrue(vm.uiState.value.sources.isEmpty())
        assertTrue(vm.uiState.value.tasks.isEmpty())
    }

    @Test fun courseQuestionsRequireExplicitSourcesAndAQuestion() = runTest(dispatcher) {
        val ai = Ai()
        val vm = StudyViewModel(ai, portal)
        vm.open(course, emptyList(), listOf(assignment))
        runCurrent()
        assertTrue(vm.uiState.value.selected.isEmpty())
        vm.generate()
        runCurrent()
        assertEquals(0, ai.generated)
        vm.select("assignment:2")
        vm.question("What should I deliver?")
        vm.generate()
        runCurrent()
        assertEquals(1, ai.generated)
        assertEquals("What should I deliver?", vm.uiState.value.answeredQuestion)
    }
}
