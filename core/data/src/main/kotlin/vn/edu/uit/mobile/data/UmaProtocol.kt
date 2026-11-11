package vn.edu.uit.mobile.data

import com.fleeksoft.ksoup.Ksoup
import kotlinx.serialization.json.*
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.forum.network.ForumParser
import vn.edu.uit.mobile.model.UmaSession

/** Observed UMA student contract, 2026-11-12. Unknown JSON fields are tolerated. */
object UmaProtocol {
    data class Bootstrap(val identity: UmaIdentity, val csrf: String, val available: Set<UmaSource>)
    fun bootstrap(html: String): Bootstrap {
        val doc = Ksoup.parse(html)
        if (doc.selectFirst("#ask #q") == null || doc.selectFirst("#ho_so")?.attr("value") != "toi") throw UmaException(UmaFailure.EXPIRED)
        val id = doc.selectFirst(".ums-tk-head span")?.text().orEmpty()
        val name = doc.selectFirst(".ums-tk-head b")?.text().orEmpty()
        val csrf = doc.selectFirst("input[name=csrfmiddlewaretoken]")?.attr("value").orEmpty()
        val available = doc.select("input[name=nguon]").mapNotNull { input -> UmaSource.entries.find { it.wire == input.attr("value") } }.toSet()
        if (id.isBlank() || name.isBlank() || !csrf.matches(Regex("[A-Za-z0-9]{32,64}")) || available.isEmpty()) throw UmaException(UmaFailure.RESPONSE)
        return Bootstrap(UmaIdentity(id, name), csrf, available)
    }
    fun session(session: UmaSession) {
        require(session.cookie.length in 20..8192 && session.cookie.none { it == '\r' || it == '\n' })
        val pairs = session.cookie.split(';').map { it.trim().substringBefore('=') to it.trim().substringAfter('=', "") }
        require(pairs.map { it.first }.toSet() == setOf("ums2_sessionid", "ums2_csrftoken") && pairs.size == 2)
        require(pairs.all { it.second.matches(Regex("[A-Za-z0-9]{16,128}")) })
        require(session.csrf.matches(Regex("[A-Za-z0-9]{32,64}")))
    }
    fun request(question: String, sources: Set<UmaSource>, history: List<UmaTurn>, suggestionId: Long?): String {
        require(question.isNotBlank() && question.length <= 1000 && sources.isNotEmpty())
        require(suggestionId == null || suggestionId > 0)
        val previous = history.filter { it.status == UmaTurnStatus.COMPLETE && it.answer != null }.takeLast(3)
        return buildJsonObject {
            put("q", question)
            put("goi_y_id", suggestionId?.let(::JsonPrimitive) ?: JsonNull)
            put("truoc", previous.lastOrNull()?.let { it.answer!!.understood.ifBlank { it.question } }.orEmpty())
            put(
                "lich_su",
                JsonArray(
                    previous.map { turn ->
                        buildJsonObject {
                            put("q", turn.answer!!.understood.ifBlank { turn.question })
                            put("tl", turn.answer!!.text.replace(Regex("\\s+"), " ").take(200))
                        }
                    }
                ).toString()
            )
            put("ho_so", "toi")
            put("nam", "")
            put("domain", "")
            put("cho_duyet", false)
            put("hoi_nhu", "")
            put("site", "portal")
            put("trang", "/sinh-vien/tkb")
            put("nguon", UmaSource.entries.filter { it in sources }.joinToString(",") { it.wire })
            put("tu_khoa_vb", "")
        }.toString()
    }
    fun answer(raw: String): UmaAnswer = try {
        val d = Json.parseToJsonElement(raw).jsonObject
        val result = d.s("ket_qua")
        val text = d.s("tra_loi")
        if (result.isBlank() || text.isBlank() || text.length > 64000) throw UmaException(UmaFailure.RESPONSE)
        val citations = d.a("trich_dan").map { value ->
            val c = value.jsonObject
            UmaCitation(c.n("n").toInt(), listOf(c.s("so_hieu"), c.s("ten")).filter(String::isNotBlank).joinToString(" — "), listOf(c.s("vi_tri"), c.s("trang")).filter(String::isNotBlank).joinToString(" · "), UmaUrls.link(c.s("xem")), UmaUrls.link(c.s("goc")), c.s("ap_dung"), c.b("khop_thap"), c.b("ban_quet"))
        }
        val blocks = markdown(text)
        val warnings = d.a("tin_cay_thap").map { it.jsonPrimitive.content } + listOf(d.s("luu_y")).filter(String::isNotBlank) + d.a("van_ban_cu").map { old ->
            val o = old.jsonObject
            listOf(o.s("ten"), o.s("ngay"), o.a("moi_hon").joinToString("; ") { it.jsonPrimitive.content }).filter(String::isNotBlank).joinToString(" · ")
        }
        val displayRoot = d["hien_thi"] as? JsonObject
        val display = displayRoot?.let { listOf(UmaDisplay("nhom", it.s("tieu_de"), children = it.a("khoi").map { k -> display(k.jsonObject, 0) }, expanded = true)) }.orEmpty()
        val week = (d["lich_tuan"] as? JsonObject)?.let { w ->
            UmaWeek(
                w.s("tieu_de"),
                w.a("buoi").map { b ->
                    val o = b.jsonObject
                    UmaLesson(o.s("ngay"), o.s("ma_lop"), o.s("ten_mon"), "${o.s("tiet_bat_dau")}–${o.s("tiet_ket_thuc")}", listOf(o.s("gio_bat_dau"), o.s("gio_ket_thuc")).filter(String::isNotBlank).joinToString("–"), o.s("phong"), o.s("giang_vien"), listOf(o.s("ghi_chu"), o.s("hinh_thuc")).filter(String::isNotBlank).joinToString(" · "))
                },
                listOf("hoi_truoc", "hoi_nay", "hoi_sau").map { w.s(it) }.filter(String::isNotBlank).map { UmaSuggestion(it) }
            )
        }
        val links = d.a("danh_sach").map { it.jsonObject.let { m -> UmaLinkItem(m.s("nhan"), UmaUrls.link(m.s("url")), m.s("chi_tiet"), m.s("tom_tat")) } } + d.a("van_ban_ums").map { it.jsonObject.let { v -> UmaLinkItem(listOf(v.s("so_hieu"), v.s("trich_yeu")).filter(String::isNotBlank).joinToString(" — "), UmaUrls.link(v.s("link")), listOf(v.s("loai"), v.s("ngay")).filter(String::isNotBlank).joinToString(" · ")) } }
        val analysis = d["phan_tich"] as? JsonObject
        fun unknown(block: UmaDisplay): Boolean = block.kind !in setOf("the", "thong_tin", "bang", "nhom", "ghi_chu") || block.children.any(::unknown)
        UmaAnswer(result, text, blocks, citations, warnings, d.a("goi_y").map { it.jsonObject.let { s -> UmaSuggestion(s.s("q"), s.n("id").takeIf { n -> n > 0 }, s.b("duyet")) } }, links, display, week, d.s("cau_hieu"), analysis?.b("noi_tiep") == true, display.any(::unknown) || blocks.any { it.kind == ForumBlockKind.OMITTED } || d.b("tim_vb") && links.isEmpty())
    } catch (e: UmaException) {
        throw e
    } catch (_: Exception) {
        throw UmaException(UmaFailure.RESPONSE)
    }
    private fun display(o: JsonObject, depth: Int): UmaDisplay {
        if (depth > 8) throw UmaException(UmaFailure.RESPONSE)
        val kind = o.s("loai")
        val rows = if (kind == "the") {
            o.a("o").map { value ->
                val c = value.jsonObject
                listOf(UmaCell(c.s("nhan")), UmaCell(c.s("gia_tri"), bold = true, tone = c.s("mau")), UmaCell(c.s("phu")))
            }
        } else {
            o.a("dong").map { row ->
                row.jsonArray.map { c ->
                    if (c is JsonObject) UmaCell(c.s("t"), c.b("dam"), c.b("ma"), c.s("mau")) else UmaCell((c as? JsonPrimitive)?.contentOrNull.orEmpty())
                }
            }
        }
        return UmaDisplay(kind, o.s("tieu_de"), listOf(o.s("phu"), o.s("t"), o.s("trong").takeIf { rows.isEmpty() }.orEmpty()).filter(String::isNotBlank).joinToString("\n"), o.a("cot").map { it.jsonObject.s("ten") }, rows, o.a("khoi").map { display(it.jsonObject, depth + 1) }, o.b("mo"))
    }
    private fun markdown(text: String): List<ForumBlock> {
        val extensions = listOf(TablesExtension.create())
        val tree = Parser.builder().extensions(extensions).build().parse(text)
        val html = HtmlRenderer.builder().extensions(extensions).escapeHtml(true).sanitizeUrls(true).build().render(tree)
        val doc = Ksoup.parseBodyFragment(html)
        doc.select("a").forEach { a -> UmaUrls.link(a.attr("href"))?.let { a.attr("href", it) } ?: a.removeAttr("href") }
        // Remote images do not receive university cookies and are never fetched implicitly.
        doc.select("img").forEach { it.replaceWith(com.fleeksoft.ksoup.nodes.TextNode(it.attr("alt"))) }
        return ForumParser.blocks(doc.body().html())
    }
    private fun JsonObject.s(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun JsonObject.n(key: String) = (get(key) as? JsonPrimitive)?.longOrNull ?: 0
    private fun JsonObject.b(key: String) = (get(key) as? JsonPrimitive)?.booleanOrNull == true
    private fun JsonObject.a(key: String) = (get(key) as? JsonArray).orEmpty().also { if (it.size > 500) throw UmaException(UmaFailure.RESPONSE) }
}
