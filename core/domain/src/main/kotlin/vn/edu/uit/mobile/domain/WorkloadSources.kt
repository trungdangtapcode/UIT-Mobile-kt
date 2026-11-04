package vn.edu.uit.mobile.domain

import java.net.URI
import java.net.URLDecoder
import java.util.Locale
import kotlinx.datetime.*
import vn.edu.uit.mobile.common.*
import vn.edu.uit.mobile.model.*

data class WorkloadItem(val ref: WorkloadRef, val title: String, val due: Long, val officialSubmitted: Boolean? = null, val conflict: Boolean = false, val end: Long = due)

/** Canonical identities never depend on titles, except where an upstream service supplies no ID. */
class WorkloadSourceIndex {
    fun items(source: WorkloadAccount): List<WorkloadItem> {
        val owner = source.account
        val snapshot = source.snapshot
        if (owner.portal != PortalType.MOODLE) {
            return snapshot.exams.groupBy { "exam:${it.classCode.trim().uppercase(Locale.ROOT)}:${it.examDate}:${it.examShift}:${UitLessonTimeCalculator.examStart(it)}" }.map { (target, exams) ->
                val starts = exams.map { dateAt(LocalDate.parse(it.examDate), UitLessonTimeCalculator.examStart(it)) }.distinct()
                val names = exams.map { it.courseName }.filter(String::isNotBlank).distinct().sorted()
                val title = names.firstOrNull() ?: exams.map { it.classCode }.filter(String::isNotBlank).sorted().firstOrNull() ?: target
                WorkloadItem(WorkloadRef(owner.id, WorkloadKind.EXAM, target), title.take(240), starts.min(), conflict = starts.size > 1 || names.size > 1)
            }
        }
        val assignments = snapshot.assignments.filter { it.accountId == owner.id && it.id > 0 }.groupBy { it.id }
        val modules = assignments.values.flatten().groupBy { it.cmid }.mapValues { (_, rows) -> rows.map { it.id }.distinct().singleOrNull() }
        val events = snapshot.events.filter { it.accountId == owner.id && it.deadline && it.start > 0 }
        val grouped = linkedMapOf<WorkloadRef, MutableList<CalendarEvent>>()
        val ambiguous = mutableSetOf<WorkloadRef>()
        for (event in events.sortedBy { it.key }) {
            val byUrl = assignmentFromUrl(owner, event.url, modules)
            val explicit = event.assignmentId?.takeIf { it > 0 }
            val conflict = explicit != null && byUrl != null && explicit != byUrl
            val id = if (conflict) null else explicit ?: byUrl
            val ref = if (id != null) WorkloadRef(owner.id, WorkloadKind.ASSIGNMENT, "assignment:$id") else WorkloadRef(owner.id, WorkloadKind.DEADLINE, "event:${event.key}")
            grouped.getOrPut(ref) { mutableListOf() } += event
            if (conflict) ambiguous += ref
        }
        assignments.keys.sorted().forEach { grouped.getOrPut(WorkloadRef(owner.id, WorkloadKind.ASSIGNMENT, "assignment:$it")) { mutableListOf() } }
        return grouped.mapNotNull { (ref, calendar) ->
            val records = if (ref.kind == WorkloadKind.ASSIGNMENT) assignments[ref.target.substringAfter(':').toLong()].orEmpty() else emptyList()
            val authoritative = records.map { it.due }.filter { it > 0 }.distinct()
            val observed = calendar.map { it.start }.distinct()
            val due = (authoritative.ifEmpty { observed }).minOrNull() ?: return@mapNotNull null
            val states = calendar.mapNotNull { it.submitted }.distinct()
            val submitted = states.singleOrNull()
            val title = (records.map { it.name }.sorted().firstOrNull() ?: calendar.map { it.name }.sorted().first()).take(240).ifBlank { ref.target }
            WorkloadItem(ref, title, due, submitted, authoritative.size > 1 || observed.any { it != due } || states.size > 1 || ref in ambiguous)
        }.sortedWith(compareBy({ it.due }, { it.ref.id }))
    }
    private fun assignmentFromUrl(owner: Account, value: String, modules: Map<Long, Long?>): Long? = runCatching {
        val base = URI(owner.baseUrl)
        val uri = base.resolve(value)
        if (!uri.scheme.equals("https", true) || uri.host?.equals(base.host, true) != true || (if (uri.port < 0) 443 else uri.port) != (if (base.port < 0) 443 else base.port) || uri.userInfo != null || uri.path != base.path.trimEnd('/') + "/mod/assign/view.php") return null
        val queryIds = uri.rawQuery.orEmpty().split('&').filter { it.substringBefore('=') == "id" }
        if (queryIds.size != 1) return null
        val ids = queryIds.mapNotNull { URLDecoder.decode(it.substringAfter('=', ""), "UTF-8").toLongOrNull() }
        if (ids.size != 1) return null
        modules[ids.single()]
    }.getOrNull()
}
