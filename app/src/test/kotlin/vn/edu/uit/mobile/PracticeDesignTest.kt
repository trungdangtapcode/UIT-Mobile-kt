package vn.edu.uit.mobile

import android.app.Application
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import java.io.File
import java.lang.reflect.Proxy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.*
import vn.edu.uit.mobile.common.*
import vn.edu.uit.mobile.courses.*
import vn.edu.uit.mobile.data.*
import vn.edu.uit.mobile.designsystem.*
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.feature.courses.R as CourseStrings
import vn.edu.uit.mobile.model.*

/** Real production screens and ViewModel; all accounts, sources and model results are synthetic. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalRoborazziApi::class, ExperimentalCoroutinesApi::class)
class PracticeDesignTest {
    @get:Rule val compose = createComposeRule()
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val course = Course(1, "owner", "IT004", "Database systems")
    private var failWrite = false
    private var failGenerate = false
    private var calls = 0
    private val owners = object : AccountRepository {
        override val accounts = MutableStateFlow(listOf(Account("owner", "https://example.invalid", 1, "Synthetic student", PortalType.MOODLE)))
        override suspend fun remove(accountId: String) = error("Account writes forbidden")
        override suspend fun saveSessions(sessions: List<PortalSession>) = error("Account writes forbidden")
    }
    private val local = LocalPracticeRepository(
        object : StudyStorage {
            private val values = mutableMapOf<String, String>()
            override fun read(key: String) = values[key]
            override fun write(key: String, value: String) {
                check(!failWrite)
                values[key] = value
            }
            override fun remove(key: String) {
                values.remove(key)
            }
            override fun removeAccount(accountId: String) = error("Account writes forbidden")
        },
        owners,
        io = dispatcher
    ) { 1793386800 }
    private val ai = object : StudyAiRepository {
        override suspend fun connection() = StudyConnection(configured = true, model = "synthetic-practice-model")
        override suspend fun configure(endpoint: String, token: String) = Unit
        override suspend fun configureDirect(apiKey: String, model: String) = Unit
        override suspend fun disconnect() = Unit
        override suspend fun generate(course: Course, mode: String, sources: List<StudySource>, question: String, language: String): StudyAnswer = error("Wrong mode")
        override suspend fun generatePractice(course: Course, sources: List<StudySource>, options: PracticeOptions): PracticeGeneration {
            calls++
            check(!failGenerate)
            return PracticeFixtures.generation
        }
        override suspend fun tasks(course: Course, assignmentId: Long) = emptyList<StudyTask>()
        override suspend fun saveTasks(course: Course, assignmentId: Long, tasks: List<StudyTask>) = error("Task writes forbidden")
    }
    private val portal = Proxy.newProxyInstance(PortalRepository::class.java.classLoader, arrayOf(PortalRepository::class.java)) { _, method, _ ->
        when (method.name) {
            "getSnapshot" -> MutableStateFlow(PortalSnapshot(courses = listOf(course)))
            else -> throw AssertionError("University operation blocked: ${method.name}")
        }
    } as PortalRepository
    private lateinit var vm: PracticeViewModel

    @Before fun prepare() {
        Dispatchers.setMain(dispatcher)
        vm = PracticeViewModel(ai, local, owners, portal, OfflineCourseSourceLoader { _, _, _ -> listOf(PracticeFixtures.source) }, io = dispatcher)
        store.put("practice", vm)
    }

    @After fun restore() {
        vm.close()
        store.clear()
        dispatcher.scheduler.runCurrent()
        Dispatchers.resetMain()
        RuntimeEnvironment.setFontScale(1f)
    }
    private fun open() {
        vm.open(course, emptyList(), emptyList())
        dispatcher.scheduler.runCurrent()
        compose.setContent { Frame { PracticeScreen(vm, {}) } }
        pump()
    }
    private fun pump() {
        compose.runOnIdle { dispatcher.scheduler.runCurrent() }
        compose.waitForIdle()
    }
    private fun click(text: String) {
        compose.onNodeWithTag("practice-content").performScrollToNode(hasText(text))
        compose.onAllNodesWithText(text, substring = false).onFirst().performScrollTo().performClick()
        pump()
    }
    private fun generate() {
        compose.onNodeWithTag("practice-source-module:1").performClick()
        pump()
        compose.onNodeWithTag("practice-content").performScrollToNode(hasText("Number of questions"))
        compose.onNodeWithText("Number of questions").performTextReplacement("2")
        pump()
        click("Send selection and generate")
        assertEquals(PracticeStage.PREVIEW, vm.uiState.value.stage)
    }

    @Test fun fullPhoneFlowReviewsEvidenceSavesPracticesRestoresAndDeletesLocally() {
        open()
        capture("practice-sources-390-phone")
        generate()
        capture("practice-draft-390-phone")
        compose.onNodeWithTag("practice-content").performScrollToNode(hasTestTag("practice-draft-q1"))
        compose.onNodeWithTag("practice-draft-q1").performScrollTo()
        capture("practice-draft-question-390-phone")
        click("Edit question")
        compose.onNodeWithText("Question prompt").performTextReplacement("Which property does a transaction preserve?")
        capture("practice-edit-390-phone")
        compose.onNodeWithText("Apply edit to draft").performClick()
        pump()
        click("Evidence · ${PracticeFixtures.source.title}")
        compose.onNodeWithText("Supporting quotation").assertExists()
        capture("practice-evidence-390-phone")
        compose.onNodeWithText("Close", substring = false).performClick()
        pump()
        click("Save reviewed set")
        capture("practice-bank-390-phone")
        assertEquals(1, vm.uiState.value.records.size)
        click("Practice")
        capture("practice-mcq-390-phone")
        compose.onNodeWithTag("practice-choice-1").performClick()
        pump()
        click("Check answer")
        capture("practice-mcq-checked-390-phone")
        assertFalse(vm.uiState.value.record!!.progress.single().lastOutcome)
        click("Next question")
        compose.onNodeWithText("Answer · All operations complete or none do.").assertDoesNotExist()
        click("Reveal answer")
        capture("practice-flashcard-390-phone")
        assertEquals(1, vm.uiState.value.record!!.progress.size)
        click("I remembered")
        click("Finish session")
        capture("practice-results-390-phone")
        assertEquals(2, vm.uiState.value.record!!.progress.size)
        val priorCalls = calls
        compose.runOnIdle {
            vm.close()
            vm.openSaved()
        }
        pump()
        capture("practice-restored-bank-390-phone")
        assertEquals(2, vm.uiState.value.records.single().progress.size)
        assertEquals(priorCalls, calls)
        click("Review missed questions")
        assertEquals(listOf("q1"), vm.uiState.value.run!!.ids)
        click("Back")
        click("Delete set")
        assertEquals(1, vm.uiState.value.records.size)
        capture("practice-delete-390-phone")
        compose.onNode(hasText("Delete set") and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        pump()
        compose.onNodeWithText("No practice sets saved").assertExists()
        assertTrue(vm.uiState.value.records.isEmpty())
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-mdpi")
    fun compactPhoneRetainsFailedDraftAndAnswerSavesUntilExplicitRetry() {
        open()
        generate()
        failWrite = true
        click("Save reviewed set")
        capture("practice-failed-save-360-phone")
        assertNotNull(vm.uiState.value.draft)
        failWrite = false
        click("Save reviewed set")
        click("Practice")
        compose.onNodeWithTag("practice-choice-0").performClick()
        pump()
        failWrite = true
        click("Check answer")
        compose.onNodeWithText("Next question").assertIsNotEnabled()
        compose.onNodeWithTag("practice-content").performScrollToNode(hasText("Retry saving this answer"))
        capture("practice-failed-answer-360-phone")
        failWrite = false
        click("Retry saving this answer")
        assertEquals(1, vm.uiState.value.record!!.progress.single().attempts)
        compose.onNodeWithText("Next question").assertIsEnabled()
    }

    @Test fun generationFailureKeepsSelectionAndNeverCreatesASavedSet() {
        open()
        failGenerate = true
        compose.onNodeWithTag("practice-source-module:1").performClick()
        pump()
        compose.onNodeWithTag("practice-content").performScrollToNode(hasText("Number of questions"))
        compose.onNodeWithText("Number of questions").performTextReplacement("2")
        pump()
        click("Send selection and generate")
        compose.onNodeWithTag("practice-content").performScrollToIndex(0)
        capture("practice-failed-generation-390-phone")
        assertEquals(PracticeProblem.GENERATE, vm.uiState.value.problem)
        assertEquals(setOf(PracticeFixtures.source.id), vm.uiState.value.selected)
        assertTrue(vm.uiState.value.records.isEmpty())
    }

    @Test
    @Config(qualifiers = "vi-w412dp-h915dp-mdpi")
    fun vietnameseLargeTextKeepsOptionsEvidenceAndEditorActionsReachable() {
        RuntimeEnvironment.setFontScale(1.3f)
        val app = RuntimeEnvironment.getApplication()
        fun text(id: Int) = app.getString(id)
        open()
        compose.onNodeWithTag("practice-source-module:1").performClick()
        pump()
        compose.onNodeWithTag("practice-content").performScrollToNode(hasText(text(CourseStrings.string.pp_count)))
        compose.onNodeWithText(text(CourseStrings.string.pp_count)).performTextReplacement("2")
        pump()
        capture("practice-options-412-vietnamese-phone")
        click(text(CourseStrings.string.pp_generate))
        click(text(CourseStrings.string.pp_edit))
        compose.onNodeWithText(text(CourseStrings.string.pp_explanation)).performScrollTo()
        capture("practice-editor-412-vietnamese-phone")
        compose.onNodeWithText(text(CourseStrings.string.pp_apply_edit)).assertIsDisplayed().performClick()
        pump()
        click(app.getString(CourseStrings.string.pp_evidence, PracticeFixtures.source.title))
        capture("practice-evidence-412-vietnamese-phone")
        compose.onNodeWithText(text(CourseStrings.string.pp_close)).assertIsDisplayed().performClick()
        pump()
        click(text(CourseStrings.string.pp_save))
        capture("practice-bank-412-vietnamese-phone")
    }

    @Composable private fun Frame(content: @Composable () -> Unit) {
        UitTheme(dark = true, dynamic = false) { WorkspaceFrame(2, {}, {}, "Synthetic student", true, false, 1793386800) { content() } }
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
