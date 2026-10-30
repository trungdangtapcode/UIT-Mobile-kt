package vn.edu.uit.mobile.courses

import android.text.Html
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.model.*

data class StudyState(
    val course: Course? = null,
    val assignment: Assignment? = null,
    val sources: List<StudySource> = emptyList(),
    val selected: Set<String> = emptySet(),
    val connection: StudyConnection = StudyConnection(),
    val question: String = "",
    val language: String = "English",
    val busy: Boolean = false,
    val answer: StudyAnswer? = null,
    val answeredQuestion: String = "",
    val tasks: List<StudyTask> = emptyList(),
    val dirty: Boolean = false,
    val message: String? = null
)

class StudyViewModel(private val ai: StudyAiRepository, portal: PortalRepository) : ViewModel() {
    private val state = MutableStateFlow(StudyState())
    val uiState = state.asStateFlow()
    private var operation: Job? = null
    private var epoch = 0
    init {
        viewModelScope.launch {
            portal.snapshot.collect { snapshot ->
                val course = state.value.course
                if (course != null && snapshot.courses.none { it.id == course.id && it.accountId == course.accountId }) close()
            }
        }
    }
    fun open(course: Course, sections: List<CourseSection>, assignments: List<Assignment>, assignment: Assignment? = null) {
        close()
        val sources = buildList {
            val records = if (assignment == null) assignments else listOf(assignment)
            records.filter { it.accountId == course.accountId && it.courseId == course.id }.forEach { a -> source("assignment:${a.id}", a.name, a.intro)?.let(::add) }
            if (assignment == null) sections.flatMap { it.modules }.filter { it.courseId == course.id }.forEach { m -> source("module:${m.id}", m.name, m.description)?.let(::add) }
        }.distinctBy { it.id }
        state.value = StudyState(course, assignment, sources, if (assignment != null) sources.filter { it.text.length <= 12000 }.take(1).map { it.id }.toSet() else emptySet())
        launchOperation {
            val connection = ai.connection()
            val tasks = assignment?.let { ai.tasks(course, it.id) }.orEmpty()
            currentCoroutineContext().ensureActive()
            state.value = state.value.copy(connection = connection, tasks = tasks)
        }
    }
    private fun source(id: String, title: String, html: String): StudySource? {
        val text = Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY).toString().trim()
        return text.takeIf { it.isNotBlank() }?.let { StudySource(id, title.take(200), it) }
    }
    fun close() {
        epoch++
        operation?.cancel()
        state.value = StudyState()
    }
    fun cancel() {
        epoch++
        operation?.cancel()
        state.value = state.value.copy(busy = false, message = "Request canceled. The AI service may already have processed it.")
    }
    fun select(id: String) {
        if (state.value.busy) return
        val selected = state.value.selected
        val source = state.value.sources.firstOrNull { it.id == id } ?: return
        if (id !in selected && (source.text.length > 12000 || selected.size >= 5)) return
        state.value = state.value.copy(selected = if (id in selected) selected - id else selected + id, answer = null, message = null)
    }
    fun question(value: String) {
        if (!state.value.busy) state.value = state.value.copy(question = value.take(1200))
    }
    fun language(value: String) {
        if (!state.value.busy && value in setOf("English", "Vietnamese")) state.value = state.value.copy(language = value, answer = null)
    }
    fun addExcerpt(title: String, text: String) {
        if (state.value.busy) return
        if (title.isBlank() || title.length > 200 || text.isBlank() || text.length > 12000) {
            state.value = state.value.copy(message = "Add a title and one to 12,000 characters of text.")
            return
        }
        val id = "excerpt:${System.nanoTime()}"
        state.value = state.value.copy(sources = state.value.sources + StudySource(id, title, text), answer = null)
    }
    fun configure(mode: StudyConnectionMode, endpoint: String, secret: String, model: String) = launchOperation {
        if (mode == StudyConnectionMode.DIRECT) ai.configureDirect(secret, model) else ai.configure(endpoint, secret)
        currentCoroutineContext().ensureActive()
        state.value = state.value.copy(connection = ai.connection(), answer = null, message = "Connection saved on this device.")
    }
    fun disconnect() = launchOperation {
        ai.disconnect()
        currentCoroutineContext().ensureActive()
        state.value = state.value.copy(connection = StudyConnection(), answer = null, message = "AI connection cleared. Your saved checklist remains on this device.")
    }
    fun generate() {
        val current = state.value
        val course = current.course ?: return
        val mode = if (current.assignment == null) "ask" else "analyze"
        val sources = current.sources.filter { it.id in current.selected }
        try {
            check(current.connection.configured) { "Configure Direct OpenAI or an optional gateway before generating an answer." }
            StudyAiRules.validate(sources, mode, current.question)
        } catch (e: IllegalArgumentException) {
            state.value = current.copy(message = e.message)
            return
        } catch (e: IllegalStateException) {
            state.value = current.copy(message = e.message)
            return
        }
        launchOperation {
            val answer = ai.generate(course, mode, sources, current.question, current.language)
            currentCoroutineContext().ensureActive()
            StudyAiRules.verify(answer, sources, mode)
            state.value = state.value.copy(answer = answer, answeredQuestion = current.question)
        }
    }
    fun addTask(text: String) {
        val current = state.value
        if (current.busy || current.assignment == null || text.isBlank() || current.tasks.size >= 30 || current.tasks.any { it.text == text }) return
        state.value = current.copy(tasks = current.tasks + StudyTask(text.take(1500)), dirty = true)
    }
    fun task(index: Int, text: String? = null, checked: Boolean? = null) {
        if (state.value.busy) return
        state.value = state.value.copy(tasks = state.value.tasks.mapIndexed { i, t -> if (i == index) t.copy(text = text?.take(1500) ?: t.text, checked = checked ?: t.checked) else t }, dirty = true)
    }
    fun removeTask(index: Int) {
        if (!state.value.busy) state.value = state.value.copy(tasks = state.value.tasks.filterIndexed { i, _ -> i != index }, dirty = true)
    }
    fun moveTask(index: Int, direction: Int) {
        val tasks = state.value.tasks.toMutableList()
        val target = index + direction
        if (state.value.busy || index !in tasks.indices || target !in tasks.indices) return
        val item = tasks.removeAt(index)
        tasks.add(target, item)
        state.value = state.value.copy(tasks = tasks, dirty = true)
    }
    fun saveTasks() {
        val current = state.value
        val course = current.course ?: return
        val assignment = current.assignment ?: return
        launchOperation {
            ai.saveTasks(course, assignment.id, current.tasks)
            currentCoroutineContext().ensureActive()
            state.value = state.value.copy(dirty = false, message = "Personal checklist saved. Moodle submission status is unchanged.")
        }
    }
    private fun launchOperation(block: suspend () -> Unit) {
        operation?.cancel()
        val expected = ++epoch
        state.value = state.value.copy(busy = true, message = null)
        operation = viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (epoch == expected) state.value = state.value.copy(message = if (e is IllegalStateException || e is IllegalArgumentException) e.message?.take(240) ?: "Study action failed. Try again." else "Study action failed. Check the AI connection and try again.")
            } finally {
                if (epoch == expected) state.value = state.value.copy(busy = false)
            }
        }
    }
}
