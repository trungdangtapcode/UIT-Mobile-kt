package vn.edu.uit.mobile.daa

import java.io.File
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import vn.edu.uit.mobile.domain.*

/** Opt-in anonymous public reads. No account credentials or mutation endpoints. */
object AnnouncementLiveResearch {
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        val client = PublicAnnouncementClient()
        var failed = false
        val results = AnnouncementSource.entries.map { source ->
            try {
                val first = client.list(source, 0)
                val second = if (first.more) client.list(source, 1) else null
                val article = client.article(first.items.first())
                check(article.bodyHtml.isNotEmpty())
                val blocks = AnnouncementParser.blocks(article)
                check(blocks.isNotEmpty())
                buildJsonObject {
                    put("source", source.name)
                    put("route", source.page(0))
                    put("count", first.items.size)
                    put("more", first.more)
                    put("secondCount", second?.items?.size ?: 0)
                    put("article", article.url)
                    put("bodyHash", announcementHash(article.bodyHtml))
                    put("blocks", blocks.size)
                    put("passed", true)
                }
            } catch (e: Exception) {
                failed = true
                buildJsonObject {
                    put("source", source.name)
                    put("passed", false)
                    put("error", e.message.orEmpty().take(200))
                }
            }
        }
        val report = buildJsonObject {
            put("capturedAt", Instant.now().toString())
            put("mode", "Anonymous production client; public GET only")
            put("sources", JsonArray(results))
        }
        val output = File(args.first())
        output.parentFile.mkdirs()
        output.writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), report))
        println(report)
        check(!failed) { "A public source failed verification" }
    }
}
