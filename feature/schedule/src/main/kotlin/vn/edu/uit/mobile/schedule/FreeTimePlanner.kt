package vn.edu.uit.mobile.schedule

import java.util.UUID
import kotlinx.coroutines.flow.*
import kotlinx.datetime.*
import vn.edu.uit.mobile.common.*
import vn.edu.uit.mobile.domain.*

data class FreeTimeState(
    val date: String = today().toString(),
    val start: String = "08:00",
    val end: String = "18:00",
    val minimum: String = "60",
    val buffer: String = "15",
    val personal: List<PersonalTimeBlock> = emptyList(),
    val message: String? = null,
    val session: Long = 0
) {
    fun options(): FreeTimeOptions {
        val day = parsePlannerDate(date)
        val from = parsePlannerTime(start)
        val through = parsePlannerTime(end)
        val duration = minimum.toIntOrNull()
        val travel = buffer.toIntOrNull()
        require(duration != null && travel != null) { "Enter whole minutes for the minimum duration and buffer." }
        return FreeTimeOptions(day, from, through, duration, travel)
    }
}

fun parsePlannerDate(value: String): LocalDate {
    require(value.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}"))) { "Enter a valid date as YYYY-MM-DD." }
    return requireNotNull(runCatching { LocalDate.parse(value) }.getOrNull()) { "Enter a valid date as YYYY-MM-DD." }
}

fun parsePlannerTime(value: String): LocalTime {
    require(value.matches(Regex("[0-9]{2}:[0-9]{2}"))) { "Enter times as HH:mm, for example 08:00." }
    return requireNotNull(runCatching { LocalTime.parse(value) }.getOrNull()) { "Enter valid 24-hour times as HH:mm." }
}

/** Owned by ScheduleViewModel. Draft blocks stay in memory until this planner closes. */
class FreeTimePlanner {
    private val state = MutableStateFlow(FreeTimeState())
    val uiState = state.asStateFlow()
    private var owners: Set<String>? = null
    private var session = 0L
    fun onAccounts(ids: Set<String>) {
        if (owners != null && owners != ids) state.value = FreeTimeState(date = state.value.date, message = "Accounts changed. Personal planning drafts were cleared.", session = ++session)
        owners = ids.toSet()
    }
    fun open(date: LocalDate) {
        state.value = FreeTimeState(date = date.toString(), session = ++session)
    }
    fun close() {
        state.value = FreeTimeState(session = ++session)
    }
    fun date(value: String) {
        state.value = state.value.copy(date = value.take(10), message = null)
    }
    fun start(value: String) {
        state.value = state.value.copy(start = value.take(5), message = null)
    }
    fun end(value: String) {
        state.value = state.value.copy(end = value.take(5), message = null)
    }
    fun minimum(value: String) {
        state.value = state.value.copy(minimum = value.take(3), message = null)
    }
    fun buffer(value: String) {
        state.value = state.value.copy(buffer = value.take(3), message = null)
    }
    fun remove(id: String) {
        state.value = state.value.copy(personal = state.value.personal.filterNot { it.id == id }, message = null)
    }
    fun add(title: String, start: String, end: String): String? = addForSession(state.value.session, title, start, end)
    fun addForSession(expectedSession: Long, title: String, start: String, end: String): String? = try {
        require(expectedSession == state.value.session) { "The planning session changed. Close this form and try again." }
        require(state.value.personal.size < 10) { "Remove a personal block before adding another. The limit is ten." }
        require(title.isNotBlank() && title.length <= 80) { "Enter a title of one to 80 characters." }
        val day = parsePlannerDate(state.value.date)
        val from = parsePlannerTime(start)
        val through = parsePlannerTime(end)
        require(from < through) { "The personal block must end after its start on the same day." }
        val block = PersonalTimeBlock(UUID.randomUUID().toString(), title.trim(), dateAt(day, from), dateAt(day, through))
        state.value = state.value.copy(personal = state.value.personal + block, message = null)
        null
    } catch (e: IllegalArgumentException) {
        e.message ?: "Check the personal block's title and times."
    }
}
