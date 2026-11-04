package vn.edu.uit.mobile.domain

import vn.edu.uit.mobile.model.*

data class TutorSnippet(val code: String, val codeLanguage: String = "Python", val firstLine: Int = 1) {
    val lines get() = TutorRules.lines(code)
    val lastLine get() = firstLine + lines.size - 1
}
data class TutorRequest(val snippet: TutorSnippet, val question: String = "", val language: String = "English", val environment: String = "", val sources: List<StudySource> = emptyList()) {
    val version get() = practiceDigest(listOf(snippet.code, snippet.codeLanguage, snippet.firstLine.toString(), question, language, environment) + sources.sortedBy { it.id }.flatMap { listOf(it.id, it.title, it.text) })
}
data class TutorCodeReference(val startLine: Int, val endLine: Int, val quote: String)
data class TutorPoint(val kind: String, val text: String, val codeReferences: List<TutorCodeReference> = emptyList(), val citations: List<StudyCitation> = emptyList())
data class TutorTestCase(val name: String, val input: String, val expected: String, val reason: String, val codeReferences: List<TutorCodeReference>)
data class TutorResult(val model: String, val generatedAt: Long, val selectionVersion: String, val status: String, val summary: String, val observations: List<TutorPoint>, val hints: List<TutorPoint>, val tests: List<TutorTestCase>, val proposedCode: String = "", val editReason: String = "")
data class TutorCodeSource(val id: String, val title: String, val code: String, val codeLanguage: String, val fileVersion: String, val cell: Int? = null)
data class TutorCodeCatalog(val sources: List<TutorCodeSource> = emptyList(), val skipped: Int = 0)
fun interface OfflineTutorCodeLoader {
    suspend fun load(course: Course, sections: List<CourseSection>, assignments: List<Assignment>): TutorCodeCatalog
}

object TutorRules {
    val codeLanguages = listOf("Python", "Kotlin", "Java", "C", "C++", "JavaScript", "TypeScript", "SQL", "Rust", "Go", "Shell", "Other")
    fun lines(code: String) = code.replace("\r\n", "\n").replace('\r', '\n').split('\n')
    fun selection(code: String, from: Int, to: Int, language: String): TutorSnippet {
        require(code.length <= 48_000)
        val lines = lines(code)
        require(from in 1..lines.size && to in from..lines.size)
        return TutorSnippet(lines.subList(from - 1, to).joinToString("\n"), language, from)
    }
    fun request(value: TutorRequest) {
        val s = value.snippet
        require(s.code.isNotBlank() && s.code.length <= 12_000 && s.lines.size <= 300)
        require(s.codeLanguage in codeLanguages && s.firstLine in 1..1_000_000 && s.lastLine <= 1_000_000)
        require(value.question.length <= 1200 && value.environment.length <= 300 && value.language in setOf("English", "Vietnamese"))
        require(value.sources.size <= 4 && value.sources.none { it.id == "snippet" })
        if (value.sources.isNotEmpty()) StudyAiRules.validateSources(value.sources)
        require(s.code.length + value.sources.sumOf { it.text.length } <= 36_000)
    }
    fun reference(ref: TutorCodeReference, snippet: TutorSnippet) {
        require(ref.startLine in snippet.firstLine..snippet.lastLine && ref.endLine in ref.startLine..snippet.lastLine)
        require(ref.quote.isNotBlank() && ref.quote.length <= 500)
        val span = snippet.lines.subList(ref.startLine - snippet.firstLine, ref.endLine - snippet.firstLine + 1).joinToString("\n")
        require(span.contains(ref.quote.replace("\r\n", "\n").replace('\r', '\n')))
    }
    private fun point(value: TutorPoint, request: TutorRequest) {
        require(value.kind in setOf("code", "course", "suggestion", "unknown"))
        require(value.text.isNotBlank() && value.text.length <= 1600 && value.codeReferences.size <= 3 && value.citations.size <= 3)
        require(value.kind != "code" || value.codeReferences.isNotEmpty())
        require(value.kind != "course" || value.citations.isNotEmpty())
        require(value.kind == "course" || value.citations.isEmpty())
        value.codeReferences.forEach { reference(it, request.snippet) }
        value.citations.forEach { c ->
            val quote = PracticeRules.normalize(c.quote)
            require(quote.length in 6..500 && request.sources.firstOrNull { it.id == c.sourceId }?.text?.let { PracticeRules.normalize(it).contains(quote) } == true)
        }
    }
    fun result(value: TutorResult, request: TutorRequest) {
        request(request)
        require(value.selectionVersion == request.version && value.model.isNotBlank() && value.model.length <= 100 && value.generatedAt in 1..4102444800L)
        require(value.summary.isNotBlank() && value.summary.length <= 1600)
        if (value.status == "insufficient_context") {
            require(value.observations.isEmpty() && value.hints.isEmpty() && value.tests.isEmpty() && value.proposedCode.isEmpty() && value.editReason.isEmpty())
            return
        }
        require(value.status == "ready" && value.observations.size in 1..8 && value.hints.size == 3 && value.tests.size in 1..6)
        (value.observations + value.hints).forEach { point(it, request) }
        require(value.hints.map { PracticeRules.normalize(it.text) }.distinct().size == 3)
        value.tests.forEach { test ->
            require(test.name.isNotBlank() && test.name.length <= 160 && test.input.isNotBlank() && test.input.length <= 1200 && test.expected.isNotBlank() && test.expected.length <= 1200 && test.reason.isNotBlank() && test.reason.length <= 1200)
            require(test.codeReferences.size in 1..3)
            test.codeReferences.forEach { reference(it, request.snippet) }
        }
        require(value.tests.map { PracticeRules.normalize(it.name) }.distinct().size == value.tests.size)
        require(value.proposedCode.length <= 12_000 && value.editReason.length <= 1500)
        require(if (value.proposedCode.isEmpty()) value.editReason.isEmpty() else value.proposedCode.isNotBlank() && value.editReason.isNotBlank() && lines(value.proposedCode).size <= 300)
    }
}
