package vn.edu.uit.mobile.dashboard

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.datetime.*
import vn.edu.uit.mobile.common.*
import vn.edu.uit.mobile.designsystem.*
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.model.*

@Immutable data class DashboardState(val snapshot: PortalSnapshot = PortalSnapshot(), val sync: SyncState = SyncState())
sealed interface DashboardIntent {
    data object Refresh : DashboardIntent
}
class DashboardViewModel(private val repository: PortalRepository) : BaseMviViewModel<DashboardState, DashboardIntent, String>(DashboardState()) {
    init {
        viewModelScope.launch { combine(repository.snapshot, repository.syncState, ::DashboardState).collect { state.value = it } }
    }
    override fun onIntent(intent: DashboardIntent) {
        viewModelScope.launch { repository.refresh() }
    }
}

@Composable
fun DashboardScreen(viewModel: DashboardViewModel, onCourse: (Course) -> Unit, onSchedule: () -> Unit = {}, onInbox: () -> Unit = {}, onConnect: () -> Unit = {}) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    DashboardContent(state, onRefresh = { viewModel.onIntent(DashboardIntent.Refresh) }, onCourse = onCourse, onSchedule = onSchedule, onInbox = onInbox, onConnect = onConnect)
}

@Composable
fun DashboardContent(
    state: DashboardState,
    now: Long = Clock.System.now().epochSeconds,
    onRefresh: () -> Unit = {},
    onCourse: (Course) -> Unit = {},
    onSchedule: () -> Unit = {},
    onInbox: () -> Unit = {},
    onConnect: () -> Unit = {}
) {
    val snapshot = state.snapshot
    val today = Instant.fromEpochSeconds(now).toLocalDateTime(UitTimeZone).date
    var selectedDay by rememberSaveable(today.toString()) { mutableStateOf(today.toString()) }
    val date = LocalDate.parse(selectedDay)
    val monday = today.minus(today.dayOfWeek.isoDayNumber - 1, DateTimeUnit.DAY)
    val week = remember(snapshot, monday) { GetUnifiedScheduleUseCase()(snapshot, monday, monday.plus(6, DateTimeUnit.DAY)) }
    // Calendar submissions have their own queue. Assignment-only dates stay visible
    // in the agenda because their submission status is not supplied by the calendar.
    val entries = week.filter { dateText(it.start) == selectedDay && (it.kind != ScheduleKind.DEADLINE || it.key.startsWith("assignment:")) }
    val deadlines = snapshot.events.filter { it.deadline && it.needsAction && it.start <= now + 7 * 86400 }.sortedBy { it.start }
    val connected = snapshot.updatedAt > 0
    var detail by remember { mutableStateOf<ScheduleEntry?>(null) }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().testTag("dashboard-content"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(date.dayOfWeek.name.lowercase().replaceFirstChar(Char::uppercase), style = MaterialTheme.typography.headlineMedium)
                        Text("${date.dayOfMonth} ${date.month.name.lowercase().replaceFirstChar(Char::uppercase)} ${date.year}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = onRefresh, enabled = !state.sync.syncing) { Icon(Icons.Outlined.Sync, "Refresh workspace", Modifier.size(22.dp)) }
                }
            }
            if (!connected) {
                item {
                    Panel(Modifier.fillMaxWidth(), spacing = 16.dp) {
                        Text("Connect your UIT account", style = MaterialTheme.typography.headlineSmall)
                        Text("Sign in to see your timetable, Moodle materials and DAA academic record.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(shape = TechnicalShapes.Control, onClick = onConnect) { Text("Connect UIT account") }
                        Text("Your credentials are entered on the UIT sign-in screen.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                item { TeachingWeek(monday, date, today, week) { selectedDay = it.toString() } }
                item { DailyAgenda(entries, now, onCourse, onSchedule) { detail = it } }
                item { SubmissionQueue(deadlines, snapshot, now, onInbox) }
                item { ScheduleChanges(snapshot) }
                item { CourseIndex(snapshot.courses, onCourse) }
                item { Announcements(snapshot) }
            }
            item { PortalFooter(snapshot, onConnect) }
        }
    }
    detail?.let { entry ->
        AlertDialog(
            onDismissRequest = { detail = null },
            title = { Text(entry.title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(scheduleLabel(entry.kind), color = if (entry.kind == ScheduleKind.CANCELED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                    Text("${dateText(entry.start)} · ${clockText(entry.start)}${if (entry.end > entry.start) "–${clockText(entry.end)}" else ""}")
                    if (entry.classCode.isNotBlank()) Text(entry.classCode)
                    if (entry.room.isNotBlank()) Text("Room ${entry.room}")
                }
            },
            confirmButton = { TextButton(shape = TechnicalShapes.Control, onClick = { detail = null }) { Text("Done") } }
        )
    }
}

@Composable private fun TeachingWeek(monday: LocalDate, selected: LocalDate, today: LocalDate, entries: List<ScheduleEntry>, onSelect: (LocalDate) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth()) {
            repeat(7) { offset ->
                val date = monday.plus(offset, DateTimeUnit.DAY)
                val active = date == selected
                val hasClasses = entries.any { dateText(it.start) == date.toString() && it.kind != ScheduleKind.DEADLINE && it.kind != ScheduleKind.CANCELED }
                Column(
                    Modifier.weight(1f).selectable(active, role = Role.Tab, onClick = { onSelect(date) })
                        .semantics { contentDescription = "Show timetable for $date" }
                        .padding(top = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Text(date.dayOfWeek.name.take(3).lowercase().replaceFirstChar(Char::uppercase), style = MaterialTheme.typography.bodySmall, color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${date.dayOfMonth}", style = MaterialTheme.typography.titleLarge.copy(fontFamily = CampusMono), color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    Text(
                        if (date == today) {
                            "Today"
                        } else if (hasClasses) {
                            "·"
                        } else {
                            ""
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.heightIn(min = 16.dp)
                    )
                    Box(Modifier.fillMaxWidth().height(2.dp).background(if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f)))
                }
            }
        }
    }
}

@Composable private fun DailyAgenda(entries: List<ScheduleEntry>, now: Long, onCourse: (Course) -> Unit, onSchedule: () -> Unit, onDetail: (ScheduleEntry) -> Unit) {
    Panel(Modifier.fillMaxWidth().testTag("dashboard-agenda"), spacing = 0.dp) {
        SectionHeading("Timetable", action = "Full schedule", onAction = onSchedule)
        if (entries.isEmpty()) Text("No classes or exams on this day.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 16.dp))
        entries.forEach { entry ->
            val current = entry.kind != ScheduleKind.CANCELED && entry.start <= now && entry.end > now
            val canceled = entry.kind == ScheduleKind.CANCELED
            Surface(
                onClick = { if (entry.course != null && !canceled) onCourse(entry.course!!) else onDetail(entry) },
                color = if (current) MaterialTheme.colorScheme.primary.copy(alpha = .055f) else Color.Transparent,
                shape = TechnicalShapes.Field,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(entry.classCode.ifBlank { scheduleLabel(entry.kind) }, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall.copy(fontFamily = CampusMono), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (current || canceled || entry.kind != ScheduleKind.CLASS) Text(if (current) "In progress" else scheduleLabel(entry.kind), style = MaterialTheme.typography.labelMedium, color = if (canceled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                    }
                    Text(entry.title, style = MaterialTheme.typography.titleMedium, textDecoration = if (canceled) TextDecoration.LineThrough else null)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${clockText(entry.start)}${if (entry.end > entry.start) "–${clockText(entry.end)}" else ""}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall.copy(fontFamily = CampusMono))
                        if (entry.room.isNotBlank()) {
                            Icon(Icons.Outlined.LocationOn, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                            Text(entry.room, style = MaterialTheme.typography.titleSmall, color = if (canceled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary)
                        }
                        Icon(Icons.Outlined.ChevronRight, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))
        }
    }
}

@Composable private fun SubmissionQueue(events: List<CalendarEvent>, snapshot: PortalSnapshot, now: Long, onInbox: () -> Unit) {
    Panel(Modifier.fillMaxWidth().testTag("dashboard-deadlines"), spacing = 0.dp) {
        SectionHeading("Due soon", action = "View all", onAction = onInbox)
        if (events.isEmpty()) Text("No pending submissions in the next 7 days.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 16.dp))
        events.take(2).forEach { event ->
            Surface(onClick = onInbox, color = Color.Transparent) {
                DeadlineCard(event, now, snapshot.courses.firstOrNull { it.id == event.courseId && it.accountId == event.accountId }?.shortname.orEmpty())
            }
        }
    }
}

@Composable private fun CourseIndex(courses: List<Course>, onCourse: (Course) -> Unit) {
    Panel(Modifier.fillMaxWidth(), spacing = 8.dp) {
        Text("Course materials", style = MaterialTheme.typography.titleLarge)
        courses.filter { !it.hidden && it.classification != "past" }.take(4).forEach { course ->
            Surface(onClick = { onCourse(course) }, color = Color.Transparent, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(courseIcon(course.shortname), null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(course.fullname, style = MaterialTheme.typography.titleSmall)
                        Text(course.shortname, style = MaterialTheme.typography.bodySmall.copy(fontFamily = CampusMono), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
        if (courses.isEmpty()) Text("Your Moodle courses will appear after syncing.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun ScheduleChanges(snapshot: PortalSnapshot) {
    if (snapshot.notices.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Schedule changes", style = MaterialTheme.typography.titleMedium)
            snapshot.notices.take(2).forEach { notice ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Outlined.Info, null, Modifier.padding(top = 2.dp).size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(notice.title, style = MaterialTheme.typography.bodyMedium)
                        Text("${notice.targetDate} · ${notice.classCode}", style = MaterialTheme.typography.bodySmall.copy(fontFamily = CampusMono), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable private fun Announcements(snapshot: PortalSnapshot) {
    var expanded by remember { mutableStateOf<String?>(null) }
    val stories = snapshot.courseNews.take(2).map { Triple(it.name, it.author.ifBlank { "Moodle" }, android.text.Html.fromHtml(it.bodyHtml, 0).toString()) } +
        snapshot.announcements.take(2).map { Triple(it.title, "Academic affairs", android.text.Html.fromHtml(it.bodyHtml, 0).toString()) }
    Panel(Modifier.fillMaxWidth()) {
        Text("Announcements", style = MaterialTheme.typography.titleMedium)
        stories.take(3).forEach { (title, source, body) ->
            Surface(onClick = { expanded = "$title\n\n$body" }, color = Color.Transparent, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    Text(source, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (stories.isEmpty()) Text("No saved announcements.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    expanded?.let { text -> AlertDialog(onDismissRequest = { expanded = null }, title = { Text("Announcement") }, text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(text) } }, confirmButton = { TextButton(shape = TechnicalShapes.Control, onClick = { expanded = null }) { Text("Done") } }) }
}

@Composable private fun PortalFooter(snapshot: PortalSnapshot, onConnect: () -> Unit) {
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Moodle" to "courses.uit.edu.vn", "Academic affairs" to "daa.uit.edu.vn", "Library" to "thuvien.uit.edu.vn").forEach { (name, host) ->
                TextButton(shape = TechnicalShapes.Control, onClick = { runCatching { uri.openUri("https://$host") } }, contentPadding = PaddingValues(horizontal = 4.dp)) { Text(name, style = MaterialTheme.typography.bodySmall) }
            }
        }
        Text(if (snapshot.updatedAt > 0) "Last saved ${dateText(snapshot.updatedAt)} at ${clockText(snapshot.updatedAt)} · GMT+07" else "Moodle · DAA · UIT", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(shape = TechnicalShapes.Control, onClick = onConnect, contentPadding = PaddingValues(0.dp)) { Text("Manage linked accounts", style = MaterialTheme.typography.bodySmall) }
    }
}
