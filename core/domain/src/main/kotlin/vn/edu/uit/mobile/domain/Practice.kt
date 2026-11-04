package vn.edu.uit.mobile.domain

import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.flow.Flow

enum class PracticeKind { MCQ, FLASHCARD, MIXED }
data class PracticeOptions(val kind: PracticeKind = PracticeKind.MIXED, val count: Int = 6, val language: String = "English", val topic: String = "")
data class PracticeQuestion(val id: String, val kind: PracticeKind, val prompt: String, val choices: List<String>, val correctIndex: Int, val answer: String, val explanation: String, val citations: List<StudyCitation>) {
    val version get() = practiceDigest(listOf(kind.name, prompt) + choices + listOf(correctIndex.toString(), answer, explanation) + citations.flatMap { listOf(it.sourceId, it.quote) })
}
data class PracticeGeneration(val model: String, val generatedAt: Long, val status: String, val message: String, val questions: List<PracticeQuestion>)
data class PracticeSet(val id: String, val accountId: String, val courseId: Long, val courseName: String, val title: String, val model: String, val generatedAt: Long, val language: String, val sources: List<StudySource>, val questions: List<PracticeQuestion>) {
    val sourceVersion get() = practiceDigest(sources.sortedBy { it.id }.flatMap { listOf(it.id, it.title, it.text) })
    val version get() = practiceDigest(listOf(accountId, courseId.toString(), title, language, sourceVersion) + questions.map { it.version })
}
data class PracticeProgress(val questionId: String, val version: String, val attempts: Int, val successful: Int, val lastAttemptId: String, val lastAt: Long, val lastOutcome: Boolean, val lastChoice: Int = -1)
data class PracticeRecord(val set: PracticeSet, val progress: List<PracticeProgress> = emptyList())
data class PracticeCollection(val accountId: String, val records: List<PracticeRecord> = emptyList(), val failed: Boolean = false)
data class PracticeAttempt(val id: String, val selected: Int? = null, val recalled: Boolean? = null)
interface PracticeRepository {
    val collections: Flow<List<PracticeCollection>>
    suspend fun save(set: PracticeSet): PracticeRecord
    suspend fun remove(expected: PracticeRecord)
    suspend fun attempt(set: PracticeSet, questionId: String, expected: PracticeProgress?, value: PracticeAttempt): PracticeRecord
}

fun practiceDigest(values: List<String>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    values.forEach { value ->
        val bytes = value.toByteArray(Charsets.UTF_8)
        digest.update("${bytes.size}:".toByteArray(Charsets.US_ASCII))
        digest.update(bytes)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
object PracticeRules {
    fun normalize(value: String) = value.replace(Regex("[\\s\\p{Z}\\u0085\\u001c-\\u001f]+"), " ").trim()
    fun request(sources: List<StudySource>, options: PracticeOptions) {
        StudyAiRules.validateSources(sources)
        require(options.count in 1..12 && (options.kind != PracticeKind.MIXED || options.count >= 2))
        require(options.language in setOf("English", "Vietnamese") && options.topic.length <= 200)
    }
    fun questions(questions: List<PracticeQuestion>, sources: List<StudySource>) {
        require(questions.size in 1..12 && questions.map { it.id }.distinct().size == questions.size)
        require(questions.map { normalize(it.prompt).lowercase(Locale.ROOT) }.distinct().size == questions.size)
        val text = sources.associate { it.id to normalize(it.text) }
        questions.forEach { q ->
            require(q.id.matches(Regex("[A-Za-z0-9_-]{1,80}")) && q.kind != PracticeKind.MIXED)
            require(q.prompt.isNotBlank() && q.prompt.length <= 1000 && q.answer.isNotBlank() && q.answer.length <= 1000 && q.explanation.isNotBlank() && q.explanation.length <= 1500)
            if (q.kind == PracticeKind.MCQ) {
                require(q.choices.size == 4 && q.choices.all { it.isNotBlank() && it.length <= 500 })
                require(q.choices.map { normalize(it).lowercase(Locale.ROOT) }.distinct().size == 4)
                require(q.correctIndex in q.choices.indices && normalize(q.answer) == normalize(q.choices[q.correctIndex]))
            } else {
                require(q.choices.isEmpty() && q.correctIndex == -1)
            }
            require(q.citations.size in 1..3)
            q.citations.forEach { c ->
                val quote = normalize(c.quote)
                require(quote.length in 6..500 && text[c.sourceId]?.contains(quote) == true)
            }
        }
    }
    fun generation(value: PracticeGeneration, sources: List<StudySource>, options: PracticeOptions) {
        request(sources, options)
        require(value.model.isNotBlank() && value.model.length <= 100 && value.generatedAt in 1..4102444800L && value.message.length <= 500)
        if (value.status == "insufficient_evidence") {
            require(value.questions.isEmpty() && value.message.isNotBlank())
            return
        }
        require(value.status == "ready" && value.questions.size == options.count)
        questions(value.questions, sources)
        require(options.kind == PracticeKind.MIXED || value.questions.all { it.kind == options.kind })
        if (options.kind == PracticeKind.MIXED) require(value.questions.map { it.kind }.toSet() == setOf(PracticeKind.MCQ, PracticeKind.FLASHCARD))
    }
    fun set(value: PracticeSet) {
        require(value.id.matches(Regex("[A-Za-z0-9_-]{1,80}")) && value.accountId.length in 1..256 && value.accountId.none { it.isISOControl() } && value.courseId > 0)
        require(value.courseName.isNotBlank() && value.courseName.length <= 240 && value.title.isNotBlank() && value.title.length <= 160)
        require(value.model.isNotBlank() && value.model.length <= 100 && value.generatedAt in 1..4102444800L && value.language in setOf("English", "Vietnamese"))
        StudyAiRules.validateSources(value.sources)
        questions(value.questions, value.sources)
    }
    fun record(value: PracticeRecord) {
        set(value.set)
        require(value.progress.size <= value.set.questions.size && value.progress.map { it.questionId }.distinct().size == value.progress.size)
        value.progress.forEach { p ->
            val q = requireNotNull(value.set.questions.singleOrNull { it.id == p.questionId })
            require(p.version == q.version && p.attempts in 1..1_000_000 && p.successful in 0..p.attempts && p.lastAt in 1..4102444800L && p.lastAttemptId.matches(Regex("[A-Za-z0-9_-]{1,80}")))
            require(if (q.kind == PracticeKind.MCQ) p.lastChoice in q.choices.indices && p.lastOutcome == (p.lastChoice == q.correctIndex) else p.lastChoice == -1)
        }
    }
    fun outcome(question: PracticeQuestion, attempt: PracticeAttempt): Boolean {
        require(attempt.id.matches(Regex("[A-Za-z0-9_-]{1,80}")))
        return if (question.kind == PracticeKind.MCQ) {
            require(attempt.recalled == null && attempt.selected in question.choices.indices)
            attempt.selected == question.correctIndex
        } else {
            require(question.kind == PracticeKind.FLASHCARD && attempt.selected == null)
            requireNotNull(attempt.recalled)
        }
    }
}
