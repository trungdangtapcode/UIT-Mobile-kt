package vn.edu.uit.mobile.data

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class NotebookTextTest {
    private fun notebook(cells: String) = """{"nbformat":4,"metadata":{"authors":[{"name":"AUTHOR_SENTINEL"}]},"cells":[$cells]}""".toByteArray()

    @Test fun readsBothSourceRepresentationsAndPreservesCodeAndBilingualMath() {
        val bytes = notebook(
            """
            {"cell_type":"markdown","source":["# Trạng thái qubit\n","Use \\alpha and \\beta.\n"],"attachments":{"image":{"image/png":"ATTACHMENT_SENTINEL"}}},
            {"cell_type":"code","source":"if ready:\n    raise RuntimeError('must never execute')\n","execution_count":7,"outputs":[{"text":"OUTPUT_SENTINEL"}]},
            {"cell_type":"raw","source":"RAW_SENTINEL"}
        """
        )
        val text = requireNotNull(extractOfflineDocumentText("lesson.ipynb", bytes))
        assertTrue(text.contains("# Trạng thái qubit\nUse \\alpha and \\beta."))
        assertTrue(text.contains("if ready:\n    raise RuntimeError('must never execute')"))
        assertTrue(text.contains("[Markdown cell 1]") && text.contains("[Code cell 2]"))
        listOf("AUTHOR_SENTINEL", "ATTACHMENT_SENTINEL", "OUTPUT_SENTINEL", "RAW_SENTINEL", "execution_count").forEach { assertFalse(text.contains(it)) }
    }

    @Test fun rejectsMalformedAndUnsupportedDocumentsWithoutFallingBackToRawJson() {
        val invalid = listOf(
            "not JSON",
            """{"nbformat":3,"cells":[]}""",
            """{"nbformat":"4","cells":[]}""",
            """{"nbformat":4,"cells":[{"cell_type":"markdown","source":["valid",1]}]}""",
            """{"nbformat":4,"cells":[{"cell_type":"code","source":null}]}""",
            """{"nbformat":4,"cells":[{"cell_type":"raw","source":"not visible"}]}""",
            """{"nbformat":4,"cells":[{"cell_type":"markdown","source":"   "}]}"""
        )
        invalid.forEach { assertNull(extractOfflineDocumentText("lesson.ipynb", it.toByteArray())) }
        assertNull(extractNotebookText(byteArrayOf(0xc3.toByte(), 0x28)))
    }

    @Test fun boundsLongNotebooksWithoutIncludingOutputPayloads() {
        val cell = buildJsonObject {
            put("cell_type", "markdown")
            put("source", "Detailed lesson. ".repeat(3000))
            put("outputs", "OUTPUT_SENTINEL".repeat(1000))
        }
        val text = requireNotNull(extractNotebookText(notebook(cell.toString())))
        assertTrue(text.length in 11_900..12_000)
        assertTrue(text.startsWith("[Markdown cell 1]\nDetailed lesson."))
        assertFalse(text.contains("OUTPUT_SENTINEL"))
        assertNull(extractNotebookText(ByteArray(8 * 1024 * 1024 + 1)))
    }

    @Test fun truncationCannotSplitASupplementaryUnicodeCharacter() {
        val heading = "[Markdown cell 1]\n"
        val cell = buildJsonObject {
            put("cell_type", "markdown")
            put("source", "a".repeat(12_000 - heading.length - 1) + "😀 trailing text")
        }
        val text = requireNotNull(extractNotebookText(notebook(cell.toString())))
        assertFalse(text.last().isHighSurrogate())
        assertEquals(text, text.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8))
        assertTrue(text.length <= 12_000)
    }
}
