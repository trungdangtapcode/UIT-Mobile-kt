package vn.edu.uit.mobile.schedule

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import vn.edu.uit.mobile.designsystem.*
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.feature.schedule.R

@Composable internal fun RevisionExamDialog(state: RevisionState, onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    WorkloadDialog(stringResource(R.string.rp_choose_exam), onDismiss, actions = { TextButton(onDismiss, shape = TechnicalShapes.Control) { Text(stringResource(R.string.wl_close)) } }) {
        if (state.exams.isEmpty()) Text(stringResource(R.string.rp_no_exams))
        state.exams.forEach { exam ->
            OutlinedButton({ onSelect(exam.ref.id) }, Modifier.fillMaxWidth(), enabled = !state.busy && !exam.conflict, shape = TechnicalShapes.Control) {
                Column {
                    Text(exam.title)
                    Text(revisionTimestamp(exam.due), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Text(stringResource(R.string.rp_identity_help), style = MaterialTheme.typography.bodySmall)
        state.message?.let { InlineNotice(revisionMessage(it), true) }
    }
}

@Composable internal fun RevisionTopicDialog(topic: StudyTopic?, state: RevisionState, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var title by rememberSaveable { mutableStateOf(topic?.title.orEmpty()) }
    var minutes by rememberSaveable { mutableStateOf(topic?.minutes?.toString().orEmpty()) }
    WorkloadDialog(stringResource(if (topic == null) R.string.rp_add_topic else R.string.rp_edit_topic), onDismiss, actions = {
        TextButton(onDismiss, enabled = !state.busy, shape = TechnicalShapes.Control) { Text(stringResource(R.string.wl_cancel)) }
        TextButton({ onSave(title, minutes) }, enabled = !state.busy, shape = TechnicalShapes.Control) { Text(stringResource(R.string.wl_save)) }
    }) {
        OutlinedTextField(title, { title = it.take(160) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.rp_topic_title)) }, enabled = !state.busy, shape = TechnicalShapes.Field)
        RevisionNumber(minutes, { minutes = it }, stringResource(R.string.rp_topic_minutes), !state.busy)
        Text(stringResource(R.string.rp_topic_help), style = MaterialTheme.typography.bodySmall)
        state.message?.let { InlineNotice(revisionMessage(it), true) }
    }
}

@Composable internal fun RevisionOptionsDialog(state: RevisionState, onDismiss: () -> Unit, onSave: (String, String, String) -> Unit) {
    var maximum by rememberSaveable { mutableStateOf(state.saved.options.maximumSession.toString()) }
    var rest by rememberSaveable { mutableStateOf(state.saved.options.breakMinutes.toString()) }
    var delay by rememberSaveable { mutableStateOf(state.saved.options.startDelay.toString()) }
    WorkloadDialog(stringResource(R.string.rp_rules), onDismiss, actions = {
        TextButton(onDismiss, enabled = !state.busy, shape = TechnicalShapes.Control) { Text(stringResource(R.string.wl_cancel)) }
        TextButton({ onSave(maximum, rest, delay) }, enabled = !state.busy, shape = TechnicalShapes.Control) { Text(stringResource(R.string.wl_save)) }
    }) {
        RevisionNumber(maximum, { maximum = it }, stringResource(R.string.rp_maximum), !state.busy)
        RevisionNumber(rest, { rest = it }, stringResource(R.string.rp_break), !state.busy)
        RevisionNumber(delay, { delay = it }, stringResource(R.string.rp_delay), !state.busy)
        Text(stringResource(R.string.rp_rules_help), style = MaterialTheme.typography.bodySmall)
        state.message?.let { InlineNotice(revisionMessage(it), true) }
    }
}

@Composable internal fun RevisionSessionDialog(plan: RevisionPlan, session: RevisionSession, state: RevisionState, onDismiss: () -> Unit, onSave: (String, String, String) -> Unit, onAction: (RevisionStatus?, Boolean) -> Unit) {
    var day by rememberSaveable(session.id) { mutableStateOf(revisionDate(session.start)) }
    var start by rememberSaveable(session.id) { mutableStateOf(revisionTime(session.start)) }
    var end by rememberSaveable(session.id) { mutableStateOf(revisionTime(session.end)) }
    WorkloadDialog(stringResource(R.string.rp_session_details), onDismiss, actions = {
        TextButton(onDismiss, enabled = !state.busy, shape = TechnicalShapes.Control) { Text(stringResource(R.string.wl_close)) }
        if (session.status == RevisionStatus.PLANNED) TextButton({ onSave(day, start, end) }, enabled = !state.busy, shape = TechnicalShapes.Control) { Text(stringResource(R.string.rp_move_save)) }
    }) {
        Text(plan.topics.first { it.id == session.topicId }.title, style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.rp_session_minutes, session.effort, session.minutes))
        if (session.status == RevisionStatus.PLANNED) {
            OutlinedTextField(day, { day = it.take(10) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.rp_date)) }, enabled = !state.busy, shape = TechnicalShapes.Field, singleLine = true)
            OutlinedTextField(start, { start = it.take(5) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.rp_start)) }, enabled = !state.busy, shape = TechnicalShapes.Field, singleLine = true)
            OutlinedTextField(end, { end = it.take(5) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.rp_end)) }, enabled = !state.busy, shape = TechnicalShapes.Field, singleLine = true)
            Text(stringResource(R.string.rp_move_help), style = MaterialTheme.typography.bodySmall)
            TextButton({ onAction(null, true) }, enabled = !state.busy, shape = TechnicalShapes.Control) { Text(stringResource(if (session.locked) R.string.rp_unlock else R.string.rp_lock)) }
            if (session.end <= state.now) Button({ onAction(RevisionStatus.COMPLETED, false) }, enabled = !state.busy, shape = TechnicalShapes.Control) { Text(stringResource(R.string.rp_mark_done)) }
            TextButton({ onAction(RevisionStatus.MISSED, false) }, enabled = !state.busy, shape = TechnicalShapes.Control) { Text(stringResource(R.string.rp_skip)) }
        } else {
            Text(stringResource(if (session.status == RevisionStatus.COMPLETED) R.string.rp_completed else R.string.rp_missed))
        }
        state.message?.let { InlineNotice(revisionMessage(it), true) }
    }
}

@Composable private fun RevisionNumber(value: String, change: (String) -> Unit, label: String, enabled: Boolean) {
    OutlinedTextField(value, { change(it.take(5)) }, Modifier.fillMaxWidth(), label = { Text(label) }, enabled = enabled, shape = TechnicalShapes.Field, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
}
