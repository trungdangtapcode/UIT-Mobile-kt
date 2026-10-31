package vn.edu.uit.mobile.domain

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import vn.edu.uit.mobile.model.*

class WorkspaceSearchTest {
    private val course = Course(1, "one", "IT004.O21", "Cơ sở dữ liệu")
    private val accounts = listOf(Account("one", "https://courses.example.org", 1, "Synthetic", PortalType.MOODLE), Account("daa", "https://daa.example.org", 1, "Synthetic", PortalType.DAA))
    private val file = CourseFile("Database notes.pdf", "https://courses.example.org/pluginfile.php/1/notes.pdf")
    private val snapshot = PortalSnapshot(courses = listOf(course), sections = mapOf("one:1" to listOf(CourseSection(1, "Week 1", listOf(CourseModule(2, 1, "Database lesson", "resource", "", files = listOf(file)))))), assignments = listOf(Assignment(3, 1, 4, "Database project", accountId = "one")), daaSearchSources = listOf(DaaSearchSource("daa", listOf(DaaAnnouncement(5, "Database room update", "", 1, "Saved body")), emptyList())))
    private val search = SearchWorkspaceUseCase()

    @Test fun `metadata groups include exact owned references`() {
        val hits = search.index(snapshot, accounts)
        assertEquals(WorkspaceSearchKind.entries.toSet(), search(hits, "database").map { it.kind }.toSet() + search(hits, "co so du lieu").map { it.kind }.toSet())
        assertEquals(file, search(hits, "notes").single().file)
        assertEquals(3L, search(hits, "project").single().assignment?.id)
        assertEquals(5L, search(hits, "room").single().announcement?.nodeId)
    }

    @Test fun `Vietnamese accents case and compact course codes normalize consistently`() {
        val hits = search.index(snapshot, accounts)
        assertEquals(course, search(hits, "CO SO DU LIEU").single().course)
        assertTrue(search(hits, "it004o21").isNotEmpty())
        assertTrue(search(hits, "IT004.O21 project").single().assignment != null)
        assertTrue(search(hits, "notes missing").isEmpty())
        assertTrue(search(hits, "  ").isEmpty())
    }

    @Test fun `removed accounts orphan metadata and ambiguous notice owners are excluded`() {
        assertTrue(search.index(snapshot, emptyList()).isEmpty())
        assertTrue(search.index(snapshot.copy(courses = emptyList()), accounts).none { it.course != null })
        val duplicate = accounts + accounts.last().copy(id = "other-daa")
        assertTrue(search.index(snapshot, duplicate).filter { it.kind == WorkspaceSearchKind.NOTICES }.all { it.accountId == "daa" })
    }

    @Test fun `stale merged notices are never reassigned after removing their owner`() {
        val ownerB = accounts.last().copy(id = "other-daa")
        assertTrue(search.index(snapshot, listOf(ownerB)).none { it.kind == WorkspaceSearchKind.NOTICES })
        assertTrue(search.index(snapshot.copy(daaSearchSources = emptyList(), announcements = listOf(DaaAnnouncement(1, "Unowned", "", 1, ""))), accounts).none { it.kind == WorkspaceSearchKind.NOTICES })
    }

    @Test fun `same course IDs across accounts cannot leak another accounts assignments`() {
        val other = course.copy(accountId = "other", fullname = "Other account")
        val source = snapshot.copy(courses = listOf(course, other), assignments = snapshot.assignments + snapshot.assignments.single().copy(accountId = "other", name = "Private other project"))
        assertTrue(search.index(source, accounts).none { it.title.contains("Private") || it.course?.accountId == "other" })
    }

    @Test fun `description text and profile records are outside the search index`() {
        val source = snapshot.copy(profile = StudentProfile(fullName = "Private profile"), assignments = snapshot.assignments.map { it.copy(intro = "Body-only term") })
        val hits = search.index(source, accounts)
        assertTrue(search(hits, "body-only").isEmpty())
        assertTrue(search(hits, "private profile").isEmpty())
        assertTrue(search.index(PortalSnapshot(updatedAt = 1), accounts).isEmpty())
    }
}
