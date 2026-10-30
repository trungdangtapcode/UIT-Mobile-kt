package vn.edu.uit.mobile.domain

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class StudyAiTest {
    private val source = StudySource("module:1", "Database lesson", "A transaction preserves consistency.\nUse three test cases.")
    private fun answer(quote: String = "A transaction preserves consistency.", id: String = source.id) = StudyAnswer("synthetic-model", 1, listOf(StudyItem("answer", "Transactions preserve consistency.", listOf(StudyCitation(id, quote)))))

    @Test fun `only explicit HTTPS origins can receive gateway credentials`() {
        assertEquals("https://study.example.org:8443", StudyAiRules.endpoint("https://study.example.org:8443/"))
        for (value in listOf("http://study.example.org", "https://user:pass@study.example.org", "https://study.example.org/path", "https://study.example.org?token=secret", "https://study.example.org#fragment")) {
            assertThrows(IllegalArgumentException::class.java) { StudyAiRules.endpoint(value) }
        }
    }

    @Test fun `source selection bounds preserve exact text rather than truncating it`() {
        StudyAiRules.validate(listOf(source), "ask", "What does a transaction preserve?")
        assertThrows(IllegalArgumentException::class.java) { StudyAiRules.validate(emptyList(), "ask", "Question") }
        assertThrows(IllegalArgumentException::class.java) { StudyAiRules.validate(listOf(source, source), "ask", "Question") }
        assertThrows(IllegalArgumentException::class.java) { StudyAiRules.validate(listOf(source.copy(text = "a".repeat(12001))), "ask", "Question") }
        assertThrows(IllegalArgumentException::class.java) { StudyAiRules.validate(listOf(source), "ask", "") }
    }

    @Test fun `evidence must exist in an authorized source and whitespace can normalize`() {
        StudyAiRules.verify(answer("consistency. Use three test cases."), listOf(source), "ask")
        assertThrows(IllegalArgumentException::class.java) { StudyAiRules.verify(answer(id = "another-account"), listOf(source), "ask") }
        assertThrows(IllegalArgumentException::class.java) { StudyAiRules.verify(answer("The final exam is tomorrow."), listOf(source), "ask") }
    }

    @Test fun `Unicode portal whitespace agrees with gateway normalization`() {
        for (space in listOf("\u00a0", "\u202f", "\u0085", "\u2003", "\u001c")) {
            val portalSource = source.copy(text = "A transaction${space}preserves consistency.")
            StudyAiRules.verify(answer(), listOf(portalSource), "ask")
            StudyAiRules.verify(answer("A transaction${space}preserves consistency."), listOf(source), "ask")
        }
    }

    @Test fun `unknown facts and suggestions never masquerade as sourced answers`() {
        StudyAiRules.verify(StudyAnswer("test", 1, listOf(StudyItem("unknown", "The selected text does not say."))), listOf(source), "ask")
        StudyAiRules.verify(StudyAnswer("test", 1, listOf(StudyItem("suggestion", "Draft an outline first."))), listOf(source), "analyze")
        assertThrows(IllegalArgumentException::class.java) { StudyAiRules.verify(StudyAnswer("test", 1, listOf(StudyItem("answer", "No evidence."))), listOf(source), "ask") }
        assertThrows(IllegalArgumentException::class.java) { StudyAiRules.verify(answer(), listOf(source), "analyze") }
    }
}
