package vn.edu.uit.mobile.data

import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.model.*

class ReadingRepositoryTest {
    private class Storage : StudyStorage {
        val values = mutableMapOf<String, String>()
        var fail = false
        var committedFailure = false
        var afterWrite: (() -> Unit)? = null
        override fun read(key: String) = values[key]
        override fun write(key: String, value: String) {
            check(!fail)
            values[key] = value
            afterWrite?.invoke()
            check(!committedFailure)
        }
        override fun remove(key: String) {
            check(!fail)
            values.remove(key)
        }
        override fun removeAccount(accountId: String) {
            values.keys.filter { it.startsWith(bookmarkDigest(accountId) + ":") }.forEach(values::remove)
        }
    }
    private class Owners : AccountRepository {
        override val accounts = MutableStateFlow(listOf("owner", "other").map { Account(it, "https://example.invalid", 1, "Synthetic", PortalType.MOODLE) })
        override suspend fun remove(accountId: String) = error("University mutation forbidden")
        override suspend fun saveSessions(sessions: List<PortalSession>) = error("University mutation forbidden")
    }

    @Test fun positionsBookmarksAndNotesRestoreWithoutOriginalBytesOrAccountFieldsInExport() = runTest {
        val storage = Storage()
        val owners = Owners()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = LocalReadingRepository(storage, owners, dispatcher)
        val original = ReadingFixtures.initial
        val next = original.copy(position = ReadingPosition(2, 2f, .2f, -.6f), bookmarks = setOf(1, 2), notes = listOf(ReadingFixtures.note))
        val saved = repo.save(original, next)
        assertEquals(saved, LocalReadingRepository(storage, owners, dispatcher).load(original.identity, original.title, original.courseId, original.pageCount))
        val json = repo.export(saved)
        val root = Json.parseToJsonElement(json).jsonObject
        assertEquals("uit-course-reading", root.getValue("format").jsonPrimitive.content)
        assertFalse(listOf("accountId", "documentId", "example.invalid", "%PDF-").any { it in json })
        assertEquals(ReadingFixtures.note.text, root.getValue("document").jsonObject.getValue("notes").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content)
    }

    @Test fun staleEditsDeleteAndExportCannotOverwriteAnotherSavedChange() = runTest {
        val storage = Storage()
        val owners = Owners()
        val repo = LocalReadingRepository(storage, owners, StandardTestDispatcher(testScheduler))
        val first = repo.save(ReadingFixtures.initial, ReadingFixtures.initial.copy(bookmarks = setOf(1)))
        val next = repo.save(first, first.copy(bookmarks = setOf(1, 2)))
        assertTrue(runCatching { repo.save(first, first.copy(notes = listOf(ReadingFixtures.note))) }.isFailure)
        assertTrue(runCatching { repo.remove(first) }.isFailure)
        assertTrue(runCatching { repo.export(first) }.isFailure)
        assertEquals(next, repo.load(first.identity, first.title, 1, 3))
    }

    @Test fun uncertainCommittedWriteCanRetryTheSameChangeWithoutDuplicateNotes() = runTest {
        val storage = Storage()
        val repo = LocalReadingRepository(storage, Owners(), StandardTestDispatcher(testScheduler))
        val next = ReadingFixtures.initial.copy(notes = listOf(ReadingFixtures.note))
        storage.committedFailure = true
        assertTrue(runCatching { repo.save(ReadingFixtures.initial, next) }.isFailure)
        storage.committedFailure = false
        val saved = repo.save(ReadingFixtures.initial, next)
        assertEquals(1, saved.notes.size)
        assertEquals(1L, saved.revision)
    }

    @Test fun removedOwnerCannotReceiveLateWritesOrReadExportAndOtherOwnerSurvives() = runTest {
        val storage = Storage()
        val owners = Owners()
        val repo = LocalReadingRepository(storage, owners, StandardTestDispatcher(testScheduler))
        val other = ReadingFixtures.initial.copy(identity = ReadingFixtures.identity.copy(accountId = "other"))
        repo.save(other, other)
        storage.afterWrite = { owners.accounts.value = owners.accounts.value.filterNot { it.id == "owner" } }
        assertTrue(runCatching { repo.save(ReadingFixtures.initial, ReadingFixtures.initial.copy(notes = listOf(ReadingFixtures.note))) }.isFailure)
        assertEquals(1, storage.values.size)
        assertTrue(runCatching { repo.export(ReadingFixtures.initial) }.isFailure)
        assertEquals("other", repo.collections.first().single().accountId)
    }

    @Test fun documentVersionsKeepNotesSeparateAndDeletionKeepsOtherVersions() = runTest {
        val repo = LocalReadingRepository(Storage(), Owners(), StandardTestDispatcher(testScheduler))
        val first = repo.save(ReadingFixtures.initial, ReadingFixtures.initial.copy(notes = listOf(ReadingFixtures.note)))
        val newer = repo.load(first.identity.copy(version = "b".repeat(64)), first.title, 1, 3)
        assertTrue(newer.notes.isEmpty())
        val saved = repo.save(newer, newer.copy(bookmarks = setOf(3)))
        repo.remove(first)
        assertEquals(listOf(saved), repo.collections.first().single { it.accountId == "owner" }.records)
        assertTrue(runCatching { repo.save(first, first.copy(bookmarks = setOf(1))) }.isFailure)
    }

    @Test fun corruptOwnerBucketRemainsUntouchedWhileAnotherOwnerCanRead() = runTest {
        val storage = Storage()
        val owners = Owners()
        val repo = LocalReadingRepository(storage, owners, StandardTestDispatcher(testScheduler))
        val other = ReadingFixtures.initial.copy(identity = ReadingFixtures.identity.copy(accountId = "other"))
        repo.save(other, other)
        val key = bookmarkDigest("owner") + ":reading"
        storage.values[key] = "corrupt-data"
        val collections = repo.collections.first()
        assertTrue(collections.single { it.accountId == "owner" }.failed)
        assertEquals(1, collections.single { it.accountId == "other" }.records.size)
        assertTrue(runCatching { repo.save(ReadingFixtures.initial, ReadingFixtures.initial) }.isFailure)
        assertEquals("corrupt-data", storage.values[key])
    }

    @Test fun capacityFailureIsTypedBeforeWritingAndExistingRecordsRemainIntact() = runTest {
        val storage = Storage()
        val repo = LocalReadingRepository(storage, Owners(), StandardTestDispatcher(testScheduler))
        repeat(50) { index ->
            val record = ReadingFixtures.initial.copy(identity = ReadingFixtures.identity.copy(documentId = index.toString(16).padStart(64, '0')))
            repo.save(record, record)
        }
        val prior = storage.values.toMap()
        assertTrue(runCatching { repo.save(ReadingFixtures.initial, ReadingFixtures.initial) }.exceptionOrNull() is ReadingLimitException)
        assertEquals(prior, storage.values)
    }

    @Test fun editingNotesInDifferentPageOrderKeepsStorageEqualityForLaterWrites() = runTest {
        val repo = LocalReadingRepository(Storage(), Owners(), StandardTestDispatcher(testScheduler))
        val notes = listOf(ReadingFixtures.note.copy(id = "later", page = 3), ReadingFixtures.note.copy(id = "earlier", page = 1))
        val first = repo.save(ReadingFixtures.initial, ReadingFixtures.initial.copy(notes = notes))
        assertEquals(first, repo.load(first.identity, first.title, 1, 3))
        val edited = repo.save(first, first.copy(notes = first.notes.reversed()))
        assertEquals(edited, repo.load(first.identity, first.title, 1, 3))
    }

    @Test fun invalidNumericTypesDuplicateBookmarksAndRecordLimitsCannotEnterStorage() = runTest {
        val json = ReadingJson.encode("owner", listOf(ReadingFixtures.initial))
        assertTrue(runCatching { ReadingJson.decode(json.replace("\"pageCount\":3", "\"pageCount\":\"3\""), "owner") }.isFailure)
        assertTrue(runCatching { ReadingJson.decode(json.replace("\"bookmarks\":[]", "\"bookmarks\":[1,1]"), "owner") }.isFailure)
        val storage = Storage()
        val repo = LocalReadingRepository(storage, Owners(), StandardTestDispatcher(testScheduler))
        assertTrue(runCatching { repo.save(ReadingFixtures.initial, ReadingFixtures.initial.copy(notes = listOf(ReadingFixtures.note.copy(page = 99)))) }.isFailure)
        assertTrue(storage.values.isEmpty())
    }
}
