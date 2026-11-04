package vn.edu.uit.mobile.domain

import kotlin.math.ln
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import vn.edu.uit.mobile.model.*

class PassageSearchTest {
    private val spec = OfflineDownloadSpec(Course(1, "owner", "IT004", "Database"), "section", "Week", CourseFile("lesson.md", "https://courses.example.org/lesson.md"))
    private fun document(text: String) = PassageDocument(spec, "a".repeat(64), listOf(PassagePart(1, PassagePartKind.TEXT, text)))
    private fun passage(id: String, text: String) = CoursePassage(id, "doc", 1, PassagePartKind.TEXT, 0, text.length, 1, 1, text)

    @Test fun `BM25 matches a hand calculated positive IDF length normalized score`() {
        val passages = listOf(passage("a", "rare rare common"), passage("b", "common"), passage("c", "other other"))
        val score = PassageBm25(passages, PassageConfig()).search("rare").single()
        val expected = ln(1.0 + 2.5 / 1.5) * (2 * 2.2 / (2 + 1.2 * (.25 + .75 * 3.0 / 2.0)))
        assertEquals("a", score.first)
        assertEquals(expected, score.second, 1e-12)
        assertEquals(score, PassageBm25(passages, PassageConfig()).search("rare rare").single())
    }

    @Test fun `length normalization and rare terms affect ranking without percent confidence`() {
        val passages = listOf(passage("short", "transaction isolation"), passage("long", "transaction " + "unrelated ".repeat(100)), passage("other", "isolation unrelated"))
        assertEquals("short", PassageBm25(passages, PassageConfig()).search("transaction").first().first)
        assertTrue(PassageBm25(passages, PassageConfig()).search("missing_term").isEmpty())
    }

    @Test fun `accent folding identifiers and programming languages retain useful terms`() {
        val tokens = PassageTokens.read("Cơ sở dữ liệu không phải C++ C# binarySearch snake_case HTTPServer IT004.O21")
        assertTrue(tokens.containsAll(listOf("co", "so", "du", "lieu", "khong", "c++", "c#", "binarysearch", "binary", "search", "snake_case", "snake", "case", "httpserver", "http", "server", "it004.o21")))
        assertEquals(PassageTokens.read("DỮ LIỆU"), PassageTokens.read("du lieu"))
    }

    @Test fun `overlap keeps exact source offsets and does not join notebook cells`() {
        val text = (1..100).joinToString("\n") { "Line $it includes a complete identifier and a symbol 😀." }
        val source = document(text).copy(parts = listOf(PassagePart(3, PassagePartKind.NOTEBOOK_MARKDOWN, text), PassagePart(9, PassagePartKind.NOTEBOOK_CODE, "print('tail')")))
        val chunks = PassageRules.chunks(source, PassageConfig(400, 80))
        chunks.forEach { p ->
            val part = source.parts.first { it.ordinal == p.ordinal }
            assertEquals(part.text.substring(p.start, p.end), p.text)
            assertFalse(p.text.last().isHighSurrogate())
            assertEquals(1 + part.text.take(p.start).count { it == '\n' }, p.firstLine)
        }
        assertEquals("print('tail')", chunks.last().text)
        assertEquals(9, chunks.last().ordinal)
        assertTrue(chunks[1].start < chunks[0].end)
    }

    @Test fun `fusion uses ranks and ignores unrelated score scales`() {
        val first = listOf("a" to 1000.0, "b" to 1.0)
        val second = listOf("b" to .9, "c" to .8)
        val result = PassageRanking.fusion(listOf(first, second), 60)
        assertEquals("b", result.first().first)
        assertEquals(1.0 / 62 + 1.0 / 61, result.first().second, 1e-12)
        assertEquals(result, PassageRanking.fusion(listOf(first.map { it.first to .0001 }, second), 60))
    }

    @Test fun `vector operations reject invalid dimensions nonfinite and zero vectors`() {
        val normalized = PassageRules.normalize(listOf(3f, 4f), 2)
        assertEquals(.6f, normalized[0], 1e-6f)
        for (bad in listOf(listOf(0f, 0f), listOf(Float.NaN, 1f), listOf(1f))) assertThrows(IllegalArgumentException::class.java) { PassageRules.normalize(bad, 2) }
        val ranked = PassageRanking.dense(listOf(passage("near", "near"), passage("far", "far")), listOf(listOf(1f, 0f), listOf(0f, 1f)), listOf(1f, 0f), 2)
        assertEquals("near", ranked.first().first)
    }

    @Test fun `invalid budgets and empty queries are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { PassageConfig(k1 = Double.NaN).validate() }
        assertThrows(IllegalArgumentException::class.java) { PassageConfig(characters = 400, overlap = 399).validate() }
        assertThrows(IllegalArgumentException::class.java) { PassageEmbeddingConfig(model = "gpt-6-luna").validate() }
        assertThrows(IllegalArgumentException::class.java) { PassageRules.query(" \" * () ") }
    }
}
