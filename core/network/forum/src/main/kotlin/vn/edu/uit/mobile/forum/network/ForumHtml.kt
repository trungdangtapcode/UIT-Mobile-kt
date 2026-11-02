package vn.edu.uit.mobile.forum.network

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Element
import com.fleeksoft.ksoup.nodes.Node
import com.fleeksoft.ksoup.nodes.TextNode
import vn.edu.uit.mobile.domain.*

/** Bounded semantic tree. No HTML, CSS or script is executed by the renderer. */
internal object ForumHtml {
    fun parse(html: String): List<ForumBlock> = Reader(html).read()

    private class Reader(private val html: String) {
        private var remaining = 400
        private var spans = 4000
        private var abbreviated = html.length > 64000
        private val blockTags = setOf("p", "div", "section", "article", "blockquote", "aside", "pre", "table", "ul", "ol", "li", "details", "img", "video", "audio", "iframe", "svg", "canvas", "object", "embed", "hr", "h1", "h2", "h3", "h4", "h5", "h6")
        private val dropped = setOf("script", "style", "form", "input", "button", "link", "meta")
        private fun clipped(text: String, limit: Int = 10000): String {
            if (text.length > limit) abbreviated = true
            return text.take(limit)
        }
        fun read(): List<ForumBlock> {
            val doc = Ksoup.parseBodyFragment(html.take(64000))
            doc.select("script,style,form,input,button,link,meta").remove()
            doc.select("img.emoji").forEach { it.replaceWith(TextNode(it.attr("alt").take(80))) }
            val blocks = nodes(doc.body().childNodes(), 0)
            return if (abbreviated) blocks + ForumBlock(ForumBlockKind.OMITTED, "") else blocks
        }
        private fun nodes(nodes: List<Node>, depth: Int): List<ForumBlock> {
            if (depth > 32 || remaining <= 0) {
                abbreviated = true
                return emptyList()
            }
            val out = mutableListOf<ForumBlock>()
            val pending = mutableListOf<Node>()
            fun flush() {
                if (pending.isNotEmpty()) {
                    paragraph(pending.toList(), depth)?.let { out += it }
                    pending.clear()
                }
            }
            nodes.forEach { node ->
                if (remaining <= 0) {
                    abbreviated = true
                    return@forEach
                }
                if (node is Element && (node.tagName() in blockTags || node.hasClass("spoiler") || node.select("img").isNotEmpty())) {
                    flush()
                    out += element(node, depth + 1)
                } else {
                    pending += node
                }
            }
            flush()
            return out
        }
        private fun paragraph(nodes: List<Node>, depth: Int, kind: ForumBlockKind = ForumBlockKind.TEXT): ForumBlock? {
            val runs = inline(nodes, ForumInline(""), depth)
            val text = runs.joinToString("") { it.text }
            if (text.isBlank()) return null
            if (remaining-- <= 0) {
                abbreviated = true
                return null
            }
            return ForumBlock(kind, text, runs.mapNotNull { r -> r.link?.let { ForumLink(r.text, it) } }.distinctBy { it.url }, inline = runs)
        }
        private fun inline(nodes: List<Node>, style: ForumInline, depth: Int): List<ForumInline> {
            if (depth > 32 || spans <= 0) {
                abbreviated = true
                return emptyList()
            }
            val out = mutableListOf<ForumInline>()
            nodes.forEach { node ->
                if (spans <= 0) {
                    abbreviated = true
                    return@forEach
                }
                when (node) {
                    is TextNode -> {
                        val value = clipped(node.getWholeText().replace(Regex("[\\t\\n\\r ]+"), " "))
                        if (value.isNotEmpty()) {
                            spans--
                            out += style.copy(text = value)
                        }
                    }
                    is Element -> {
                        val tag = node.tagName()
                        if (tag in dropped) return@forEach
                        if (tag == "br") {
                            spans--
                            out += style.copy(text = "\n")
                            return@forEach
                        }
                        val next = style.copy(
                            bold = style.bold || tag in setOf("strong", "b", "th"),
                            italic = style.italic || tag in setOf("em", "i"),
                            code = style.code || tag == "code",
                            strike = style.strike || tag in setOf("s", "strike", "del"),
                            underline = style.underline || tag in setOf("u", "ins"),
                            link = if (tag == "a") ForumUrls.safe(node.attr("href")) else style.link,
                            spoiler = style.spoiler || node.hasClass("spoiler"),
                            change = when {
                                tag == "ins" -> 1
                                tag == "del" -> -1
                                else -> style.change
                            }
                        )
                        out += inline(node.childNodes(), next, depth + 1)
                    }
                }
            }
            return out
        }
        private fun element(e: Element, depth: Int): List<ForumBlock> {
            if (depth > 32 || remaining-- <= 0) {
                abbreviated = true
                return emptyList()
            }
            val tag = e.tagName()
            if (tag in dropped) return emptyList()
            if (e.hasClass("spoiler") && tag != "img") {
                return listOf(ForumBlock(ForumBlockKind.SPOILER, "", children = nodes(e.childNodes(), depth)))
            }
            return when {
                tag == "pre" -> listOf(ForumBlock(ForumBlockKind.CODE, clipped(e.wholeText(), 16000), label = e.select("code").firstOrNull()?.classNames()?.firstOrNull { it.startsWith("lang-") || it.startsWith("language-") }?.substringAfter('-').orEmpty()))
                tag == "img" -> {
                    val src = ForumUrls.safe(e.attr("src")) ?: return emptyList()
                    val lightbox = e.parents().firstOrNull { it.tagName() == "a" && it.hasClass("lightbox") }
                    listOf(ForumBlock(ForumBlockKind.IMAGE, e.attr("alt").take(300), listOf(ForumLink(e.attr("alt"), src)), original = lightbox?.attr("href")?.let(ForumUrls::safe)))
                }
                tag == "blockquote" || (tag == "aside" && e.hasClass("quote")) -> {
                    val copy = e.clone()
                    val title = copy.select(".title").firstOrNull()
                    val attribution = title?.text()?.take(300).orEmpty()
                    title?.remove()
                    val topic = e.attr("data-topic").toLongOrNull()
                    val post = e.attr("data-post").toIntOrNull()
                    val target = if (topic != null && topic > 0 && post != null && post > 0) "${ForumLimits.ORIGIN}/t/$topic/$post" else null
                    listOf(ForumBlock(ForumBlockKind.QUOTE, clipped(copy.text(), 8000), target?.let { listOf(ForumLink(attribution, it)) }.orEmpty(), children = nodes(copy.childNodes(), depth), label = attribution))
                }
                tag == "details" -> {
                    val copy = e.clone()
                    val summary = copy.children().firstOrNull { it.tagName() == "summary" }
                    val label = summary?.text()?.take(500).orEmpty()
                    summary?.remove()
                    listOf(ForumBlock(ForumBlockKind.DETAILS, clipped(copy.text()), children = nodes(copy.childNodes(), depth), label = label))
                }
                tag == "table" -> {
                    val rows = e.select("tr").take(100).map { row -> row.children().filter { it.tagName() in setOf("th", "td") }.take(12).map { cell -> nodes(cell.childNodes(), depth + 1) } }
                    listOf(ForumBlock(ForumBlockKind.TABLE, "", rows = e.select("tr").take(100).map { it.children().filter { c -> c.tagName() in setOf("th", "td") }.take(12).map { c -> c.text().take(1000) } }, cells = rows))
                }
                tag == "li" -> {
                    val marker = if (e.parent()?.tagName() == "ol") ((e.parent()?.attr("start")?.toIntOrNull() ?: 1) + (e.parent()?.children()?.filter { it.tagName() == "li" }?.indexOf(e) ?: 0)).toString() + ". " else "• "
                    val children = nodes(e.childNodes(), depth)
                    listOf(ForumBlock(ForumBlockKind.TEXT, marker + clipped(e.text()), children = children, label = marker))
                }
                tag.matches(Regex("h[1-6]")) -> listOfNotNull(paragraph(e.childNodes(), depth, ForumBlockKind.HEADING)?.copy(level = tag.drop(1).toInt()))
                tag in setOf("iframe", "video", "audio", "object", "embed", "svg", "canvas") -> {
                    // Lightbox chrome uses SVG icons; omit those icons, not diagram content.
                    if (tag == "svg" && e.hasClass("svg-icon")) emptyList() else listOf(ForumBlock(ForumBlockKind.UNSUPPORTED, ""))
                }
                tag == "hr" -> listOf(ForumBlock(ForumBlockKind.TEXT, "—"))
                e.hasClass("meta") && e.parents().any { it.hasClass("lightbox-wrapper") } -> emptyList()
                else -> nodes(e.childNodes(), depth)
            }
        }
    }
}
