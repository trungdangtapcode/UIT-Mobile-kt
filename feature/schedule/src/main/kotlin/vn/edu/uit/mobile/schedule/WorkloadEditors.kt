package vn.edu.uit.mobile.schedule

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import vn.edu.uit.mobile.designsystem.*
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.feature.schedule.R
import vn.edu.uit.mobile.model.*

@Composable internal fun WorkloadEstimateDialog(task: WorkloadTask, busy: Boolean, problem: WorkloadProblem?, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var remaining by rememberSaveable { mutableStateOf(task.estimate?.remainingMinutes?.toString().orEmpty()) }
    var duration by rememberSaveable { mutableStateOf(task.estimate?.examMinutes?.toString().orEmpty()) }
    WorkloadDialog(stringResource(R.string.wl_estimate), onDismiss, actions = {
        TextButton(onDismiss, enabled = !busy, shape = TechnicalShapes.Control) { Text(stringResource(R.string.wl_cancel)) }
        TextButton({ onSave(remaining, duration) }, enabled = !busy, shape = TechnicalShapes.Control) { Text(stringResource(R.string.wl_save)) }
    }) {
        Text(task.item.title, style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.wl_estimate_help), style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(remaining, { remaining = it.take(5) }, Modifier.fillMaxWidth(), enabled = !busy, label = { Text(stringResource(R.string.wl_remaining)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), shape = TechnicalShapes.Field, singleLine = true)
        if (task.item.ref.kind == WorkloadKind.EXAM) {
            OutlinedTextField(duration, { duration = it.take(3) }, Modifier.fillMaxWidth(), enabled = !busy, label = { Text(stringResource(R.string.wl_exam_duration)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), shape = TechnicalShapes.Field, singleLine = true)
            Text(stringResource(R.string.wl_duration_help), style = MaterialTheme.typography.bodySmall)
        }
        problem?.let { InlineNotice(workloadProblem(it), true) }
    }
}

@Composable internal fun WorkloadSettingsDialog(selected: WorkloadAccount, owners: List<Account>, busy: Boolean, problem: WorkloadProblem?, onDismiss: () -> Unit, onSave: (WorkloadPolicy) -> Unit) {
    val policy = selected.policy
    var horizon by rememberSaveable { mutableIntStateOf(policy.horizonDays) }
    var minimum by rememberSaveable { mutableStateOf(policy.minimumSession.toString()) }
    var buffer by rememberSaveable { mutableStateOf(policy.travelBuffer.toString()) }
    var lead by rememberSaveable { mutableStateOf(policy.deadlineLead.toString()) }
    var stale by rememberSaveable { mutableStateOf(policy.staleHours.toString()) }
    var other by rememberSaveable { mutableStateOf(policy.includeOtherEvents) }
    var calendar by rememberSaveable { mutableStateOf(policy.calendarAccountId) }
    val initial = policy.days.sortedBy { it.isoDay }
    var starts by rememberSaveable { mutableStateOf(initial.map { timeText(it.startMinute) }) }
    var ends by rememberSaveable { mutableStateOf(initial.map { timeText(it.endMinute) }) }
    var budgets by rememberSaveable { mutableStateOf(initial.map { it.minutes.toString() }) }
    var day by rememberSaveable { mutableIntStateOf(0) }
    var invalid by remember { mutableStateOf(false) }
    val weekdays = stringArrayResource(R.array.wl_weekdays)
    WorkloadDialog(stringResource(R.string.wl_configure), onDismiss, actions = {
        TextButton(onDismiss, enabled = !busy, shape = TechnicalShapes.Control) { Text(stringResource(R.string.wl_cancel)) }
        TextButton({
            val value = runCatching {
                val rules = (0..6).map { StudyDayBudget(it + 1, minuteOfDay(starts[it]), minuteOfDay(ends[it]), budgets[it].toInt()) }
                WorkloadPolicy(horizon, rules, minimum.toInt(), buffer.toInt(), lead.toInt(), stale.toInt(), other, calendar).also(::validateWorkloadPolicy)
            }.getOrNull()
            invalid = value == null
            if (value != null) onSave(value)
        }, enabled = !busy, shape = TechnicalShapes.Control) { Text(stringResource(R.string.wl_save)) }
    }) {
        Text(stringResource(R.string.wl_config_help), style = MaterialTheme.typography.bodySmall)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(7, 14, 28).forEach { value -> FilterChip(selected = horizon == value, onClick = { horizon = value }, enabled = !busy, shape = TechnicalShapes.Control, label = { Text(stringResource(R.string.wl_horizon, value)) }) }
        }
        Text(stringResource(R.string.wl_teaching_source), style = MaterialTheme.typography.titleMedium)
        if (selected.account.portal == PortalType.MOODLE) {
            CalendarChoice(stringResource(R.string.wl_no_calendar), calendar.isEmpty(), !busy) { calendar = "" }
            owners.filter { it.portal != PortalType.MOODLE }.forEach { owner -> CalendarChoice(accountLabel(owner), calendar == owner.id, !busy) { calendar = owner.id } }
        } else {
            Text(accountLabel(selected.account), style = MaterialTheme.typography.bodySmall)
        }
        Text(stringResource(R.string.wl_source_help), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.wl_weekday_budget), style = MaterialTheme.typography.titleMedium)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { weekdays.forEachIndexed { index, label -> FilterChip(selected = day == index, onClick = { day = index }, enabled = !busy, shape = TechnicalShapes.Control, label = { Text(label) }) } }
        Text(weekdays[day], style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(starts[day], { text -> starts = starts.mapIndexed { i, value -> if (i == day) text.take(5) else value } }, Modifier.fillMaxWidth(), enabled = !busy, label = { Text(stringResource(R.string.wl_window_start)) }, singleLine = true, shape = TechnicalShapes.Field)
        OutlinedTextField(ends[day], { text -> ends = ends.mapIndexed { i, value -> if (i == day) text.take(5) else value } }, Modifier.fillMaxWidth(), enabled = !busy, label = { Text(stringResource(R.string.wl_window_end)) }, singleLine = true, shape = TechnicalShapes.Field)
        NumberField(budgets[day], { text -> budgets = budgets.mapIndexed { i, value -> if (i == day) text else value } }, stringResource(R.string.wl_budget), !busy)
        Text(stringResource(R.string.wl_budget_help), style = MaterialTheme.typography.bodySmall)
        NumberField(minimum, { minimum = it }, stringResource(R.string.wl_minimum), !busy)
        NumberField(buffer, { buffer = it }, stringResource(R.string.wl_buffer), !busy)
        NumberField(lead, { lead = it }, stringResource(R.string.wl_lead), !busy)
        NumberField(stale, { stale = it }, stringResource(R.string.wl_stale_hours), !busy)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(other, { other = it }, enabled = !busy)
            Text(stringResource(R.string.wl_other_events), style = MaterialTheme.typography.bodyMedium)
        }
        if (invalid) InlineNotice(stringResource(R.string.wl_config_invalid), true)
        problem?.let { InlineNotice(workloadProblem(it), true) }
    }
}

@Composable private fun NumberField(value: String, onChange: (String) -> Unit, label: String, enabled: Boolean) {
    OutlinedTextField(value, { onChange(it.take(5)) }, Modifier.fillMaxWidth(), enabled = enabled, label = { Text(label) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, shape = TechnicalShapes.Field)
}

@Composable private fun CalendarChoice(label: String, selected: Boolean, enabled: Boolean, onSelect: () -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, null, enabled = enabled)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
private fun timeText(minutes: Int) = "%02d:%02d".format(java.util.Locale.ROOT, minutes / 60, minutes % 60)
private fun minuteOfDay(value: String): Int = parsePlannerTime(value).let { it.hour * 60 + it.minute }
