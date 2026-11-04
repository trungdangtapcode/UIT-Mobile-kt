package vn.edu.uit.mobile

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import vn.edu.uit.mobile.courses.*
import vn.edu.uit.mobile.data.LocalKnowledgeRepository
import vn.edu.uit.mobile.domain.*

@OptIn(ExperimentalCoroutinesApi::class)
class KnowledgeViewModelTest {
    private fun checkModel(block: suspend TestScope.(KnowledgeTestHarness) -> Unit) = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val h = KnowledgeTestHarness(dispatcher)
        val store = ViewModelStore().apply { put("map", h.vm) }
        try {
            runCurrent()
            block(h)
            assertTrue(h.calls.all { it == "getSnapshot" })
        } finally {
            store.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }
    private fun TestScope.prepare(h: KnowledgeTestHarness) {
        h.vm.open(KnowledgeFixtures.course)
        runCurrent()
        assertTrue(h.vm.uiState.value.selected.isEmpty())
        h.vm.select(KnowledgeFixtures.sources.first().id)
        h.vm.focus(KnowledgeFixtures.graph.focus)
    }

    @Test fun explicitSendEditSaveAndOfflineReopenPreserveEvidence() = checkModel { h ->
        prepare(h)
        assertEquals(0, h.generations)
        h.vm.generate()
        runCurrent()
        assertEquals(KnowledgeStage.MAP, h.vm.uiState.value.stage)
        val record = h.vm.uiState.value.record!!
        assertTrue(h.vm.edit(record.graph.copy(nodes = record.graph.nodes.map { if (it.id == "bfs") it.copy(label = "Breadth-first search") else it })))
        h.vm.save()
        runCurrent()
        assertFalse(h.vm.uiState.value.dirty)
        assertFalse(h.vm.uiState.value.pendingSave)
        val restored = LocalKnowledgeRepository(h.storage, h.accounts, StandardTestDispatcher(testScheduler)).collections.first().single().records.single()
        assertEquals("Breadth-first search", restored.graph.nodes.first { it.id == "bfs" }.label)
        h.vm.bank()
        runCurrent()
        h.vm.show(restored)
        assertEquals(restored, h.vm.uiState.value.record)
        assertEquals(1, h.generations)
    }

    @Test fun committedSaveFailureRetriesWithoutDuplicatesAndBlocksEditing() = checkModel { h ->
        prepare(h)
        h.vm.generate()
        runCurrent()
        h.committedFailure = true
        h.vm.save()
        runCurrent()
        assertEquals(KnowledgeProblem.SAVE, h.vm.uiState.value.problem)
        assertTrue(h.vm.uiState.value.pendingSave)
        assertFalse(h.vm.edit(h.vm.uiState.value.record!!.graph.copy(title = "Unsafe edit during retry")))
        h.committedFailure = false
        h.vm.save()
        runCurrent()
        assertEquals(1, h.library.collections.first().single().records.size)
        assertFalse(h.vm.uiState.value.dirty)
        assertEquals(1L, h.vm.uiState.value.record!!.revision)
    }

    @Test fun removedOwnerClearsPrivateDraftWhileGenerationIsInFlight() = checkModel { h ->
        prepare(h)
        h.waitGenerate = CompletableDeferred()
        h.vm.generate()
        runCurrent()
        h.ownerFlow.value = emptyList()
        runCurrent()
        h.waitGenerate!!.complete(Unit)
        runCurrent()
        assertNull(h.vm.uiState.value.record)
        assertTrue(h.vm.uiState.value.sources.isEmpty())
        assertEquals(KnowledgeProblem.REMOVED, h.vm.uiState.value.problem)
        assertTrue(h.values.isEmpty())
    }

    @Test fun cancellationAndInsufficientEvidenceRetainSelectionWithoutCreatingDrafts() = checkModel { h ->
        prepare(h)
        h.waitGenerate = CompletableDeferred()
        h.vm.generate()
        runCurrent()
        h.vm.cancel()
        runCurrent()
        assertEquals(KnowledgeProblem.CANCELED, h.vm.uiState.value.problem)
        assertNull(h.vm.uiState.value.record)
        h.waitGenerate = null
        h.result = h.result.copy(status = "insufficient_evidence", message = "More context is needed.", graph = h.result.graph.copy(nodes = emptyList(), edges = emptyList()))
        h.vm.generate()
        runCurrent()
        assertEquals("More context is needed.", h.vm.uiState.value.message)
        assertEquals(setOf(KnowledgeFixtures.sources.first().id), h.vm.uiState.value.selected)
        assertNull(h.vm.uiState.value.record)
    }

    @Test fun changedSourceMetadataCancelsAnInFlightMapAndRequiresReselection() = checkModel { h ->
        prepare(h)
        h.waitGenerate = CompletableDeferred()
        h.vm.generate()
        runCurrent()
        h.snapshot.value = h.snapshot.value.copy(sections = mapOf("owner:1" to listOf(vn.edu.uit.mobile.model.CourseSection(1, "Changed source", emptyList()))))
        runCurrent()
        h.waitGenerate!!.complete(Unit)
        runCurrent()
        assertNull(h.vm.uiState.value.record)
        assertEquals(KnowledgeProblem.CHANGED, h.vm.uiState.value.problem)
        assertTrue(h.vm.uiState.value.sourceChanged)
        assertFalse(h.vm.uiState.value.loaded)
        assertTrue(h.vm.uiState.value.selected.isEmpty())
    }

    @Test fun invalidReferencesAreRejectedBeforeSaveAndDeletionIsLocalOnly() = checkModel { h ->
        prepare(h)
        h.vm.generate()
        runCurrent()
        val graph = h.vm.uiState.value.record!!.graph
        assertFalse(h.vm.edit(graph.copy(edges = graph.edges.map { it.copy(to = "outside") })))
        h.vm.save()
        runCurrent()
        val saved = h.vm.uiState.value.record!!
        h.vm.remove(saved) {}
        runCurrent()
        assertNull(h.vm.uiState.value.record)
        assertTrue(h.library.collections.first().single().records.isEmpty())
    }
}
