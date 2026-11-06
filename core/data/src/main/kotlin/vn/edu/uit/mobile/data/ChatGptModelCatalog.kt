package vn.edu.uit.mobile.data

import kotlinx.serialization.json.*
import vn.edu.uit.mobile.domain.*

/** The plan catalog can contain large capability descriptions irrelevant to the picker. */
internal object ChatGptModelCatalog {
    const val MAX_BYTES = 4 * 1024 * 1024

    fun parse(result: JsonObject): List<ChatGptModel> {
        val catalog = result["models"] as? JsonArray ?: throw ChatGptException(
            ChatGptProblem.RESPONSE,
            ChatGptDiagnostic(200, "unexpected_model_catalog", bodyShape = if (result["data"] is JsonArray) "api-model-list" else "other-json", operation = "models")
        )
        val visible = catalog.mapNotNull { it as? JsonObject }.filter { string(it, "visibility") == "list" }
        val models = visible.mapNotNull { entry ->
            val slug = string(entry, "slug") ?: return@mapNotNull null
            if (slug.length !in 1..100 || !slug.all { it.code in 33..126 }) return@mapNotNull null
            ChatGptModel(slug, string(entry, "display_name")?.takeIf { it.isNotBlank() }?.take(120) ?: slug)
        }.distinctBy { it.id }.take(100)
        if (models.isEmpty() && visible.isNotEmpty()) {
            throw ChatGptException(
                ChatGptProblem.RESPONSE,
                ChatGptDiagnostic(200, "invalid_model_entries", bodyShape = "models-array", operation = "models")
            )
        }
        return models
    }

    private fun string(item: JsonObject, key: String): String? = (item[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
}
