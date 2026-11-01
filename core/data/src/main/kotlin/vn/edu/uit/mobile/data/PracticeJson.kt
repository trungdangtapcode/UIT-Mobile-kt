package vn.edu.uit.mobile.data

import java.util.Locale
import kotlinx.serialization.json.*
import vn.edu.uit.mobile.domain.*

internal object PracticeJson {
    fun text(element: JsonElement): String = element.jsonPrimitive.let {
        require(it.isString)
        it.content
    }
    fun text(doc: JsonObject, key: String) = text(doc.getValue(key))
    fun number(doc: JsonObject, key: String): Long = doc.getValue(key).jsonPrimitive.let {
        require(!it.isString)
        it.long
    }
    fun int(doc: JsonObject, key: String): Int = number(doc, key).also { require(it in Int.MIN_VALUE..Int.MAX_VALUE) }.toInt()
    fun boolean(doc: JsonObject, key: String): Boolean = doc.getValue(key).jsonPrimitive.let {
        require(!it.isString)
        it.boolean
    }
    fun question(q: JsonObject, id: String) = PracticeQuestion(id, PracticeKind.valueOf(text(q, "kind").uppercase(Locale.ROOT)), text(q, "prompt"), q.getValue("choices").jsonArray.map(::text), int(q, "correctIndex"), text(q, "answer"), text(q, "explanation"), q.getValue("citations").jsonArray.map { c -> StudyCitation(text(c.jsonObject, "sourceId"), text(c.jsonObject, "quote")) })
    fun question(q: PracticeQuestion) = buildJsonObject {
        put("id", q.id)
        put("kind", q.kind.name.lowercase(Locale.ROOT))
        put("prompt", q.prompt)
        put("choices", JsonArray(q.choices.map(::JsonPrimitive)))
        put("correctIndex", q.correctIndex)
        put("answer", q.answer)
        put("explanation", q.explanation)
        put(
            "citations",
            JsonArray(
                q.citations.map { c ->
                    buildJsonObject {
                        put("sourceId", c.sourceId)
                        put("quote", c.quote)
                    }
                }
            )
        )
    }
    fun encode(records: List<PracticeRecord>) = buildJsonObject {
        put("version", 1)
        put(
            "records",
            JsonArray(
                records.map { record ->
                    buildJsonObject {
                        val s = record.set
                        put("id", s.id)
                        put("account", s.accountId)
                        put("course", s.courseId)
                        put("courseName", s.courseName)
                        put("title", s.title)
                        put("model", s.model)
                        put("generatedAt", s.generatedAt)
                        put("language", s.language)
                        put("sourceVersion", s.sourceVersion)
                        put("setVersion", s.version)
                        put(
                            "sources",
                            JsonArray(
                                s.sources.map { source ->
                                    buildJsonObject {
                                        put("id", source.id)
                                        put("title", source.title)
                                        put("text", source.text)
                                    }
                                }
                            )
                        )
                        put("questions", JsonArray(s.questions.map(::question)))
                        put(
                            "progress",
                            JsonArray(
                                record.progress.map { p ->
                                    buildJsonObject {
                                        put("question", p.questionId)
                                        put("version", p.version)
                                        put("attempts", p.attempts)
                                        put("successful", p.successful)
                                        put("attemptId", p.lastAttemptId)
                                        put("at", p.lastAt)
                                        put("outcome", p.lastOutcome)
                                        put("choice", p.lastChoice)
                                    }
                                }
                            )
                        )
                    }
                }
            )
        )
    }.toString().also { require(it.length <= 2_000_000) }
    fun decode(raw: String, owner: String): List<PracticeRecord> {
        require(raw.length <= 2_000_000)
        val root = Json.parseToJsonElement(raw).jsonObject
        require(int(root, "version") == 1)
        val records = root.getValue("records").jsonArray
        require(records.size <= 20)
        val result = records.map { element ->
            val s = element.jsonObject
            val sources = s.getValue("sources").jsonArray.map { source -> source.jsonObject.let { StudySource(text(it, "id"), text(it, "title"), text(it, "text")) } }
            val questions = s.getValue("questions").jsonArray.map { q -> question(q.jsonObject, text(q.jsonObject, "id")) }
            val set = PracticeSet(text(s, "id"), text(s, "account"), number(s, "course"), text(s, "courseName"), text(s, "title"), text(s, "model"), number(s, "generatedAt"), text(s, "language"), sources, questions)
            require(set.accountId == owner && set.sourceVersion == text(s, "sourceVersion") && set.version == text(s, "setVersion"))
            val progress = s.getValue("progress").jsonArray.map { element -> element.jsonObject.let { p -> PracticeProgress(text(p, "question"), text(p, "version"), int(p, "attempts"), int(p, "successful"), text(p, "attemptId"), number(p, "at"), boolean(p, "outcome"), int(p, "choice")) } }
            PracticeRecord(set, progress).also(PracticeRules::record)
        }
        require(result.map { it.set.id }.distinct().size == result.size && result.map { it.set.version }.distinct().size == result.size)
        return result
    }
}
