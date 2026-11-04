package vn.edu.uit.mobile.domain

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CourseReadingTest {
    @Test fun `valid viewport and page-linked notes retain exact document identity`() {
        val record = ReadingFixtures.initial.copy(position = ReadingPosition(2, 2.5f, -.5f, .8f), notes = listOf(ReadingFixtures.note), bookmarks = setOf(1, 3))
        ReadingRules.record(record)
        assertEquals(2, record.notes.single().page)
        assertNotEquals(record.identity, record.identity.copy(version = "a".repeat(64)))
        assertNotEquals(record.identity, record.identity.copy(accountId = "other"))
    }

    @Test fun `invalid geometry pages and unbounded annotation input are rejected`() {
        val original = ReadingFixtures.initial
        val invalid = listOf(original.copy(position = ReadingPosition(0)), original.copy(position = ReadingPosition(4)), original.copy(position = ReadingPosition(zoom = Float.NaN)), original.copy(position = ReadingPosition(panY = 1.1f)), original.copy(bookmarks = setOf(4)), original.copy(notes = listOf(ReadingFixtures.note.copy(text = " "))), original.copy(notes = listOf(ReadingFixtures.note.copy(x = Float.POSITIVE_INFINITY))), original.copy(notes = listOf(ReadingFixtures.note.copy(text = "x".repeat(4001)))), original.copy(notes = listOf(ReadingFixtures.note, ReadingFixtures.note)))
        invalid.forEach { assertThrows(IllegalArgumentException::class.java) { ReadingRules.record(it) } }
    }

    @Test fun `collection bounds prohibit another owner duplicate versions and unlimited notes`() {
        val initial = ReadingFixtures.initial
        assertThrows(IllegalArgumentException::class.java) { ReadingRules.collection("other", listOf(initial)) }
        assertThrows(IllegalArgumentException::class.java) { ReadingRules.collection("owner", listOf(initial, initial)) }
        assertThrows(IllegalArgumentException::class.java) { ReadingRules.record(initial.copy(notes = (1..101).map { ReadingFixtures.note.copy(id = "note-$it") })) }
    }
}
