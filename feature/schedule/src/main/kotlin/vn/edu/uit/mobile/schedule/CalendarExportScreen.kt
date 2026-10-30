package vn.edu.uit.mobile.schedule

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.datetime.*
import vn.edu.uit.mobile.common.*
import vn.edu.uit.mobile.designsystem.*
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.model.*

@Composable
fun CalendarExportContent(snapshot: PortalSnapshot, anchor: LocalDate, busy: Boolean = false, message: String? = null, onBack: () -> Unit = {}, onSave: (CalendarExportPlan) -> Unit = {}) {
    var from by rememberSaveable { mutableStateOf(anchor.toString()) }
    var through by rememberSaveable { mutableStateOf(anchor.plus(6, DateTimeUnit.DAY).toString()) }
    var typeNames by rememberSaveable { mutableStateOf(CalendarExportType.entries.map { it.name }) }
    val result = remember(snapshot, from, through, typeNames) {
        runCatching {
            val first = runCatching { LocalDate.parse(from) }.getOrNull()
            val last = runCatching { LocalDate.parse(through) }.getOrNull()
            require(first != null && last != null) { "Enter valid dates as YYYY-MM-DD." }
            CalendarFileExport().plan(snapshot, CalendarExportOptions(first, last, typeNames.map { CalendarExportType.valueOf(it) }.toSet()))
        }
    }
    val plan = result.getOrNull()
    BackHandler { if (!busy) onBack() }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).testTag("calendar-export-content"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                TextButton(onBack, enabled = !busy, shape = TechnicalShapes.Control) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, null)
                    Text("Schedule")
                }
                PageHeading("Export calendar", "Choose the saved events to include in an .ics file.")
            }
            item {
                Panel(Modifier.fillMaxWidth()) {
                    Text("Date range", style = MaterialTheme.typography.titleMedium)
                    Text("One to 90 days · Vietnam time (GMT+07)", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(from, { from = it.take(10) }, modifier = Modifier.fillMaxWidth(), enabled = !busy, label = { Text("Start date · YYYY-MM-DD") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii), shape = TechnicalShapes.Field)
                    OutlinedTextField(through, { through = it.take(10) }, modifier = Modifier.fillMaxWidth(), enabled = !busy, label = { Text("End date · YYYY-MM-DD") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii), shape = TechnicalShapes.Field)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton({
                            from = anchor.toString()
                            through = anchor.plus(6, DateTimeUnit.DAY).toString()
                        }, enabled = !busy, shape = TechnicalShapes.Control) { Text("7 days") }
                        TextButton({
                            from = anchor.toString()
                            through = anchor.plus(29, DateTimeUnit.DAY).toString()
                        }, enabled = !busy, shape = TechnicalShapes.Control) { Text("30 days") }
                    }
                }
            }
            item {
                SectionHeading("Include", "Classes include make-up and canceled meetings.")
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CalendarExportType.entries.forEach { type ->
                        FilterChip(selected = type.name in typeNames, onClick = { typeNames = if (type.name in typeNames) typeNames - type.name else typeNames + type.name }, enabled = !busy, shape = TechnicalShapes.Control, label = { Text(exportTypeLabel(type)) })
                    }
                }
            }
            item {
                Panel(Modifier.fillMaxWidth()) {
                    Text("A copy of your saved schedule", style = MaterialTheme.typography.titleMedium)
                    Text("The file contains event titles, dates, times, class codes and rooms. It is an unencrypted copy saved to the location you choose.", style = MaterialTheme.typography.bodyMedium)
                    Text("This is a file export, not a live subscription. Later changes, cancellations and account removal will not update an imported copy.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (snapshot.updatedAt > 0) Text("Snapshot saved · ${dateText(snapshot.updatedAt)} ${clockText(snapshot.updatedAt)} · GMT+07", style = MaterialTheme.typography.bodySmall)
                    Text("Only cached events are included. Sync first if dates or courses are missing. Your calendar app may display times in its own time zone.", style = MaterialTheme.typography.bodySmall)
                }
                result.exceptionOrNull()?.let { InlineNotice(it.message ?: "The saved schedule could not be exported.", true) }
                message?.let { InlineNotice(it) }
            }
            if (plan != null) {
                item { SectionHeading("Preview · ${plan.entries.size} events", "Review this list before choosing a file location.") }
                if (plan.entries.isEmpty()) item { EmptyState("No events in this selection", "Try another date range or event type. Nothing will be exported.") }
                items(plan.entries, key = { it.key }) { entry ->
                    Panel(Modifier.fillMaxWidth()) {
                        Text(scheduleLabel(entry.kind), style = MaterialTheme.typography.labelMedium, color = scheduleColor(entry.kind))
                        Text(entry.title, style = MaterialTheme.typography.titleMedium)
                        Text("${dateText(entry.start)} · ${clockText(entry.start)}${if (entry.end > entry.start) "–${clockText(entry.end)}" else " · end time not provided"}", style = MaterialTheme.typography.bodyMedium)
                        if (entry.classCode.isNotBlank()) Text(entry.classCode, style = MaterialTheme.typography.bodySmall)
                        if (entry.room.isNotBlank()) Text("Room · ${entry.room}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        Button(onClick = { plan?.let(onSave) }, enabled = !busy && !plan?.entries.isNullOrEmpty(), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), shape = TechnicalShapes.Control) {
            Icon(Icons.Outlined.SaveAlt, null)
            Spacer(Modifier.width(8.dp))
            Text(if (busy) "Saving calendar…" else "Save .ics file")
        }
    }
}

private fun exportTypeLabel(type: CalendarExportType) = when (type) {
    CalendarExportType.CLASSES -> "Classes"
    CalendarExportType.EXAMS -> "Exams"
    CalendarExportType.DEADLINES -> "Deadlines"
    CalendarExportType.OTHER -> "Other events"
}
