package vn.edu.uit.mobile.courses

import androidx.compose.runtime.Immutable
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import vn.edu.uit.mobile.common.*
import vn.edu.uit.mobile.designsystem.*
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.model.*

@Immutable data class CoursesState(
    val snapshot: PortalSnapshot = PortalSnapshot(),
    val selected: Course? = null,
    val sections: List<CourseSection> = emptyList(),
    val assignments: List<Assignment> = emptyList(),
    val status: Map<Long, SubmissionStatus> = emptyMap(),
    val discussions: List<Discussion> = emptyList(),
    val grades: List<GradeItem> = emptyList(),
    val participants: List<Participant> = emptyList(),
    val tab: Int = 0,
    val loading: Boolean = false,
    val filter: String = "all",
    val query: String = "",
    val error: AppError? = null,
    val h5p: H5pSummary? = null
)
sealed interface CoursesIntent {
    data class Select(val course: Course) : CoursesIntent
    data object Back : CoursesIntent
    data class Tab(val index: Int) : CoursesIntent
    data class Query(val text: String) : CoursesIntent
    data class Filter(val value: String) : CoursesIntent
    data class Forum(val module: CourseModule) : CoursesIntent
    data class Reply(val postId: Long, val message: String) : CoursesIntent
    data class Download(val file: CourseFile) : CoursesIntent
    data class Inspect(val file: CourseFile) : CoursesIntent
    data class Submit(val assignment: Assignment, val name: String, val bytes: ByteArray, val confirmed: Boolean) : CoursesIntent
    data object DismissH5p : CoursesIntent
}
sealed interface CoursesEffect {
    data class Message(val text: String) : CoursesEffect
    data class OpenFile(val path: String) : CoursesEffect
}
class CoursesViewModel(private val repository: PortalRepository) : BaseMviViewModel<CoursesState, CoursesIntent, CoursesEffect>(CoursesState()) {
    private var selection: Job? = null
    init {
        viewModelScope.launch { repository.snapshot.collect { state.value = state.value.copy(snapshot = it) } }
    }
    override fun onIntent(intent: CoursesIntent) {
        when (intent) {
            is CoursesIntent.Query -> state.value = state.value.copy(query = intent.text)
            is CoursesIntent.Filter -> state.value = state.value.copy(filter = intent.value)
            is CoursesIntent.Select -> {
                selection?.cancel()
                val key = "${intent.course.accountId}:${intent.course.id}"
                state.value = CoursesState(
                    snapshot = state.value.snapshot,
                    selected = intent.course,
                    sections = state.value.snapshot.sections[key].orEmpty(),
                    assignments = state.value.snapshot.assignments.filter { it.courseId == intent.course.id && it.accountId == intent.course.accountId },
                    grades = state.value.snapshot.grades[key].orEmpty(),
                    loading = true
                )
                selection = viewModelScope.launch {
                    result(repository.courseDetails(intent.course)) { state.value = state.value.copy(sections = it) }
                    state.value = state.value.copy(loading = false)
                }
            }
            CoursesIntent.Back -> {
                selection?.cancel()
                state.value = CoursesState(snapshot = state.value.snapshot)
            }
            is CoursesIntent.Tab -> {
                state.value = state.value.copy(tab = intent.index)
                val course = state.value.selected ?: return
                selection?.cancel()
                selection = viewModelScope.launch {
                    state.value = state.value.copy(loading = true, error = null)
                    when (intent.index) {
                        0 -> result(repository.courseDetails(course)) { state.value = state.value.copy(sections = it) }
                        1 -> result(repository.assignments(course)) { assignments ->
                            state.value = state.value.copy(assignments = assignments)
                            for (assignment in assignments) result(repository.submission(course, assignment)) { status -> state.value = state.value.copy(status = state.value.status + (assignment.id to status)) }
                        }
                        2 -> {
                            val forums = state.value.sections.flatMap { it.modules }.filter { it.type == "forum" }
                            val news = forums.filter { Regex("thông báo|announcement|tin tức|news", RegexOption.IGNORE_CASE).containsMatchIn(it.name) }.ifEmpty { forums }
                            val posts = mutableListOf<Discussion>()
                            for (module in news) result(repository.discussions(course, module)) { posts += it }
                            state.value = state.value.copy(discussions = posts.sortedByDescending { it.created })
                        }
                        3 -> {
                            result(repository.grades(course)) { state.value = state.value.copy(grades = it) }
                            result(repository.participants(course)) { state.value = state.value.copy(participants = it) }
                        }
                    }
                    state.value = state.value.copy(loading = false)
                }
            }
            CoursesIntent.DismissH5p -> state.value = state.value.copy(h5p = null)
            else -> viewModelScope.launch {
                val course = state.value.selected ?: return@launch
                when (intent) {
                    is CoursesIntent.Forum -> result(repository.discussions(course, intent.module)) { state.value = state.value.copy(tab = 2, discussions = it) }
                    is CoursesIntent.Reply -> result(repository.reply(course, intent.postId, intent.message)) { effects.send(CoursesEffect.Message("Reply sent")) }
                    is CoursesIntent.Download -> result(repository.download(course, intent.file)) {
                        effects.send(CoursesEffect.Message("Saved ${intent.file.name}"))
                        effects.send(CoursesEffect.OpenFile(it.path))
                    }
                    is CoursesIntent.Inspect -> result(repository.inspectH5p(course, intent.file)) { state.value = state.value.copy(h5p = it) }
                    is CoursesIntent.Submit -> {
                        state.value = state.value.copy(loading = true)
                        result(SubmitAssignmentUseCase(repository)(course, intent.assignment, intent.name, intent.bytes, intent.confirmed)) {
                            effects.send(CoursesEffect.Message("File saved to your submission. Check Moodle for any final submit step."))
                            result(repository.submission(course, intent.assignment)) { status -> state.value = state.value.copy(status = state.value.status + (intent.assignment.id to status)) }
                        }
                        state.value = state.value.copy(loading = false)
                    }
                    else -> Unit
                }
            }
        }
    }
    private suspend fun <T> result(result: AppResult<T, AppError>, success: suspend (T) -> Unit) {
        when (result) {
            is AppResult.Success -> success(result.data)
            is AppResult.Failure -> {
                state.value = state.value.copy(error = result.error)
                effects.send(CoursesEffect.Message(result.error.text()))
            }
        }
    }
}
