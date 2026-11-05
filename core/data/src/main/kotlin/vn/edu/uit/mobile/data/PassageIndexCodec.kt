package vn.edu.uit.mobile.data

import java.nio.ByteBuffer
import java.util.Base64
import kotlinx.serialization.json.*
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.model.*

/** Version all derived data; reject incompatible, oversized or cross-account records. */
internal object PassageIndexCodec {
    private fun JsonObject.s(k: String) = getValue(k).jsonPrimitive.content
    private fun JsonObject.n(k: String) = getValue(k).jsonPrimitive.long
    private fun JsonObject.i(k: String) = getValue(k).jsonPrimitive.int
    private fun JsonObject.b(k: String) = getValue(k).jsonPrimitive.boolean
    fun encode(index: PassageIndex): String = buildJsonObject {
        put("version", 2)
        put("extractor", PassageRules.EXTRACTOR)
        put("owner", index.owner)
        put("course", index.courseId)
        put("revision", index.revision)
        put("at", index.builtAt)
        put(
            "config",
            buildJsonObject {
                val c = index.config
                put("characters", c.characters)
                put("overlap", c.overlap)
                put("k1", c.k1)
                put("b", c.b)
                put("candidates", c.candidates)
                put("fusionK", c.fusionK)
            }
        )
        put(
            "documents",
            JsonArray(
                index.documents.map { d ->
                    buildJsonObject {
                        put("id", d.spec.id)
                        put("hash", d.version)
                        put("partial", d.partial)
                        put("shortname", d.spec.course.shortname)
                        put("fullname", d.spec.course.fullname)
                        put("section", d.spec.section)
                        put("sectionName", d.spec.sectionName)
                        put("name", d.spec.file.name)
                        put("url", d.spec.file.url)
                        put("bytes", d.spec.file.size)
                    }
                }
            )
        )
        put(
            "passages",
            JsonArray(
                index.passages.map { p ->
                    buildJsonObject {
                        put("id", p.id)
                        put("document", p.documentId)
                        put("ordinal", p.ordinal)
                        put("kind", p.kind.name)
                        put("start", p.start)
                        put("end", p.end)
                        put("firstLine", p.firstLine)
                        put("lastLine", p.lastLine)
                        put("text", p.text)
                    }
                }
            )
        )
        put(
            "embedding",
            index.embedding?.let {
                buildJsonObject {
                    put("model", it.model)
                    put("dimensions", it.dimensions)
                }
            } ?: JsonNull
        )
        put("vectors", JsonArray(index.vectors.map { v -> JsonPrimitive(Base64.getEncoder().encodeToString(ByteBuffer.allocate(v.size * 4).apply { v.forEach(::putFloat) }.array())) }))
        put("tokens", index.embeddingTokens)
        put("semanticFailed", index.semanticFailed)
        put("limited", index.limited)
        index.update?.let { receipt ->
            put(
                "update",
                buildJsonObject {
                    put("reused", receipt.reused)
                    put("embedded", receipt.embedded)
                    put("requests", receipt.requests)
                    put("removed", receipt.removed)
                }
            )
        }
    }.toString().also { require(it.length <= 5 * 1024 * 1024) }
    fun decode(raw: String, owner: String): PassageIndex {
        require(raw.length <= 5 * 1024 * 1024)
        val o = Json.parseToJsonElement(raw).jsonObject
        require(o.i("version") in 1..2 && o.s("extractor") == PassageRules.EXTRACTOR && o.s("owner") == owner && o.n("course") > 0)
        val c = o.getValue("config").jsonObject
        val config = PassageConfig(c.i("characters"), c.i("overlap"), c.getValue("k1").jsonPrimitive.double, c.getValue("b").jsonPrimitive.double, c.i("candidates"), c.i("fusionK")).also { it.validate() }
        val docs = o.getValue("documents").jsonArray.also { require(it.size in 1..PassageRules.DOCUMENTS) }.map { value ->
            val d = value.jsonObject
            val course = Course(o.n("course"), owner, d.s("shortname"), d.s("fullname"))
            require(course.shortname.length <= 1000 && course.fullname.length <= 1000 && d.s("name").length <= 1000 && d.s("url").length <= 8192 && d.s("section").length <= 200 && d.s("sectionName").length <= 1000)
            PassageDocumentInfo(OfflineDownloadSpec(course, d.s("section"), d.s("sectionName"), CourseFile(d.s("name"), d.s("url"), d.n("bytes"))), d.s("hash"), d.b("partial")).also { require(it.spec.id == d.s("id") && it.version.matches(Regex("[a-f0-9]{64}"))) }
        }
        require(docs.distinctBy { it.spec.id }.size == docs.size)
        val passages = o.getValue("passages").jsonArray.also { require(it.size in 1..PassageRules.PASSAGES) }.map { value ->
            val p = value.jsonObject
            CoursePassage(p.s("id"), p.s("document"), p.i("ordinal"), PassagePartKind.valueOf(p.s("kind")), p.i("start"), p.i("end"), p.i("firstLine"), p.i("lastLine"), p.s("text")).also {
                require(it.documentId in docs.map { d -> d.spec.id } && it.ordinal in 1..10000 && it.start >= 0 && it.end in it.start + 1..PassageRules.DOCUMENT_CHARACTERS && it.text.length == it.end - it.start && it.text.length <= config.characters && it.firstLine > 0 && it.lastLine >= it.firstLine)
                val version = docs.first { d -> d.spec.id == it.documentId }.version
                require(it.id == practiceDigest(listOf(PassageRules.EXTRACTOR, it.documentId, version, it.ordinal.toString(), it.start.toString(), it.end.toString())))
            }
        }
        require(passages.distinctBy { it.id }.size == passages.size)
        val embedding = (o["embedding"] as? JsonObject)?.let { PassageEmbeddingConfig(it.s("model"), it.i("dimensions")).also { e -> e.validate() } }
        val vectors = o.getValue("vectors").jsonArray.also { require(it.isEmpty() || embedding != null && it.size == passages.size) }.map { value ->
            val dimensions = requireNotNull(embedding).dimensions
            val text = value.jsonPrimitive.content.also { require(it.length <= dimensions * 6) }
            val bytes = Base64.getDecoder().decode(text).also { require(it.size == dimensions * 4) }
            val buffer = ByteBuffer.wrap(bytes)
            PassageRules.normalize(List(dimensions) { buffer.float }, dimensions)
        }
        return PassageIndex(
            owner, o.n("course"), o.s("revision").also { require(it.length in 1..80) }, o.n("at"), config, docs, passages, embedding, vectors, o.n("tokens").also { require(it >= 0) }, o.b("semanticFailed"), o.b("limited"),
            (o["update"] as? JsonObject)?.let { r ->
                PassageUpdateReceipt(r.i("reused"), r.i("embedded"), r.i("requests"), r.i("removed")).also {
                    require(it.reused in 0..PassageRules.PASSAGES && it.embedded in 0..PassageRules.PASSAGES && it.requests in 0..PassageRules.PASSAGES && it.removed in 0..PassageRules.DOCUMENTS)
                    require(it.reused + it.embedded <= passages.size)
                }
            }
        )
    }
}
