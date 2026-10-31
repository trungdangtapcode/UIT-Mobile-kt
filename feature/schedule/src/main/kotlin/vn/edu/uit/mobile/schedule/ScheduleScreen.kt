package vn.edu.uit.mobile.schedule

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.datetime.*
import vn.edu.uit.mobile.common.*
import vn.edu.uit.mobile.designsystem.*
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.model.*

@Immutable data class ScheduleState(val snapshot: PortalSnapshot = PortalSnapshot(), val anchor: LocalDate = today(), val monthly: Boolean = false, val focusRequest: Int = 0, val calendarExportBusy: Boolean = false, val calendarExportMessage: String? = null)
sealed interface ScheduleIntent {
    data class Move(val amount: Int) : ScheduleIntent
    data object Toggle : ScheduleIntent
    data class Semester(val date: LocalDate) : ScheduleIntent
    data class Date(val date: LocalDate) : ScheduleIntent
    data class FocusDate(val date: LocalDate) : ScheduleIntent
}
class ScheduleViewModel(private val repository: PortalRepository, accounts: AccountRepository) : BaseMviViewModel<ScheduleState, ScheduleIntent, Unit>(ScheduleState()) {
    val freeTimePlanner = FreeTimePlanner()
    init {
        viewModelScope.launch {
            repository.snapshot.collect {
                state.value = state.value.copy(snapshot = it)
            }
        }
        viewModelScope.launch { accounts.accounts.collect { freeTimePlanner.onAccounts(it.map { account -> account.id }.toSet()) } }
    }
    private var pendingCalendarExport: CalendarExportPlan? = null
    fun prepareCalendarExport(plan: CalendarExportPlan): String? {
        if (state.value.calendarExportBusy) return null
        return try {
            check(plan.entries == CalendarFileExport().plan(state.value.snapshot, plan.options).entries) { "The saved schedule changed. Review the updated preview and try again." }
            check(plan.entries.isNotEmpty()) { "There are no events to export." }
            pendingCalendarExport = plan
            state.value = state.value.copy(calendarExportBusy = true, calendarExportMessage = null)
            plan.fileName
        } catch (e: Exception) {
            state.value = state.value.copy(calendarExportMessage = "The saved schedule changed or this range cannot be exported. Review the preview and try a shorter range.")
            null
        }
    }
    fun calendarPickerUnavailable() {
        pendingCalendarExport = null
        state.value = state.value.copy(calendarExportBusy = false, calendarExportMessage = "No document picker is available on this device.")
    }
    fun completeCalendarExport(context: Context, uri: Uri?) = viewModelScope.launch {
        val plan = pendingCalendarExport
        pendingCalendarExport = null
        if (uri == null) {
            state.value = state.value.copy(calendarExportBusy = false, calendarExportMessage = "Export canceled. No events were written.")
            return@launch
        }
        try {
            check(plan != null) { "The export was interrupted. Review the selection and save again." }
            check(plan.entries == CalendarFileExport().plan(state.value.snapshot, plan.options).entries) { "The saved schedule changed. Review the updated preview and save again." }
            withContext(Dispatchers.IO) {
                val bytes = CalendarFileExport().render(plan, Clock.System.now().epochSeconds).toByteArray(Charsets.UTF_8)
                val stream = context.applicationContext.contentResolver.openOutputStream(uri, "wt") ?: error("No output stream")
                stream.use { it.write(bytes) }
            }
            state.value = state.value.copy(calendarExportMessage = "Calendar file saved. Import it with your calendar app; it will not update automatically.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            state.value = state.value.copy(calendarExportMessage = "Calendar file was not saved. The selection may have changed or the destination is unavailable. Review and try again.")
        } finally {
            state.value = state.value.copy(calendarExportBusy = false)
        }
    }
    override fun onIntent(intent: ScheduleIntent) {
        state.value = when (intent) {
            is ScheduleIntent.Move -> state.value.copy(anchor = state.value.anchor.plus(intent.amount, if (state.value.monthly) DateTimeUnit.MONTH else DateTimeUnit.WEEK))
            ScheduleIntent.Toggle -> state.value.copy(monthly = !state.value.monthly)
            is ScheduleIntent.Date -> state.value.copy(anchor = intent.date)
            is ScheduleIntent.Semester -> state.value.copy(anchor = intent.date)
            is ScheduleIntent.FocusDate -> state.value.copy(anchor = intent.date, monthly = false, focusRequest = state.value.focusRequest + 1)
        }
        if (state.value.monthly) viewModelScope.launch { repository.calendar(state.value.anchor.year, state.value.anchor.monthNumber) }
    }
}

@Composable fun ScheduleScreen(viewModel: ScheduleViewModel, onCourse: (Course) -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var exporting by rememberSaveable { mutableStateOf(false) }
    var finding by rememberSaveable { mutableStateOf(false) }
    val freeTime by viewModel.freeTimePlanner.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current.applicationContext
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/calendar")) { uri -> viewModel.completeCalendarExport(context, uri) }
    if (exporting) {
        CalendarExportContent(state.snapshot, state.anchor, state.calendarExportBusy, state.calendarExportMessage, { exporting = false }) { plan ->
            viewModel.prepareCalendarExport(plan)?.let { fileName ->
                try {
                    picker.launch(fileName)
                } catch (_: android.content.ActivityNotFoundException) {
                    viewModel.calendarPickerUnavailable()
                }
            }
        }
    } else if (finding) {
        val planner = viewModel.freeTimePlanner
        val planningSession = freeTime.session
        FreeTimeContent(state.snapshot, freeTime, {
            planner.close()
            finding = false
        }, planner::date, planner::start, planner::end, planner::minimum, planner::buffer, { title, start, end -> planner.addForSession(planningSession, title, start, end) }, planner::remove)
    } else {
        ScheduleContent(state, viewModel::onIntent, onCourse, { exporting = true }, {
            viewModel.freeTimePlanner.open(state.anchor)
            finding = true
        })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleContent(state: ScheduleState, onIntent: (ScheduleIntent) -> Unit = {}, onCourse: (Course) -> Unit = {}, onExport: (() -> Unit)? = null, onFreeTime: (() -> Unit)? = null) {
    var selected by remember { mutableStateOf<ScheduleEntry?>(null) }
    var semesters by remember { mutableStateOf(false) }
    var filter by rememberSaveable { mutableStateOf("All events") }
    var mode by rememberSaveable { mutableStateOf("Day") }
    LaunchedEffect(state.focusRequest) {
        if (state.focusRequest > 0) {
            mode = "Day"
            filter = "All events"
            selected = null
        }
    }
    val week = state.anchor.minus(state.anchor.dayOfWeek.isoDayNumber - 1, DateTimeUnit.DAY)
    val month = LocalDate(state.anchor.year, state.anchor.monthNumber, 1)
    val first = when (mode) {
        "Month" -> month
        "Week" -> week
        else -> state.anchor
    }
    val last = when (mode) {
        "Month" -> month.plus(1, DateTimeUnit.MONTH).minus(1, DateTimeUnit.DAY)
        "Week" -> week.plus(6, DateTimeUnit.DAY)
        else -> first
    }
    val all = remember(state.snapshot, first, last) { GetUnifiedScheduleUseCase()(state.snapshot, first, last) }
    val entries = all.filter {
        when (filter) {
            "Classes" -> it.kind in setOf(ScheduleKind.CLASS, ScheduleKind.CANCELED, ScheduleKind.MAKE_UP)
            "Exams" -> it.kind == ScheduleKind.EXAM
            "Deadlines" -> it.kind == ScheduleKind.DEADLINE
            else -> true
        }
    }
    LazyColumn(Modifier.fillMaxSize().testTag("schedule-content"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { PageHeading("Class schedule", "Classes, rooms and schedule changes.") }
                if (onExport != null) IconButton(onExport) { Icon(Icons.Outlined.SaveAlt, "Export calendar file") }
            }
            if (onFreeTime != null) AssistChip(onClick = onFreeTime, shape = TechnicalShapes.Control, label = { Text("Find free time") }, leadingIcon = { Icon(Icons.Outlined.EventAvailable, null, Modifier.size(18.dp)) })
        }
        item {
            Panel {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("${state.anchor.month.name.lowercase().replaceFirstChar(Char::uppercase)} ${state.anchor.year}", style = MaterialTheme.typography.titleLarge)
                        Text("${entries.size} events · GMT+07", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { if (mode == "Day") onIntent(ScheduleIntent.Date(state.anchor.minus(1, DateTimeUnit.DAY))) else onIntent(ScheduleIntent.Move(-1)) }) { Icon(Icons.Outlined.ChevronLeft, "Previous ${mode.lowercase()}") }
                    IconButton(onClick = { if (mode == "Day") onIntent(ScheduleIntent.Date(state.anchor.plus(1, DateTimeUnit.DAY))) else onIntent(ScheduleIntent.Move(1)) }) { Icon(Icons.Outlined.ChevronRight, "Next ${mode.lowercase()}") }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
                        listOf("Day", "Week", "Month").forEach { label ->
                            SegmentedButton(selected = mode == label, onClick = {
                                mode = label
                                if ((label == "Month") != state.monthly) onIntent(ScheduleIntent.Toggle)
                            }, shape = TechnicalShapes.Control, label = { Text(label) })
                        }
                    }
                    TextButton(shape = TechnicalShapes.Control, onClick = { onIntent(ScheduleIntent.Date(today())) }) { Text("Today") }
                }
                if (mode == "Day") {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        repeat(7) { offset ->
                            val date = week.plus(offset, DateTimeUnit.DAY)
                            val active = date == state.anchor
                            Column(
                                Modifier.weight(1f).clip(TechnicalShapes.Control).background(if (active) MaterialTheme.colorScheme.primary else Color.Transparent)
                                    .clickable { onIntent(ScheduleIntent.Date(date)) }.padding(vertical = 10.dp).semantics {
                                        this.selected = active
                                        contentDescription = "Show schedule for $date"
                                    },
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(date.dayOfWeek.name.take(2), style = MaterialTheme.typography.labelSmall, color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("${date.dayOfMonth}", style = MaterialTheme.typography.titleMedium, color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box {
                    AssistChip(shape = TechnicalShapes.Control, onClick = { semesters = true }, label = { Text("Semester") }, leadingIcon = { Icon(Icons.Outlined.Tune, "Select semester", Modifier.size(16.dp)) })
                    DropdownMenu(semesters, { semesters = false }) {
                        state.snapshot.timetable.map { it.startDate }.distinct().sortedDescending().forEach { date ->
                            DropdownMenuItem(text = { Text("Semester from $date") }, onClick = {
                                onIntent(ScheduleIntent.Semester(LocalDate.parse(date)))
                                semesters = false
                            })
                        }
                        if (state.snapshot.timetable.isEmpty()) DropdownMenuItem(text = { Text("Sync DAA to load semesters") }, onClick = { semesters = false }, enabled = false)
                    }
                }
                listOf("All events", "Classes", "Exams", "Deadlines").forEach { label -> FilterChip(shape = TechnicalShapes.Control, selected = filter == label, onClick = { filter = label }, label = { Text(label) }) }
            }
        }
        if (mode == "Month") {
            item {
                MonthCalendar(month, entries, state.anchor) { date ->
                    onIntent(ScheduleIntent.Date(date))
                    if (state.monthly) onIntent(ScheduleIntent.Toggle)
                    mode = "Day"
                }
            }
        }
        if (entries.isEmpty()) item { EmptyState("No scheduled events", "No events match this ${mode.lowercase()}. Try another date or filter.") }
        entries.groupBy { dateText(it.start) }.forEach { (date, rows) ->
            item(key = "date:$date") { SectionHeading(friendlyDate(LocalDate.parse(date)), if (mode == "Week") "${rows.size} events" else null) }
            rows.forEach { entry -> item(key = entry.key) { ScheduleCard(entry) { selected = entry } } }
        }
    }
    selected?.let { entry ->
        ModalBottomSheet(shape = TechnicalShapes.Dialog, onDismissRequest = { selected = null }) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                StatusPill(scheduleLabel(entry.kind), scheduleColor(entry.kind), true)
                Text(entry.title, style = MaterialTheme.typography.headlineMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    IconTile(Icons.Outlined.Schedule, scheduleColor(entry.kind))
                    Column {
                        Text(friendlyDate(Instant.fromEpochSeconds(entry.start).toLocalDateTime(UitTimeZone).date), style = MaterialTheme.typography.titleSmall)
                        Text("${clockText(entry.start)}${if (entry.end > entry.start) "–${clockText(entry.end)}" else ""} · Vietnam time", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (entry.classCode.isNotBlank()) DetailLine(Icons.Outlined.Class, "Class", entry.classCode)
                if (entry.room.isNotBlank()) DetailLine(Icons.Outlined.LocationOn, "Room", entry.room)
                state.snapshot.timetable.firstOrNull { it.classCode == entry.classCode }?.lecturerName?.takeIf(String::isNotBlank)?.let { DetailLine(Icons.Outlined.PersonOutline, "Lecturer", it) }
                val notices = state.snapshot.notices.filter { it.classCode == entry.classCode }
                if (notices.isNotEmpty()) {
                    SectionHeading("Schedule updates")
                    notices.forEach { InlineNotice("${it.title} · ${it.targetDate}", it.type == ScheduleChangeType.CANCELED) }
                }
                entry.course?.let { course ->
                    Button(onClick = {
                        selected = null
                        onCourse(course)
                    }, modifier = Modifier.fillMaxWidth(), shape = TechnicalShapes.Control) {
                        Text("Open course workspace")
                        Spacer(Modifier.width(8.dp))
                        Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(18.dp))
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable private fun DetailLine(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, Modifier.width(64.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleSmall)
    }
}

@Composable private fun MonthCalendar(month: LocalDate, entries: List<ScheduleEntry>, selected: LocalDate, onDate: (LocalDate) -> Unit) {
    val offset = month.dayOfWeek.isoDayNumber - 1
    val days = month.plus(1, DateTimeUnit.MONTH).toEpochDays() - month.toEpochDays()
    Panel {
        Row(Modifier.fillMaxWidth()) { listOf("M", "T", "W", "T", "F", "S", "S").forEach { Text(it, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center) } }
        repeat((offset + days + 6) / 7) { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(7) { column ->
                    val index = row * 7 + column - offset
                    if (index !in 0 until days) {
                        Spacer(Modifier.weight(1f).height(54.dp))
                    } else {
                        val date = month.plus(index, DateTimeUnit.DAY)
                        val events = entries.filter { dateText(it.start) == date.toString() }
                        Column(
                            Modifier.weight(1f).height(54.dp).clip(TechnicalShapes.Field).background(if (date == selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                                .clickable { onDate(date) }.semantics { contentDescription = "$date, ${events.size} events" },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text("${date.dayOfMonth}", style = MaterialTheme.typography.titleSmall, color = if (date == today()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                            Row(Modifier.height(10.dp).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) { events.take(3).forEach { Box(Modifier.size(4.dp).background(scheduleColor(it.kind), CircleShape)) } }
                        }
                    }
                }
            }
        }
    }
}
