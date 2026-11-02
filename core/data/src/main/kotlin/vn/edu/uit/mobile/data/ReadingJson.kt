package vn.edu.uit.mobile.data

import kotlinx.serialization.json.*
import vn.edu.uit.mobile.domain.*

/** Versioned encrypted storage and explicit portable export; no URL, account identity or PDF bytes in export. */
object ReadingJson {
    private fun position(p: ReadingPosition) = buildJsonObject {
        put("page", p.page)
        put("zoom", p.zoom)
        put("panX", p.panX)
        put("panY", p.panY)
    }
    private fun note(n: ReadingNote) = buildJsonObject {
        put("id", n.id)
        put("page", n.page)
        put("text", n.text)
        put("x", n.x)
        put("y", n.y)
        put("createdAt", n.createdAt)
        put("updatedAt", n.updatedAt)
    }
    private fun record(r: ReadingRecord, portable: Boolean = false) = buildJsonObject {
        if (!portable) {
            put("documentId", r.identity.documentId)
            put("courseId", r.courseId)
            put("revision", r.revision)
        }
        put("documentVersion", r.identity.version)
        put("title", r.title)
        put("pageCount", r.pageCount)
        put("position", position(r.position))
        put("bookmarks", JsonArray(r.bookmarks.sorted().map(::JsonPrimitive)))
        val notes = if (portable) r.notes.sortedWith(compareBy({ it.page }, { it.createdAt }, { it.id })) else r.notes
        put("notes", JsonArray(notes.map(::note)))
    }
    fun export(record: ReadingRecord): String {
        ReadingRules.record(record)
        return Json { prettyPrint = true }.encodeToString(
            JsonElement.serializer(),
            buildJsonObject {
                put("format", "uit-course-reading")
                put("version", 1)
                put("pageNumbering", "one-based")
                put("annotationKind", "page-linked-text-note-with-normalized-pin")
                put("document", record(record, true))
            }
        )
    }
    fun encode(accountId: String, values: List<ReadingRecord>): String {
        ReadingRules.collection(accountId, values)
        return buildJsonObject {
            put("version", 1)
            put("records", JsonArray(values.map { record(it) }))
        }.toString().also { require(it.length <= 2_000_000) }
    }
    fun decode(raw: String, owner: String): List<ReadingRecord> {
        require(raw.length <= 2_000_000)
        val root = Json.parseToJsonElement(raw).jsonObject
        require(number(root, "version") == 1L)
        val values = root.getValue("records").jsonArray
        require(values.size <= 50)
        return values.map { value ->
            val r = value.jsonObject
            val p = r.getValue("position").jsonObject
            val bookmarks = r.getValue("bookmarks").jsonArray.map { strictNumber(it).toInt().also { n -> require(n.toLong() == strictNumber(it)) } }
            require(bookmarks.distinct().size == bookmarks.size)
            val notes = r.getValue("notes").jsonArray.also { require(it.size <= 100) }.map { element ->
                val n = element.jsonObject
                ReadingNote(text(n, "id"), integer(n, "page"), text(n, "text"), decimal(n, "x"), decimal(n, "y"), number(n, "createdAt"), number(n, "updatedAt"))
            }
            ReadingRecord(ReadingIdentity(owner, text(r, "documentId"), text(r, "documentVersion")), text(r, "title"), number(r, "courseId"), integer(r, "pageCount"), ReadingPosition(integer(p, "page"), decimal(p, "zoom"), decimal(p, "panX"), decimal(p, "panY")), bookmarks.toSet(), notes, number(r, "revision"))
        }.also { ReadingRules.collection(owner, it) }
    }
    private fun strictNumber(e: JsonElement) = e.jsonPrimitive.also { require(!it.isString) }.long
    private fun number(o: JsonObject, key: String) = strictNumber(o.getValue(key))
    private fun integer(o: JsonObject, key: String) = number(o, key).let {
        require(it in Int.MIN_VALUE..Int.MAX_VALUE)
        it.toInt()
    }
    private fun decimal(o: JsonObject, key: String) = o.getValue(key).jsonPrimitive.also { require(!it.isString) }.float.also { require(it.isFinite()) }
    private fun text(o: JsonObject, key: String) = o.getValue(key).jsonPrimitive.also { require(it.isString) }.content
}
