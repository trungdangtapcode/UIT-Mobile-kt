package vn.edu.uit.mobile

import android.app.Application
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
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

/** Synthetic interaction coverage only; no screenshot exports. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-w390dp-h844dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PassageSearchDesignTest {
    @get:Rule val compose = createComposeRule()
    private val fixture = PassageFixtures
    private val state = PassageSearchState(courses = listOf(fixture.course), course = fixture.course, candidates = listOf(PassageCandidate(fixture.spec, true)), selected = setOf(fixture.spec.id), library = PassageLibrary(listOf(fixture.index)))

    @Test fun typingAndChangingModesDoNotSubmitAQuery() {
        var requests = 0
        compose.setContent { UitTheme { PassageSearchContent(state, onSearch = { requests++ }) } }
        compose.onNodeWithTag("passage-query").performScrollTo().performTextInput("isolation")
        assertEquals(0, requests)
        compose.onNodeWithTag("passage-mode-SEMANTIC").assertIsNotEnabled()
    }

    @Test fun explicitSearchAndSourceInspectionKeepExactIdentity() {
        var requests = 0
        var inspected: PassageHit? = null
        val input = state.copy(query = "isolation", result = PassageSearchResult(listOf(fixture.hit), PassageSearchMode.BM25, PassageSearchMode.BM25))
        compose.setContent { UitTheme { PassageSearchContent(input, onSearch = { requests++ }, onInspect = { inspected = it }) } }
        compose.onNodeWithTag("passage-submit").performScrollTo().performClick()
        assertEquals(1, requests)
        compose.onNodeWithTag("passage-inspect-${fixture.hit.passage.id}").performScrollTo().performClick()
        assertEquals(fixture.hit, inspected)
    }

    private fun plan(embedding: PassageEmbeddingConfig? = null, transfers: Int = 0) = PassageUpdatePlan(fixture.course.accountId, fixture.course.id, "reviewed", 1793836800, PassageConfig(), embedding, listOf(PassageFileCheck(fixture.spec, PassageFileStatus.KEYWORDS_READY, 1, 0, false)), 0, 1, 0, transfers, if (transfers > 0) 100 else 0)

    @Test fun semanticTransferRequiresBothAReviewedPlanAndADirectConnection() {
        var updates = 0
        compose.setContent { UitTheme { PassageSearchContent(state.copy(plan = plan(PassageEmbeddingConfig(), 1)), onUpdate = { updates++ }) } }
        compose.onNodeWithTag("passage-manage").performScrollTo().performClick()
        compose.onNodeWithTag("passage-enable-semantic").performScrollTo().performClick()
        compose.onNodeWithTag("passage-build").performClick()
        compose.onNodeWithTag("passage-confirm-update").assertIsNotEnabled()
        assertEquals(0, updates)
    }

    @Test fun checkingFilesAndConfirmingLocalUpdateAreSeparateActionsWithoutAnAiKey() {
        var checks = 0
        var updates = 0
        var current by mutableStateOf(state)
        compose.setContent {
            UitTheme {
                PassageSearchContent(current, onCheck = { _, embedding ->
                    assertNull(embedding)
                    checks++
                    current = current.copy(plan = plan())
                }, onUpdate = { updates++ })
            }
        }
        compose.onNodeWithTag("passage-manage").performScrollTo().performClick()
        compose.onNodeWithTag("passage-check").assertIsEnabled().performClick()
        assertEquals(1, checks)
        assertEquals(0, updates)
        compose.onNodeWithTag("passage-build").performClick()
        compose.onNodeWithText("No AI request is needed for this update.").assertExists()
        compose.onNodeWithTag("passage-confirm-update").assertIsEnabled().performClick()
        assertEquals(1, updates)
    }

    @Test fun aPreviouslySelectedFileCanStillBeDeselectedAfterItsDownloadIsRemoved() {
        var toggled: String? = null
        compose.setContent { UitTheme { PassageSearchContent(state.copy(candidates = listOf(PassageCandidate(fixture.spec, false))), onToggle = { toggled = it }) } }
        compose.onNodeWithTag("passage-manage").performScrollTo().performClick()
        compose.onNodeWithTag("passage-source-${fixture.spec.id}").assertIsEnabled().performClick()
        assertEquals(fixture.spec.id, toggled)
    }

    @Test fun unverifiedPreparedFilesAreNeverLabeledSyncedWithMoodle() {
        compose.setContent { UitTheme { PassageSearchContent(state) } }
        compose.onNodeWithTag("passage-manage").performScrollTo().performClick()
        compose.onNodeWithText("Prepared · check saved version").assertExists()
        compose.onNodeWithText("Moodle changes are not checked here. Download the latest file from the course first.").assertExists()
    }

    @Test fun confirmedResetIsAvailableEvenWhenCorruptStorageHasNoSelectedIndex() {
        var reset = 0
        compose.setContent { UitTheme { PassageSearchContent(PassageSearchState(library = PassageLibrary(storageFailed = true)), onReset = { reset++ }) } }
        compose.onNodeWithText("Reset local search indexes").performScrollTo().performClick()
        assertEquals(0, reset)
        compose.onAllNodesWithText("Reset local search indexes").onLast().performClick()
        assertEquals(1, reset)
    }

    @Test fun fallbackAndSourceInvalidationAreDisclosed() {
        val input = state.copy(query = "isolation", result = PassageSearchResult(listOf(fixture.hit), PassageSearchMode.HYBRID, PassageSearchMode.BM25, invalidated = 1))
        compose.setContent { UitTheme { PassageSearchContent(input) } }
        compose.onNodeWithText("Semantic search is unavailable. Showing local BM25 results instead; no generated answer is substituted.").performScrollTo().assertExists()
        compose.onNodeWithText("1 changed or unavailable documents were removed from this index. Rebuild after reviewing downloads.").performScrollTo().assertExists()
    }

    @Test fun allInvalidatedSourcesStillExplainWhyTheIndexDisappeared() {
        val input = state.copy(library = PassageLibrary(), result = PassageSearchResult(emptyList(), PassageSearchMode.BM25, PassageSearchMode.BM25, invalidated = 1))
        compose.setContent { UitTheme { PassageSearchContent(input) } }
        compose.onNodeWithText("1 changed or unavailable documents were removed from this index. Rebuild after reviewing downloads.").performScrollTo().assertExists()
    }

    @Test fun openingOriginalPdfPassesTheExpectedSourceVersionBeforeNavigation() {
        val document = fixture.source.copy(spec = fixture.spec.copy(file = fixture.spec.file.copy(name = "notes.pdf")), parts = fixture.source.parts.map { it.copy(kind = PassagePartKind.PDF_EXTRACT) })
        val passage = PassageRules.chunks(document, PassageConfig()).single()
        val hit = PassageHit(passage, PassageDocumentInfo(document.spec, document.version, true), 1.0)
        var target: Triple<vn.edu.uit.mobile.model.Course, vn.edu.uit.mobile.model.CourseFile, String>? = null
        var dismissed = false
        compose.setContent {
            UitTheme {
                PassageSearchContent(state.copy(inspection = PassageInspection(hit, document.parts.single())), onDismissInspection = { dismissed = true }, onOpenPdf = { course, file, version ->
                    assertTrue(dismissed)
                    target = Triple(course, file, version)
                })
            }
        }
        compose.onNodeWithText("Open original PDF").performClick()
        assertEquals(Triple(document.spec.course, document.spec.file, document.version), target)
    }
}
