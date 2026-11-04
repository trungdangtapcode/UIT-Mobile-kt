package vn.edu.uit.mobile.schedule

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import vn.edu.uit.mobile.designsystem.*
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.feature.schedule.R
import vn.edu.uit.mobile.model.*

@Composable fun WorkloadScreen(model: WorkloadViewModel, onBack: () -> Unit, onPersonal: () -> Unit) {
    val state by model.uiState.collectAsStateWithLifecycle()
    key(state.session) { WorkloadContent(state, onBack, onPersonal, model::select, model::reload, model::policy, model::estimate, model::remove) }
}

@Composable fun WorkloadContent(state: WorkloadState, onBack: () -> Unit = {}, onPersonal: () -> Unit = {}, onAccount: (String) -> Unit = {}, onReload: () -> Unit = {}, onPolicy: (WorkloadPolicy, () -> Unit) -> Unit = { _, _ -> }, onEstimate: (WorkloadRef, String, String, () -> Unit) -> Unit = { _, _, _, _ -> }, onRemove: (WorkloadRef) -> Unit = {}) {
    var settings by rememberSaveable { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var explain by rememberSaveable { mutableStateOf<Long?>(null) }
    var showOverdue by rememberSaveable { mutableStateOf(false) }
    val result = state.result
    val selected = state.selected
    val enabled = !state.busy && !state.loading
    BackHandler(onBack = onBack)
    LazyColumn(Modifier.fillMaxSize().testTag("workload-content"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            TextButton(onBack, shape = TechnicalShapes.Control) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, null)
                Text(stringResource(R.string.wl_back))
            }
            PageHeading(stringResource(R.string.wl_title), stringResource(R.string.wl_subtitle))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.owners.forEach { owner -> FilterChip(selected = state.selectedId == owner.id, onClick = { onAccount(owner.id) }, enabled = !state.busy, shape = TechnicalShapes.Control, label = { Text(accountLabel(owner)) }) }
            }
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                TextButton({ settings = true }, enabled = !state.busy && selected != null && !selected.storageFailed, shape = TechnicalShapes.Control) { Text(stringResource(R.string.wl_configure)) }
                TextButton(onReload, enabled = !state.busy, shape = TechnicalShapes.Control) { Text(stringResource(R.string.wl_reload)) }
            }
            if (state.loading || state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.problem?.let { InlineNotice(workloadProblem(it), true) }
        }
        if (state.owners.isEmpty()) item { EmptyState(stringResource(R.string.wl_connect), stringResource(R.string.wl_connect_hint)) }
        if (result != null) {
            item {
                Panel(Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.wl_range, result.from.toString(), result.through.toString()), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.wl_known, result.tasks.sumOf { it.estimate?.remainingMinutes?.toLong() ?: 0 }), style = MaterialTheme.typography.headlineSmall)
                    Text(stringResource(R.string.wl_unknown, result.tasks.count { it.estimate?.remainingMinutes == null }), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val worst = result.checkpoints.minByOrNull { it.pressure.ordinal }
                    worst?.let { point ->
                        Text(pressureLabel(point.pressure), style = MaterialTheme.typography.titleMedium, color = if (point.pressure == WorkloadPressure.SHORTFALL) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                        if (point.pressure == WorkloadPressure.SHORTFALL) Text(stringResource(R.string.wl_deficit, point.demand - point.capacity.optimistic, dateText(point.cutoff), clockText(point.cutoff)), style = MaterialTheme.typography.bodySmall)
                        TextButton({ explain = point.cutoff }, shape = TechnicalShapes.Control) { Text(stringResource(R.string.wl_explain)) }
                    }
                    if (result.issues.isNotEmpty()) Text(stringResource(R.string.wl_gaps, result.issues.size), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (result.submittedCount > 0) Text(stringResource(R.string.wl_submitted, result.submittedCount), style = MaterialTheme.typography.bodySmall)
                    if (result.assumedExamEnds > 0) Text(stringResource(R.string.wl_assumed, result.assumedExamEnds), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.wl_snapshot_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (result.issues.isNotEmpty()) {
                item {
                    Panel {
                        Text(stringResource(R.string.wl_limitations), style = MaterialTheme.typography.titleMedium)
                        result.issues.sortedBy { it.ordinal }.forEach { Text(issueLabel(it), style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
            item {
                Text(stringResource(R.string.wl_dates), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.wl_estimate_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (result.tasks.isEmpty()) item { EmptyState(stringResource(R.string.wl_empty), stringResource(R.string.wl_empty_hint)) }
            result.days.filter { it.tasks.isNotEmpty() }.forEach { day ->
                item(key = "day:${day.date}") {
                    Panel(Modifier.fillMaxWidth()) {
                        Text(day.date.toString(), style = MaterialTheme.typography.titleLarge)
                        Text(capacityText(day.capacity, fullDay = true), style = MaterialTheme.typography.bodySmall)
                        day.tasks.forEach { task -> WorkloadTaskRow(task, state.owners, enabled) { editing = task.item.ref.id } }
                    }
                }
            }
            item {
                TextButton({ showOverdue = !showOverdue }, shape = TechnicalShapes.Control) { Text(stringResource(R.string.wl_overdue, result.overdue.size)) }
                Text(stringResource(R.string.wl_overdue_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (showOverdue) result.overdue.forEach { task -> item(key = "overdue:${task.item.ref.id}") { Panel { WorkloadTaskRow(task, state.owners, enabled) { editing = task.item.ref.id } } } }
            if (result.orphanEstimates.isNotEmpty()) {
                item { SectionHeading(stringResource(R.string.wl_unavailable), stringResource(R.string.wl_unavailable_hint)) }
                result.orphanEstimates.forEach { estimate ->
                    item(key = "orphan:${estimate.ref.id}") {
                        Panel {
                            Text(estimate.title)
                            TextButton({ onRemove(estimate.ref) }, enabled = enabled, shape = TechnicalShapes.Control) { Text(stringResource(R.string.wl_remove)) }
                        }
                    }
                }
            }
            item {
                TextButton(onPersonal, enabled = !state.busy, shape = TechnicalShapes.Control) { Text(stringResource(R.string.wl_personal)) }
                Text(stringResource(R.string.wl_personal_hint), style = MaterialTheme.typography.bodySmall)
                result.sources.forEach { source -> Text(stringResource(R.string.wl_saved_source, accountLabel(source.account), if (source.updatedAt > 0) "${dateText(source.updatedAt)} ${clockText(source.updatedAt)} GMT+07" else "—"), style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
    if (settings && selected != null && !selected.storageFailed) {
        WorkloadSettingsDialog(selected, state.owners, state.busy, state.problem, { settings = false }) { policy -> onPolicy(policy) { settings = false } }
    }
    editing?.let { id ->
        (result?.tasks.orEmpty() + result?.overdue.orEmpty()).firstOrNull { it.item.ref.id == id }?.let { task ->
            WorkloadEstimateDialog(task, state.busy, state.problem, { editing = null }) { minutes, duration -> onEstimate(task.item.ref, minutes, duration) { editing = null } }
        }
    }
    explain?.let { cutoff ->
        result?.checkpoints?.firstOrNull { it.cutoff == cutoff }?.let { point ->
            WorkloadDialog(stringResource(R.string.wl_explain), { explain = null }, actions = { TextButton({ explain = null }, shape = TechnicalShapes.Control) { Text(stringResource(R.string.wl_close)) } }) {
                Text(pressureLabel(point.pressure), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.wl_cutoff, dateText(point.cutoff), clockText(point.cutoff)))
                Text(stringResource(R.string.wl_demand, point.demand))
                Text(capacityText(point.capacity))
                Text(stringResource(R.string.wl_missing_prefix, point.missingEstimates))
                Text(stringResource(R.string.wl_formula), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.wl_not_schedule), style = MaterialTheme.typography.bodySmall)
                val policy = selected?.policy ?: WorkloadPolicy()
                result.tasks.filter { it.item.due - policy.deadlineLead * 60L <= cutoff }.forEach { task ->
                    Text(task.item.title, style = MaterialTheme.typography.titleSmall)
                    Text(task.estimate?.remainingMinutes?.let { stringResource(R.string.wl_minutes, it) } ?: stringResource(R.string.wl_not_estimated), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable private fun WorkloadTaskRow(task: WorkloadTask, owners: List<Account>, enabled: Boolean, onEdit: () -> Unit) {
    Column(Modifier.fillMaxWidth().testTag("workload-task:${task.item.ref.id}"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text(task.item.title, style = MaterialTheme.typography.titleMedium)
        Text(stringResource(if (task.item.ref.kind == WorkloadKind.EXAM) R.string.wl_exam else R.string.wl_deadline) + " · ${dateText(task.item.due)} ${clockText(task.item.due)} GMT+07", style = MaterialTheme.typography.bodySmall)
        owners.firstOrNull { it.id == task.item.ref.accountId }?.let { Text(accountLabel(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Text(task.estimate?.remainingMinutes?.let { stringResource(R.string.wl_minutes, it) } ?: stringResource(R.string.wl_not_estimated), style = MaterialTheme.typography.bodyMedium)
        TextButton(onEdit, enabled = enabled, shape = TechnicalShapes.Control) { Text(stringResource(if (task.estimate == null) R.string.wl_estimate else R.string.wl_edit_estimate)) }
    }
}

@Composable internal fun WorkloadDialog(title: String, onDismiss: () -> Unit, actions: @Composable RowScope.() -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.padding(16.dp).widthIn(max = 480.dp).fillMaxWidth().heightIn(max = 640.dp).testTag("workload-dialog"), shape = TechnicalShapes.Dialog) {
            Column(Modifier.padding(20.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(12.dp))
                Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, content = actions)
            }
        }
    }
}

@Composable internal fun workloadProblem(problem: WorkloadProblem): String = stringResource(
    when (problem) {
        WorkloadProblem.LOAD -> R.string.wl_load_error
        WorkloadProblem.SAVE -> R.string.wl_save_error
        WorkloadProblem.INPUT -> R.string.wl_input_error
        WorkloadProblem.ANALYSIS -> R.string.wl_analysis_error
    }
)

@Composable private fun pressureLabel(pressure: WorkloadPressure): String = stringResource(
    when (pressure) {
        WorkloadPressure.SHORTFALL -> R.string.wl_shortfall
        WorkloadPressure.UNCERTAIN -> R.string.wl_uncertain
        WorkloadPressure.WITHIN_ENTERED_BUDGET -> R.string.wl_within
    }
)

@Composable private fun capacityText(capacity: WorkloadCapacity, fullDay: Boolean = false): String = if (capacity.conservative == capacity.optimistic) stringResource(if (fullDay) R.string.wl_day_capacity_exact else R.string.wl_capacity_exact, capacity.conservative) else stringResource(if (fullDay) R.string.wl_day_capacity else R.string.wl_capacity, capacity.conservative, capacity.optimistic)
internal fun accountLabel(account: Account): String = account.displayName + " · " + runCatching { java.net.URI(account.baseUrl).host }.getOrDefault(account.baseUrl)

@Composable private fun issueLabel(issue: WorkloadIssue): String = stringResource(
    when (issue) {
        WorkloadIssue.MISSING_CALENDAR -> R.string.wl_issue_calendar
        WorkloadIssue.SOURCE_UNAVAILABLE -> R.string.wl_issue_source
        WorkloadIssue.LOCAL_STATE -> R.string.wl_issue_local
        WorkloadIssue.STALE -> R.string.wl_issue_stale
        WorkloadIssue.UNKNOWN_END -> R.string.wl_issue_end
        WorkloadIssue.CONFLICT -> R.string.wl_issue_conflict
        WorkloadIssue.PERSONAL_BLOCKS -> R.string.wl_issue_personal
        WorkloadIssue.UNCONFIRMED_BUDGET -> R.string.wl_issue_budget
        WorkloadIssue.UNSCHEDULED -> R.string.wl_issue_unscheduled
        WorkloadIssue.REFRESH_FAILED -> R.string.wl_issue_refresh
    }
)
