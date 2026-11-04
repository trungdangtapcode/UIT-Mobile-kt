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

    @Test fun semanticTransferRequiresBothExplicitSelectionAndADirectConnection() {
        var builds = 0
        compose.setContent { UitTheme { PassageSearchContent(state, onBuild = { _, _ -> builds++ }) } }
        compose.onNodeWithTag("passage-manage").performScrollTo().performClick()
        compose.onNodeWithTag("passage-enable-semantic").performScrollTo().performClick()
        compose.onNodeWithTag("passage-build").assertIsNotEnabled()
        assertEquals(0, builds)
    }

    @Test fun localBuildIsExplicitAndNeedsNoAiKey() {
        var builds = 0
        var semantic: PassageEmbeddingConfig? = PassageEmbeddingConfig()
        compose.setContent {
            UitTheme {
                PassageSearchContent(state, onBuild = { _, embedding ->
                    builds++
                    semantic = embedding
                })
            }
        }
        compose.onNodeWithTag("passage-manage").performScrollTo().performClick()
        assertEquals(0, builds)
        compose.onNodeWithTag("passage-build").assertIsEnabled().performClick()
        assertEquals(1, builds)
        assertNull(semantic)
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
