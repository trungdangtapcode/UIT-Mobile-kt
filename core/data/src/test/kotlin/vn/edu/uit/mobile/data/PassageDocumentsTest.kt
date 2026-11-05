package vn.edu.uit.mobile.data

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import vn.edu.uit.mobile.domain.*

class PassageDocumentsTest {
    private val spec = PassageFixtures.spec

    @Test fun notebookSourceRetainsCellAndLineLocationsWithoutOutputsMetadataOrExecution() {
        val notebook = """{"nbformat":4,"metadata":{"secret":"Never include"},"cells":[{"cell_type":"markdown","source":["# Title\n","Exact source text."],"attachments":{"secret":"Never include"}},{"cell_type":"code","source":"import os\nprint('source only')","outputs":[{"text":"Never include"}]}]}"""
        val document = PassageDocuments.extract(spec.copy(file = spec.file.copy(name = "notes.ipynb")), notebook.toByteArray(), "a".repeat(64))
        assertEquals(listOf(1, 2), document.parts.map { it.ordinal })
        assertTrue(document.parts.last().text.startsWith("import os\n"))
        assertFalse(document.parts.any { "Never include" in it.text })
        assertEquals(PassagePartKind.NOTEBOOK_CODE, document.parts.last().kind)
        assertFalse(document.partial)
    }

    @Test fun extractionCapAndSurrogateBoundariesAreExplicit() {
        val text = "x".repeat(PassageRules.DOCUMENT_CHARACTERS - 1) + "😀 end"
        val doc = PassageDocuments.extract(spec, text.toByteArray(), "a".repeat(64))
        assertTrue(doc.partial)
        assertFalse(doc.parts.single().text.last().isHighSurrogate())
        assertTrue(doc.parts.single().text.length <= PassageRules.DOCUMENT_CHARACTERS)
    }

    @Test fun unsupportedBinaryAndMalformedNotebookDoNotBecomeEvidence() {
        assertTrue(runCatching { PassageDocuments.extract(spec.copy(file = spec.file.copy(name = "archive.zip")), byteArrayOf(0, 1, 2), "a".repeat(64)) }.isFailure)
        assertTrue(runCatching { PassageDocuments.extract(spec, byteArrayOf(0, 1, 2), "a".repeat(64)) }.isFailure)
        val invalid = """{"nbformat":"4","cells":[]}"""
        assertTrue(runCatching { PassageDocuments.extract(spec.copy(file = spec.file.copy(name = "notes.ipynb")), invalid.toByteArray(), "a".repeat(64)) }.isFailure)
    }
}
