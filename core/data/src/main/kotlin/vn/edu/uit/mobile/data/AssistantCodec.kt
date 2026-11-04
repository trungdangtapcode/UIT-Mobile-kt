package vn.edu.uit.mobile.data

import kotlinx.serialization.json.*
import vn.edu.uit.mobile.domain.*

/** Explicit versioned public-workspace format. Provider reasoning/tool transcripts are never stored. */
internal object AssistantCodec {
    private fun strings(values: Collection<String>) = JsonArray(values.map(::JsonPrimitive))
    private fun JsonObject.s(key: String) = getValue(key).jsonPrimitive.content
    private fun JsonObject.n(key: String) = getValue(key).jsonPrimitive.long
    private fun JsonObject.b(key: String) = getValue(key).jsonPrimitive.boolean
    private fun JsonObject.a(key: String) = getValue(key).jsonArray
    fun encode(value: AssistantLibrary): String = buildJsonObject {
        put("version", 2)
        put(
            "workspaces",
            JsonArray(
                value.workspaces.map { workspace ->
                    buildJsonObject {
                        put("id", workspace.id)
                        put("name", workspace.name)
                        put("createdAt", workspace.createdAt)
                        put("revision", workspace.revision)
                        put("sources", strings(workspace.scope.sources.map { it.name }))
                        put("urls", strings(workspace.scope.urls))
                        put("forum", workspace.scope.forum?.let(AssistantForumCodec::scope) ?: JsonNull)
                        put(
                            "turns",
                            JsonArray(
                                workspace.turns.map { turn ->
                                    buildJsonObject {
                                        put("id", turn.id)
                                        put("question", turn.question)
                                        put("at", turn.at)
                                        put("status", turn.status.name)
                                        put("model", turn.model)
                                        put("coverage", turn.coverage)
                                        put("forumCoverage", turn.forumCoverage?.let(AssistantForumCodec::coverage) ?: JsonNull)
                                        put(
                                            "events",
                                            JsonArray(
                                                turn.events.map {
                                                    buildJsonObject {
                                                        put("tool", it.tool)
                                                        put("results", it.results)
                                                    }
                                                }
                                            )
                                        )
                                        put(
                                            "facts",
                                            JsonArray(
                                                turn.facts.map { fact ->
                                                    buildJsonObject {
                                                        put("kind", fact.kind.name)
                                                        put("text", fact.text)
                                                        put(
                                                            "references",
                                                            JsonArray(
                                                                fact.references.map {
                                                                    buildJsonObject {
                                                                        put("id", it.evidenceId)
                                                                        put("paragraph", it.paragraph)
                                                                        put("quote", it.quote)
                                                                    }
                                                                }
                                                            )
                                                        )
                                                    }
                                                }
                                            )
                                        )
                                        put(
                                            "evidence",
                                            JsonArray(
                                                turn.evidence.map { item ->
                                                    buildJsonObject {
                                                        put("id", item.id)
                                                        put("url", item.url)
                                                        put("source", item.source?.let { JsonPrimitive(it.name) } ?: JsonNull)
                                                        put("forum", item.forum?.let(AssistantForumCodec::post) ?: JsonNull)
                                                        put("title", item.title)
                                                        put("date", item.publishedDate)
                                                        put("at", item.fetchedAt)
                                                        put("version", item.version)
                                                        put("paragraphs", strings(item.paragraphs))
                                                        put("offline", item.offline)
                                                        put("abbreviated", item.abbreviated)
                                                    }
                                                }
                                            )
                                        )
                                        put(
                                            "checklist",
                                            JsonArray(
                                                turn.checklist.map {
                                                    buildJsonObject {
                                                        put("id", it.id)
                                                        put("text", it.text)
                                                        put("checked", it.checked)
                                                    }
                                                }
                                            )
                                        )
                                    }
                                }
                            )
                        )
                    }
                }
            )
        )
    }.toString().also { require(it.length <= 12 * 1024 * 1024) }
    fun decode(raw: String): AssistantLibrary {
        require(raw.length <= 12 * 1024 * 1024)
        val root = Json.parseToJsonElement(raw).jsonObject
        require(root.n("version") in 1L..2L && root.a("workspaces").size <= AssistantRules.WORKSPACES)
        val result = root.a("workspaces").map { v ->
            val w = v.jsonObject
            val scope = AssistantScope(w.a("sources").map { AnnouncementSource.valueOf(it.jsonPrimitive.content) }.toSet(), w.a("urls").map { it.jsonPrimitive.content }.toSet(), (w["forum"] as? JsonObject)?.let(AssistantForumCodec::scope))
            AssistantRules.scope(scope)
            AssistantRules.name(w.s("name"))
            require(w.s("id").matches(Regex("[A-Za-z0-9-]{1,80}")) && w.a("turns").size <= AssistantRules.TURNS)
            val turns = w.a("turns").map { value ->
                val t = value.jsonObject
                require(t.s("question").length <= 1200 && t.a("evidence").size <= AssistantRules.EVIDENCE && t.s("coverage").length <= 6000 && t.a("events").size <= AssistantRules.MAX_CALLS)
                val forumCoverage = (t["forumCoverage"] as? JsonObject)?.let(AssistantForumCodec::coverage)
                if (forumCoverage != null) require(forumCoverage.topicId == scope.forum?.topicId && forumCoverage.selectedIds.size <= requireNotNull(scope.forum).limit)
                val evidence = t.a("evidence").map { entry ->
                    val e = entry.jsonObject
                    val source = e["source"]?.jsonPrimitive?.contentOrNull?.let(AnnouncementSource::valueOf)
                    val post = (e["forum"] as? JsonObject)?.let(AssistantForumCodec::post)
                    if (post == null) {
                        require(source != null && source in scope.sources && AnnouncementUrls.article(e.s("url"), source) == e.s("url") && (scope.urls.isEmpty() || e.s("url") in scope.urls))
                    } else {
                        require(source == null && post.topicId == scope.forum?.topicId && post.number >= requireNotNull(scope.forum).startNumber && forumCoverage != null && post.postId in forumCoverage.loadedIds)
                        require(e.s("url") == "${ForumLimits.ORIGIN}/t/${post.topicId}/${post.number}" && e.s("id") == "forum_${post.topicId}_${post.postId}")
                    }
                    val paragraphs = e.a("paragraphs").map { it.jsonPrimitive.content }
                    require(e.s("title").length <= 1000 && paragraphs.size in 1..AssistantRules.MAX_PARAGRAPHS && paragraphs.all { it.length <= AssistantRules.MAX_PASSAGE } && e.s("version").matches(Regex("[a-f0-9]{64}")))
                    AssistantEvidence(e.s("id"), e.s("url"), source, e.s("title"), e.s("date"), e.n("at"), e.s("version"), paragraphs, e.b("offline"), e.b("abbreviated"), post)
                }
                val facts = t.a("facts").map { entry ->
                    val f = entry.jsonObject
                    AssistantFact(AssistantFactKind.valueOf(f.s("kind")), f.s("text"), f.a("references").map { c -> c.jsonObject.let { AssistantReference(it.s("id"), it.n("paragraph").toInt(), it.s("quote")) } })
                }
                val status = AssistantTurnStatus.valueOf(t.s("status"))
                if (status == AssistantTurnStatus.COMPLETE) AssistantRules.verify(facts, evidence) else require(facts.isEmpty() && evidence.isEmpty())
                val checklist = t.a("checklist").map { c -> c.jsonObject.let { AssistantChecklistItem(it.s("id"), it.s("text"), it.b("checked")) } }
                require(checklist.size <= 20 && checklist.all { it.text.length in 1..1200 && it.id.length in 1..80 } && checklist.distinctBy { it.id }.size == checklist.size)
                AssistantTurn(t.s("id"), t.s("question"), t.n("at"), status, facts, evidence, t.a("events").map { e -> e.jsonObject.let { AssistantToolEvent(it.s("tool"), it.n("results").toInt()) } }, t.s("model"), t.s("coverage"), checklist, forumCoverage)
            }
            require(turns.distinctBy { it.id }.size == turns.size)
            AssistantWorkspace(w.s("id"), w.s("name"), scope, w.n("createdAt"), turns, w.n("revision"))
        }
        require(result.distinctBy { it.id }.size == result.size)
        return AssistantLibrary(result)
    }
}
