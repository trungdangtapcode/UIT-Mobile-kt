package vn.edu.uit.mobile.moodle

import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import vn.edu.uit.mobile.common.*
import vn.edu.uit.mobile.model.*
import vn.edu.uit.mobile.network.*

interface MoodleApiClient {
    suspend fun call(method: String, args: Map<String, JsonElement> = emptyMap()): JsonElement
}
class AjaxMoodleApiClient(private val transport: PortalTransport) : MoodleApiClient {
    override suspend fun call(method: String, args: Map<String, JsonElement>): JsonElement {
        if (method == "core_enrol_get_users_courses") return discover(args)
        return raw(method, args)
    }
    suspend fun raw(method: String, args: Map<String, JsonElement> = emptyMap()): JsonElement {
        val bytes = transport.request("/lib/ajax/service.php?sesskey=${encode(transport.session.sesskey)}", HttpMethod.Post) {
            contentType(ContentType.Application.Json)
            setBody(MoodleAjaxHelper.buildAjaxInfo(method, args))
        }
        return MoodleAjaxHelper.unwrapAjaxResponse(parseJson(bytes))
    }
    private suspend fun discover(args: Map<String, JsonElement>): JsonElement = coroutineScope {
        var supported = false
        val results = (listOf<String?>(null) + listOf("allincludinghidden", "all", "inprogress", "past", "future", "hidden")).map { bucket ->
            async {
                try {
                    if (bucket == null) {
                        raw("core_enrol_get_users_courses", args).list()
                    } else {
                        val entries = mutableListOf<JsonElement>()
                        var offset = 0
                        repeat(1000) {
                            val page = raw("core_course_get_enrolled_courses_by_timeline_classification", obj("classification" to bucket, "limit" to 100, "offset" to offset))
                            val courses = page.list("courses")
                            entries += courses.map { item -> JsonObject(item.jsonObject + ("classification" to JsonPrimitive(bucket))) }
                            val next = page.str("nextoffset").toIntOrNull() ?: -1
                            if (courses.isEmpty() || next < 0 || next <= offset) return@async entries
                            offset = next
                        }
                        throw InvalidResponseException("Course pagination exceeded limit")
                    }
                } catch (e: PortalException) {
                    if (!MoodleErrorClassifier.isUnavailableSessionMethod(e)) throw e
                    null
                }
            }
        }.awaitAll()
        val merged = linkedMapOf<Long, JsonObject>()
        for (batch in results) {
            if (batch == null) continue
            supported = true
            for (course in batch) {
                val id = course.long("id")
                if (id <= 0) continue
                val old = merged[id]
                val combined = if (old == null) course.jsonObject else JsonObject(course.jsonObject + old.filterValues { it != JsonNull && it != JsonPrimitive("") })
                merged[id] = if (course.str("classification") in setOf("inprogress", "past", "future", "hidden")) JsonObject(combined + ("classification" to course.field("classification"))) else combined
            }
        }
        if (!supported) throw PortalException("servicenotavailable", "Course discovery is unavailable")
        JsonArray(merged.values.toList())
    }
}
class ResilientMoodleApiClient(private val primary: MoodleApiClient, private val fallback: MoodleApiClient) : MoodleApiClient {
    override suspend fun call(method: String, args: Map<String, JsonElement>): JsonElement = try {
        primary.call(method, args)
    } catch (e: PortalException) {
        if (!MoodleErrorClassifier.isUnavailableSessionMethod(e)) throw e
        fallback.call(method, args)
    }
}
