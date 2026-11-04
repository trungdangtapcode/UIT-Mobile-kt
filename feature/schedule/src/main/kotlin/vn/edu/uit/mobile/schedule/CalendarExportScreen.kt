package vn.edu.uit.mobile.schedule

import android.content.Intent
import android.provider.CalendarContract
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.datetime.*
import vn.edu.uit.mobile.common.*
import vn.edu.uit.mobile.designsystem.*
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.feature.schedule.R
import vn.edu.uit.mobile.model.*

fun calendarInsertIntent(entry: ScheduleEntry, updatedAt: Long = 0): Intent {
    val event = CalendarFileExport().deviceEvent(entry, updatedAt)
    return Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI).apply {
        putExtra(CalendarContract.Events.TITLE, event.title)
        if (event.location.isNotBlank()) putExtra(CalendarContract.Events.EVENT_LOCATION, event.location)
        putExtra(CalendarContract.Events.DESCRIPTION, event.description)
        putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, event.startMillis)
        event.endMillis?.let { putExtra(CalendarContract.EXTRA_EVENT_END_TIME, it) }
        putExtra(CalendarContract.Events.AVAILABILITY, if (event.free) CalendarContract.Events.AVAILABILITY_FREE else CalendarContract.Events.AVAILABILITY_BUSY)
    }
}

@Composable
fun CalendarExportContent(snapshot: PortalSnapshot, anchor: LocalDate, busy: Boolean = false, message: CalendarExportMessage? = null, onBack: () -> Unit = {}, onAddDevice: ((ScheduleEntry) -> Unit)? = null, onShare: ((CalendarExportPlan) -> Unit)? = null, onSave: (CalendarExportPlan) -> Unit = {}) {
    var from by rememberSaveable { mutableStateOf(anchor.toString()) }
    var through by rememberSaveable { mutableStateOf(anchor.plus(6, DateTimeUnit.DAY).toString()) }
    var details by rememberSaveable { mutableStateOf(false) }
    var typeNames by rememberSaveable { mutableStateOf(CalendarExportType.entries.map { it.name }) }
    var excluded by rememberSaveable(from, through, typeNames) { mutableStateOf(emptyList<String>()) }
    val result = remember(snapshot, from, through, typeNames) {
        runCatching {
            val first = runCatching { LocalDate.parse(from) }.getOrNull()
            val last = runCatching { LocalDate.parse(through) }.getOrNull()
            require(first != null && last != null) { "Enter valid dates as YYYY-MM-DD." }
            CalendarFileExport().plan(snapshot, CalendarExportOptions(first, last, typeNames.map { CalendarExportType.valueOf(it) }.toSet()))
        }
    }
    val available = result.getOrNull()
    val plan = remember(available, excluded) { available?.copy(options = available.options.copy(excludedKeys = excluded.toSet()), entries = available.entries.filterNot { it.key in excluded }) }
    BackHandler { if (!busy) onBack() }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).testTag("calendar-export-content"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                TextButton(onBack, enabled = !busy, shape = TechnicalShapes.Control) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, null)
                    Text(stringResource(R.string.exp_back))
                }
                PageHeading(stringResource(R.string.exp_title), stringResource(R.string.exp_subtitle))
            }
            item {
                Panel(Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.exp_range), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.exp_range_hint), style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(from, { from = it.take(10) }, modifier = Modifier.fillMaxWidth(), enabled = !busy, label = { Text(stringResource(R.string.exp_start)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii), shape = TechnicalShapes.Field)
                    OutlinedTextField(through, { through = it.take(10) }, modifier = Modifier.fillMaxWidth(), enabled = !busy, label = { Text(stringResource(R.string.exp_end)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii), shape = TechnicalShapes.Field)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton({
                            from = anchor.toString()
                            through = anchor.plus(6, DateTimeUnit.DAY).toString()
                        }, enabled = !busy, shape = TechnicalShapes.Control) { Text(stringResource(R.string.exp_7d)) }
                        TextButton({
                            from = anchor.toString()
                            through = anchor.plus(29, DateTimeUnit.DAY).toString()
                        }, enabled = !busy, shape = TechnicalShapes.Control) { Text(stringResource(R.string.exp_30d)) }
                    }
                }
            }
            item {
                SectionHeading(stringResource(R.string.exp_include), stringResource(R.string.exp_include_hint))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CalendarExportType.entries.forEach { type ->
                        FilterChip(selected = type.name in typeNames, onClick = { typeNames = if (type.name in typeNames) typeNames - type.name else typeNames + type.name }, enabled = !busy, shape = TechnicalShapes.Control, label = { Text(exportTypeLabel(type)) })
                    }
                }
            }
            item {
                Panel(Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.exp_copy_title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.exp_one_time), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { details = !details }) { Text(stringResource(if (details) R.string.exp_details_hide else R.string.exp_details)) }
                    if (details) {
                        Text(stringResource(R.string.exp_copy_body), style = MaterialTheme.typography.bodyMedium)
                        Text(stringResource(R.string.exp_copy_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (snapshot.updatedAt > 0) Text(stringResource(R.string.exp_snapshot, dateText(snapshot.updatedAt), clockText(snapshot.updatedAt)), style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(R.string.exp_cached_note), style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (result.isFailure) {
                    InlineNotice(
                        stringResource(
                            when {
                                runCatching {
                                    LocalDate.parse(from)
                                    LocalDate.parse(through)
                                }.isFailure -> R.string.exp_invalid_dates
                                typeNames.isEmpty() -> R.string.exp_choose_types
                                runCatching { LocalDate.parse(through).toEpochDays() - LocalDate.parse(from).toEpochDays() in 0..89 }.getOrDefault(false).not() -> R.string.exp_invalid_range
                                else -> R.string.exp_too_many
                            }
                        ),
                        true
                    )
                }
                message?.let { InlineNotice(stringResource(exportMessageLabel(it)), it.error) }
            }
            if (plan != null) {
                item {
                    SectionHeading(stringResource(R.string.exp_preview, plan.entries.size), stringResource(R.string.exp_preview_hint))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { excluded = emptyList() }, enabled = !busy && excluded.isNotEmpty()) { Text(stringResource(R.string.exp_select_all)) }
                        TextButton(onClick = { excluded = available!!.entries.map { it.key } }, enabled = !busy && plan.entries.isNotEmpty()) { Text(stringResource(R.string.exp_select_none)) }
                    }
                }
                if (available!!.entries.isEmpty()) item { EmptyState(stringResource(R.string.exp_none_title), stringResource(R.string.exp_none_body)) }
                items(available!!.entries, key = { it.key }) { entry ->
                    Panel(Modifier.fillMaxWidth()) {
                        Text(scheduleLabel(entry.kind), style = MaterialTheme.typography.labelMedium, color = scheduleColor(entry.kind))
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = entry.key !in excluded, enabled = !busy, role = Role.Checkbox, onValueChange = { checked -> excluded = if (checked) excluded - entry.key else excluded + entry.key }).testTag("calendar-select-${entry.key}"), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = entry.key !in excluded, onCheckedChange = null)
                            Spacer(Modifier.width(8.dp))
                            Text(entry.title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        }
                        Text("${dateText(entry.start)} · ${clockText(entry.start)}${if (entry.end > entry.start) "–${clockText(entry.end)}" else stringResource(R.string.exp_end_missing)}", style = MaterialTheme.typography.bodyMedium)
                        if (entry.classCode.isNotBlank()) Text(entry.classCode, style = MaterialTheme.typography.bodySmall)
                        if (entry.room.isNotBlank()) Text(stringResource(R.string.exp_room, entry.room), style = MaterialTheme.typography.bodySmall)
                        if (onAddDevice != null) {
                            TextButton(onClick = { onAddDevice(entry) }, enabled = !busy, shape = TechnicalShapes.Control) {
                                Text(stringResource(R.string.exp_add_device))
                            }
                        }
                    }
                }
            }
        }
        if (onShare != null) {
            OutlinedButton(onClick = { plan?.let(onShare) }, enabled = !busy && !plan?.entries.isNullOrEmpty(), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), shape = TechnicalShapes.Control) {
                Icon(Icons.Outlined.Share, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.exp_share))
            }
        }
        Button(onClick = { plan?.let(onSave) }, enabled = !busy && !plan?.entries.isNullOrEmpty(), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), shape = TechnicalShapes.Control) {
            Icon(Icons.Outlined.SaveAlt, null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(if (busy) R.string.exp_saving else R.string.exp_save))
        }
    }
}

@Composable
private fun exportTypeLabel(type: CalendarExportType): String = stringResource(
    when (type) {
        CalendarExportType.CLASSES -> R.string.exp_type_classes
        CalendarExportType.EXAMS -> R.string.exp_type_exams
        CalendarExportType.DEADLINES -> R.string.exp_type_deadlines
        CalendarExportType.OTHER -> R.string.exp_type_other
    }
)

internal fun exportMessageLabel(message: CalendarExportMessage): Int = when (message) {
    CalendarExportMessage.SAVED -> R.string.exp_saved
    CalendarExportMessage.CANCELED -> R.string.exp_canceled
    CalendarExportMessage.REVIEW -> R.string.exp_review
    CalendarExportMessage.SAVE_FAILED -> R.string.exp_save_failed
    CalendarExportMessage.NO_PICKER -> R.string.exp_no_picker
    CalendarExportMessage.NO_CALENDAR -> R.string.exp_no_calendar_app
    CalendarExportMessage.SHARE_FAILED -> R.string.exp_share_failed
    CalendarExportMessage.SHARE_OPENED -> R.string.exp_share_opened
}
