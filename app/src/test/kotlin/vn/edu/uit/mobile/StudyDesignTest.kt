package vn.edu.uit.mobile

import android.app.Application
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import vn.edu.uit.mobile.courses.*
import vn.edu.uit.mobile.designsystem.*
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.model.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StudyDesignTest {
    @get:Rule val compose = createComposeRule()
    private val course = Course(1, "synthetic", "IT004.O21", "Database Systems")
    private val source = StudySource("assignment:1", "Database project brief", "Deliver a normalized SQL database and a short report. Include three test cases.")
    private val assignment = Assignment(1, 1, 2, source.title, source.text, due = 1793548800, accountId = course.accountId)
    private val state = StudyState(course, assignment, listOf(source), setOf(source.id), StudyConnection("https://study.example.org", true))
    private val answer = StudyAnswer(
        "synthetic-preview",
        1793386800,
        listOf(
            StudyItem("requirement", "Deliver a normalized SQL database and a short report.", listOf(StudyCitation(source.id, "Deliver a normalized SQL database and a short report."))),
            StudyItem("suggestion", "Sketch your schema before writing the SQL.")
        )
    )

    @Test fun selectedAssignmentSourcesRequireAnExplicitSendAndCanBeInspected() {
        var sent = 0
        compose.setContent { Frame { StudyContent(state, onGenerate = { sent++ }) } }
        capture("ai-assignment-390-phone")
        compose.onNodeWithText("Review source").performScrollTo().performClick()
        compose.onNodeWithText("Selected source text").assertIsDisplayed()
        compose.onNodeWithText("Close source").performClick()
        assertEquals(0, sent)
        compose.onNodeWithText("Send selected text").performScrollTo().performClick()
        assertEquals(1, sent)
    }

    @Test fun generatedEvidenceCanBeOpenedAndSuggestionsDoNotAutomaticallyBecomeTasks() {
        var task: String? = null
        compose.setContent { Frame { StudyContent(state.copy(answer = answer), onAddTask = { task = it }) } }
        compose.onNodeWithTag("study-content").performScrollToNode(hasText("Study result"))
        capture("ai-evidence-390-phone")
        assertNull(task)
        compose.onNodeWithText(source.title).performScrollTo().performClick()
        compose.onNodeWithText("Cited passage").assertIsDisplayed()
        compose.onNodeWithText("Close source").performClick()
        compose.onAllNodesWithText("Add to checklist").onFirst().performScrollTo().performClick()
        assertEquals(answer.items.first().text, task)
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-mdpi")
    fun compactQuestionScreenKeepsSourceSelectionAndConfigurationExplicit() {
        var selected: String? = null
        compose.setContent { Frame { StudyContent(state.copy(assignment = null, selected = emptySet(), connection = StudyConnection()), onSelect = { selected = it }) } }
        capture("ai-question-360-phone")
        compose.onNodeWithContentDescription("Select source ${source.title}").performClick()
        assertEquals(source.id, selected)
        compose.onNodeWithText("Send selected text").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Configure gateway").performScrollTo().assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w412dp-h915dp-mdpi")
    fun checklistWithLargeTextIsEditableAndHasAnExplicitSave() {
        var saved = false
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                Frame { StudyContent(state.copy(tasks = listOf(StudyTask("Draft the database schema")), dirty = true), onSave = { saved = true }) }
            }
        }
        compose.onNodeWithTag("study-content").performScrollToNode(hasText("Personal checklist"))
        capture("ai-checklist-412-large-text-phone")
        compose.onNodeWithText("Save checklist on this device").performScrollTo().performClick()
        assertTrue(saved)
    }

    @Test fun leavingAnEditedChecklistOffersToKeepTheDraft() {
        var closed = false
        compose.setContent { Frame { StudyContent(state.copy(dirty = true), onBack = { closed = true }) } }
        compose.onNodeWithText("Course", substring = false).performClick()
        compose.onNodeWithText("Leave unsaved changes?").assertIsDisplayed()
        compose.onNodeWithText("Keep editing").performClick()
        assertFalse(closed)
    }

    @Composable private fun Frame(content: @Composable () -> Unit) {
        UitTheme(dark = true, dynamic = false) { WorkspaceFrame(2, {}, {}, "Synthetic student", true, false, 1793386800) { content() } }
    }
    private fun capture(name: String) {
        if (java.lang.Boolean.getBoolean("roborazzi.test.record")) compose.onRoot().captureRoboImage(File(System.getProperty("uit.preview.dir"), "$name.png").absolutePath)
    }
}
