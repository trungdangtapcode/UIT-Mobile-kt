package vn.edu.uit.mobile.data

import android.content.Context
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.*
import vn.edu.uit.mobile.auth.*
import vn.edu.uit.mobile.common.*
import vn.edu.uit.mobile.daa.*
import vn.edu.uit.mobile.database.*
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.model.*
import vn.edu.uit.mobile.moodle.*
import vn.edu.uit.mobile.network.*

class VaultAccountRepository(private val vault: SessionStore, private val store: SnapshotStore, private val materials: File? = null) : AccountRepository {
    override val accounts: Flow<List<Account>> = vault.sessions.map { sessions -> sessions.map { it.account } }
    override suspend fun saveSessions(sessions: List<PortalSession>) {
        vault.save(sessions)
    }
    override suspend fun remove(accountId: String) {
        vault.remove(accountId)
        store.delete(accountId)
        materials?.let { root -> File(root, accountFolder(accountId)).deleteRecursively() }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class OfflinePortalRepository(
    private val vault: SessionStore,
    private val store: SnapshotStore,
    private val fileRoot: File,
    private val dispatchers: AppDispatchers = AppDispatchers(),
    private val transportFactory: (PortalSession) -> PortalTransport = { SafePortalTransport(it) }
) : PortalRepository {
    private val mutex = Mutex()
    private val state = MutableStateFlow(SyncState())
    override val syncState: StateFlow<SyncState> = state.asStateFlow()
    override val snapshot: Flow<PortalSnapshot> = vault.sessions.flatMapLatest { sessions ->
        if (sessions.isEmpty()) {
            flowOf(PortalSnapshot())
        } else {
            combine(sessions.map { store.observe(it.account.id) }) { entries ->
                PortalSnapshot(
                    courses = entries.flatMap { it.courses }, timetable = entries.flatMap { it.timetable }, notices = entries.flatMap { it.notices },
                    exams = entries.flatMap { it.exams }, events = entries.flatMap { it.events }, announcements = entries.flatMap { it.announcements }.sortedByDescending { it.createdAt },
                    transcript = entries.firstOrNull { it.transcript.semesters.isNotEmpty() }?.transcript ?: AcademicTranscript(),
                    profile = entries.firstOrNull { it.profile.studentId.isNotEmpty() }?.profile ?: StudentProfile(),
                    sections = entries.flatMap { it.sections.entries }.associate { it.toPair() }, assignments = entries.flatMap { it.assignments },
                    courseNews = entries.flatMap { it.courseNews }.sortedByDescending { it.created },
                    studentServices = entries.firstNotNullOfOrNull { it.studentServices },
                    portalLinked = sessions.any { it.account.portal == PortalType.UIT_PORTAL },
                    grades = entries.flatMap { it.grades.entries }.associate { it.toPair() }, updatedAt = entries.maxOfOrNull { it.updatedAt } ?: 0
                )
            }
        }
    }.distinctUntilChanged()
    override suspend fun refresh(): AppResult<Unit, AppError> = withContext(dispatchers.io) {
        mutex.withLock {
            state.value = SyncState(syncing = true)
            var error: AppError? = null
            try {
                for (session in vault.sessions.first()) {
                    transportFactory(session).use { transport ->
                        var cached = store.read(session.account.id)
                        suspend fun update(block: suspend () -> PortalSnapshot) {
                            when (val result = appResult(session.account.portal) { block() }) {
                                is AppResult.Success -> {
                                    cached = result.data.copy(updatedAt = Clock.System.now().epochSeconds)
                                    store.write(session.account.id, cached)
                                }
                                is AppResult.Failure -> {
                                    error = result.error
                                    if (result.error == AppError.SessionExpired) throw SessionExpiredException()
                                }
                            }
                        }
                        val result = appResult(session.account.portal) {
                            if (session.account.portal == PortalType.MOODLE) {
                                val api = MoodleGateway(transport)
                                update { cached.copy(courses = api.courses()) }
                                val date = today()
                                val month = LocalDate(date.year, date.monthNumber, 1)
                                val events = mutableListOf<CalendarEvent>()
                                var loaded = false
                                for (delta in 0..2) {
                                    val m = month.plus(delta, DateTimeUnit.MONTH)
                                    when (val calendar = appResult(PortalType.MOODLE) { api.calendar(m.year, m.monthNumber) }) {
                                        is AppResult.Success -> {
                                            loaded = true
                                            events += calendar.data
                                        }
                                        is AppResult.Failure -> {
                                            error = calendar.error
                                            if (calendar.error == AppError.SessionExpired) throw SessionExpiredException()
                                        }
                                    }
                                }
                                if (loaded) update { cached.copy(events = events.distinctBy { it.key }) }
                                // Limit expensive detail syncs to current courses and four concurrent calls.
                                val limit = kotlinx.coroutines.sync.Semaphore(4)
                                val details = coroutineScope {
                                    cached.courses.filter { it.classification !in setOf("past", "hidden", "future") && !it.hidden }.map { course ->
                                        async {
                                            limit.acquire()
                                            try {
                                                val gradeResult = appResult(PortalType.MOODLE) { api.grades(course.id) }
                                                val newsResult = appResult(PortalType.MOODLE) {
                                                    val sections = api.sections(course.id)
                                                    val news = sections.flatMap { it.modules }.filter { it.type == "forum" && Regex("thông báo|announcement|tin tức|news", RegexOption.IGNORE_CASE).containsMatchIn(it.name) }
                                                    news.flatMap { api.discussions(it) }
                                                }
                                                Triple(course, gradeResult, newsResult)
                                            } finally {
                                                limit.release()
                                            }
                                        }
                                    }.awaitAll()
                                }
                                val grades = cached.grades.toMutableMap()
                                var gradesLoaded = false
                                val news = mutableListOf<Discussion>()
                                var newsLoaded = false
                                for ((course, gradeResult, newsResult) in details) {
                                    when (gradeResult) {
                                        is AppResult.Success -> {
                                            gradesLoaded = true
                                            grades["${course.accountId}:${course.id}"] = gradeResult.data
                                        }
                                        is AppResult.Failure -> {
                                            if (gradeResult.error == AppError.SessionExpired) throw SessionExpiredException()
                                            error = gradeResult.error
                                        }
                                    }
                                    when (newsResult) {
                                        is AppResult.Success -> {
                                            newsLoaded = true
                                            news += newsResult.data
                                        }
                                        is AppResult.Failure -> {
                                            if (newsResult.error == AppError.SessionExpired) throw SessionExpiredException()
                                            error = newsResult.error
                                        }
                                    }
                                }
                                if (gradesLoaded || newsLoaded) update { cached.copy(grades = grades, courseNews = if (newsLoaded) news.distinctBy { it.id } else cached.courseNews) }
                            } else if (session.account.portal == PortalType.UIT_PORTAL) {
                                val api = UitPortalClient(transport)
                                update { cached.copy(timetable = api.timetable()) }
                                update { cached.copy(exams = api.exams()) }
                                update { cached.copy(profile = api.profile()) }
                                update { cached.copy(transcript = api.transcript()) }
                            } else {
                                val api = DaaClient(transport)
                                update { cached.copy(timetable = api.timetable()) }
                                update { cached.copy(exams = api.exams()) }
                                update { cached.copy(notices = api.changes()) }
                                update { cached.copy(announcements = api.announcements()) }
                                update { cached.copy(profile = api.profile()) }
                                update { cached.copy(transcript = api.transcript()) }
                            }
                        }
                        if (result is AppResult.Failure) error = result.error
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = AppError.NetworkOffline
            } finally {
                state.value = SyncState(error = error)
            }
            error?.let { AppResult.Failure(it) } ?: AppResult.Success(Unit)
        }
    }
    override suspend fun refreshStudentServices(): AppResult<Unit, AppError> = withContext(dispatchers.io) {
        mutex.withLock {
            val session = vault.sessions.first().firstOrNull { it.account.portal == PortalType.UIT_PORTAL }
                ?: return@withLock AppResult.Failure(AppError.SessionExpired)
            var cached = store.read(session.account.id)
            var services = cached.studentServices ?: StudentServicesSnapshot()
            var firstError: AppError? = null
            val issues = services.issues.associateBy { it.key }.toMutableMap()
            suspend fun persist() {
                services = services.copy(issues = issues.values.toList())
                cached = cached.copy(studentServices = services)
                store.write(session.account.id, cached)
            }
            suspend fun attempt(key: String, read: suspend () -> StudentServicesSnapshot): Boolean = when (val result = appResult(PortalType.UIT_PORTAL) { read() }) {
                is AppResult.Success -> {
                    services = result.data
                    issues.remove(key)
                    persist()
                    true
                }
                is AppResult.Failure -> {
                    firstError = firstError ?: result.error
                    val failure = when (val error = result.error) {
                        AppError.SessionExpired -> ServiceFailureKind.SIGN_IN
                        AppError.NetworkOffline -> ServiceFailureKind.NETWORK
                        is AppError.PortalUnavailable -> if (error.code == "invalidresponse") ServiceFailureKind.INVALID_RESPONSE else ServiceFailureKind.UNAVAILABLE
                        else -> ServiceFailureKind.UNAVAILABLE
                    }
                    issues[key] = ServiceIssue(key, failure)
                    persist()
                    failure != ServiceFailureKind.SIGN_IN
                }
            }
            when (
                val result = appResult(PortalType.UIT_PORTAL) {
                    transportFactory(session).use { transport ->
                        val api = StudentServicesClient(transport)
                        if (!attempt("tuition") { services.copy(tuition = api.tuition().copy(fetchedAt = Clock.System.now().epochSeconds)) }) return@use
                        for (kind in ProcedureKind.entries) {
                            if (!attempt(kind.name) {
                                    val section = api.procedure(kind).copy(fetchedAt = Clock.System.now().epochSeconds)
                                    services.copy(procedures = services.procedures.filterNot { it.kind == kind } + section)
                                }
                            ) {
                                return@use
                            }
                        }
                        attempt("graduation") { services.copy(graduation = api.graduation().copy(fetchedAt = Clock.System.now().epochSeconds)) }
                    }
                }
            ) {
                is AppResult.Failure -> firstError = result.error
                else -> Unit
            }
            firstError?.let { AppResult.Failure(it) } ?: AppResult.Success(Unit)
        }
    }
    override suspend fun calendar(year: Int, month: Int): AppResult<Unit, AppError> = appResult(PortalType.MOODLE) {
        require(year in 1970..9998 && month in 1..12)
        val first = LocalDate(year, month, 1)
        val start = dateAt(first, LocalTime(0, 0))
        val end = dateAt(first.plus(1, DateTimeUnit.MONTH), LocalTime(0, 0))
        for (session in vault.sessions.first().filter { it.account.portal == PortalType.MOODLE }) {
            transportFactory(session).use { transport ->
                val events = MoodleGateway(transport).calendar(year, month)
                mutex.withLock {
                    val old = store.read(session.account.id)
                    store.write(session.account.id, old.copy(events = (old.events.filterNot { it.start >= start && it.start < end } + events).distinctBy { it.key }))
                }
            }
        }
    }
    private suspend fun <T> withCourse(course: Course, block: suspend (MoodleGateway, PortalTransport) -> T): AppResult<T, AppError> = appResult(PortalType.MOODLE) {
        withContext(dispatchers.io) {
            val session = vault.sessions.first().firstOrNull { it.account.id == course.accountId } ?: throw SessionExpiredException()
            transportFactory(session).use { block(MoodleGateway(it), it) }
        }
    }
    private suspend fun <T> withActive(block: suspend (MoodleGateway) -> T): AppResult<T, AppError> = appResult(PortalType.MOODLE) {
        withContext(dispatchers.io) {
            val session = vault.sessions.first().firstOrNull { it.account.portal == PortalType.MOODLE } ?: throw SessionExpiredException()
            transportFactory(session).use { block(MoodleGateway(it)) }
        }
    }
    override suspend fun courseDetails(course: Course): AppResult<List<CourseSection>, AppError> = withCourse(course) { api, _ ->
        val sections = api.sections(course.id)
        mutex.withLock {
            val old = store.read(course.accountId)
            store.write(course.accountId, old.copy(sections = old.sections + ("${course.accountId}:${course.id}" to sections)))
        }
        sections
    }
    override suspend fun assignments(course: Course): AppResult<List<Assignment>, AppError> = withCourse(course) { api, _ ->
        val assignments = api.assignments(course.id)
        mutex.withLock {
            val old = store.read(course.accountId)
            store.write(course.accountId, old.copy(assignments = old.assignments.filterNot { it.courseId == course.id } + assignments))
        }
        assignments
    }
    override suspend fun submission(course: Course, assignment: Assignment) = withCourse(course) { api, _ ->
        val status = api.submission(assignment)
        mutex.withLock {
            val old = store.read(course.accountId)
            store.write(
                course.accountId,
                old.copy(
                    events = old.events.map { event ->
                        if (event.courseId == course.id && event.assignmentId == assignment.id) event.copy(submitted = status.submitted, needsAction = !status.submitted) else event
                    }
                )
            )
        }
        status
    }
    override suspend fun submitFile(course: Course, assignment: Assignment, name: String, bytes: ByteArray, confirmed: Boolean) = withCourse(course) { api, _ -> api.submitFile(assignment, name, bytes, confirmed) }
    override suspend fun discussions(course: Course, module: CourseModule) = withCourse(course) { api, _ -> api.discussions(module) }
    override suspend fun reply(course: Course, postId: Long, message: String) = withCourse(course) { api, _ -> api.reply(postId, message) }
    override suspend fun grades(course: Course) = withCourse(course) { api, _ ->
        val grades = api.grades(course.id)
        mutex.withLock {
            val old = store.read(course.accountId)
            store.write(course.accountId, old.copy(grades = old.grades + ("${course.accountId}:${course.id}" to grades)))
        }
        grades
    }
    override suspend fun participants(course: Course) = withCourse(course) { api, _ -> api.participants(course.id) }
    override suspend fun notifications() = withActive { it.notifications() }
    override suspend fun markRead(id: Long?) = withActive { it.markRead(id) }
    override suspend fun conversations() = withActive { it.conversations() }
    override suspend fun messages(conversationId: Long) = withActive { it.messages(conversationId) }
    override suspend fun sendMessage(conversationId: Long, text: String) = withActive { it.sendMessage(conversationId, text) }
    override suspend fun download(course: Course, file: CourseFile) = withCourse(course) { _, transport ->
        val key = accountFolder(course.accountId)
        val filename = file.name.substringAfterLast('/').substringAfterLast('\\').replace(Regex("[^\\p{L}\\p{N}._ -]"), "_").takeIf { it.isNotBlank() && it !in setOf(".", "..") } ?: "resource"
        val folder = File(fileRoot, "$key/${course.id}")
        val urlKey = MessageDigest.getInstance("SHA-256").digest(file.url.toByteArray()).take(6).joinToString("") { "%02x".format(it) }
        val destination = File(folder, "$urlKey-$filename")
        val checksum = File(destination.path + ".sha256")
        if (destination.isFile && checksum.isFile) {
            val digest = MessageDigest.getInstance("SHA-256")
            destination.inputStream().use { input ->
                val buffer = ByteArray(65536)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (actual == checksum.readText()) return@withCourse DownloadedFile(destination.path, actual)
        }
        val downloaded = transport.download(file.url, destination)
        checksum.writeText(downloaded.sha256)
        downloaded
    }
    override suspend fun inspectH5p(course: Course, file: CourseFile) = withCourse(course) { _, transport -> H5pPackageReader().read(transport.request(file.url)) }
}
class UitGraph(context: Context) {
    private val app = context.applicationContext
    val database = UitDatabase.create(app)
    val store: SnapshotStore = RoomSnapshotStore(database)
    val vault = EncryptedSessionVault(app)
    val accounts: AccountRepository = VaultAccountRepository(vault, store, File(app.filesDir, "materials"))
    val portal: PortalRepository = OfflinePortalRepository(vault, store, File(app.filesDir, "materials"))
    companion object {
        @Volatile private var instance: UitGraph? = null
        fun get(context: Context): UitGraph = instance ?: synchronized(this) { instance ?: UitGraph(context).also { instance = it } }
    }
}

private fun accountFolder(accountId: String): String = MessageDigest.getInstance("SHA-256").digest(accountId.toByteArray()).take(8).joinToString("") { "%02x".format(it) }
