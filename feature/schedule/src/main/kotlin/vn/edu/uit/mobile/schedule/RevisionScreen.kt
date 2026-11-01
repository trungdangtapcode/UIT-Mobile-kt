package vn.edu.uit.mobile.schedule

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.datetime.*
import vn.edu.uit.mobile.common.*
import vn.edu.uit.mobile.designsystem.*
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.feature.schedule.R
import vn.edu.uit.mobile.model.*

@Composable fun RevisionScreen(model: RevisionViewModel, onBack: () -> Unit) {
    val state by model.uiState.collectAsStateWithLifecycle()
    val session = state.base.session
    key(state.base.session) {
        RevisionContent(state, onBack, model::selectOwner, { id ->
            model.selectExam(id)
            if (state.saved.plans.none { it.exam.id == id }) model.addExam(id)
        }, model::selectExam, model::reload, model::generate, model::saveDraft, model::discard, model::topic, model::reorder, model::archive, model::relink, model::session, model::move, { maximum, rest, delay, done ->
            if (model.workload.uiState.value.session == session) model.options(maximum, rest, delay, done)
        }, { policy, done ->
            if (model.workload.uiState.value.session == session) model.workload.policy(policy, done)
        }, model.workload::estimate, model::removePlan)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RevisionContent(
    state: RevisionState,
    onBack: () -> Unit = {},
    onOwner: (String) -> Unit = {},
    onExam: (String) -> Unit = {},
    onSelect: (String) -> Unit = {},
    onReload: () -> Unit = {},
    onGenerate: () -> Unit = {},
    onSaveDraft: () -> Unit = {},
    onDiscard: () -> Unit = {},
    onTopic: (WorkloadRef, String?, String, String, () -> Unit) -> Unit = { _, _, _, _, _ -> },
    onReorder: (WorkloadRef, String, Int) -> Unit = { _, _, _ -> },
    onArchive: (WorkloadRef) -> Unit = {},
    onRelink: (WorkloadRef, String, () -> Unit) -> Unit = { _, _, _ -> },
    onSession: (WorkloadRef, String, RevisionStatus?, Boolean) -> Unit = { _, _, _, _ -> },
    onMove: (WorkloadRef, String, String, String, String, () -> Unit) -> Unit = { _, _, _, _, _, _ -> },
    onOptions: (String, String, String, () -> Unit) -> Unit = { _, _, _, _ -> },
    onPolicy: (WorkloadPolicy, () -> Unit) -> Unit = { _, _ -> },
    onEstimate: (WorkloadRef, String, String, () -> Unit) -> Unit = { _, _, _, _ -> },
    onRemovePlan: (WorkloadRef) -> Unit = {}
) {
    var chooser by rememberSaveable { mutableStateOf(false) }
    var settings by rememberSaveable { mutableStateOf(false) }
    var rules by rememberSaveable { mutableStateOf(false) }
    var topicId by rememberSaveable { mutableStateOf<String?>(null) }
    var topicOpen by rememberSaveable { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var relinking by rememberSaveable { mutableStateOf(false) }
    var duration by rememberSaveable { mutableStateOf(false) }
    var history by rememberSaveable { mutableStateOf(false) }
    var deleting by rememberSaveable { mutableStateOf(false) }
    val plan = state.plan
    val owner = state.owner
    val workspace = state.draft?.workspace ?: state.saved
    val busy = state.busy || state.base.busy
    val enabled = !busy && !state.failed && owner != null
    BackHandler(onBack = onBack)
    LazyColumn(Modifier.fillMaxSize().testTag("revision-content"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            TextButton(onBack, shape = TechnicalShapes.Control) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, null)
                Text(stringResource(R.string.wl_back))
            }
            PageHeading(stringResource(R.string.rp_title), stringResource(R.string.rp_subtitle))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.base.owners.filter { it.portal != PortalType.MOODLE }.forEach { account -> FilterChip(state.base.selectedId == account.id, { onOwner(account.id) }, enabled = !busy, shape = TechnicalShapes.Control, label = { Text(accountLabel(account)) }) }
            }
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                TextButton({ settings = true }, enabled = enabled && owner?.storageFailed == false, shape = TechnicalShapes.Control) { Text(stringResource(R.string.wl_configure)) }
                TextButton({ rules = true }, enabled = enabled, shape = TechnicalShapes.Control) { Text(stringResource(R.string.rp_rules)) }
                TextButton(onReload, enabled = !busy, shape = TechnicalShapes.Control) { Text(stringResource(R.string.wl_reload)) }
            }
            if (busy || state.base.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.message?.let { InlineNotice(revisionMessage(it), it != RevisionMessage.GENERATED) }
            state.base.problem?.let { InlineNotice(workloadProblem(it), true) }
            if (state.failed) InlineNotice(stringResource(R.string.rp_load_error), true)
        }
        if (owner == null && !state.base.loading) item { EmptyState(stringResource(R.string.rp_connect), stringResource(R.string.rp_connect_hint)) }
        if (owner != null) {
            item {
                Panel(Modifier.fillMaxWidth()) {
                    Text(stringResource(if (state.draft == null) R.string.rp_saved else R.string.rp_draft), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    val complete = workspace.plans.sumOf { p -> p.sessions.filter { it.status == RevisionStatus.COMPLETED }.sumOf { it.effort } }
                    Text(stringResource(R.string.rp_progress, complete, workspace.plans.sumOf { p -> p.topics.sumOf { it.minutes } }), style = MaterialTheme.typography.headlineSmall)
                    Text(stringResource(R.string.rp_scope), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.rp_source_time, revisionTimestamp(owner.snapshot.updatedAt)), style = MaterialTheme.typography.bodySmall)
                    if (!owner.configured) InlineNotice(stringResource(R.string.rp_confirm_budget), true)
                    state.base.result?.let { result ->
                        if (result.issues.any { it != WorkloadIssue.UNKNOWN_END }) InlineNotice(stringResource(R.string.rp_quality), true)
                        if (WorkloadIssue.UNKNOWN_END in result.issues) Text(stringResource(R.string.rp_unknown_end), style = MaterialTheme.typography.bodySmall)
                    }
                    if (state.draft != null) {
                        Text(stringResource(R.string.rp_unplaced, state.draft.unplaced.sumOf { it.minutes }), style = MaterialTheme.typography.titleMedium)
                        state.draft.problems.forEach { problem -> InlineNotice(revisionProblem(problem), true) }
                        Row {
                            Button(onSaveDraft, enabled = enabled && state.draft.problems.isEmpty(), shape = TechnicalShapes.Control) { Text(stringResource(R.string.rp_save_draft)) }
                            TextButton(onDiscard, enabled = !busy, shape = TechnicalShapes.Control) { Text(stringResource(R.string.rp_discard)) }
                        }
                    } else {
                        Button(onGenerate, enabled = enabled && !state.base.loading && workspace.plans.any { it.active && it.topics.isNotEmpty() }, shape = TechnicalShapes.Control) { Text(stringResource(R.string.rp_generate)) }
                    }
                    Text(stringResource(R.string.rp_heuristic), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                Text(stringResource(R.string.rp_exams), style = MaterialTheme.typography.titleLarge)
                OutlinedButton({ chooser = true }, enabled = enabled && state.draft == null, shape = TechnicalShapes.Control) { Text(stringResource(R.string.rp_choose_exam)) }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.saved.plans.forEach { p -> FilterChip(plan?.exam == p.exam, { onSelect(p.exam.id) }, enabled = !busy, shape = TechnicalShapes.Control, label = { Text(p.title, Modifier.widthIn(max = 240.dp)) }) }
                }
            }
            if (plan != null) {
                item {
                    Panel(Modifier.fillMaxWidth()) {
                        Text(plan.title, style = MaterialTheme.typography.titleLarge)
                        Text(stringResource(R.string.rp_exam_at, revisionTimestamp(plan.due)), style = MaterialTheme.typography.bodyMedium)
                        if (!plan.active) InlineNotice(stringResource(R.string.rp_archived))
                        if (state.exams.none { it.ref == plan.exam && it.due == plan.due && !it.conflict }) InlineNotice(stringResource(R.string.rp_source_changed), true)
                        FlowRow(Modifier.fillMaxWidth()) {
                            TextButton({ onArchive(plan.exam) }, enabled = enabled && state.draft == null, shape = TechnicalShapes.Control) { Text(stringResource(if (plan.active) R.string.rp_archive else R.string.rp_resume)) }
                            TextButton({ relinking = true }, enabled = enabled && state.draft == null, shape = TechnicalShapes.Control) { Text(stringResource(R.string.rp_change_exam)) }
                            TextButton({ deleting = true }, enabled = enabled && state.draft == null, shape = TechnicalShapes.Control) { Text(stringResource(R.string.rp_delete_plan)) }
                            if (state.exams.any { it.ref == plan.exam }) TextButton({ duration = true }, enabled = enabled, shape = TechnicalShapes.Control) { Text(stringResource(R.string.rp_exam_duration)) }
                        }
                        Text(stringResource(R.string.rp_topics), style = MaterialTheme.typography.titleMedium)
                        if (plan.topics.isEmpty()) Text(stringResource(R.string.rp_no_topics), style = MaterialTheme.typography.bodyMedium)
                        plan.topics.forEachIndexed { index, topic ->
                            HorizontalDivider()
                            Text(topic.title, style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.rp_topic_progress, plan.sessions.filter { it.topicId == topic.id && it.status == RevisionStatus.COMPLETED }.sumOf { it.effort }, topic.minutes), style = MaterialTheme.typography.bodyMedium)
                            Row(Modifier.horizontalScroll(rememberScrollState())) {
                                TextButton({
                                    topicId = topic.id
                                    topicOpen = true
                                }, enabled = enabled && state.draft == null, shape = TechnicalShapes.Control) { Text(stringResource(R.string.rp_edit_topic)) }
                                if (index > 0) TextButton({ onReorder(plan.exam, topic.id, -1) }, enabled = enabled && state.draft == null, shape = TechnicalShapes.Control) { Text(stringResource(R.string.rp_earlier)) }
                                if (index < plan.topics.lastIndex) TextButton({ onReorder(plan.exam, topic.id, 1) }, enabled = enabled && state.draft == null, shape = TechnicalShapes.Control) { Text(stringResource(R.string.rp_later)) }
                            }
                        }
                        TextButton({
                            topicId = null
                            topicOpen = true
                        }, enabled = enabled && plan.topics.size < 20 && state.draft == null, shape = TechnicalShapes.Control) { Text(stringResource(R.string.rp_add_topic)) }
                    }
                }
            }
            val unplaced = state.draft?.unplaced.orEmpty()
            if (unplaced.isNotEmpty()) {
                item {
                    Panel(Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.rp_unplaced_title), style = MaterialTheme.typography.titleMedium)
                        unplaced.forEach { missing ->
                            val p = workspace.plans.first { it.exam == missing.exam }
                            Text(stringResource(R.string.rp_unplaced_item, p.title, p.topics.first { it.id == missing.topicId }.title, missing.minutes))
                        }
                    }
                }
            }
            item {
                Text(stringResource(R.string.rp_sessions), style = MaterialTheme.typography.titleLarge)
                TextButton({ history = !history }, shape = TechnicalShapes.Control) { Text(stringResource(if (history) R.string.rp_hide_history else R.string.rp_show_history)) }
            }
            val sessions = workspace.plans.flatMap { p -> p.sessions.filter { (p.active && it.status == RevisionStatus.PLANNED) || history }.map { p to it } }.sortedBy { it.second.start }
            if (sessions.isEmpty()) item { EmptyState(stringResource(R.string.rp_empty_sessions), stringResource(R.string.rp_empty_sessions_hint)) }
            sessions.groupBy { revisionDate(it.second.start) }.forEach { (day, rows) ->
                item {
                    Panel(Modifier.fillMaxWidth().testTag("revision-day-$day")) {
                        Text(day, style = MaterialTheme.typography.titleLarge)
                        rows.forEachIndexed { index, (p, session) ->
                            if (index > 0) HorizontalDivider()
                            Text("${revisionTime(session.start)}–${revisionTime(session.end)}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                            Text(p.topics.first { it.id == session.topicId }.title, style = MaterialTheme.typography.titleMedium)
                            Text(p.title, style = MaterialTheme.typography.bodySmall)
                            Text(stringResource(R.string.rp_session_minutes, session.effort, session.minutes), style = MaterialTheme.typography.bodyMedium)
                            Text(
                                stringResource(
                                    when {
                                        session.status == RevisionStatus.COMPLETED -> R.string.rp_completed
                                        session.status == RevisionStatus.MISSED || session.end <= state.now -> R.string.rp_missed
                                        session.locked -> R.string.rp_locked
                                        else -> R.string.rp_planned
                                    }
                                ),
                                style = MaterialTheme.typography.labelLarge
                            )
                            if (session.status == RevisionStatus.PLANNED && p.active && state.draft == null) TextButton({ editing = session.id }, Modifier.testTag("revision-edit-${session.id}"), enabled = enabled, shape = TechnicalShapes.Control) { Text(stringResource(R.string.rp_session_details)) }
                        }
                    }
                }
            }
        }
    }
    if (chooser || relinking) {
        RevisionExamDialog(state, {
            chooser = false
            relinking = false
        }) { id ->
            if (relinking && plan != null) {
                onRelink(plan.exam, id) {
                    relinking = false
                    onSelect(id)
                }
            } else {
                onExam(id)
                chooser = false
            }
        }
    }
    if (settings && owner != null) WorkloadSettingsDialog(owner, state.base.owners, busy, state.base.problem, { settings = false }) { value -> onPolicy(value) { settings = false } }
    if (rules) RevisionOptionsDialog(state, { rules = false }) { a, b, c -> onOptions(a, b, c) { rules = false } }
    if (topicOpen && plan != null) RevisionTopicDialog(plan.topics.firstOrNull { it.id == topicId }, state, { topicOpen = false }) { title, minutes -> onTopic(plan.exam, topicId, title, minutes) { topicOpen = false } }
    if (duration && plan != null) {
        val item = state.base.result?.tasks?.firstOrNull { it.item.ref == plan.exam }
        if (item != null) WorkloadEstimateDialog(item, busy, state.base.problem, { duration = false }) { remaining, examDuration -> onEstimate(plan.exam, remaining, examDuration) { duration = false } }
    }
    val edited = state.saved.plans.flatMap { p -> p.sessions.map { p to it } }.firstOrNull { it.second.id == editing }
    if (deleting && plan != null) {
        WorkloadDialog(stringResource(R.string.rp_delete_plan), { deleting = false }, actions = {
            TextButton({ deleting = false }, enabled = !busy, shape = TechnicalShapes.Control) { Text(stringResource(R.string.wl_cancel)) }
            TextButton({
                onRemovePlan(plan.exam)
                deleting = false
            }, enabled = enabled, shape = TechnicalShapes.Control) { Text(stringResource(R.string.rp_delete_plan)) }
        }) {
            Text(stringResource(R.string.rp_delete_help))
            Text(plan.title, style = MaterialTheme.typography.titleMedium)
        }
    }
    if (edited != null) RevisionSessionDialog(edited.first, edited.second, state, { editing = null }, { day, start, end -> onMove(edited.first.exam, edited.second.id, day, start, end) { editing = null } }, { status, lock -> onSession(edited.first.exam, edited.second.id, status, lock) })
}

@Composable internal fun revisionMessage(message: RevisionMessage) = stringResource(
    when (message) {
        RevisionMessage.LOAD -> R.string.rp_load_error
        RevisionMessage.SAVE -> R.string.rp_save_error
        RevisionMessage.INPUT -> R.string.rp_input_error
        RevisionMessage.CHANGED -> R.string.rp_changed
        RevisionMessage.CONFLICT -> R.string.rp_conflict
        RevisionMessage.GENERATED -> R.string.rp_generated
    }
)
internal fun revisionDate(time: Long) = Instant.fromEpochSeconds(time).toLocalDateTime(UitTimeZone).date.toString()
internal fun revisionTime(time: Long) = Instant.fromEpochSeconds(time).toLocalDateTime(UitTimeZone).time.toString().take(5)
internal fun revisionTimestamp(time: Long) = if (time <= 0) "—" else "${revisionDate(time)} ${revisionTime(time)}"

@Composable internal fun revisionProblem(problem: RevisionProblem) = stringResource(
    when (problem) {
        RevisionProblem.SOURCE_CHANGED -> R.string.rp_source_changed
        RevisionProblem.INPUT_QUALITY -> R.string.rp_quality
        RevisionProblem.SESSION_CONFLICT -> R.string.rp_session_conflict
        RevisionProblem.DAILY_BUDGET -> R.string.rp_budget_conflict
        RevisionProblem.SESSION_LIMIT -> R.string.rp_session_limit
        RevisionProblem.COMPUTATION_LIMIT -> R.string.rp_search_limit
        RevisionProblem.OPTIONS -> R.string.rp_option_conflict
    }
)
