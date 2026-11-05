package vn.edu.uit.mobile.domain

import vn.edu.uit.mobile.model.*

/** Authored behavior fixture. Never export it as actual course review evidence. */
object PassageFixtures {
    val course = Course(1, "synthetic-owner", "IT004", "Synthetic database course")
    val spec = OfflineDownloadSpec(course, "section:1", "Week 1", CourseFile("transaction-notes.md", "https://courses.example.org/notes.md"))
    val source = PassageDocument(spec, "a".repeat(64), listOf(PassagePart(1, PassagePartKind.TEXT, "Transaction isolation preserves consistency.\nA rollback restores the previous state.")))
    val passages = PassageRules.chunks(source, PassageConfig())
    val index = PassageIndex(course.accountId, course.id, "synthetic-index", 1, PassageConfig(), listOf(PassageDocumentInfo(spec, source.version, false)), passages)
    val hit = PassageHit(passages.single(), index.documents.single(), 1.0)
}
