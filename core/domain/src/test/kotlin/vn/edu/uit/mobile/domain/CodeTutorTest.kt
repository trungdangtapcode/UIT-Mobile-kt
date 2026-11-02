package vn.edu.uit.mobile.domain

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CodeTutorTest {
    private val request = TutorFixtures.request
    private val reply = TutorFixtures.result()

    @Test fun `selection sends only explicit lines and preserves indentation`() {
        val source = "UNSELECTED PRIVATE PREFIX\r\nif ready:\r\n\treturn value\r\nUNSELECTED SUFFIX"
        val chosen = TutorRules.selection(source, 2, 3, "Python")
        assertEquals("if ready:\n\treturn value", chosen.code)
        assertEquals(2, chosen.firstLine)
        assertEquals(3, chosen.lastLine)
        assertThrows(IllegalArgumentException::class.java) { TutorRules.selection(source, 0, 1, "Python") }
    }

    @Test fun `numbered references must match the declared span with literal code spacing`() {
        TutorRules.result(reply, request)
        TutorRules.reference(TutorFixtures.reference, request.snippet)
        listOf(TutorFixtures.reference.copy(startLine = 10, endLine = 10), TutorFixtures.reference.copy(endLine = 99), TutorFixtures.reference.copy(quote = "return  values[0]")).forEach { ref -> assertThrows(IllegalArgumentException::class.java) { TutorRules.reference(ref, request.snippet) } }
    }

    @Test fun `limits reject empty oversized and cross-scope context before generation`() {
        listOf(request.copy(snippet = request.snippet.copy(code = " ")), request.copy(snippet = request.snippet.copy(code = "x".repeat(12001))), request.copy(snippet = request.snippet.copy(code = "x\n".repeat(301))), request.copy(language = "Unsupported"), request.copy(environment = "x".repeat(301)), request.copy(sources = listOf(TutorFixtures.context.copy(id = "snippet")))).forEach { assertThrows(IllegalArgumentException::class.java) { TutorRules.request(it) } }
        assertThrows(IllegalArgumentException::class.java) { TutorRules.request(request.copy(sources = (1..4).map { TutorFixtures.context.copy(id = "source:$it", text = "x".repeat(12000)) })) }
    }

    @Test fun `course evidence and general advice cannot impersonate each other`() {
        val scoped = request.copy(sources = listOf(TutorFixtures.context))
        val point = TutorPoint("course", "The selected lesson defines stack order.", citations = listOf(StudyCitation(TutorFixtures.context.id, TutorFixtures.context.text)))
        TutorRules.result(TutorFixtures.result(scoped).copy(observations = listOf(point)), scoped)
        for (bad in listOf(point.copy(kind = "suggestion"), point.copy(citations = listOf(StudyCitation("unknown", TutorFixtures.context.text))), point.copy(citations = listOf(StudyCitation(TutorFixtures.context.id, "A stack removes the most recently added item first."))))) {
            assertThrows(IllegalArgumentException::class.java) { TutorRules.result(TutorFixtures.result(scoped).copy(observations = listOf(bad)), scoped) }
        }
    }

    @Test fun `hint tiers test proposals and edits are bounded without executed-result fields`() {
        assertThrows(IllegalArgumentException::class.java) { TutorRules.result(reply.copy(hints = reply.hints.take(2)), request) }
        assertThrows(IllegalArgumentException::class.java) { TutorRules.result(reply.copy(hints = List(3) { reply.hints.first() }), request) }
        assertThrows(IllegalArgumentException::class.java) { TutorRules.result(reply.copy(tests = listOf(reply.tests.first().copy(codeReferences = emptyList()))), request) }
        assertThrows(IllegalArgumentException::class.java) { TutorRules.result(reply.copy(editReason = ""), request) }
        assertEquals(TutorFixtures.code, request.snippet.code)
    }

    @Test fun `insufficient context has no invented hints tests or edit`() {
        val unknown = reply.copy(status = "insufficient_context", observations = emptyList(), hints = emptyList(), tests = emptyList(), proposedCode = "", editReason = "")
        TutorRules.result(unknown, request)
        assertThrows(IllegalArgumentException::class.java) { TutorRules.result(unknown.copy(tests = reply.tests), request) }
        assertThrows(IllegalArgumentException::class.java) { TutorRules.result(reply.copy(selectionVersion = "stale"), request) }
        assertNotEquals(request.version, request.copy(snippet = request.snippet.copy(firstLine = 20)).version)
    }
}
