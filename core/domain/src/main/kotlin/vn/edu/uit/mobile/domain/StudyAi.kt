package vn.edu.uit.mobile.domain

import java.net.URI
import vn.edu.uit.mobile.model.Course

data class StudySource(val id: String, val title: String, val text: String)
data class StudyCitation(val sourceId: String, val quote: String)
data class StudyItem(val kind: String, val text: String, val citations: List<StudyCitation> = emptyList())
data class StudyAnswer(val model: String, val generatedAt: Long, val items: List<StudyItem>)
enum class StudyConnectionMode { DIRECT, GATEWAY }
data class StudyConnection(val endpoint: String = "https://api.openai.com", val configured: Boolean = false, val mode: StudyConnectionMode = StudyConnectionMode.DIRECT, val model: String = "")
data class StudyTask(val text: String, val checked: Boolean = false)
class StudyAiException(message: String) : IllegalStateException(message)

interface StudyAiRepository {
    suspend fun connection(): StudyConnection
    suspend fun configure(endpoint: String, token: String)
    suspend fun configureDirect(apiKey: String, model: String)
    suspend fun disconnect()
    suspend fun generate(course: Course, mode: String, sources: List<StudySource>, question: String, language: String): StudyAnswer
    suspend fun tasks(course: Course, assignmentId: Long): List<StudyTask>
    suspend fun saveTasks(course: Course, assignmentId: Long, tasks: List<StudyTask>)
}

object StudyAiRules {
    fun endpoint(value: String): String {
        val uri = URI(value.trim())
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) { "Enter an HTTPS gateway URL without credentials or query parameters." }
        require(uri.path.isNullOrEmpty() || uri.path == "/") { "Enter the gateway origin, without a path." }
        return uri.toString().trimEnd('/')
    }

    fun validate(sources: List<StudySource>, mode: String, question: String) {
        require(mode in setOf("analyze", "ask")) { "Choose a supported study action." }
        require(mode != "ask" || question.isNotBlank()) { "Enter a question about the selected text." }
        require(question.length <= 1200) { "Keep the question under 1,200 characters." }
        require(sources.size in 1..5 && sources.map { it.id }.distinct().size == sources.size) { "Select one to five different sources." }
        require(sources.all { it.id.matches(Regex("[A-Za-z0-9:_-]{1,80}")) && it.title.isNotBlank() && it.title.length <= 200 && it.text.isNotBlank() && it.text.length <= 12000 }) { "Each source needs a title and at most 12,000 characters of text." }
        require(sources.sumOf { it.text.length } <= 36000) { "Select at most 36,000 characters in total." }
    }

    fun verify(answer: StudyAnswer, sources: List<StudySource>, mode: String) {
        val text = sources.associate { it.id to normalize(it.text) }
        require(answer.items.size in 1..12 && answer.model.isNotBlank() && answer.model.length <= 100) { "The AI service returned an invalid result." }
        require(answer.generatedAt in 1..4102444800L) { "The AI service returned an invalid timestamp." }
        answer.items.forEach { item ->
            require(item.kind in if (mode == "ask") setOf("answer", "unknown") else setOf("requirement", "suggestion", "unknown")) { "The AI service returned an unsupported result." }
            require(item.text.isNotBlank() && item.text.length <= 1500 && item.citations.size <= 3) { "The AI service returned an invalid result." }
            require(item.kind !in setOf("requirement", "answer") || item.citations.isNotEmpty()) { "The answer has no supporting source." }
            item.citations.forEach { citation ->
                val quote = normalize(citation.quote)
                require(quote.length in 6..500 && text[citation.sourceId]?.contains(quote) == true) { "The AI service cited text outside your selection." }
            }
        }
    }

    private fun normalize(text: String) = text.trim().replace(Regex("\\s+"), " ")
}
