package vn.edu.uit.mobile.schedule

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.datetime.*
import vn.edu.uit.mobile.common.*
import vn.edu.uit.mobile.designsystem.*
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.feature.schedule.R
import vn.edu.uit.mobile.model.*

@Composable
fun FreeTimeContent(snapshot: PortalSnapshot, state: FreeTimeState, onBack: () -> Unit = {}, onDate: (String) -> Unit = {}, onStart: (String) -> Unit = {}, onEnd: (String) -> Unit = {}, onMinimum: (String) -> Unit = {}, onBuffer: (String) -> Unit = {}, onAdd: (String, String, String) -> String? = { _, _, _ -> null }, onRemove: (String) -> Unit = {}) {
    var adding by remember(state.session) { mutableStateOf(false) }
    var selected by remember(state.session) { mutableStateOf<FreeTimeSlot?>(null) }
    val calculation = remember(snapshot, state) { runCatching { FindFreeTimeUseCase()(snapshot, state.options(), state.personal) } }
    val result = calculation.getOrNull()
    val hasUniversityCommitments = snapshot.timetable.isNotEmpty() || snapshot.events.isNotEmpty() || snapshot.exams.isNotEmpty() || snapshot.notices.any { it.type == ScheduleChangeType.MAKE_UP }
    LaunchedEffect(snapshot, state) { selected = null }
    BackHandler(onBack = onBack)
    LazyColumn(Modifier.fillMaxSize().testTag("free-time-content"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            TextButton(onBack, shape = TechnicalShapes.Control) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, null)
                Text(stringResource(R.string.ft_back))
            }
            PageHeading(stringResource(R.string.ft_title), stringResource(R.string.ft_subtitle))
        }
        item {
            Panel(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.ft_window), style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(state.date, onDate, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.ft_date)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii), shape = TechnicalShapes.Field)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PlannerField(state.start, onStart, stringResource(R.string.ft_from), Modifier.weight(1f))
                    PlannerField(state.end, onEnd, stringResource(R.string.ft_until), Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PlannerField(state.minimum, onMinimum, stringResource(R.string.ft_minimum), Modifier.weight(1f), true)
                    PlannerField(state.buffer, onBuffer, stringResource(R.string.ft_buffer), Modifier.weight(1f), true)
                }
                Text(stringResource(R.string.ft_buffer_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            SectionHeading(stringResource(R.string.ft_blocks_title), stringResource(R.string.ft_blocks_hint))
            OutlinedButton(onClick = { adding = true }, enabled = state.personal.size < 10, shape = TechnicalShapes.Control) {
                Icon(Icons.Outlined.Add, null)
                Text(stringResource(R.string.ft_add_block))
            }
        }
        items(state.personal, key = { "personal:${it.id}" }) { entry ->
            Panel(Modifier.fillMaxWidth()) {
                Text(entry.title, style = MaterialTheme.typography.titleMedium)
                Text("${dateText(entry.start)} · ${clockText(entry.start)}–${clockText(entry.end)}", style = MaterialTheme.typography.bodyMedium)
                TextButton({ onRemove(entry.id) }, shape = TechnicalShapes.Control) { Text(stringResource(R.string.ft_remove_block)) }
            }
        }
        item {
            if (!hasUniversityCommitments) InlineNotice(if (state.personal.isEmpty()) "No saved university commitments are available. Schedule coverage is unverified; sync or add personal blocks to plan from." else "Only your personal blocks are available. University commitments are not included.", true)
            if (snapshot.updatedAt > 0) Text("Snapshot saved · ${dateText(snapshot.updatedAt)} ${clockText(snapshot.updatedAt)} · GMT+07", style = MaterialTheme.typography.bodySmall)
            Text("Candidate gaps reflect cached commitments, not guaranteed availability or room occupancy. Deadlines do not reserve study time automatically.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            state.message?.let { InlineNotice(it) }
            calculation.exceptionOrNull()?.let { InlineNotice(it.message ?: "The saved schedule could not be checked. Review the date and times.", true) }
        }
        if (result != null && (hasUniversityCommitments || state.personal.isNotEmpty())) {
            if (result.busy.any { it.unknownEnd }) item { InlineNotice("Some commitments have no end time. They block the rest of their UIT day and your buffer; check the commitments below.", true) }
            item { SectionHeading(stringResource(R.string.ft_gaps, result.slots.size), stringResource(R.string.ft_gaps_hint)) }
            if (result.slots.isEmpty()) item { EmptyState(stringResource(R.string.ft_no_gaps_title), stringResource(R.string.ft_no_gaps_body)) }
            items(result.slots, key = { "gap:${it.start}:${it.end}" }) { slot ->
                Panel(Modifier.fillMaxWidth()) {
                    Text("${clockText(slot.start)}–${clockText(slot.end)}", style = MaterialTheme.typography.titleLarge)
                    Text("${slot.minutes} minutes · ${dateText(slot.start)}", style = MaterialTheme.typography.bodyMedium)
                    TextButton({ selected = slot }, shape = TechnicalShapes.Control) { Text(stringResource(R.string.ft_inspect)) }
                }
            }
            item { SectionHeading("Commitments used · ${result.busy.size}", "${result.canceledCount} canceled meetings excluded. Times below include the buffer within your window.") }
            if (result.busy.isEmpty()) item { Text("No saved timed commitments overlap this window.", style = MaterialTheme.typography.bodyMedium) }
            items(result.busy, key = { "busy:${it.key}:${it.start}:${it.end}" }) { block ->
                Panel(Modifier.fillMaxWidth()) {
                    Text(block.title, style = MaterialTheme.typography.titleMedium)
                    Text("${clockText(block.bufferedStart)}–${clockText(block.bufferedEnd)} · ${if (block.kind == "PERSONAL") "Personal block" else block.kind.lowercase().replaceFirstChar(Char::uppercase)}", style = MaterialTheme.typography.bodyMedium)
                    if (block.unknownEnd) Text("End time missing · reserved through the end of its UIT day", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
            if (result.deadlines.isNotEmpty()) {
                item { SectionHeading("Deadlines on this date", "Add a personal block if you want to reserve preparation time.") }
                items(result.deadlines, key = { "deadline:${it.key}" }) { deadline -> Text("${clockText(deadline.start)} · ${deadline.title}", style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
    if (adding) PersonalBlockDialog(state.date, { adding = false }, onAdd)
    val slot = selected?.takeIf { result?.slots?.contains(it) == true }
    if (slot != null && result != null) {
        val before = result.busy.filter { it.bufferedEnd <= slot.start }.maxByOrNull { it.bufferedEnd }
        val after = result.busy.filter { it.bufferedStart >= slot.end }.minByOrNull { it.bufferedStart }
        AlertDialog(onDismissRequest = { selected = null }, shape = TechnicalShapes.Dialog, title = { Text(stringResource(R.string.ft_interval)) }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.ft_interval_body, dateText(slot.start), clockText(slot.start), clockText(slot.end), slot.minutes))
                Text(before?.let { stringResource(R.string.ft_after, it.title, clockText(it.bufferedEnd)) } ?: stringResource(R.string.ft_window_start))
                Text(after?.let { stringResource(R.string.ft_before, it.title, clockText(it.bufferedStart)) } ?: stringResource(R.string.ft_window_end))
                Text(stringResource(R.string.ft_gap_note), style = MaterialTheme.typography.bodySmall)
            }
        }, confirmButton = { TextButton({ selected = null }, shape = TechnicalShapes.Control) { Text(stringResource(R.string.ft_close_interval)) } })
    }
}

@Composable private fun PlannerField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier, numeric: Boolean = false) {
    OutlinedTextField(value, onChange, modifier = modifier, label = { Text(label) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Ascii), shape = TechnicalShapes.Field)
}

@Composable private fun PersonalBlockDialog(date: String, onClose: () -> Unit, onAdd: (String, String, String) -> String?) {
    var title by remember { mutableStateOf("") }
    var start by remember { mutableStateOf("12:00") }
    var end by remember { mutableStateOf("13:00") }
    var error by remember { mutableStateOf<String?>(null) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.padding(16.dp).widthIn(max = 480.dp).fillMaxWidth().heightIn(max = 600.dp), shape = TechnicalShapes.Dialog, color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(20.dp)) {
                Text(stringResource(R.string.ft_dialog_title), style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(16.dp))
                Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.ft_dialog_draft, date), style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(title, { title = it.take(80) }, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.ft_block_title)) }, singleLine = true, shape = TechnicalShapes.Field)
                    OutlinedTextField(start, { start = it.take(5) }, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.ft_block_start)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii), shape = TechnicalShapes.Field)
                    OutlinedTextField(end, { end = it.take(5) }, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.ft_block_end)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii), shape = TechnicalShapes.Field)
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClose, shape = TechnicalShapes.Control) { Text(stringResource(R.string.ft_cancel)) }
                    TextButton({
                        error = onAdd(title, start, end)
                        if (error == null) onClose()
                    }, enabled = title.isNotBlank(), shape = TechnicalShapes.Control) { Text(stringResource(R.string.ft_add_draft)) }
                }
            }
        }
    }
}
