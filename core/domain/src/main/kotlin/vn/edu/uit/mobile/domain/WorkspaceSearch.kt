package vn.edu.uit.mobile.domain

import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import vn.edu.uit.mobile.model.*

enum class WorkspaceSearchKind { COURSES, MATERIALS, ASSIGNMENTS, NOTICES }
data class WorkspaceSearchHit(
    val key: String,
    val kind: WorkspaceSearchKind,
    val accountId: String,
    val title: String,
    val context: String,
    val course: Course? = null,
    val module: CourseModule? = null,
    val file: CourseFile? = null,
    val assignment: Assignment? = null,
    val announcement: DaaAnnouncement? = null,
    val notice: ClassScheduleChangeNotice? = null
)

class SearchWorkspaceUseCase {
    fun index(snapshot: PortalSnapshot, accounts: List<Account>): List<WorkspaceSearchHit> = buildList {
        val owners = accounts.filter { it.portal == PortalType.MOODLE }.map { it.id }.toSet()
        snapshot.courses.filter { it.accountId in owners }.distinctBy { it.accountId to it.id }.forEach { course ->
            val prefix = "${course.accountId}:${course.id}"
            add(WorkspaceSearchHit("course:$prefix", WorkspaceSearchKind.COURSES, course.accountId, course.fullname, course.shortname, course))
            snapshot.sections[prefix].orEmpty().forEach { section ->
                section.modules.filter { it.courseId == course.id }.forEach { module ->
                    val context = "${course.shortname} · ${section.name}"
                    add(WorkspaceSearchHit("module:$prefix:${module.id}", WorkspaceSearchKind.MATERIALS, course.accountId, module.name, context, course, module))
                    module.files.forEach { file ->
                        add(WorkspaceSearchHit("file:$prefix:${module.id}:${digest(file.url)}", WorkspaceSearchKind.MATERIALS, course.accountId, file.name, "$context · ${module.name}", course, module, file))
                    }
                }
            }
            snapshot.assignments.filter { it.accountId == course.accountId && it.courseId == course.id }.forEach { assignment ->
                add(WorkspaceSearchHit("assignment:$prefix:${assignment.id}", WorkspaceSearchKind.ASSIGNMENTS, course.accountId, assignment.name, course.shortname, course, assignment = assignment))
            }
        }
        val daaOwners = accounts.filter { it.portal == PortalType.DAA }.map { it.id }.toSet()
        snapshot.daaSearchSources.filter { it.accountId in daaOwners }.forEach { source ->
            val ownerId = source.accountId
            source.announcements.forEach { notice -> add(WorkspaceSearchHit("announcement:$ownerId:${notice.nodeId}", WorkspaceSearchKind.NOTICES, ownerId, notice.title, "DAA announcement", announcement = notice)) }
            source.notices.forEach { notice -> add(WorkspaceSearchHit("notice:$ownerId:${notice.nodeId}:${notice.classCode}:${notice.targetDate}:${notice.lessonStart}:${notice.lessonEnd}", WorkspaceSearchKind.NOTICES, ownerId, notice.title, "${notice.classCode} · ${notice.targetDate}", notice = notice)) }
        }
    }.distinctBy { it.key }

    operator fun invoke(hits: List<WorkspaceSearchHit>, query: String): List<WorkspaceSearchHit> {
        val words = normalize(query.take(120)).split(' ').filter(String::isNotBlank)
        if (words.isEmpty()) return emptyList()
        return hits.filter { hit ->
            val text = normalize("${hit.title} ${hit.context}")
            val code = normalize(hit.course?.shortname.orEmpty()).replace(" ", "")
            words.all { it in text || (code.isNotEmpty() && it in code) }
        }.sortedWith(compareBy({ it.kind.ordinal }, { normalize(it.title) }, { it.key }))
    }
    private fun normalize(value: String) = Normalizer.normalize(value, Normalizer.Form.NFD).lowercase(Locale.ROOT).replace('đ', 'd').replace(Regex("\\p{M}+"), "").replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
